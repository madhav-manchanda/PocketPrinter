package com.pocketprint

import com.pocketprint.data.print.quality.MonochromeRowRasterizer
import com.pocketprint.data.print.quality.MonochromeStrategy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonochromeRowRasterizerTest {
    @Test
    fun `all white and all black rows pack to expected bits`() {
        val whiteRasterizer = MonochromeRowRasterizer(10, MonochromeStrategy.TEXT)
        val blackRasterizer = MonochromeRowRasterizer(10, MonochromeStrategy.TEXT)

        assertArrayEquals(byteArrayOf(0x00, 0x00), whiteRasterizer.rasterizeRow(IntArray(10) { WHITE }))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xC0.toByte()),
            blackRasterizer.rasterizeRow(IntArray(10) { BLACK })
        )
    }

    @Test
    fun `transparent black rasterizes as white`() {
        val rasterizer = MonochromeRowRasterizer(2, MonochromeStrategy.TEXT)

        assertArrayEquals(
            byteArrayOf(0x40),
            rasterizer.rasterizeRow(intArrayOf(0x00000000, BLACK))
        )
    }

    @Test
    fun `partial final byte is zero padded`() {
        val rasterizer = MonochromeRowRasterizer(10, MonochromeStrategy.TEXT)
        val pixels = IntArray(10) { if (it >= 8) BLACK else WHITE }

        val packed = rasterizer.rasterizeRow(pixels)

        assertArrayEquals(byteArrayOf(0x00, 0xC0.toByte()), packed)
        assertEquals(0, packed[1].toInt() and 0x3F)
    }

    @Test
    fun `vertical and horizontal one pixel lines remain intact`() {
        val vertical = MonochromeRowRasterizer(8, MonochromeStrategy.TEXT)
        repeat(5) {
            val row = IntArray(8) { x -> if (x == 3) BLACK else WHITE }
            assertArrayEquals(byteArrayOf(0x10), vertical.rasterizeRow(row))
        }

        val horizontal = MonochromeRowRasterizer(8, MonochromeStrategy.TEXT)
        repeat(5) { y ->
            val color = if (y == 2) BLACK else WHITE
            val expected = if (y == 2) 0xFF.toByte() else 0x00
            assertArrayEquals(byteArrayOf(expected), horizontal.rasterizeRow(IntArray(8) { color }))
        }
    }

    @Test
    fun `checkerboard packs in MSB first order`() {
        val rasterizer = MonochromeRowRasterizer(8, MonochromeStrategy.TEXT)
        val first = IntArray(8) { x -> if (x and 1 == 0) BLACK else WHITE }
        val second = IntArray(8) { x -> if (x and 1 == 1) BLACK else WHITE }

        assertArrayEquals(byteArrayOf(0xAA.toByte()), rasterizer.rasterizeRow(first))
        assertArrayEquals(byteArrayOf(0x55), rasterizer.rasterizeRow(second))
    }

    @Test
    fun `alternating pattern crosses a byte boundary without changing order`() {
        val rasterizer = MonochromeRowRasterizer(10, MonochromeStrategy.TEXT)
        val alternating = IntArray(10) { x -> if (x and 1 == 0) BLACK else WHITE }

        assertArrayEquals(
            byteArrayOf(0xAA.toByte(), 0x80.toByte()),
            rasterizer.rasterizeRow(alternating)
        )
    }

    @Test
    fun `text threshold is configurable`() {
        val pixels = IntArray(8) { opaqueGray(120) }

        assertArrayEquals(
            byteArrayOf(0x00),
            MonochromeRowRasterizer(8, MonochromeStrategy.TEXT, threshold = 100)
                .rasterizeRow(pixels)
        )
        assertArrayEquals(
            byteArrayOf(0xFF.toByte()),
            MonochromeRowRasterizer(8, MonochromeStrategy.TEXT, threshold = 130)
                .rasterizeRow(pixels)
        )
    }

    @Test
    fun `bayer output is deterministic and follows the documented four by four phase`() {
        val pixels = IntArray(4) { opaqueGray(128) }
        val first = MonochromeRowRasterizer(4, MonochromeStrategy.MIXED)
        val second = MonochromeRowRasterizer(4, MonochromeStrategy.MIXED)
        val expected = arrayOf(
            byteArrayOf(0x50),
            byteArrayOf(0xA0.toByte()),
            byteArrayOf(0x50),
            byteArrayOf(0xA0.toByte())
        )

        expected.forEach { row -> assertArrayEquals(row, first.rasterizeRow(pixels)) }
        expected.forEach { row -> assertArrayEquals(row, second.rasterizeRow(pixels)) }
    }

    @Test
    fun `mixed gradient has monotonically decreasing ink density`() {
        val densities = (0..255 step 16).map { tone ->
            inkCountForSolidTone(MonochromeStrategy.MIXED, tone, 16, 16)
        } + inkCountForSolidTone(MonochromeStrategy.MIXED, 255, 16, 16)

        assertMonotonicallyDecreasing(densities)
        assertEquals(256, densities.first())
        assertEquals(0, densities.last())
    }

    @Test
    fun `photo gradient has monotonically decreasing ink density`() {
        val densities = (0..255 step 16).map { tone ->
            inkCountForSolidTone(MonochromeStrategy.PHOTO, tone, 32, 32)
        } + inkCountForSolidTone(MonochromeStrategy.PHOTO, 255, 32, 32)

        assertMonotonicallyDecreasing(densities)
        assertEquals(1_024, densities.first())
        assertEquals(0, densities.last())
    }

    @Test
    fun `photo uses horizontal and vertical Floyd Steinberg diffusion`() {
        val rasterizer = MonochromeRowRasterizer(8, MonochromeStrategy.PHOTO)
        val gray = IntArray(8) { opaqueGray(128) }

        val first = rasterizer.rasterizeRow(gray)
        val second = rasterizer.rasterizeRow(gray)

        assertArrayEquals(byteArrayOf(0x55), first)
        assertArrayEquals(byteArrayOf(0xAA.toByte()), second)
        rasterizer.reset()
        assertArrayEquals(first, rasterizer.rasterizeRow(gray))
    }

    private fun inkCountForSolidTone(
        strategy: MonochromeStrategy,
        tone: Int,
        width: Int,
        height: Int
    ): Int {
        val rasterizer = MonochromeRowRasterizer(width, strategy)
        val pixels = IntArray(width) { opaqueGray(tone) }
        var ink = 0
        repeat(height) {
            for (byte in rasterizer.rasterizeRow(pixels)) {
                ink += Integer.bitCount(byte.toInt() and 0xFF)
            }
        }
        return ink
    }

    private fun assertMonotonicallyDecreasing(values: List<Int>) {
        values.zipWithNext().forEach { (darker, lighter) ->
            assertTrue("Ink density increased from $darker to $lighter", darker >= lighter)
        }
    }

    private fun opaqueGray(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
    }
}
