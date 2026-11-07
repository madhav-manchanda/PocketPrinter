package com.pocketprint

import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.quality.PrintQualityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrintOptionsTest {

    @Test
    fun `a4 portrait dimensions are derived from millimetres at 300 dpi`() {
        val options = PrintOptions(paperSize = PaperSize.A4, dpi = 300, orientation = PrintOrientation.PORTRAIT)
        assertEquals(2480, options.widthPixels)
        assertEquals(3508, options.heightPixels)
    }

    @Test
    fun `a4 landscape swaps width and height`() {
        val options = PrintOptions(paperSize = PaperSize.A4, dpi = 300, orientation = PrintOrientation.LANDSCAPE)
        assertEquals(3508, options.widthPixels)
        assertEquals(2480, options.heightPixels)
    }

    @Test
    fun `us letter portrait dimensions are derived correctly`() {
        val options = PrintOptions(paperSize = PaperSize.LETTER, dpi = 300, orientation = PrintOrientation.PORTRAIT)
        assertEquals(2550, options.widthPixels)
        assertEquals(3300, options.heightPixels)
    }

    @Test
    fun `us legal portrait dimensions are derived correctly`() {
        val options = PrintOptions(paperSize = PaperSize.LEGAL, dpi = 300, orientation = PrintOrientation.PORTRAIT)
        assertEquals(2550, options.widthPixels)
        assertEquals(4200, options.heightPixels)
    }

    @Test
    fun `b5 dimensions are derived correctly`() {
        val options = PrintOptions(paperSize = PaperSize.B5, dpi = 300, orientation = PrintOrientation.PORTRAIT)
        assertEquals(2079, options.widthPixels)
        assertEquals(2953, options.heightPixels)
    }

    @Test
    fun `bytes per row rounds monochrome row width up to whole bytes`() {
        val options = PrintOptions(paperSize = PaperSize.A4, dpi = 300, orientation = PrintOrientation.PORTRAIT)
        assertEquals(310, options.bytesPerRow)
    }

    @Test
    fun `monochrome flag reflects the monochrome print mode`() {
        assertEquals(PrintColorMode.MONOCHROME_1BIT, PrintOptions().colorMode)
        assertEquals(true, PrintOptions().isMonochrome)
        assertEquals(false, PrintOptions(colorMode = PrintColorMode.COLOR_RGB).isMonochrome)
    }

    @Test
    fun `scaling and orientation defaults are stable`() {
        val options = PrintOptions()
        assertEquals(PrintingMode.AUTO, options.printingMode)
        assertEquals(true, options.hybridProcessingEnabled)
        assertEquals(PrintQualityProfile.NORMAL, options.qualityProfile)
        assertEquals(300, options.dpi)
        assertEquals(PrintScaling.FIT_TO_PAGE, options.scaling)
        assertEquals(PrintOrientation.PORTRAIT, options.orientation)
        assertEquals(PrintMargins.MINIMUM, options.margins)
        assertEquals(1, options.copies)
        assertEquals("", options.pageRange)
    }

    @Test
    fun `monochrome threshold is bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrintOptions(monochromeThreshold = 256)
        }
    }
}
