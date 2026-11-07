package com.pocketprint.data.print.model

import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import kotlin.math.roundToInt

/**
 * Standard paper sizes with corresponding PCL paper codes and metric dimensions.
 */
enum class PaperSize(val codePcl: Int, val widthMm: Float, val heightMm: Float, val displayName: String) {
    A4(26, 210.0f, 297.0f, "A4 (210 × 297 mm)"),
    A5(25, 148.0f, 210.0f, "A5 (148 × 210 mm)"),
    A6(24, 105.0f, 148.0f, "A6 (105 × 148 mm)"),
    LETTER(2, 215.9f, 279.4f, "US Letter (8.5 × 11 in)"),
    LEGAL(3, 215.9f, 355.6f, "US Legal (8.5 × 14 in)"),
    EXECUTIVE(1, 184.1f, 266.7f, "Executive (7.25 × 10.5 in)"),
    B5(100, 176.0f, 250.0f, "JIS B5 (176 × 250 mm)"),
    CUSTOM(101, 210.0f, 297.0f, "Custom Size")
}

/**
 * Document rendering and printer color modes.
 */
enum class PrintColorMode(val displayName: String) {
    MONOCHROME_1BIT("Black & White (1-bit Ink Jet)"),
    GRAYSCALE_8BIT("Grayscale (Neutral Luminance)"),
    COLOR_RGB("Full Color (RGB)")
}

enum class PrintingMode(val displayName: String) {
    AUTO("Automatic"),
    BLACK_AND_WHITE("Black and white"),
    GRAYSCALE("Grayscale"),
    COLOR("Full color")
}

/**
 * Document presentation orientation.
 */
enum class PrintOrientation(val displayName: String) {
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

/**
 * Document scaling when rendering to the physical paper size.
 */
enum class PrintScaling(val displayName: String) {
    FIT_TO_PAGE("Fit to Page (Maintain Aspect Ratio)"),
    ACTUAL_SIZE("Actual Size (100% Scale, Center/Crop)"),
    FILL_PAGE("Fill Page (Scale to Fill)")
}

enum class PageSet(val displayName: String) {
    ALL("All selected pages"),
    ODD("Odd pages only"),
    EVEN("Even pages only")
}

/** Physical page margins in millimetres. */
data class PrintMargins(
    val leftMm: Float = DEFAULT_MARGIN_MM,
    val topMm: Float = DEFAULT_MARGIN_MM,
    val rightMm: Float = DEFAULT_MARGIN_MM,
    val bottomMm: Float = DEFAULT_MARGIN_MM
) {
    init {
        require(listOf(leftMm, topMm, rightMm, bottomMm).all { it.isFinite() && it >= 0f }) {
            "Print margins must be finite, non-negative millimetre values"
        }
    }

    companion object {
        const val DEFAULT_MARGIN_MM = 5f

        val NONE = uniform(0f)
        val MINIMUM = uniform(DEFAULT_MARGIN_MM)
        val NORMAL = uniform(10f)
        val WIDE = uniform(20f)

        fun uniform(millimetres: Float): PrintMargins = PrintMargins(
            leftMm = millimetres,
            topMm = millimetres,
            rightMm = millimetres,
            bottomMm = millimetres
        )
    }
}

/** Integer pixel rectangle within the full paper bitmap. Right/bottom are exclusive. */
data class PrintPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Complete options for a print job.
 */
data class PrintOptions(
    val paperSize: PaperSize = PaperSize.A4,
    val dpi: Int = 300,
    val printingMode: PrintingMode = PrintingMode.AUTO,
    val hybridProcessingEnabled: Boolean = true,
    val colorMode: PrintColorMode = PrintColorMode.MONOCHROME_1BIT,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val scaling: PrintScaling = PrintScaling.FIT_TO_PAGE,
    val margins: PrintMargins = PrintMargins.MINIMUM,
    val pageRange: String = "",
    val pageSet: PageSet = PageSet.ALL,
    val qualityProfile: PrintQualityProfile = PrintQualityProfile.NORMAL,
    val monochromeStrategy: MonochromeStrategy = MonochromeStrategy.AUTO,
    val monochromeThreshold: Int = 144,
    val copies: Int = 1,
    val collate: Boolean = true
) {
    init {
        require(monochromeThreshold in 0..255) { "Monochrome threshold must be in 0..255" }
    }
    // Legacy compatibility property
    val isMonochrome: Boolean
        get() = colorMode == PrintColorMode.MONOCHROME_1BIT

    val widthPixels: Int
        get() {
            val (wMm, _) = if (orientation == PrintOrientation.PORTRAIT) {
                Pair(paperSize.widthMm, paperSize.heightMm)
            } else {
                Pair(paperSize.heightMm, paperSize.widthMm)
            }
            return ((wMm / 25.4f) * dpi).roundToInt()
        }

    val heightPixels: Int
        get() {
            val (_, hMm) = if (orientation == PrintOrientation.PORTRAIT) {
                Pair(paperSize.widthMm, paperSize.heightMm)
            } else {
                Pair(paperSize.heightMm, paperSize.widthMm)
            }
            return ((hMm / 25.4f) * dpi).roundToInt()
        }

    val bytesPerRow: Int
        get() = (widthPixels + 7) / 8

    val printableBounds: PrintPixelBounds
        get() {
            val left = millimetresToPixels(margins.leftMm)
            val top = millimetresToPixels(margins.topMm)
            val right = widthPixels - millimetresToPixels(margins.rightMm)
            val bottom = heightPixels - millimetresToPixels(margins.bottomMm)
            require(right > left && bottom > top) {
                "Margins leave no printable area on ${paperSize.displayName}"
            }
            return PrintPixelBounds(left, top, right, bottom)
        }

    val printableWidthMm: Float
        get() = orientedPaperWidthMm - margins.leftMm - margins.rightMm

    val printableHeightMm: Float
        get() = orientedPaperHeightMm - margins.topMm - margins.bottomMm

    private val orientedPaperWidthMm: Float
        get() = if (orientation == PrintOrientation.PORTRAIT) paperSize.widthMm else paperSize.heightMm

    private val orientedPaperHeightMm: Float
        get() = if (orientation == PrintOrientation.PORTRAIT) paperSize.heightMm else paperSize.widthMm

    private fun millimetresToPixels(value: Float): Int = ((value / 25.4f) * dpi).roundToInt()
}

/**
 * Granular print job execution states matching the product specification.
 */
enum class PrintJobState(val displayName: String) {
    IDLE("Idle"),
    QUEUED("Queued"),
    DOWNLOADING("Downloading Document"),
    VALIDATING("Validating Format"),
    CONVERTING("Converting Document"),
    PREPARING_DOCUMENT("Preparing Document"),
    RENDERING_BITMAP("Rendering Pages"),
    ENCODING_RASTER("Encoding Raster"),
    READY("Ready to Transmit"),
    TRANSMITTING_USB("Sending print data"),
    /** All bytes confirmed; issuing ZLP / end-of-job finalization. */
    FINALIZING("Finalizing print job"),
    /** All bytes confirmed at USB layer; printer is processing internally. */
    PRINTER_PROCESSING("Waiting for printer"),
    /** A bulk transfer stalled; connection valid; retrying with backoff. */
    TRANSFER_RECOVERING("Recovering USB transfer"),
    /** A bulk transfer failed; USB link may still be valid. */
    TRANSFER_ERROR("USB transfer error"),
    /** The physical USB device disappeared during the job. */
    DISCONNECTED("Printer disconnected"),
    COMPLETED_SUCCESS("Print job completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
    /** Transfer outcome is ambiguous; job must not be automatically replayed. */
    NEEDS_RECOVERY("Transfer outcome uncertain"),
    NEEDS_ATTENTION("Needs Attention")
}

/**
 * Live print progress update with full transfer diagnostics.
 */
data class PrintProgress(
    val state: PrintJobState = PrintJobState.IDLE,
    val progressPercent: Int = 0,
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val message: String = "Ready",
    // Extended fields for Phase 5 UI
    val retryCount: Int = 0,
    val lastChunkMs: Long = 0,
    val transferSpeedBps: Long = 0,
    val connectionState: String = "",
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val currentChunkSize: Int = 0,
    val currentOffset: Long = 0,
    val lastTransferResult: String = "",
    val portStatus: String = "",
    val elapsedMs: Long = 0,
    val recoveryRequired: Boolean = false,
    val detail: String = "",
    val documentName: String = "",
    val documentPage: Int = 0,
    val documentPageCount: Int = 0,
    val scheduledItem: Int = 0,
    val scheduledItemCount: Int = 0,
    val completedItems: Int = 0,
    val copyNumber: Int = 0,
    val copyCount: Int = 0,
    val overallProgressPercent: Int = 0
)
