package com.pocketprint

import com.pocketprint.data.print.quality.PerceptualLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualLuminanceTest {
    @Test
    fun `transparent black composites to white`() {
        assertEquals(255, PerceptualLuminance.fromArgb(0x00000000))
    }

    @Test
    fun `opaque endpoints retain their luminance`() {
        assertEquals(0, PerceptualLuminance.fromArgb(0xFF000000.toInt()))
        assertEquals(255, PerceptualLuminance.fromArgb(0xFFFFFFFF.toInt()))
        assertEquals(127, PerceptualLuminance.fromArgb(0x80000000.toInt()))
    }

    @Test
    fun `bt709 gives green then red then blue descending weight`() {
        val red = PerceptualLuminance.fromRgb(255, 0, 0)
        val green = PerceptualLuminance.fromRgb(0, 255, 0)
        val blue = PerceptualLuminance.fromRgb(0, 0, 255)

        assertEquals(54, red)
        assertEquals(182, green)
        assertEquals(18, blue)
        assertTrue(green > red)
        assertTrue(red > blue)
    }
}
