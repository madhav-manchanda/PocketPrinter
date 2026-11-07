package com.pocketprint.data.print.quality

import android.graphics.Bitmap
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintingMode

enum class DetectedContentType {
    TEXT,
    TABLE_OR_DIAGRAM,
    PHOTOGRAPH,
    SHADED_BACKGROUND,
    COLOR_GRAPHICS,
    MIXED
}

data class PageProcessingDecision(
    val requestedMode: PrintingMode,
    val selectedColorMode: PrintColorMode,
    val detectedContentTypes: Set<DetectedContentType>,
    val confidence: Double,
    val hybridRequested: Boolean,
    val hybridApplied: Boolean,
    val fallbackReason: String?,
    val profile: PageContentProfile
)

object PageProcessingDecisionEngine {
    fun decide(profile: PageContentProfile, options: PrintOptions): PageProcessingDecision {
        val selectedMode = when (options.printingMode) {
            PrintingMode.BLACK_AND_WHITE -> PrintColorMode.MONOCHROME_1BIT
            PrintingMode.GRAYSCALE -> PrintColorMode.GRAYSCALE_8BIT
            PrintingMode.COLOR -> PrintColorMode.COLOR_RGB
            PrintingMode.AUTO -> when (profile.recommendedStrategy) {
                MonochromeStrategy.TEXT -> PrintColorMode.MONOCHROME_1BIT
                MonochromeStrategy.MIXED,
                MonochromeStrategy.PHOTO -> PrintColorMode.GRAYSCALE_8BIT
                MonochromeStrategy.AUTO -> error("Classifier must resolve AUTO")
            }
        }
        val content = buildSet {
            when (profile.recommendedStrategy) {
                MonochromeStrategy.TEXT -> {
                    add(DetectedContentType.TEXT)
                    add(DetectedContentType.TABLE_OR_DIAGRAM)
                }
                MonochromeStrategy.MIXED -> add(DetectedContentType.MIXED)
                MonochromeStrategy.PHOTO -> add(DetectedContentType.PHOTOGRAPH)
                MonochromeStrategy.AUTO -> Unit
            }
            if (profile.midtoneFraction > 0.15) add(DetectedContentType.SHADED_BACKGROUND)
            if (profile.colorFraction > 0.10) add(DetectedContentType.COLOR_GRAPHICS)
        }
        val confidence = when (profile.recommendedStrategy) {
            MonochromeStrategy.TEXT -> (1.0 - profile.midtoneFraction).coerceIn(0.55, 0.98)
            MonochromeStrategy.PHOTO -> (0.55 + profile.midtoneFraction * 0.45).coerceAtMost(0.98)
            MonochromeStrategy.MIXED -> 0.60
            MonochromeStrategy.AUTO -> 0.50
        }
        val fallback = if (options.hybridProcessingEnabled) {
            "Region-level hybrid processing is not physically validated; safe page-level ${selectedMode.displayName} was used."
        } else null
        return PageProcessingDecision(
            requestedMode = options.printingMode,
            selectedColorMode = selectedMode,
            detectedContentTypes = content,
            confidence = confidence,
            hybridRequested = options.hybridProcessingEnabled,
            hybridApplied = false,
            fallbackReason = fallback,
            profile = profile
        )
    }
}

object PageQualityAnalyzer {
    fun analyze(bitmap: Bitmap, options: PrintOptions): PageProcessingDecision {
        val sampleWidth = minOf(bitmap.width, 64)
        val sampleHeight = minOf(bitmap.height, 64)
        val samples = IntArray(sampleWidth * sampleHeight)
        var index = 0
        for (sampleY in 0 until sampleHeight) {
            val y = if (sampleHeight == 1) 0 else sampleY * (bitmap.height - 1) / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val x = if (sampleWidth == 1) 0 else sampleX * (bitmap.width - 1) / (sampleWidth - 1)
                samples[index++] = bitmap.getPixel(x, y)
            }
        }
        return PageProcessingDecisionEngine.decide(PageContentClassifier.classify(samples), options)
    }
}
