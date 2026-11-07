package com.pocketprint.data.discovery.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.model.FramingStyle
import com.pocketprint.data.print.encoder.HpPclmEncoder
import com.pocketprint.data.print.encoder.HpPcl3Gui2Encoder
import com.pocketprint.data.print.encoder.HpPcl3Gui2ColorEncoder
import com.pocketprint.data.print.model.PrintOptions
import android.graphics.LinearGradient
import android.graphics.Shader
import java.io.ByteArrayOutputStream

/**
 * Deterministic payload synthesis engine for automated discovery experiments.
 * Implements strict format specifications according to HP PCL3GUI and PCL standards.
 */
object PayloadGenerator {

    fun createDpiValidationBitmap(candidate: DiscoveryCandidate): Bitmap {
        require(candidate.resolutionDpi == 600 || candidate.resolutionDpi == 1200)
        val dpi = candidate.resolutionDpi
        val width = (dpi * 8.27f).toInt()
        val height = (dpi * 1.5f).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val margin = dpi * 0.12f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        ink.textSize = dpi * 18f / 72f
        ink.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText("PocketPrint Experimental ${dpi} DPI PCL3GUI2", margin, margin + ink.textSize, ink)
        ink.typeface = Typeface.DEFAULT
        ink.textSize = dpi * 9f / 72f
        canvas.drawText(
            "Confirm physical width, sharpness, no crop/distortion, and page ejection before verification.",
            margin,
            margin + dpi * 0.42f,
            ink
        )
        val rulerY = margin + dpi * 0.62f
        ink.strokeWidth = 1f
        canvas.drawLine(margin, rulerY, margin + dpi, rulerY, ink)
        for (tick in 0..10) {
            val x = margin + dpi * tick / 10f
            val tickHeight = if (tick % 5 == 0) dpi * 0.12f else dpi * 0.06f
            canvas.drawLine(x, rulerY - tickHeight, x, rulerY + tickHeight, ink)
        }
        canvas.drawText("Exactly 1 inch if raster DPI is accepted", margin + dpi + dpi * 0.08f, rulerY, ink)
        val lineY = rulerY + dpi * 0.28f
        for ((index, stroke) in listOf(1f, 2f, 4f, 8f).withIndex()) {
            ink.strokeWidth = stroke
            canvas.drawLine(margin, lineY + index * dpi * 0.08f, width - margin, lineY + index * dpi * 0.08f, ink)
        }
        return bitmap
    }

    /**
     * Generates a calibrated 1-page test document bitmap matching candidate parameters.
     * Contains standard diagnostic elements:
     * - Diagnostic identifier & test ID
     * - Text parameter breakdown
     * - Black horizontal and vertical calibration lines (stepped line widths)
     * - Grayscale stepped ramps and continuous linear gradient
     * - Color blocks (CMYK & RGB)
     * - Small geometric shapes (circle, square, triangle, diamond, star)
     * - Corner page boundary crop markers
     */
    fun createTestBitmap(candidate: DiscoveryCandidate): Bitmap {
        val width = (candidate.resolutionDpi * 8.27).toInt() // A4 width: 8.27 in (~2480 at 300 DPI)
        val height = (candidate.resolutionDpi * 11.69).toInt() // A4 height: 11.69 in (~3508 at 300 DPI)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val margin = (candidate.resolutionDpi * 0.5f).toInt() // 0.5 inch margins
        val cropOffset = (candidate.resolutionDpi * 0.25f).toInt() // 0.25 inch crop marks

        // 1. PAGE BOUNDARY MARKERS (Corner Crop Marks)
        val cropPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val cropLen = 30f
        // Top-Left
        canvas.drawLine(cropOffset.toFloat(), cropOffset.toFloat(), cropOffset + cropLen, cropOffset.toFloat(), cropPaint)
        canvas.drawLine(cropOffset.toFloat(), cropOffset.toFloat(), cropOffset.toFloat(), cropOffset + cropLen, cropPaint)
        // Top-Right
        canvas.drawLine((width - cropOffset).toFloat(), cropOffset.toFloat(), (width - cropOffset - cropLen).toFloat(), cropOffset.toFloat(), cropPaint)
        canvas.drawLine((width - cropOffset).toFloat(), cropOffset.toFloat(), (width - cropOffset).toFloat(), cropOffset + cropLen, cropPaint)
        // Bottom-Left
        canvas.drawLine(cropOffset.toFloat(), (height - cropOffset).toFloat(), cropOffset + cropLen, (height - cropOffset).toFloat(), cropPaint)
        canvas.drawLine(cropOffset.toFloat(), (height - cropOffset).toFloat(), cropOffset.toFloat(), (height - cropOffset - cropLen).toFloat(), cropPaint)
        // Bottom-Right
        canvas.drawLine((width - cropOffset).toFloat(), (height - cropOffset).toFloat(), (width - cropOffset - cropLen).toFloat(), (height - cropOffset).toFloat(), cropPaint)
        canvas.drawLine((width - cropOffset).toFloat(), (height - cropOffset).toFloat(), (width - cropOffset).toFloat(), (height - cropOffset - cropLen).toFloat(), cropPaint)

        // 2. OUTER CALIBRATION BORDER
        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawRect(
            margin.toFloat(),
            margin.toFloat(),
            (width - margin).toFloat(),
            (height - margin).toFloat(),
            borderPaint
        )

        // 3. HEADER BANNER (DIAGNOSTIC IDENTIFIER)
        val bannerPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            margin.toFloat(),
            margin.toFloat(),
            (width - margin).toFloat(),
            (margin + 160).toFloat(),
            bannerPaint
        )

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText("POCKETPRINT UNIVERSAL PRINTER DIAGNOSTIC", (margin + 40).toFloat(), (margin + 90).toFloat(), titlePaint)

        val subtitlePaint = Paint().apply {
            color = Color.LTGRAY
            textSize = 26f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        canvas.drawText("Phase 3 Discovery Engine · Target: HP Ink Tank 310 series (VID 0x03F0, PID 0x1053)", (margin + 40).toFloat(), (margin + 135).toFloat(), subtitlePaint)

        // 4. TEST PARAMETER TEXT
        val textPaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        var y = margin + 220f
        fun drawLine(str: String) {
            canvas.drawText(str, (margin + 40).toFloat(), y, textPaint)
            y += 42f
        }

        drawLine("CANDIDATE ID   : ${candidate.candidateId}")
        drawLine("CANDIDATE NAME : ${candidate.name}")
        drawLine("PROTOCOL       : ${candidate.protocol.displayName}")
        drawLine("COLOR MODE     : ${candidate.colorMode.displayName} (${candidate.colorMode.bitsPerPixel} bpp)")
        drawLine("RESOLUTION     : ${candidate.resolutionDpi} DPI | USB CHUNK: ${candidate.usbChunkSize} B")
        drawLine("JOB FRAMING    : ${candidate.framingStyle.displayName}")
        drawLine("ZLP TERMINATION: ${if (candidate.sendZlpFlush) "ENABLED (Zero-Length Packet)" else "DISABLED"}")
        drawLine("TIMESTAMP      : ${java.util.Date()}")
        y += 20f

        // 5. BLACK HORIZONTAL & VERTICAL CALIBRATION LINES (STEPPED WIDTHS)
        val sectionHeaderPaint = Paint().apply {
            color = Color.BLACK
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText("1. RESOLUTION & LINE FIDELITY TEST (1px, 2px, 4px, 8px, 16px):", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 35f

        val lineWidths = floatArrayOf(1f, 2f, 4f, 8f, 16f)
        val linePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
        }
        val lineStartX = margin + 40f
        val lineEndX = width - margin - 40f

        for (lw in lineWidths) {
            linePaint.strokeWidth = lw
            canvas.drawLine(lineStartX, y, lineEndX, y, linePaint)
            val labelPaint = Paint().apply {
                color = Color.DKGRAY
                textSize = 20f
                typeface = Typeface.MONOSPACE
            }
            canvas.drawText("${lw.toInt()}px", lineEndX + 8f, y + 6f, labelPaint)
            y += 28f
        }
        y += 20f

        // 6. GRAYSCALE GRADIENT RAMP
        canvas.drawText("2. GRAYSCALE DENSITY & HALFTONE RAMP (0% to 100%):", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 30f

        val numSteps = 21
        val stepWidth = (lineEndX - lineStartX) / numSteps
        val stepHeight = 50f
        val grayPaint = Paint().apply { style = Paint.Style.FILL }

        for (i in 0 until numSteps) {
            val grayValue = (255 * (1.0 - (i.toDouble() / (numSteps - 1)))).toInt().coerceIn(0, 255)
            grayPaint.color = Color.rgb(grayValue, grayValue, grayValue)
            val left = lineStartX + (i * stepWidth)
            canvas.drawRect(left, y, left + stepWidth, y + stepHeight, grayPaint)

            // Border
            canvas.drawRect(left, y, left + stepWidth, y + stepHeight, borderPaint)

            // Step Label
            val pct = i * 5
            if (i % 5 == 0) {
                val labelColor = if (grayValue < 128) Color.WHITE else Color.BLACK
                val stepLabelPaint = Paint().apply {
                    color = labelColor
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("${pct}%", left + (stepWidth / 2f), y + 34f, stepLabelPaint)
            }
        }
        y += stepHeight + 40f

        // 7. COLOR BLOCKS (CMYK & RGB)
        canvas.drawText("3. COLOR REPRODUCTION BLOCKS (CYAN, MAGENTA, YELLOW, KEY, RGB):", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 30f

        val colorList = listOf(
            Pair("CYAN", Color.rgb(0, 255, 255)),
            Pair("MAGENTA", Color.rgb(255, 0, 255)),
            Pair("YELLOW", Color.rgb(255, 255, 0)),
            Pair("BLACK", Color.rgb(0, 0, 0)),
            Pair("RED", Color.rgb(255, 0, 0)),
            Pair("GREEN", Color.rgb(0, 180, 0)),
            Pair("BLUE", Color.rgb(0, 0, 255))
        )
        val cWidth = (lineEndX - lineStartX) / colorList.size
        val cHeight = 55f

        for (i in colorList.indices) {
            val (cName, cVal) = colorList[i]
            val cPaint = Paint().apply {
                color = cVal
                style = Paint.Style.FILL
            }
            val left = lineStartX + (i * cWidth)
            canvas.drawRect(left, y, left + cWidth, y + cHeight, cPaint)
            canvas.drawRect(left, y, left + cWidth, y + cHeight, borderPaint)

            val textC = if (cName == "BLACK" || cName == "BLUE" || cName == "RED") Color.WHITE else Color.BLACK
            val cLabelPaint = Paint().apply {
                color = textC
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(cName, left + (cWidth / 2f), y + 36f, cLabelPaint)
        }
        y += cHeight + 40f

        // 8. GEOMETRIC SHAPES
        canvas.drawText("4. GEOMETRIC REGISTRATION & ALIGNMENT SHAPES:", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 40f

        val shapePaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val shapeStroke = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }

        val shapeSpacing = (lineEndX - lineStartX) / 5f
        var sx = lineStartX + (shapeSpacing / 2f)

        // Circle
        canvas.drawCircle(sx, y + 25f, 25f, shapePaint)
        canvas.drawCircle(sx, y + 25f, 25f, shapeStroke)
        sx += shapeSpacing

        // Square
        canvas.drawRect(sx - 25f, y, sx + 25f, y + 50f, shapePaint)
        sx += shapeSpacing

        // Triangle
        val triPath = android.graphics.Path().apply {
            moveTo(sx, y)
            lineTo(sx - 30f, y + 50f)
            lineTo(sx + 30f, y + 50f)
            close()
        }
        canvas.drawPath(triPath, shapePaint)
        sx += shapeSpacing

        // Diamond
        val diaPath = android.graphics.Path().apply {
            moveTo(sx, y)
            lineTo(sx + 30f, y + 25f)
            lineTo(sx, y + 50f)
            lineTo(sx - 30f, y + 25f)
            close()
        }
        canvas.drawPath(diaPath, shapePaint)
        sx += shapeSpacing

        // Star / Crosshair
        canvas.drawLine(sx - 28f, y + 25f, sx + 28f, y + 25f, shapeStroke)
        canvas.drawLine(sx, y - 3f, sx, y + 53f, shapeStroke)
        canvas.drawCircle(sx, y + 25f, 15f, shapeStroke)

        y += 80f

        // 9. SMALL TEXT AND FINANCIAL TABLE
        canvas.drawText("5. SMALL TEXT & TABLE FIDELITY:", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 35f
        val pointSizes = listOf(6, 8, 10, 12)
        for (points in pointSizes) {
            val samplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = points * candidate.resolutionDpi / 72f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }
            canvas.drawText(
                "${points}pt  Invoice  #10482  Qty 17  Rate 123.45  Total 2,098.65  ,.;:!?%",
                lineStartX,
                y,
                samplePaint
            )
            y += samplePaint.textSize * 1.35f
        }

        val tableTop = y + 12f
        val rowHeight = 42f
        val columns = floatArrayOf(
            lineStartX,
            lineStartX + 520f,
            lineStartX + 780f,
            lineStartX + 1030f,
            lineEndX
        )
        val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        repeat(6) { row ->
            val rowY = tableTop + row * rowHeight
            canvas.drawLine(columns.first(), rowY, columns.last(), rowY, tablePaint)
        }
        columns.forEach { x ->
            canvas.drawLine(x, tableTop, x, tableTop + 5 * rowHeight, tablePaint)
        }
        val tableText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 24f
        }
        val rows = listOf(
            listOf("Description", "Quantity", "Rate", "Amount"),
            listOf("Precision print service", "2", "425.75", "851.50"),
            listOf("Fine-line calibration", "1", "118.25", "118.25"),
            listOf("Colour registration", "3", "92.40", "277.20"),
            listOf("Total", "", "", "1,246.95")
        )
        rows.forEachIndexed { row, cells ->
            cells.forEachIndexed { column, value ->
                canvas.drawText(value, columns[column] + 10f, tableTop + row * rowHeight + 29f, tableText)
            }
        }
        y = tableTop + 5 * rowHeight + 50f

        // 10. CONTINUOUS TONE AND CHECKERBOARD
        canvas.drawText("6. TONE PRESERVATION & 1-PIXEL PATTERNS:", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 30f
        val gradientPaint = Paint().apply {
            shader = LinearGradient(
                lineStartX,
                y,
                lineEndX,
                y,
                Color.WHITE,
                Color.BLACK,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(lineStartX, y, lineEndX, y + 90f, gradientPaint)
        y += 115f
        val cell = 8f
        val checkerPaint = Paint().apply { isAntiAlias = false }
        for (row in 0 until 12) {
            for (column in 0 until 64) {
                checkerPaint.color = if ((row + column) and 1 == 0) Color.BLACK else Color.WHITE
                canvas.drawRect(
                    lineStartX + column * cell,
                    y + row * cell,
                    lineStartX + (column + 1) * cell,
                    y + (row + 1) * cell,
                    checkerPaint
                )
            }
        }
        y += 12 * cell + 45f
        canvas.drawText("7. REFERENCE SKIN, SHADOW & HIGHLIGHT PATCHES:", (margin + 40).toFloat(), y, sectionHeaderPaint)
        y += 30f
        val skinAndNeutral = listOf(
            Color.rgb(255, 224, 189),
            Color.rgb(234, 192, 134),
            Color.rgb(198, 134, 66),
            Color.rgb(141, 85, 36),
            Color.rgb(48, 27, 18),
            Color.rgb(16, 16, 16),
            Color.rgb(224, 224, 224),
            Color.WHITE
        )
        val referencePatchWidth = (lineEndX - lineStartX) / skinAndNeutral.size
        skinAndNeutral.forEachIndexed { index, color ->
            val left = lineStartX + index * referencePatchWidth
            canvas.drawRect(
                left,
                y,
                left + referencePatchWidth,
                y + 75f,
                Paint().apply { this.color = color }
            )
        }
        y += 100f
        val photoReference = createPhotoReferenceBitmap(640, 240)
        canvas.drawBitmap(
            photoReference,
            null,
            android.graphics.RectF(lineStartX, y, lineEndX, y + 260f),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        )
        photoReference.recycle()

        // Footer
        val footPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 24f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        canvas.drawText(
            "PocketPrint Phase 3 Engine · Auto-Testing & Protocol Discovery · github.com/pocketprint",
            (margin + 40).toFloat(),
            (height - margin - 30).toFloat(),
            footPaint
        )

        return bitmap
    }

    /** Deterministic continuous-tone scene used identically by every benchmark profile. */
    private fun createPhotoReferenceBitmap(width: Int, height: Int): Bitmap {
        val pixels = IntArray(width * height)
        var noiseState = 0x4A3B2C1D
        for (y in 0 until height) {
            for (x in 0 until width) {
                noiseState = noiseState * 1_664_525 + 1_013_904_223
                val noise = ((noiseState ushr 24) and 0x0F) - 7
                val skyRatio = y / height.toFloat()
                var red = (70 + 80 * (1f - skyRatio)).toInt()
                var green = (120 + 90 * (1f - skyRatio)).toInt()
                var blue = (175 + 70 * (1f - skyRatio)).toInt()

                val faceX = (x - width * 0.52f) / (width * 0.16f)
                val faceY = (y - height * 0.48f) / (height * 0.38f)
                val faceDistance = faceX * faceX + faceY * faceY
                if (faceDistance < 1f) {
                    val light = (1f - faceDistance) * 35f - faceX * 22f
                    red = (190 + light).toInt()
                    green = (125 + light * 0.65f).toInt()
                    blue = (82 + light * 0.45f).toInt()
                    if (faceY < -0.72f || (faceX * faceX + (faceY + 0.65f) * (faceY + 0.65f)) < 0.20f) {
                        red = 38 + noise
                        green = 27 + noise
                        blue = 24 + noise
                    }
                } else if (y > height * 0.68f) {
                    val foliage = ((x * 17 + y * 31 + noiseState) ushr 5) and 0x3F
                    red = 28 + foliage / 3
                    green = 62 + foliage
                    blue = 30 + foliage / 2
                }
                pixels[y * width + x] = Color.rgb(
                    (red + noise).coerceIn(0, 255),
                    (green + noise).coerceIn(0, 255),
                    (blue + noise).coerceIn(0, 255)
                )
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Generates a dedicated full-color diagnostic test document bitmap matching Phase 4 criteria:
     * - Primary colors (Pure Red, Pure Green, Pure Blue)
     * - Secondary colors (Cyan, Magenta, Yellow)
     * - Neutral density steps (Black, 75%, 50%, 25%, White)
     * - Continuous RGB spectrum gradient ramp
     * - Color checkerboard & fine text detail
     * - Thin coloured line calibration
     */
    fun createColorTestBitmap(candidate: DiscoveryCandidate): Bitmap {
        val width = (candidate.resolutionDpi * 8.27).toInt() // A4 width: 8.27 in (~2480 at 300 DPI)
        val height = (candidate.resolutionDpi * 11.69).toInt() // A4 height: 11.69 in (~3508 at 300 DPI)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val margin = (candidate.resolutionDpi * 0.5f).toInt()
        val contentWidth = width - (2 * margin)

        // 1. Header Banner
        val bannerPaint = Paint().apply {
            color = Color.rgb(20, 40, 80)
            style = Paint.Style.FILL
        }
        canvas.drawRect(margin.toFloat(), margin.toFloat(), (width - margin).toFloat(), (margin + 160).toFloat(), bannerPaint)

        val titlePaint = Paint().apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
        canvas.drawText("POCKETPRINT COLOR CALIBRATION & DIAGNOSTIC", (margin + 30).toFloat(), (margin + 80).toFloat(), titlePaint)

        val subPaint = Paint().apply {
            color = Color.rgb(200, 220, 255)
            textSize = 24f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        canvas.drawText("HP Ink Tank 310 · Pcl3Gui2 Dual-Plane (K Mode 9 + RGB Mode 10) · 300 DPI", (margin + 30).toFloat(), (margin + 125).toFloat(), subPaint)

        var y = (margin + 200).toFloat()

        val labelPaint = Paint().apply {
            color = Color.BLACK
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val textPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 22f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }

        // 2. Primary Colours (RGB)
        canvas.drawText("1. PRIMARY ADDITIVE COLOURS (RGB):", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val blockWidth = (contentWidth - 60) / 3f
        val blockHeight = 120f
        val primaries = listOf(
            Triple("PURE RED (#FF0000)", Color.rgb(255, 0, 0), margin + 10f),
            Triple("PURE GREEN (#00FF00)", Color.rgb(0, 255, 0), margin + 30f + blockWidth),
            Triple("PURE BLUE (#0000FF)", Color.rgb(0, 0, 255), margin + 50f + blockWidth * 2)
        )
        for ((name, col, x) in primaries) {
            val p = Paint().apply { color = col; style = Paint.Style.FILL }
            canvas.drawRect(x, y, x + blockWidth, y + blockHeight, p)
            val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
            canvas.drawRect(x, y, x + blockWidth, y + blockHeight, stroke)
            canvas.drawText(name, x + 10f, y + blockHeight + 30f, textPaint)
        }
        y += blockHeight + 60f

        // 3. Secondary Colours (CMY)
        canvas.drawText("2. SECONDARY SUBTRACTIVE COLOURS (CMY):", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val secondaries = listOf(
            Triple("CYAN (#00FFFF)", Color.rgb(0, 255, 255), margin + 10f),
            Triple("MAGENTA (#FF00FF)", Color.rgb(255, 0, 255), margin + 30f + blockWidth),
            Triple("YELLOW (#FFFF00)", Color.rgb(255, 255, 0), margin + 50f + blockWidth * 2)
        )
        for ((name, col, x) in secondaries) {
            val p = Paint().apply { color = col; style = Paint.Style.FILL }
            canvas.drawRect(x, y, x + blockWidth, y + blockHeight, p)
            val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
            canvas.drawRect(x, y, x + blockWidth, y + blockHeight, stroke)
            canvas.drawText(name, x + 10f, y + blockHeight + 30f, textPaint)
        }
        y += blockHeight + 60f

        // 4. Neutral Density Steps
        canvas.drawText("3. NEUTRAL DENSITY STEPS (K Pigment vs Dye Neutral):", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val neutralBlockW = (contentWidth - 80) / 5f
        val neutrals = listOf(
            Pair("100% Black", Color.rgb(0, 0, 0)),
            Pair("75% Gray", Color.rgb(64, 64, 64)),
            Pair("50% Gray", Color.rgb(128, 128, 128)),
            Pair("25% Gray", Color.rgb(192, 192, 192)),
            Pair("0% White", Color.rgb(255, 255, 255))
        )
        for (i in neutrals.indices) {
            val (name, col) = neutrals[i]
            val x = margin + 10f + (i * (neutralBlockW + 15f))
            val p = Paint().apply { color = col; style = Paint.Style.FILL }
            canvas.drawRect(x, y, x + neutralBlockW, y + 80f, p)
            val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
            canvas.drawRect(x, y, x + neutralBlockW, y + 80f, stroke)
            canvas.drawText(name, x + 5f, y + 110f, textPaint)
        }
        y += 140f

        // 5. Continuous RGB Spectrum Ramp
        canvas.drawText("4. CONTINUOUS RGB SPECTRUM RAMP:", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val gradPaint = Paint().apply {
            shader = LinearGradient(
                (margin + 10).toFloat(), y,
                (width - margin - 10).toFloat(), y,
                intArrayOf(
                    Color.RED, Color.YELLOW, Color.GREEN,
                    Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect((margin + 10).toFloat(), y, (width - margin - 10).toFloat(), y + 80f, gradPaint)
        val gradStroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2f }
        canvas.drawRect((margin + 10).toFloat(), y, (width - margin - 10).toFloat(), y + 80f, gradStroke)
        y += 120f

        // 6. Checkerboard & Fine Color Text
        canvas.drawText("5. COLOR REGISTRATION & FINE TEXT TARGETS:", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val checkTileSize = 40f
        val checkCols = 12
        val checkRows = 4
        for (r in 0 until checkRows) {
            for (c in 0 until checkCols) {
                val cx = margin + 10f + (c * checkTileSize)
                val cy = y + (r * checkTileSize)
                val isColor1 = (r + c) % 2 == 0
                val tilePaint = Paint().apply {
                    color = if (isColor1) Color.rgb(0, 180, 220) else Color.rgb(240, 100, 30)
                }
                canvas.drawRect(cx, cy, cx + checkTileSize, cy + checkTileSize, tilePaint)
            }
        }

        val textStartX = margin + 20f + (checkCols * checkTileSize)
        var textY = y + 30f
        val sampleColors = listOf(
            Pair("PCL3GUI2 Ink Tank 310 Native Engine (Black)", Color.BLACK),
            Pair("RGB Mode 10 Full Contone Cyan Plane", Color.rgb(0, 150, 220)),
            Pair("High Contrast Magenta Linework 12pt", Color.rgb(200, 0, 150)),
            Pair("Micro-Detail Yellow/Amber Diagnostic 10pt", Color.rgb(180, 140, 0))
        )
        for ((str, col) in sampleColors) {
            val p = Paint().apply {
                color = col
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                isAntiAlias = true
            }
            canvas.drawText(str, textStartX, textY, p)
            textY += 35f
        }
        y += (checkRows * checkTileSize) + 50f

        // 7. Thin line registration
        canvas.drawText("6. THIN LINE REGISTRATION (1px, 2px, 4px):", (margin + 20).toFloat(), y, labelPaint)
        y += 40f

        val lineColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK)
        for (col in lineColors) {
            val lp1 = Paint().apply { color = col; strokeWidth = 2f }
            val lp2 = Paint().apply { color = col; strokeWidth = 5f }
            val lp3 = Paint().apply { color = col; strokeWidth = 10f }
            canvas.drawLine((margin + 10).toFloat(), y, (width - margin - 10).toFloat(), y, lp1)
            canvas.drawLine((margin + 10).toFloat(), y + 15f, (width - margin - 10).toFloat(), y + 15f, lp2)
            canvas.drawLine((margin + 10).toFloat(), y + 35f, (width - margin - 10).toFloat(), y + 35f, lp3)
            y += 55f
        }

        // 8. Skin tones, shadow detail, and highlight detail
        canvas.drawText("7. SKIN TONES, SHADOWS & HIGHLIGHTS:", (margin + 20).toFloat(), y, labelPaint)
        y += 35f
        val referenceColors = listOf(
            Color.rgb(255, 224, 189),
            Color.rgb(234, 192, 134),
            Color.rgb(198, 134, 66),
            Color.rgb(141, 85, 36),
            Color.rgb(84, 48, 26),
            Color.rgb(48, 27, 18)
        )
        val referenceWidth = (contentWidth - 20f) / referenceColors.size
        referenceColors.forEachIndexed { index, color ->
            val left = margin + 10f + index * referenceWidth
            canvas.drawRect(left, y, left + referenceWidth, y + 80f, Paint().apply { this.color = color })
        }
        y += 100f
        val shadowGradient = Paint().apply {
            shader = LinearGradient(
                (margin + 10).toFloat(),
                y,
                width / 2f - 10f,
                y,
                Color.rgb(0, 0, 0),
                Color.rgb(64, 64, 64),
                Shader.TileMode.CLAMP
            )
        }
        val highlightGradient = Paint().apply {
            shader = LinearGradient(
                width / 2f + 10f,
                y,
                (width - margin - 10).toFloat(),
                y,
                Color.rgb(192, 192, 192),
                Color.WHITE,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect((margin + 10).toFloat(), y, width / 2f - 10f, y + 70f, shadowGradient)
        canvas.drawRect(width / 2f + 10f, y, (width - margin - 10).toFloat(), y + 70f, highlightGradient)

        val footPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 24f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        canvas.drawText(
            "PocketPrint Phase 4 Engine · Verified Color Profile · github.com/pocketprint",
            (margin + 40).toFloat(),
            (height - margin - 30).toFloat(),
            footPaint
        )

        return bitmap
    }

    /**
     * Synthesizes the full byte payload for a given candidate configuration.
     */
    fun generatePayload(candidate: DiscoveryCandidate, bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()

        // PCL3GUI2 encoders own their complete FIFO flush, PJL envelope, raster,
        // and job trailer. Do not prepend a second envelope here.
        when (candidate.protocol) {
            DiscoveryProtocol.PCL3GUI2_K_MODE9 -> {
                HpPcl3Gui2Encoder().encodePage(
                    bitmap,
                    PrintOptions(dpi = candidate.resolutionDpi),
                    out
                )
                return out.toByteArray()
            }
            DiscoveryProtocol.PCL3GUI2_COLOR_MODE10 -> {
                HpPcl3Gui2ColorEncoder().encodePage(
                    bitmap,
                    PrintOptions(dpi = candidate.resolutionDpi),
                    out
                )
                return out.toByteArray()
            }
            else -> Unit
        }

        // 1. Framing header
        when (candidate.framingStyle) {
            FramingStyle.PJL_ENTER_PCL3GUI -> {
                out.write("\u001b%-12345X@PJL\r\n".toByteArray(Charsets.ISO_8859_1))
                out.write("@PJL SET STRINGCODESET=UTF8\r\n".toByteArray(Charsets.ISO_8859_1))
                out.write("@PJL ENTER LANGUAGE=PCL3GUI\r\n".toByteArray(Charsets.ISO_8859_1))
            }
            FramingStyle.RAW_UEL -> {
                out.write("\u001b%-12345X".toByteArray(Charsets.ISO_8859_1))
            }
            FramingStyle.NONE -> {
                // No prefix
            }
        }

        // 2. Protocol body
        when (candidate.protocol) {
            DiscoveryProtocol.PCL3GUI2_K_MODE9,
            DiscoveryProtocol.PCL3GUI2_COLOR_MODE10 -> error("PCL3GUI2 handled before outer framing")
            DiscoveryProtocol.PCL3GUI_MODE9 -> {
                encodePcl3GuiMode9(candidate, bitmap, out)
            }
            DiscoveryProtocol.PCL3GUI_MODE2 -> {
                encodePcl3GuiMode2(candidate, bitmap, out)
            }
            DiscoveryProtocol.PCL3GUI_MODE0 -> {
                encodePcl3GuiMode0(candidate, bitmap, out)
            }
            DiscoveryProtocol.PCL_SIMPLE_RASTER -> {
                encodePclSimple(candidate, bitmap, out)
            }
            DiscoveryProtocol.PCLM_RAW -> {
                encodePclmRaw(candidate, bitmap, out)
            }
            DiscoveryProtocol.PCLM_WRAPPED -> {
                encodePclmWrapped(candidate, bitmap, out)
            }
        }

        // 3. Framing footer
        when (candidate.framingStyle) {
            FramingStyle.PJL_ENTER_PCL3GUI,
            FramingStyle.RAW_UEL -> {
                out.write("\u001b%-12345X".toByteArray(Charsets.ISO_8859_1))
            }
            FramingStyle.NONE -> {
                // No suffix
            }
        }

        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // Protocol Encoders
    // -------------------------------------------------------------------------

    private fun encodePcl3GuiMode9(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        // Printer reset
        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))

        // 8-byte Configure Raster Data (Format 2, 1 component, 1 bpp):
        //   ESC*g8W + [format=2, nComp=1, bitsPerIdx=0, bitsPerPx=1, xDPI BE, yDPI BE]
        val dpi = candidate.resolutionDpi
        val crd = byteArrayOf(
            0x1B, 0x2A, 0x67, 0x38, 0x57,          // ESC*g8W
            0x02,                                    // format = 2 (direct pixel)
            0x01,                                    // nComponents = 1 (K only)
            0x00,                                    // bits per index = 0
            0x01,                                    // bits per pixel = 1
            (dpi shr 8).toByte(),                   // xDPI high
            (dpi and 0xFF).toByte(),                // xDPI low
            (dpi shr 8).toByte(),                   // yDPI high
            (dpi and 0xFF).toByte()                 // yDPI low
        )
        out.write(crd)

        // Set resolution
        out.write("\u001b*t${candidate.resolutionDpi}R".toByteArray(Charsets.ISO_8859_1))

        // Start raster graphics at current cursor (0,0)
        out.write("\u001b*r1A".toByteArray(Charsets.ISO_8859_1))

        // Set compression Mode 9 (Delta row)
        out.write("\u001b*b9M".toByteArray(Charsets.ISO_8859_1))

        val prevRow = ByteArray(bytesPerRow)
        val currentRow = ByteArray(bytesPerRow)
        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            // Convert ARGB to 1-bit monochrome row (threshold 128)
            var byteIdx = 0
            var bitIdx = 7
            var currentByte = 0

            for (x in 0 until width) {
                val p = pixels[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val lum = (299 * r + 587 * g + 114 * b) / 1000
                if (lum < 128) {
                    currentByte = currentByte or (1 shl bitIdx)
                }
                bitIdx--
                if (bitIdx < 0) {
                    currentRow[byteIdx++] = currentByte.toByte()
                    currentByte = 0
                    bitIdx = 7
                }
            }
            if (bitIdx != 7 && byteIdx < bytesPerRow) {
                currentRow[byteIdx] = currentByte.toByte()
            }

            // Encode Mode 9 Delta row
            val delta = encodeMode9Delta(prevRow, currentRow)
            out.write("\u001b*b${delta.size}W".toByteArray(Charsets.ISO_8859_1))
            out.write(delta)

            System.arraycopy(currentRow, 0, prevRow, 0, bytesPerRow)
        }

        // End raster graphics
        out.write("\u001b*rC".toByteArray(Charsets.ISO_8859_1))
        // Form feed
        out.write(byteArrayOf(0x0C))
        // Reset
        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))
    }

    private fun encodePcl3GuiMode2(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))
        out.write("\u001b*t${candidate.resolutionDpi}R".toByteArray(Charsets.ISO_8859_1))
        out.write("\u001b*r1A".toByteArray(Charsets.ISO_8859_1))
        out.write("\u001b*b2M".toByteArray(Charsets.ISO_8859_1)) // Mode 2: TIFF PackBits

        val currentRow = ByteArray(bytesPerRow)
        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var byteIdx = 0
            var bitIdx = 7
            var currentByte = 0
            for (x in 0 until width) {
                val p = pixels[x]
                val lum = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                if (lum < 128) currentByte = currentByte or (1 shl bitIdx)
                bitIdx--
                if (bitIdx < 0) {
                    currentRow[byteIdx++] = currentByte.toByte()
                    currentByte = 0
                    bitIdx = 7
                }
            }
            if (bitIdx != 7 && byteIdx < bytesPerRow) currentRow[byteIdx] = currentByte.toByte()

            val packbits = encodePackBits(currentRow)
            out.write("\u001b*b${packbits.size}W".toByteArray(Charsets.ISO_8859_1))
            out.write(packbits)
        }

        out.write("\u001b*rB".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x0C))
        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))
    }

    private fun encodePcl3GuiMode0(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width)

        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))

        when (candidate.colorMode) {
            com.pocketprint.data.discovery.model.DiscoveryColorMode.GRAYSCALE_8BIT -> {
                // 8-bit grayscale: 1 byte per pixel, 256 shades
                val bytesPerRow = width
                out.write("\u001b*t${candidate.resolutionDpi}R".toByteArray(Charsets.ISO_8859_1))
                out.write("\u001b*v6W".toByteArray(Charsets.ISO_8859_1)) // color mode: device RGB
                out.write("\u001b*r1A".toByteArray(Charsets.ISO_8859_1))
                out.write("\u001b*b0M".toByteArray(Charsets.ISO_8859_1))

                val currentRow = ByteArray(bytesPerRow)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) {
                        val p = pixels[x]
                        val lum = ((299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000).coerceIn(0, 255)
                        // Invert: 0 = white in PCL (no ink), 255 = black (full ink)
                        currentRow[x] = (255 - lum).toByte()
                    }
                    out.write("\u001b*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1))
                    out.write(currentRow)
                }
            }
            else -> {
                // 1-bit monochrome (default)
                val bytesPerRow = (width + 7) / 8
                out.write("\u001b*t${candidate.resolutionDpi}R".toByteArray(Charsets.ISO_8859_1))
                out.write("\u001b*r1A".toByteArray(Charsets.ISO_8859_1))
                out.write("\u001b*b0M".toByteArray(Charsets.ISO_8859_1))

                val currentRow = ByteArray(bytesPerRow)
                for (y in 0 until height) {
                    bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
                    var byteIdx = 0
                    var bitIdx = 7
                    var currentByte = 0
                    for (x in 0 until width) {
                        val p = pixels[x]
                        val lum = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                        if (lum < 128) currentByte = currentByte or (1 shl bitIdx)
                        bitIdx--
                        if (bitIdx < 0) {
                            currentRow[byteIdx++] = currentByte.toByte()
                            currentByte = 0
                            bitIdx = 7
                        }
                    }
                    if (bitIdx != 7 && byteIdx < bytesPerRow) currentRow[byteIdx] = currentByte.toByte()
                    out.write("\u001b*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1))
                    out.write(currentRow)
                }
            }
        }

        out.write("\u001b*rB".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(0x0C))
        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))
    }

    private fun encodePclSimple(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1)) // Reset
        out.write("\u001b&l26A".toByteArray(Charsets.ISO_8859_1)) // Page size: A4
        out.write("\u001b*t${candidate.resolutionDpi}R".toByteArray(Charsets.ISO_8859_1))
        out.write("\u001b*r1A".toByteArray(Charsets.ISO_8859_1)) // Start raster

        val currentRow = ByteArray(bytesPerRow)
        val pixels = IntArray(width)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            var byteIdx = 0
            var bitIdx = 7
            var currentByte = 0
            for (x in 0 until width) {
                val p = pixels[x]
                val lum = (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
                if (lum < 128) currentByte = currentByte or (1 shl bitIdx)
                bitIdx--
                if (bitIdx < 0) {
                    currentRow[byteIdx++] = currentByte.toByte()
                    currentByte = 0
                    bitIdx = 7
                }
            }
            if (bitIdx != 7 && byteIdx < bytesPerRow) currentRow[byteIdx] = currentByte.toByte()

            out.write("\u001b*b${bytesPerRow}W".toByteArray(Charsets.ISO_8859_1))
            out.write(currentRow)
        }

        out.write("\u001b*rB".toByteArray(Charsets.ISO_8859_1)) // End raster
        out.write(byteArrayOf(0x0C)) // Form feed
        out.write("\u001bE".toByteArray(Charsets.ISO_8859_1))
    }

    private fun encodePclmRaw(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val encoder = HpPclmEncoder()
        encoder.encodePage(bitmap, PrintOptions(dpi = candidate.resolutionDpi), out)
    }

    private fun encodePclmWrapped(candidate: DiscoveryCandidate, bitmap: Bitmap, out: ByteArrayOutputStream) {
        val pclmStream = ByteArrayOutputStream()
        val encoder = HpPclmEncoder()
        encoder.encodePage(bitmap, PrintOptions(dpi = candidate.resolutionDpi), pclmStream)
        out.write(pclmStream.toByteArray())
    }

    // -------------------------------------------------------------------------
    // Compression Algorithms
    // -------------------------------------------------------------------------

    private fun encodeMode9Delta(prev: ByteArray, curr: ByteArray): ByteArray {
        return com.pocketprint.data.print.encoder.HpMode9Compressor.compress(curr, prev, curr.size)
    }

    private fun encodePackBits(src: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        val len = src.size

        while (i < len) {
            var run = 1
            while (i + run < len && src[i + run] == src[i] && run < 128) {
                run++
            }
            if (run >= 3) {
                out.write(1 - run)
                out.write(src[i].toInt())
                i += run
            } else {
                var lit = 0
                while (i + lit < len && lit < 128) {
                    if (i + lit + 2 < len && src[i + lit] == src[i + lit + 1] && src[i + lit] == src[i + lit + 2]) {
                        break
                    }
                    lit++
                }
                if (lit > 0) {
                    out.write(lit - 1)
                    out.write(src, i, lit)
                    i += lit
                }
            }
        }
        return out.toByteArray()
    }
}
