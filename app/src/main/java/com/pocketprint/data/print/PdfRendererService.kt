package com.pocketprint.data.print

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.pocketprint.data.document.office.ExtractedOfficeDocument
import com.pocketprint.data.document.office.OfficeDocumentExtractor
import com.pocketprint.data.document.tiff.TiffDocument
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.util.PrintLayoutCalculator
import android.content.ContentResolver
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Locale

class PdfRendererService(private val context: Context) {

    private companion object {
        const val MAX_REFLOW_BYTES = 16 * 1024 * 1024
        const val MAX_REFLOW_CHARS = 8 * 1024 * 1024
        const val MAX_TIFF_INPUT_BYTES = 64 * 1024 * 1024
        const val MAX_PAGE_BITMAP_BYTES = 96L * 1024L * 1024L
        const val MAX_DECODED_IMAGE_PIXELS = 24_000_000L
    }

    /**
     * Scheme-aware, robust document stream opener.
     * Supports file://, content://, android.resource://, and direct absolute filesystem paths.
     * Android 14+ / 16 restricts ContentResolver access to file:// URIs, so we open
     * direct FileInputStream for file:// schemes and local cache files.
     */
    fun openDocumentInputStream(uri: Uri): InputStream {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)

        // 1. Direct file handling for file://, missing scheme, or empty scheme
        if (scheme == null || scheme == ContentResolver.SCHEME_FILE || scheme.isEmpty()) {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            val file = File(path)
            if (!file.exists()) {
                throw FileNotFoundException("Document file does not exist on disk: $path (URI: $uri)")
            }
            if (!file.canRead()) {
                throw IOException("Document file is unreadable (permission denied): $path")
            }
            if (file.length() == 0L) {
                throw IOException("Document file is empty (0 bytes): $path")
            }
            return FileInputStream(file)
        }

        // 2. If path is a local file on disk that exists, prefer FileInputStream directly
        val rawPath = uri.path
        if (rawPath != null) {
            val file = File(rawPath)
            if (file.exists() && file.isFile && file.length() > 0L) {
                return FileInputStream(file)
            }
        }

        // 3. For content:// and other schemes, use ContentResolver
        val crStream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            DiagnosticLogger.log("RENDER", "ContentResolver.openInputStream failed for $uri: ${e.message}")
            null
        }

        if (crStream != null) return crStream

        // 4. Fallback: check if the string representation points to a readable file
        val stringPath = uri.toString().removePrefix("file://")
        val fallbackFile = File(stringPath)
        if (fallbackFile.exists() && fallbackFile.isFile && fallbackFile.length() > 0L) {
            return FileInputStream(fallbackFile)
        }

        throw FileNotFoundException("Cannot open input stream for document URI: $uri (scheme=$scheme)")
    }

    /**
     * Scheme-aware, robust ParcelFileDescriptor opener for PDF rendering.
     */
    fun openDocumentFileDescriptor(uri: Uri, mode: String = "r"): ParcelFileDescriptor {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == null || scheme == ContentResolver.SCHEME_FILE || scheme.isEmpty()) {
            val path = uri.path ?: uri.toString().removePrefix("file://")
            val file = File(path)
            if (!file.exists()) {
                throw FileNotFoundException("PDF file does not exist: $path (URI: $uri)")
            }
            val modeBits = when (mode) {
                "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
                "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
            return ParcelFileDescriptor.open(file, modeBits)
        }

        val rawPath = uri.path
        if (rawPath != null) {
            val file = File(rawPath)
            if (file.exists() && file.isFile) {
                val modeBits = when (mode) {
                    "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY
                    "rw" -> ParcelFileDescriptor.MODE_READ_WRITE
                    else -> ParcelFileDescriptor.MODE_READ_ONLY
                }
                return ParcelFileDescriptor.open(file, modeBits)
            }
        }

        return context.contentResolver.openFileDescriptor(uri, mode)
            ?: throw FileNotFoundException("Cannot open ParcelFileDescriptor for URI: $uri (scheme=$scheme)")
    }

    private val officeCache = LruCache<String, ExtractedOfficeDocument>(3)

    /**
     * Inspects a document URI (PDF or image) and returns the total page count.
     */
    fun getPageCount(
        documentUri: Uri,
        mimeType: String? = null,
        options: PrintOptions = PrintOptions()
    ): Int {
        val detectedType = mimeType ?: context.contentResolver.getType(documentUri) ?: ""
        return if (isTiff(detectedType, documentUri)) {
            loadTiff(documentUri).pageCount
        } else if (isOfficeDocument(detectedType, documentUri)) {
            officePrintablePages(documentUri, detectedType, options).size
        } else if (isImageMime(detectedType) || isImageExtension(documentUri)) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openDocumentInputStream(documentUri).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IllegalStateException("Image file is corrupt or has invalid dimensions (${bounds.outWidth}x${bounds.outHeight}): $documentUri")
            }
            DiagnosticLogger.usb("[PREFLIGHT] Validated image dimensions: ${bounds.outWidth}x${bounds.outHeight} for $documentUri")
            1
        } else if (isTextMime(detectedType) || isTextFile(documentUri)) {
            paginateText(readTextFile(documentUri), options).size
        } else {
            val pfd = try {
                openDocumentFileDescriptor(documentUri, "r")
            } catch (e: Exception) {
                DiagnosticLogger.usbError("Failed to open PDF descriptor during preflight: ${e.message}")
                return 1
            }
            pfd.use { descriptor ->
                try {
                    PdfRenderer(descriptor).use { it.pageCount }
                } catch (e: Exception) {
                    DiagnosticLogger.usbError("Failed to parse PDF page count: ${e.message}")
                    1
                }
            }
        }
    }

    /**
     * Renders a document page (PDF or Image) to a calibrated target bitmap.
     */
    fun renderPageToBitmap(documentUri: Uri, pageIndex: Int, options: PrintOptions, mimeType: String? = null): Bitmap {
        validatePageAllocation(options)
        val detectedType = mimeType ?: context.contentResolver.getType(documentUri) ?: ""
        return when {
            isTiff(detectedType, documentUri) -> renderTiffPage(documentUri, pageIndex, options)
            isOfficeDocument(detectedType, documentUri) -> {
                val pages = officePrintablePages(documentUri, detectedType, options)
                renderTextPageToBitmap(
                    pages.getOrElse(pageIndex) {
                        throw IndexOutOfBoundsException("Office page $pageIndex outside ${pages.indices}")
                    },
                    options
                )
            }
            isImageMime(detectedType) || isImageExtension(documentUri) -> renderImageToBitmap(documentUri, options)
            isTextMime(detectedType) || isTextFile(documentUri) -> {
                val pages = paginateText(readTextFile(documentUri), options)
                renderTextPageToBitmap(
                    pages.getOrElse(pageIndex) {
                        throw IndexOutOfBoundsException("Text page $pageIndex outside ${pages.indices}")
                    },
                    options
                )
            }
            else -> renderPdfPageToBitmap(documentUri, pageIndex, options)
        }
    }

    /**
     * Renders an image file (PNG, JPG, BMP, WEBP) centered and scaled to target paper dimensions.
     */
    fun renderImageToBitmap(imageUri: Uri, options: PrintOptions): Bitmap {
        validatePageAllocation(options)
        val metadata = readImageMetadata(imageUri)
        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openDocumentInputStream(imageUri).use {
            BitmapFactory.decodeStream(it, null, boundsOptions)
        }
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            throw IllegalStateException("Failed to inspect image dimensions: $imageUri")
        }
        val swapsAxes = metadata.rotationDegrees % 180 != 0
        val orientedWidth = if (swapsAxes) boundsOptions.outHeight else boundsOptions.outWidth
        val orientedHeight = if (swapsAxes) boundsOptions.outWidth else boundsOptions.outHeight
        val sampleSize = calculateImageSampleSize(
            orientedWidth,
            orientedHeight,
            options,
            metadata.dpi
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val decoded = openDocumentInputStream(imageUri).use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: throw IllegalStateException("Failed to decode image from: $imageUri")
        val source = applyExifTransform(decoded, metadata)
        if (source !== decoded) decoded.recycle()
        DiagnosticLogger.usb(
            "[RENDER] IMAGE source=${boundsOptions.outWidth}x${boundsOptions.outHeight} " +
                "oriented=${orientedWidth}x${orientedHeight} sample=$sampleSize " +
                "decoded=${source.width}x${source.height} rotation=${metadata.rotationDegrees} " +
                "flipped=${metadata.flipped} sourceDpi=${metadata.dpi ?: "unknown"}"
        )

        return try {
            val actualScale = (metadata.dpi?.takeIf { it > 0f }?.let { options.dpi / it } ?: 1f) *
                sampleSize
            renderBitmapToPage(source, options, actualScale)
        } finally {
            source.recycle()
        }
    }

    private fun renderBitmapToPage(
        source: Bitmap,
        options: PrintOptions,
        actualSizeScale: Float = 1f
    ): Bitmap {
        val output = Bitmap.createBitmap(
            options.widthPixels,
            options.heightPixels,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        try {
            val layout = PrintLayoutCalculator.calculate(
                sourceWidth = source.width.toFloat(),
                sourceHeight = source.height.toFloat(),
                options = options,
                actualSizeScale = actualSizeScale
            )
            val matrix = Matrix().apply {
                setScale(layout.scale, layout.scale)
                postTranslate(layout.translateX, layout.translateY)
            }

            val paint = Paint(
                Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
            )
            canvas.save()
            canvas.clipRect(
                layout.clipBounds.left,
                layout.clipBounds.top,
                layout.clipBounds.right,
                layout.clipBounds.bottom
            )
            canvas.drawBitmap(source, matrix, paint)
            canvas.restore()
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
    }

    private data class ImageMetadata(
        val rotationDegrees: Int = 0,
        val flipped: Boolean = false,
        val dpi: Float? = null
    )

    private fun readImageMetadata(uri: Uri): ImageMetadata = runCatching {
        openDocumentInputStream(uri).use { input ->
            val exif = ExifInterface(input)
            val unit = exif.getAttributeInt(
                ExifInterface.TAG_RESOLUTION_UNIT,
                ExifInterface.RESOLUTION_UNIT_INCHES.toInt()
            )
            val rawDpi = exif.getAttributeDouble(ExifInterface.TAG_X_RESOLUTION, Double.NaN)
            val dpi = when {
                !rawDpi.isFinite() || rawDpi <= 0.0 -> null
                unit == ExifInterface.RESOLUTION_UNIT_CENTIMETERS.toInt() -> (rawDpi * 2.54).toFloat()
                else -> rawDpi.toFloat()
            }
            ImageMetadata(exif.rotationDegrees, exif.isFlipped, dpi)
        } ?: ImageMetadata()
    }.getOrDefault(ImageMetadata())

    private fun calculateImageSampleSize(
        orientedWidth: Int,
        orientedHeight: Int,
        options: PrintOptions,
        sourceDpi: Float?
    ): Int {
        val target = options.printableBounds
        val requiredScale = when (options.scaling) {
            PrintScaling.FIT_TO_PAGE -> minOf(
                target.width / orientedWidth.toFloat(),
                target.height / orientedHeight.toFloat()
            )
            PrintScaling.FILL_PAGE -> maxOf(
                target.width / orientedWidth.toFloat(),
                target.height / orientedHeight.toFloat()
            )
            PrintScaling.ACTUAL_SIZE -> sourceDpi?.takeIf { it > 0f }
                ?.let { options.dpi / it }
                ?: 1f
        }
        val maximumSample = if (requiredScale >= 1f) 1 else (1f / requiredScale).toInt().coerceAtLeast(1)
        var sample = 1
        while (sample <= maximumSample / 2) sample *= 2
        while (orientedWidth.toLong() / sample * (orientedHeight.toLong() / sample) > MAX_DECODED_IMAGE_PIXELS) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifTransform(source: Bitmap, metadata: ImageMetadata): Bitmap {
        if (metadata.rotationDegrees == 0 && !metadata.flipped) return source
        val matrix = Matrix().apply {
            if (metadata.flipped) postScale(-1f, 1f)
            if (metadata.rotationDegrees != 0) postRotate(metadata.rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Renders a selected PDF page into a high-resolution Bitmap with matrix scaling.
     */
    fun renderPdfPageToBitmap(pdfUri: Uri, pageIndex: Int, options: PrintOptions): Bitmap {
        val pfd = openDocumentFileDescriptor(pdfUri, "r")


        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { pdfRenderer ->
                if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) {
                    throw IllegalArgumentException("Page index $pageIndex out of bounds (total: ${pdfRenderer.pageCount})")
                }

                val page = pdfRenderer.openPage(pageIndex)
                try {
                    return renderPdfPageToBitmap(page, options)
                } finally {
                    page.close()
                }
            }
        }
    }

    /** Renders an already-open framework PDF page using the shared print layout rules. */
    fun renderPdfPageToBitmap(page: PdfRenderer.Page, options: PrintOptions): Bitmap {
        validatePageAllocation(options)
        val bitmap = Bitmap.createBitmap(options.widthPixels, options.heightPixels, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val layout = PrintLayoutCalculator.calculate(
                sourceWidth = page.width.toFloat(),
                sourceHeight = page.height.toFloat(),
                options = options,
                actualSizeScale = options.dpi / 72f
            )
            val matrix = Matrix().apply {
                setScale(layout.scale, layout.scale)
                postTranslate(layout.translateX, layout.translateY)
            }
            val clip = Rect(
                layout.clipBounds.left,
                layout.clipBounds.top,
                layout.clipBounds.right,
                layout.clipBounds.bottom
            )
            DiagnosticLogger.usb(
                "[RENDER] PDF source=${page.width}x${page.height}pt target=${bitmap.width}x${bitmap.height}px " +
                    "dpi=${options.dpi} scaling=${options.scaling} scale=${layout.scale} " +
                    "clip=${clip.left},${clip.top}-${clip.right},${clip.bottom} config=${bitmap.config}"
            )
            page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    /**
     * Synthesizes the standard diagnostic calibration test page.
     */
    fun generateTestPageBitmap(options: PrintOptions): Bitmap {
        val width = options.widthPixels
        val height = options.heightPixels

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val paint = Paint().apply {
            isAntiAlias = false
            color = Color.BLACK
        }

        val margin = (options.dpi * 0.5f)
        val contentWidth = width - (2 * margin)

        // Frame
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(margin, margin, width - margin, height - margin, paint)

        // Header
        paint.style = Paint.Style.FILL
        paint.textSize = 60f
        paint.isFakeBoldText = true
        var y = margin + 90f
        canvas.drawText("PocketPrint Universal Diagnostic", margin + 30f, y, paint)

        // Subtitle
        paint.textSize = 26f
        paint.isFakeBoldText = false
        y += 42f
        canvas.drawText("Native HPLIP Pcl3Gui2 Engine · Target: HP Ink Tank 310 series · USB Host", margin + 30f, y, paint)

        // Divider
        y += 20f
        paint.strokeWidth = 2f
        canvas.drawLine(margin + 30f, y, margin + contentWidth - 30f, y, paint)

        // Body
        y += 45f
        paint.textSize = 24f
        canvas.drawText("Status: USB payload diagnostic — confirm physical output.", margin + 30f, y, paint)
        y += 35f
        canvas.drawText("Format: ${options.paperSize.displayName} · Color Mode: ${options.colorMode.displayName}", margin + 30f, y, paint)
        y += 35f
        canvas.drawText("Resolution: ${options.dpi} DPI · Orientation: ${options.orientation.displayName}", margin + 30f, y, paint)

        // Logo Badge
        y += 60f
        val badge = RectF(margin + 30f, y, margin + 280f, y + 80f)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        canvas.drawRect(badge, paint)

        paint.color = Color.WHITE
        paint.textSize = 30f
        paint.isFakeBoldText = true
        canvas.drawText("POCKETPRINT", margin + 45f, y + 52f, paint)

        // Footer
        val footerY = height - margin - 35f
        paint.color = Color.DKGRAY
        paint.textSize = 22f
        paint.isFakeBoldText = false
        canvas.drawText("Native USB Print Engine · Zero Cloud/Plugin Dependence", margin + 30f, footerY, paint)

        return bitmap
    }

    private fun isImageExtension(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".png") || path.endsWith(".jpg") ||
                path.endsWith(".jpeg") || path.endsWith(".bmp") ||
                path.endsWith(".webp")
    }

    private fun isImageMime(mimeType: String?): Boolean =
        mimeType?.contains("image", ignoreCase = true) == true

    private fun isTextMime(mimeType: String?): Boolean =
        mimeType?.startsWith("text/", ignoreCase = true) == true

    private fun isTextFile(uri: Uri): Boolean {
        val path = uri.path?.lowercase() ?: return false
        return path.endsWith(".txt")
    }

    private fun isTiff(mimeType: String?, uri: Uri): Boolean {
        val path = uri.path?.lowercase().orEmpty()
        return mimeType.equals("image/tiff", ignoreCase = true) ||
            mimeType.equals("image/tif", ignoreCase = true) ||
            path.endsWith(".tif") || path.endsWith(".tiff")
    }

    private fun isOfficeDocument(mimeType: String?, uri: Uri): Boolean =
        OfficeDocumentExtractor.supports(uri.lastPathSegment.orEmpty(), mimeType)

    private fun readTextFile(uri: Uri): String {
        val inputStream = openDocumentInputStream(uri)

        val bytes = inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_REFLOW_BYTES) { "Text document exceeds the safe reflow limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }

    fun renderTextToBitmap(text: String, options: PrintOptions): Bitmap {
        return renderTextPageToBitmap(paginateText(text, options).firstOrNull().orEmpty(), options)
    }

    private fun renderTextPageToBitmap(text: String, options: PrintOptions): Bitmap {
        val width = options.widthPixels
        val height = options.heightPixels
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val bounds = options.printableBounds
        val layout = createTextLayout(text, bounds.width, options)
        canvas.save()
        canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun paginateText(text: String, options: PrintOptions): List<String> {
        require(text.length <= MAX_REFLOW_CHARS) { "Document text exceeds the safe pagination limit" }
        val bounds = options.printableBounds
        val source = text.ifEmpty { " " }
        val layout = createTextLayout(source, bounds.width, options)
        val pages = mutableListOf<String>()
        var firstLine = 0
        while (firstLine < layout.lineCount) {
            val pageTop = layout.getLineTop(firstLine)
            var endLine = firstLine
            while (endLine < layout.lineCount &&
                layout.getLineBottom(endLine) - pageTop <= bounds.height
            ) {
                endLine++
            }
            if (endLine == firstLine) endLine++
            val startOffset = layout.getLineStart(firstLine)
            val endOffset = layout.getLineEnd(endLine - 1)
            pages += source.substring(startOffset, endOffset)
            firstLine = endLine
        }
        return pages.ifEmpty { listOf("") }
    }

    @SuppressLint("WrongConstant")
    private fun createTextLayout(text: String, width: Int, options: PrintOptions): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = 11f * options.dpi / 72f
            color = Color.BLACK
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.2f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .build()
    }

    private fun renderTiffPage(uri: Uri, pageIndex: Int, options: PrintOptions): Bitmap {
        val page = loadTiff(uri).decodePage(pageIndex)
        val source = Bitmap.createBitmap(page.argb, page.width, page.height, Bitmap.Config.ARGB_8888)
        return try {
            renderBitmapToPage(source, options)
        } finally {
            source.recycle()
        }
    }

    private fun loadTiff(uri: Uri): TiffDocument {
        val input = openDocumentInputStream(uri)
        val bytes = input.use { source ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var total = 0
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_TIFF_INPUT_BYTES) { "TIFF exceeds the safe input limit" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return TiffDocument(bytes)
    }

    private fun officePrintablePages(
        uri: Uri,
        mimeType: String?,
        options: PrintOptions
    ): List<String> = loadOffice(uri, mimeType).pages
        .flatMap { paginateText(it, options) }
        .ifEmpty { listOf("") }

    private fun loadOffice(uri: Uri, mimeType: String?): ExtractedOfficeDocument {
        val key = "${uri}|${mimeType.orEmpty()}"
        synchronized(officeCache) { officeCache.get(key)?.let { return it } }
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        val input = openDocumentInputStream(uri)
        val document = OfficeDocumentExtractor.extract(input, fileName, mimeType)
        synchronized(officeCache) { officeCache.put(key, document) }
        return document
    }

    private fun validatePageAllocation(options: PrintOptions) {
        val bytes = options.widthPixels.toLong() * options.heightPixels.toLong() * 4L
        val limit = if (
            options.colorMode == com.pocketprint.data.print.model.PrintColorMode.MONOCHROME_1BIT &&
            options.dpi <= 1200
        ) {
            160L * 1024L * 1024L
        } else {
            MAX_PAGE_BITMAP_BYTES
        }
        require(bytes <= limit) {
            "${options.widthPixels}x${options.heightPixels} ARGB page requires ${bytes / (1024 * 1024)} MiB; " +
                "reduce the verified DPI or paper size"
        }
    }

    /** Creates a chart for operator validation of the software-grayscale color path. */
    fun generateGrayscaleVerificationBitmap(options: PrintOptions): Bitmap {
        val bitmap = Bitmap.createBitmap(options.widthPixels, options.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val bounds = options.printableBounds
        val inch = options.dpi.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        var y = bounds.top + inch * 0.35f
        paint.textSize = inch * 0.20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("PocketPrint Grayscale Verification", bounds.left.toFloat(), y, paint)

        y += inch * 0.26f
        paint.textSize = inch * 0.10f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("BT.709 luminance through verified PCL3GUI2 dual-plane color", bounds.left.toFloat(), y, paint)

        y += inch * 0.30f
        paint.textSize = inch * 0.09f
        canvas.drawText("21-step neutral ramp: every adjacent block should remain distinguishable", bounds.left.toFloat(), y, paint)
        y += inch * 0.10f
        val stepGap = inch * 0.01f
        val stepCount = 21
        val stepWidth = (bounds.width - stepGap * (stepCount - 1)) / stepCount
        val stepHeight = inch * 0.55f
        for (step in 0 until stepCount) {
            val shade = 255 - (step * 255 / (stepCount - 1))
            paint.color = Color.rgb(shade, shade, shade)
            val left = bounds.left + step * (stepWidth + stepGap)
            canvas.drawRect(left, y, left + stepWidth, y + stepHeight, paint)
        }

        y += stepHeight + inch * 0.30f
        paint.color = Color.BLACK
        canvas.drawText("Smooth ramp: no color cast, abrupt bands, or reversed polarity", bounds.left.toFloat(), y, paint)
        y += inch * 0.10f
        val rampHeight = inch * 0.55f
        val stripWidth = bounds.width / 256f
        for (shade in 0..255) {
            paint.color = Color.rgb(shade, shade, shade)
            val left = bounds.left + shade * stripWidth
            canvas.drawRect(left, y, left + stripWidth + 1f, y + rampHeight, paint)
        }

        y += rampHeight + inch * 0.35f
        paint.color = Color.BLACK
        canvas.drawText("Color-source luminance order: green lightest, red middle, blue darkest", bounds.left.toFloat(), y, paint)
        y += inch * 0.10f
        val patchGap = inch * 0.12f
        val patchWidth = (bounds.width - patchGap * 2f) / 3f
        val patchHeight = inch * 0.65f
        listOf(Color.GREEN, Color.RED, Color.BLUE).forEachIndexed { index, color ->
            val left = bounds.left + index * (patchWidth + patchGap)
            paint.color = color
            canvas.drawRect(left, y, left + patchWidth, y + patchHeight, paint)
        }

        y += patchHeight + inch * 0.35f
        paint.color = Color.BLACK
        paint.textSize = inch * 0.12f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText("Text and line fidelity", bounds.left.toFloat(), y, paint)
        y += inch * 0.20f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = inch * 0.09f
        canvas.drawText("Black text  |  50% gray text  |  Small text remains legible", bounds.left.toFloat(), y, paint)
        y += inch * 0.16f
        for (stroke in listOf(1f, 2f, 4f, 8f)) {
            paint.strokeWidth = stroke
            canvas.drawLine(bounds.left.toFloat(), y, bounds.right.toFloat(), y, paint)
            y += inch * 0.08f
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRect(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
            paint
        )
        return bitmap
    }

    private fun wrapText(text: String, maxWidth: Float, paint: Paint): List<String> {
        val lines = mutableListOf<String>()
        text.lines().forEach { paragraph ->
            val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) {
                lines.add("")
            } else {
                var line = StringBuilder()
                for (word in words) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                        lines.add(line.toString())
                        line = StringBuilder(word)
                    } else {
                        line.append(if (line.isEmpty()) "" else " ").append(word)
                    }
                }
                if (line.isNotEmpty()) lines.add(line.toString())
            }
        }
        return lines
    }
}
