package com.pocketprint

import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PageContentProfile
import com.pocketprint.data.print.quality.PageProcessingDecisionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class PageProcessingDecisionTest {
    @Test
    fun `AUTO selects crisp mono for text and grayscale for photographs`() {
        assertEquals(
            PrintColorMode.MONOCHROME_1BIT,
            PageProcessingDecisionEngine.decide(profile(MonochromeStrategy.TEXT, 0.04), PrintOptions())
                .selectedColorMode
        )
        assertEquals(
            PrintColorMode.GRAYSCALE_8BIT,
            PageProcessingDecisionEngine.decide(profile(MonochromeStrategy.PHOTO, 0.75), PrintOptions())
                .selectedColorMode
        )
    }

    @Test
    fun `explicit user modes override classification`() {
        val photo = profile(MonochromeStrategy.PHOTO, 0.80)
        assertEquals(
            PrintColorMode.MONOCHROME_1BIT,
            PageProcessingDecisionEngine.decide(
                photo,
                PrintOptions(printingMode = PrintingMode.BLACK_AND_WHITE)
            ).selectedColorMode
        )
        assertEquals(
            PrintColorMode.COLOR_RGB,
            PageProcessingDecisionEngine.decide(
                photo,
                PrintOptions(printingMode = PrintingMode.COLOR)
            ).selectedColorMode
        )
    }

    @Test
    fun `hybrid default is requested but honestly falls back to page mode`() {
        val decision = PageProcessingDecisionEngine.decide(
            profile(MonochromeStrategy.MIXED, 0.35),
            PrintOptions()
        )
        assertEquals(true, decision.hybridRequested)
        assertFalse(decision.hybridApplied)
        assertNotNull(decision.fallbackReason)
    }

    private fun profile(strategy: MonochromeStrategy, midtones: Double) = PageContentProfile(
        sampleCount = 100,
        minimumLuminance = 0,
        maximumLuminance = 255,
        occupiedLuminanceBins = if (strategy == MonochromeStrategy.TEXT) 3 else 12,
        midtoneFraction = midtones,
        colorFraction = 0.0,
        recommendedStrategy = strategy
    )
}
