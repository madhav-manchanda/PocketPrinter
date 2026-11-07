package com.pocketprint

import com.pocketprint.data.print.encoder.HpMode9Compressor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class HpMode9CompressorTest {

    @Test
    fun testIdenticalRowProducesEmptyOutput() {
        val seed = ByteArray(300) { 0x55.toByte() }
        val current = ByteArray(300) { 0x55.toByte() }
        val compressed = HpMode9Compressor.compress(current, seed, 300)
        assertEquals(0, compressed.size)
    }

    @Test
    fun testMode0LiteralRunRoundTrip() {
        val seed = ByteArray(100) { 0 }
        val current = seed.copyOf()
        // Non-repeating literal bytes
        current[10] = 0x12.toByte()
        current[11] = 0x34.toByte()
        current[12] = 0x56.toByte()

        val compressed = HpMode9Compressor.compress(current, seed, 100)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode9Compressor.decompress(compressed, seed, 100)
        assertArrayEquals(current, decompressed)
    }

    @Test
    fun testMode1RleRunRoundTrip() {
        val seed = ByteArray(200) { 0 }
        val current = seed.copyOf()
        // Run of 50 repeating bytes
        for (i in 20 until 70) {
            current[i] = 0xFF.toByte()
        }

        val compressed = HpMode9Compressor.compress(current, seed, 200)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode9Compressor.decompress(compressed, seed, 200)
        assertArrayEquals(current, decompressed)
    }

    @Test
    fun testLargeOffsetAndLargeRunRoundTrip() {
        val seed = ByteArray(1000) { 0 }
        val current = seed.copyOf()
        // Large offset (> 255)
        for (i in 400 until 700) {
            current[i] = 0xAA.toByte()
        }

        val compressed = HpMode9Compressor.compress(current, seed, 1000)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode9Compressor.decompress(compressed, seed, 1000)
        assertArrayEquals(current, decompressed)
    }

    @Test
    fun testMixedMode0AndMode1RoundTrip() {
        val seed = ByteArray(500) { 0 }
        val current = seed.copyOf()
        // Literal section
        current[5] = 0x01.toByte()
        current[6] = 0x02.toByte()
        current[7] = 0x03.toByte()
        // RLE section
        for (i in 15 until 60) {
            current[i] = 0xEE.toByte()
        }
        // Another literal section after offset
        current[150] = 0x77.toByte()
        current[151] = 0x88.toByte()

        val compressed = HpMode9Compressor.compress(current, seed, 500)
        assertTrue(compressed.isNotEmpty())

        val decompressed = HpMode9Compressor.decompress(compressed, seed, 500)
        assertArrayEquals(current, decompressed)
    }

    @Test
    fun testRandomFuzzingRoundTrip() {
        val rng = Random(42)
        val len = 612 // typical A4/letter row width in bytes at 300-600 DPI
        for (iteration in 0 until 100) {
            val seed = ByteArray(len)
            val current = ByteArray(len)
            rng.nextBytes(seed)
            rng.nextBytes(current)

            val compressed = HpMode9Compressor.compress(current, seed, len)
            val decompressed = HpMode9Compressor.decompress(compressed, seed, len)
            assertArrayEquals("Failed on fuzz iteration $iteration", current, decompressed)
        }
    }
}
