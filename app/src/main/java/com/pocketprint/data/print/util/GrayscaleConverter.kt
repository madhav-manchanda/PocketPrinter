package com.pocketprint.data.print.util

/**
 * Pure grayscale conversion helpers.
 *
 * Converts an ARGB pixel buffer into an opaque grayscale (luminance) buffer
 * suitable for the printer-side grayscale raster stage. Kept free of Android
 * rendering dependencies so luminance math stays unit-testable in isolation.
 */
object GrayscaleConverter {

    /**
     * BT.709/sRGB perceptual luma conversion.
     */
    fun luminance(red: Int, green: Int, blue: Int): Int =
        (2126 * red + 7152 * green + 722 * blue + 5000) / 10000

    fun toOpaqueGrayscale(pixel: Int): Int {
        val alpha = (pixel ushr 24) and 0xFF
        var red = (pixel ushr 16) and 0xFF
        var green = (pixel ushr 8) and 0xFF
        var blue = pixel and 0xFF
        if (alpha < 255) {
            red = blendTowardWhite(red, alpha)
            green = blendTowardWhite(green, alpha)
            blue = blendTowardWhite(blue, alpha)
        }
        val luminance = luminance(red, green, blue).coerceIn(0, 255)
        return (0xFF shl 24) or (luminance shl 16) or (luminance shl 8) or luminance
    }

    /**
     * Converts a row-major ARGB buffer (width x height) into an opaque grayscale
     * buffer. Translucent pixels are alpha-blended toward white before luma
     * extraction; output pixels are stored as opaque grayscale ARGB values.
     */
    fun applyLuminance(pixels: IntArray, width: Int, height: Int): IntArray {
        val converted = IntArray(pixels.size)
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                val source = rowStart + x
                converted[source] = toOpaqueGrayscale(pixels[source])
            }
        }
        return converted
    }

    private fun blendTowardWhite(channel: Int, alpha: Int): Int =
        ((channel * alpha + 255 * (255 - alpha)) / 255).coerceIn(0, 255)
}
