package com.pocketprint.data.print.quality

/** Alpha-composited-on-white, eight-bit BT.709/sRGB luminance. */
object PerceptualLuminance {
    private const val RED_WEIGHT = 2_126
    private const val GREEN_WEIGHT = 7_152
    private const val BLUE_WEIGHT = 722
    private const val WEIGHT_SCALE = 10_000

    fun fromArgb(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return 255

        val red = compositeOnWhite((argb ushr 16) and 0xFF, alpha)
        val green = compositeOnWhite((argb ushr 8) and 0xFF, alpha)
        val blue = compositeOnWhite(argb and 0xFF, alpha)
        return fromRgb(red, green, blue)
    }

    fun fromRgb(red: Int, green: Int, blue: Int): Int {
        require(red in 0..255) { "red must be in 0..255" }
        require(green in 0..255) { "green must be in 0..255" }
        require(blue in 0..255) { "blue must be in 0..255" }

        return (
            RED_WEIGHT * red +
                GREEN_WEIGHT * green +
                BLUE_WEIGHT * blue +
                WEIGHT_SCALE / 2
            ) / WEIGHT_SCALE
    }

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255
}
