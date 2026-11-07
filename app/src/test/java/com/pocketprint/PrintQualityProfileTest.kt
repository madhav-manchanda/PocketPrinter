package com.pocketprint

import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintQualityProfileTest {
    @Test
    fun `production profiles remain at verified 300 DPI`() {
        assertEquals(300, PrintQualityProfile.DRAFT.targetDpi)
        assertEquals(300, PrintQualityProfile.NORMAL.targetDpi)
        assertEquals(300, PrintQualityProfile.PHOTO.targetDpi)
        assertFalse(PrintQualityProfile.NORMAL.experimentalResolution)
    }

    @Test
    fun `higher resolution profiles are capability gated`() {
        assertFalse(PrintQualityProfile.HIGH.isAvailable(300))
        assertTrue(PrintQualityProfile.HIGH.isAvailable(600))
        assertFalse(PrintQualityProfile.MAXIMUM.isAvailable(600))
        assertFalse(PrintQualityProfile.MAXIMUM.isAvailable(1200))
    }

    @Test
    fun `profiles select materially different mono processing`() {
        assertEquals(MonochromeStrategy.TEXT, PrintQualityProfile.DRAFT.monochromeStrategy)
        assertEquals(MonochromeStrategy.AUTO, PrintQualityProfile.NORMAL.monochromeStrategy)
        assertEquals(MonochromeStrategy.PHOTO, PrintQualityProfile.PHOTO.monochromeStrategy)
    }
}
