package com.pocketprint

import com.pocketprint.data.print.util.GrayscaleConverter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GrayscaleConverterTest {

    @Test
    fun `luminance of black is zero`() {
        assertEquals(0, GrayscaleConverter.luminance(0, 0, 0))
    }

    @Test
    fun `luminance of white is full scale`() {
        assertEquals(255, GrayscaleConverter.luminance(255, 255, 255))
    }

    @Test
    fun `luminance uses bt601 coefficients per channel`() {
        assertEquals(54, GrayscaleConverter.luminance(255, 0, 0))
        assertEquals(182, GrayscaleConverter.luminance(0, 255, 0))
        assertEquals(18, GrayscaleConverter.luminance(0, 0, 255))
        assertEquals(128, GrayscaleConverter.luminance(128, 128, 128))
    }

    @Test
    fun `applyLuminance converts buffer to opaque grayscale`() {
        val pixels = intArrayOf(-1, -16777216, -65536, -1)
        val converted = GrayscaleConverter.applyLuminance(pixels, 2, 2)
        assertArrayEquals(
            intArrayOf(-1, -16777216, 0xFF363636.toInt(), -1),
            converted
        )
    }

    @Test
    fun `applyLuminance does not mutate source buffer`() {
        val pixels = intArrayOf(-1, -16777216)
        GrayscaleConverter.applyLuminance(pixels, 2, 1)
        assertEquals(-1, pixels[0])
        assertEquals(-16777216, pixels[1])
    }

    @Test
    fun `transparent pixels blend toward white before conversion`() {
        assertEquals(0xFFFFFFFF.toInt(), GrayscaleConverter.toOpaqueGrayscale(0x00000000))
        assertEquals(0xFF7F7F7F.toInt(), GrayscaleConverter.toOpaqueGrayscale(0x80000000.toInt()))
    }
}
