package com.pocketprint.data.print.quality

/** Stateful row rasterizer. Call [rasterizeRow] in top-to-bottom page order. */
class MonochromeRowRasterizer @JvmOverloads constructor(
    val width: Int,
    strategy: MonochromeStrategy,
    val threshold: Int = 144,
    pageProfile: PageContentProfile? = null
) {
    init {
        require(width > 0) { "width must be positive" }
        require(threshold in 0..255) { "threshold must be in 0..255" }
        require(strategy != MonochromeStrategy.AUTO || pageProfile != null) {
            "AUTO requires a sampled page profile"
        }
    }

    val requestedStrategy: MonochromeStrategy = strategy
    val resolvedStrategy: MonochromeStrategy = if (strategy == MonochromeStrategy.AUTO) {
        requireNotNull(pageProfile).recommendedStrategy
    } else {
        strategy
    }

    val strategy: MonochromeStrategy
        get() = resolvedStrategy

    var rowsRasterized: Long = 0
        private set

    private var currentErrors = errorRow()
    private var nextErrors = errorRow()

    fun rasterizeRow(argbPixels: IntArray, offset: Int = 0): ByteArray {
        require(offset >= 0 && offset <= argbPixels.size - width) {
            "The row must contain width pixels from offset"
        }

        val packed = ByteArray((width - 1) / 8 + 1)
        when (resolvedStrategy) {
            MonochromeStrategy.TEXT -> rasterizeText(argbPixels, offset, packed)
            MonochromeStrategy.MIXED -> rasterizeMixed(argbPixels, offset, packed)
            MonochromeStrategy.PHOTO -> rasterizePhoto(argbPixels, offset, packed)
            MonochromeStrategy.AUTO -> error("AUTO must be resolved before rasterization")
        }
        rowsRasterized++
        return packed
    }

    fun reset() {
        rowsRasterized = 0
        currentErrors.fill(0.0)
        nextErrors.fill(0.0)
    }

    companion object {
        private const val PHOTO_THRESHOLD = 128.0
        private val BAYER_4X4 = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )

        @JvmStatic
        @JvmOverloads
        fun auto(
            width: Int,
            pageProfile: PageContentProfile,
            threshold: Int = 144
        ): MonochromeRowRasterizer = MonochromeRowRasterizer(
            width = width,
            strategy = MonochromeStrategy.AUTO,
            threshold = threshold,
            pageProfile = pageProfile
        )
    }

    private fun rasterizeText(pixels: IntArray, offset: Int, packed: ByteArray) {
        for (x in 0 until width) {
            if (PerceptualLuminance.fromArgb(pixels[offset + x]) < threshold) {
                setBlack(packed, x)
            }
        }
    }

    private fun rasterizeMixed(pixels: IntArray, offset: Int, packed: ByteArray) {
        val matrixRow = BAYER_4X4[(rowsRasterized and 3L).toInt()]
        for (x in 0 until width) {
            val orderedThreshold = matrixRow[x and 3] * 16 + 8
            if (PerceptualLuminance.fromArgb(pixels[offset + x]) < orderedThreshold) {
                setBlack(packed, x)
            }
        }
    }

    private fun rasterizePhoto(pixels: IntArray, offset: Int, packed: ByteArray) {
        for (x in 0 until width) {
            val errorIndex = x + 1
            val luminance = PerceptualLuminance.fromArgb(pixels[offset + x])
            val adjusted = (luminance + currentErrors[errorIndex]).coerceIn(0.0, 255.0)
            val black = adjusted < PHOTO_THRESHOLD
            if (black) setBlack(packed, x)

            val quantizationError = adjusted - if (black) 0.0 else 255.0
            currentErrors[errorIndex + 1] += quantizationError * (7.0 / 16.0)
            nextErrors[errorIndex - 1] += quantizationError * (3.0 / 16.0)
            nextErrors[errorIndex] += quantizationError * (5.0 / 16.0)
            nextErrors[errorIndex + 1] += quantizationError * (1.0 / 16.0)
        }

        val recycled = currentErrors
        currentErrors = nextErrors
        nextErrors = recycled
        nextErrors.fill(0.0)
    }

    private fun errorRow(): DoubleArray = if (resolvedStrategy == MonochromeStrategy.PHOTO) {
        DoubleArray(width + 2)
    } else {
        DoubleArray(0)
    }

    private fun setBlack(packed: ByteArray, x: Int) {
        val byteIndex = x ushr 3
        packed[byteIndex] = (packed[byteIndex].toInt() or (0x80 ushr (x and 7))).toByte()
    }
}
