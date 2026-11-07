package com.pocketprint.data.print.quality

import kotlin.math.max
import kotlin.math.min

data class PageContentProfile(
    val sampleCount: Int,
    val minimumLuminance: Int,
    val maximumLuminance: Int,
    val occupiedLuminanceBins: Int,
    val midtoneFraction: Double,
    val colorFraction: Double,
    val recommendedStrategy: MonochromeStrategy
) {
    init {
        require(sampleCount > 0) { "sampleCount must be positive" }
        require(minimumLuminance in 0..255) { "minimumLuminance must be in 0..255" }
        require(maximumLuminance in minimumLuminance..255) {
            "maximumLuminance must not be less than minimumLuminance"
        }
        require(occupiedLuminanceBins in 1..16) {
            "occupiedLuminanceBins must be in 1..16"
        }
        require(midtoneFraction in 0.0..1.0) { "midtoneFraction must be in 0.0..1.0" }
        require(colorFraction in 0.0..1.0) { "colorFraction must be in 0.0..1.0" }
        require(recommendedStrategy != MonochromeStrategy.AUTO) {
            "A page profile must resolve to a concrete strategy"
        }
    }
}

/** Builds a deterministic profile without retaining or visiting more than [MAX_SAMPLES] pixels. */
object PageContentClassifier {
    const val MAX_SAMPLES = 4_096

    private const val HISTOGRAM_BINS = 16
    private const val MIDTONE_LOW = 32
    private const val MIDTONE_HIGH = 223
    private const val COLOR_CHROMA_MINIMUM = 24
    private const val GOLDEN_RATIO_32 = 2_654_435_761L

    fun classify(sampledArgbPixels: IntArray): PageContentProfile {
        require(sampledArgbPixels.isNotEmpty()) { "At least one sampled pixel is required" }
        return analyze(sampledArgbPixels.size) { sampledArgbPixels[it] }
    }

    fun classify(argbPixels: IntArray, width: Int, height: Int): PageContentProfile {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= argbPixels.size.toLong()) {
            "ARGB buffer is smaller than width * height"
        }
        return analyze(pixelCount.toInt()) { argbPixels[it] }
    }

    private inline fun analyze(pixelCount: Int, pixelAt: (Int) -> Int): PageContentProfile {
        val sampleCount = min(pixelCount, MAX_SAMPLES)
        val histogram = IntArray(HISTOGRAM_BINS)
        var minimumLuminance = 255
        var maximumLuminance = 0
        var midtoneCount = 0
        var colorCount = 0

        for (sample in 0 until sampleCount) {
            val sourceIndex = sourceIndex(sample, pixelCount, sampleCount)
            val pixel = pixelAt(sourceIndex)
            val luminance = PerceptualLuminance.fromArgb(pixel)
            minimumLuminance = min(minimumLuminance, luminance)
            maximumLuminance = max(maximumLuminance, luminance)
            histogram[luminance ushr 4]++
            if (luminance in MIDTONE_LOW..MIDTONE_HIGH) midtoneCount++
            if (compositedChroma(pixel) >= COLOR_CHROMA_MINIMUM) colorCount++
        }

        val occupiedBins = histogram.count { it != 0 }
        val midtoneFraction = midtoneCount.toDouble() / sampleCount
        val colorFraction = colorCount.toDouble() / sampleCount
        val strategy = when {
            midtoneFraction <= 0.12 && colorFraction <= 0.15 -> MonochromeStrategy.TEXT
            midtoneFraction >= 0.50 && occupiedBins >= 8 -> MonochromeStrategy.PHOTO
            occupiedBins >= 12 -> MonochromeStrategy.PHOTO
            colorFraction >= 0.50 && occupiedBins >= 6 -> MonochromeStrategy.PHOTO
            else -> MonochromeStrategy.MIXED
        }

        return PageContentProfile(
            sampleCount = sampleCount,
            minimumLuminance = minimumLuminance,
            maximumLuminance = maximumLuminance,
            occupiedLuminanceBins = occupiedBins,
            midtoneFraction = midtoneFraction,
            colorFraction = colorFraction,
            recommendedStrategy = strategy
        )
    }

    private fun sourceIndex(sample: Int, pixelCount: Int, sampleCount: Int): Int {
        if (pixelCount == sampleCount) return sample
        val position = (sample.toLong() * GOLDEN_RATIO_32) and 0xFFFF_FFFFL
        return ((position * pixelCount.toLong()) ushr 32).toInt()
    }

    private fun compositedChroma(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        val red = compositeOnWhite((argb ushr 16) and 0xFF, alpha)
        val green = compositeOnWhite((argb ushr 8) and 0xFF, alpha)
        val blue = compositeOnWhite(argb and 0xFF, alpha)
        return max(red, max(green, blue)) - min(red, min(green, blue))
    }

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255
}
