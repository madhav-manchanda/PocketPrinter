package com.pocketprint.data.print.quality

import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.util.GrayscaleConverter

/** Applies the same color-mode conversion used immediately before PCL encoding. */
object PrintQualityPreview {

    fun applyInPlace(bitmap: Bitmap, options: PrintOptions): PageProcessingDecision {
        val decision = PageQualityAnalyzer.analyze(bitmap, options)
        val hybridApplied = decision.hybridRequested &&
            decision.requestedMode == com.pocketprint.data.print.model.PrintingMode.AUTO &&
            decision.profile.recommendedStrategy == MonochromeStrategy.MIXED
        if (hybridApplied) {
            applyHybrid(bitmap, options)
            return decision.copy(hybridApplied = true, fallbackReason = null)
        }
        when (decision.selectedColorMode) {
            PrintColorMode.COLOR_RGB -> Unit
            PrintColorMode.GRAYSCALE_8BIT -> applyGrayscale(bitmap)
            PrintColorMode.MONOCHROME_1BIT -> applyMonochrome(
                bitmap,
                options.copy(
                    colorMode = decision.selectedColorMode,
                    monochromeStrategy = if (options.monochromeStrategy == MonochromeStrategy.AUTO) {
                        decision.profile.recommendedStrategy
                    } else options.monochromeStrategy
                )
            )
        }
        return decision
    }

    private fun applyHybrid(bitmap: Bitmap, options: PrintOptions) {
        val analyzer = HybridRegionAnalyzer(
            bitmap.width,
            bitmap.height,
            maxOf(32, options.dpi / 5)
        )
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            analyzer.addRow(row, y)
        }
        val regions = analyzer.finish()
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                val route = HybridPixelRouter.route(
                    row[x],
                    regions.isTextRegion(x, y),
                    options.monochromeThreshold
                )
                row[x] = if (route.useBlackPlane) {
                    BLACK
                } else {
                    val gray = route.grayscale
                    (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        DiagnosticLogger.usb(
            "[PREVIEW] Experimental hybrid applied to ${regions.textTileCount}/${regions.totalTileCount} text tiles"
        )
    }

    private fun applyGrayscale(bitmap: Bitmap) {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) row[x] = GrayscaleConverter.toOpaqueGrayscale(row[x])
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
    }

    private fun applyMonochrome(bitmap: Bitmap, options: PrintOptions) {
        val profile = if (options.monochromeStrategy == MonochromeStrategy.AUTO) {
            classify(bitmap)
        } else null
        val rasterizer = MonochromeRowRasterizer(
            bitmap.width,
            options.monochromeStrategy,
            options.monochromeThreshold,
            profile
        )
        val source = IntArray(bitmap.width)
        val output = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(source, 0, bitmap.width, 0, y, bitmap.width, 1)
            val packed = rasterizer.rasterizeRow(source)
            for (x in output.indices) {
                val black = (packed[x ushr 3].toInt() and (0x80 ushr (x and 7))) != 0
                output[x] = if (black) BLACK else WHITE
            }
            bitmap.setPixels(output, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        DiagnosticLogger.usb(
            "[PREVIEW] Final mono raster simulation strategy=${rasterizer.resolvedStrategy} " +
                "threshold=${options.monochromeThreshold} stride=${(bitmap.width + 7) / 8}"
        )
    }

    private fun classify(bitmap: Bitmap): PageContentProfile {
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
        return PageContentClassifier.classify(samples)
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
