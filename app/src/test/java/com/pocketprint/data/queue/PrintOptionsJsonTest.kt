package com.pocketprint.data.queue

import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import com.pocketprint.data.print.model.PrintingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintOptionsJsonTest {
    @Test
    fun `all print options round trip including margins and collate`() {
        val options = PrintOptions(
            paperSize = PaperSize.LEGAL,
            dpi = 600,
            colorMode = PrintColorMode.COLOR_RGB,
            orientation = PrintOrientation.LANDSCAPE,
            scaling = PrintScaling.FILL_PAGE,
            margins = PrintMargins(1.25f, 2.5f, 3.75f, 4.5f),
            pageRange = "1-3,\"5\"\\draft\n",
            qualityProfile = PrintQualityProfile.PHOTO,
            monochromeStrategy = MonochromeStrategy.PHOTO,
            monochromeThreshold = 132,
            copies = 3,
            collate = false
        )

        val json = PrintOptionsJson.encode(options)

        assertEquals(options, PrintOptionsJson.decode(json))
        assertTrue(json.contains("\"margins\""))
        assertTrue(json.contains("\"collate\":false"))
    }

    @Test
    fun `older JSON receives safe defaults for fields that are absent`() {
        val decoded = PrintOptionsJson.decode(
            """{"paperSize":"LETTER","dpi":150,"copies":2,"unknown":{"future":true}}"""
        )

        assertEquals(PaperSize.LETTER, decoded.paperSize)
        assertEquals(150, decoded.dpi)
        assertEquals(2, decoded.copies)
        assertEquals(PrintMargins.MINIMUM, decoded.margins)
        assertTrue(decoded.collate)
    }

    @Test
    fun `legacy explicit color mode is retained when printing mode is absent`() {
        val decoded = PrintOptionsJson.decode(
            """{"colorMode":"COLOR_RGB","dpi":300,"copies":1}"""
        )
        assertEquals(PrintingMode.COLOR, decoded.printingMode)
        assertTrue(decoded.hybridProcessingEnabled)
    }

    @Test
    fun `unknown enum and invalid scalar values fall back without losing valid fields`() {
        val decoded = PrintOptionsJson.decode(
            """{"paperSize":"FUTURE_SIZE","dpi":-1,"copies":0,"collate":false,"pageRange":"2"}"""
        )

        assertEquals(PaperSize.A4, decoded.paperSize)
        assertEquals(300, decoded.dpi)
        assertEquals(1, decoded.copies)
        assertFalse(decoded.collate)
        assertEquals("2", decoded.pageRange)
    }

    @Test
    fun `malformed JSON is rejected rather than partially decoded`() {
        assertThrows(IllegalArgumentException::class.java) {
            PrintOptionsJson.decode("{\"paperSize\":\"A4\"")
        }
    }
}
