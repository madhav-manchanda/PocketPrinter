package com.pocketprint

import com.pocketprint.data.print.encoder.HpMode10Compressor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HpMode10CompressorTest {

    @Test
    fun `sequential rows use the printer reconstructed seed`() {
        val width = 12
        var seed = ByteArray(width * 3) { 0xFF.toByte() }
        for (blue in listOf(254, 253, 252, 251)) {
            val row = ByteArray(width * 3)
            for (pixel in 0 until width) {
                row[pixel * 3] = 120
                row[pixel * 3 + 1] = 140.toByte()
                row[pixel * 3 + 2] = blue.toByte()
            }
            val result = HpMode10Compressor.compressWithReconstructedSeed(row, seed, width)
            assertArrayEquals(
                HpMode10Compressor.decompress(result.compressed, seed, width),
                result.reconstructedRow
            )
            seed = result.reconstructedRow
        }
    }

    @Test
    fun `odd blue quantization becomes the next host seed`() {
        val width = 4
        val seed = ByteArray(width * 3) { 0xFF.toByte() }
        val oddBlue = ByteArray(width * 3)
        for (pixel in 0 until width) {
            oddBlue[pixel * 3] = 120
            oddBlue[pixel * 3 + 1] = 140.toByte()
            oddBlue[pixel * 3 + 2] = 253.toByte()
        }
        val first = HpMode10Compressor.compressWithReconstructedSeed(oddBlue, seed, width)
        assertEquals(252, first.reconstructedRow[2].toInt() and 0xFF)

        val printerMatchingNextRow = first.reconstructedRow.copyOf()
        val second = HpMode10Compressor.compressWithReconstructedSeed(
            printerMatchingNextRow,
            first.reconstructedRow,
            width
        )
        assertEquals(0, second.compressed.size)
    }

    @Test
    fun `literal runs longer than eight pixels round trip to reconstructed data`() {
        val width = 40
        val seed = ByteArray(width * 3) { 0xFF.toByte() }
        val row = ByteArray(width * 3)
        for (pixel in 0 until width) {
            row[pixel * 3] = (pixel * 5).toByte()
            row[pixel * 3 + 1] = (pixel * 3 + 20).toByte()
            row[pixel * 3 + 2] = (pixel * 7 + 1).toByte()
        }
        val result = HpMode10Compressor.compressWithReconstructedSeed(row, seed, width)
        assertArrayEquals(
            result.reconstructedRow,
            HpMode10Compressor.decompress(result.compressed, seed, width)
        )
    }

    @Test
    fun `identical one pixel row compresses safely`() {
        val seed = byteArrayOf(1, 2, 3)
        assertEquals(0, HpMode10Compressor.compress(seed.copyOf(), seed, 1).size)
    }

    private fun assertPixelRowsMatch(expected: ByteArray, actual: ByteArray, width: Int) {
        assertEquals("Row length mismatch", expected.size, actual.size)
        for (i in 0 until width) {
            val rExp = expected[i * 3].toInt() and 0xFF
            val gExp = expected[i * 3 + 1].toInt() and 0xFF
            val bExp = expected[i * 3 + 2].toInt() and 0xFF

            val rAct = actual[i * 3].toInt() and 0xFF
            val gAct = actual[i * 3 + 1].toInt() and 0xFF
            val bAct = actual[i * 3 + 2].toInt() and 0xFF

            assertEquals("Pixel $i Red mismatch", rExp, rAct)
            assertEquals("Pixel $i Green mismatch", gExp, gAct)
            // Mode 10 Bert compression quantizes Blue by 1 bit (LSB) per HP specification:
            // "Lose the lsb of blue and zero out the msb of the 3 bytes" (Mode10.cpp:363)
            assertTrue(
                "Pixel $i Blue mismatch: expected=$bExp, actual=$bAct (diff=${abs(bExp - bAct)})",
                abs(bExp - bAct) <= 1
            )
        }
    }

    @Test
    fun testAllWhiteMatchesSeedReturnsEmpty() {
        val width = 200
        val seed = ByteArray(width * 3) { 0xFF.toByte() }
        val curr = ByteArray(width * 3) { 0xFF.toByte() }

        val compressed = HpMode10Compressor.compress(curr, seed, width)
        assertEquals(0, compressed.size)
    }

    @Test
    fun testSolidColorRleRoundTrip() {
        val width = 300
        val seed = ByteArray(width * 3) { 0xFF.toByte() }
        val curr = ByteArray(width * 3)
        // Red bar: (255, 0, 0)
        for (i in 0 until width) {
            curr[i * 3] = 0xFF.toByte()
            curr[i * 3 + 1] = 0x00.toByte()
            curr[i * 3 + 2] = 0x00.toByte()
        }

        val compressed = HpMode10Compressor.compress(curr, seed, width)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode10Compressor.decompress(compressed, seed, width)
        assertPixelRowsMatch(curr, decompressed, width)
    }

    @Test
    fun testPrimaryColorsRoundTrip() {
        val width = 70
        val seed = ByteArray(width * 3) { 0xFF.toByte() }
        val curr = ByteArray(width * 3) { 0xFF.toByte() }

        // Red, Green, Blue, Cyan, Magenta, Yellow, Black in 10-pixel blocks
        val colors = listOf(
            Triple(0xFF, 0x00, 0x00), // Red
            Triple(0x00, 0xFF, 0x00), // Green
            Triple(0x00, 0x00, 0xFE), // Blue (even LSB)
            Triple(0x00, 0xFF, 0xFE), // Cyan
            Triple(0xFF, 0x00, 0xFE), // Magenta
            Triple(0xFF, 0xFF, 0x00), // Yellow
            Triple(0x00, 0x00, 0x00)  // Black
        )

        for (b in colors.indices) {
            val (r, g, blue) = colors[b]
            for (p in 0 until 10) {
                val idx = b * 10 + p
                curr[idx * 3] = r.toByte()
                curr[idx * 3 + 1] = g.toByte()
                curr[idx * 3 + 2] = blue.toByte()
            }
        }

        val compressed = HpMode10Compressor.compress(curr, seed, width)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode10Compressor.decompress(compressed, seed, width)
        assertPixelRowsMatch(curr, decompressed, width)
    }

    @Test
    fun testSubtleGradientShortDeltaRoundTrip() {
        val width = 100
        val seed = ByteArray(width * 3)
        val curr = ByteArray(width * 3)

        // Seed: smooth blue gradient
        for (i in 0 until width) {
            seed[i * 3] = 0x20.toByte()
            seed[i * 3 + 1] = 0x40.toByte()
            seed[i * 3 + 2] = ((100 + i) and 0xFE).toByte()
        }

        // Curr: slightly shifted (dr=+2, dg=-3, db=+4 -> falls squarely within ShortDelta range)
        for (i in 0 until width) {
            curr[i * 3] = 0x22.toByte()
            curr[i * 3 + 1] = 0x3D.toByte()
            curr[i * 3 + 2] = ((104 + i) and 0xFE).toByte()
        }

        val compressed = HpMode10Compressor.compress(curr, seed, width)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode10Compressor.decompress(compressed, seed, width)
        assertPixelRowsMatch(curr, decompressed, width)
    }
}
