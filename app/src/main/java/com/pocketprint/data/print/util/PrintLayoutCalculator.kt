package com.pocketprint.data.print.util

import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintPixelBounds
import com.pocketprint.data.print.model.PrintScaling

/** Pure layout calculation shared by PDF and bitmap renderers. */
data class PrintRenderLayout(
    val scale: Float,
    val translateX: Float,
    val translateY: Float,
    val clipBounds: PrintPixelBounds
)

object PrintLayoutCalculator {

    fun calculate(
        sourceWidth: Float,
        sourceHeight: Float,
        options: PrintOptions,
        actualSizeScale: Float = 1f
    ): PrintRenderLayout {
        require(sourceWidth > 0f && sourceHeight > 0f) { "Source dimensions must be positive" }
        require(actualSizeScale > 0f) { "Actual-size scale must be positive" }

        val bounds = options.printableBounds
        val scale = when (options.scaling) {
            PrintScaling.FIT_TO_PAGE -> minOf(
                bounds.width / sourceWidth,
                bounds.height / sourceHeight
            )
            PrintScaling.FILL_PAGE -> maxOf(
                bounds.width / sourceWidth,
                bounds.height / sourceHeight
            )
            PrintScaling.ACTUAL_SIZE -> actualSizeScale
        }
        val translateX = bounds.left + (bounds.width - sourceWidth * scale) / 2f
        val translateY = bounds.top + (bounds.height - sourceHeight * scale) / 2f
        return PrintRenderLayout(scale, translateX, translateY, bounds)
    }
}
