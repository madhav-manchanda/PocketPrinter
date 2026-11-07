package com.pocketprint

import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.util.PrintLayoutCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintLayoutCalculatorTest {

    @Test
    fun `five millimetre margins reduce every printable edge`() {
        val options = PrintOptions(dpi = 254, margins = PrintMargins.uniform(5f))
        val bounds = options.printableBounds

        assertEquals(50, bounds.left)
        assertEquals(50, bounds.top)
        assertEquals(options.widthPixels - 50, bounds.right)
        assertEquals(options.heightPixels - 50, bounds.bottom)
    }

    @Test
    fun `fit scales source inside printable bounds`() {
        val options = PrintOptions(
            dpi = 254,
            margins = PrintMargins.uniform(5f),
            scaling = PrintScaling.FIT_TO_PAGE
        )
        val layout = PrintLayoutCalculator.calculate(1000f, 1000f, options)

        assertEquals(options.printableBounds.width / 1000f, layout.scale, 0.001f)
        assertEquals(options.printableBounds.left.toFloat(), layout.translateX, 0.001f)
        assertTrue(layout.translateY > options.printableBounds.top)
    }

    @Test
    fun `fill covers printable bounds and clips overflow`() {
        val options = PrintOptions(
            dpi = 254,
            margins = PrintMargins.uniform(10f),
            scaling = PrintScaling.FILL_PAGE
        )
        val layout = PrintLayoutCalculator.calculate(2000f, 1000f, options)

        assertEquals(options.printableBounds.height / 1000f, layout.scale, 0.001f)
        assertTrue(layout.translateX < options.printableBounds.left)
        assertEquals(options.printableBounds, layout.clipBounds)
    }

    @Test
    fun `actual size uses supplied physical scale and centers`() {
        val options = PrintOptions(scaling = PrintScaling.ACTUAL_SIZE)
        val layout = PrintLayoutCalculator.calculate(612f, 792f, options, actualSizeScale = 300f / 72f)

        assertEquals(300f / 72f, layout.scale, 0.001f)
        assertTrue(layout.translateX < layout.clipBounds.left)
        assertTrue(layout.translateY > 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `margins cannot consume the whole page`() {
        PrintOptions(margins = PrintMargins.uniform(200f)).printableBounds
    }
}
