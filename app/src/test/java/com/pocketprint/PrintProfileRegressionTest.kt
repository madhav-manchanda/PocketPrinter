package com.pocketprint

import com.pocketprint.data.discovery.engine.CandidateGenerator
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.print.encoder.HpMode9Compressor
import com.pocketprint.data.print.transport.StreamConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Locks the previously verified diagnostic profiles and encoder behaviour so
 * future streaming changes cannot silently alter the byte stream that the
 * HP Ink Tank 310 already accepted.
 */
class PrintProfileRegressionTest {

    @Test
    fun `verified mono profile keeps 16KB chunk and 15s timeout`() {
        val candidate = CandidateGenerator.generateCandidates()
            .first { it.candidateId == "HP_PCL3GUI2_K_M9_HPLIP" }
        assertEquals(DiscoveryProtocol.PCL3GUI2_K_MODE9, candidate.protocol)
        assertEquals(16_384, candidate.usbChunkSize)
        assertEquals(15_000, candidate.transferTimeoutMs)
        assertTrue(candidate.sendZlpFlush)
        assertTrue(candidate.performSoftReset)
    }

    @Test
    fun `verified colour profile keeps 16KB chunk and 15s timeout`() {
        val candidate = CandidateGenerator.generateCandidates()
            .first { it.candidateId == "HP_PCL3GUI2_COLOR_HPLIP" }
        assertEquals(DiscoveryProtocol.PCL3GUI2_COLOR_MODE10, candidate.protocol)
        assertEquals(16_384, candidate.usbChunkSize)
        assertEquals(15_000, candidate.transferTimeoutMs)
    }

    @Test
    fun `experimental DPI candidates are isolated from production discovery list`() {
        val production = CandidateGenerator.generateCandidates()
        val experimental = CandidateGenerator.generateExperimentalDpiCandidates()
        assertTrue(production.none { it.candidateId.endsWith("_EXPERIMENTAL") })
        assertEquals(listOf(600, 1200), experimental.map { it.resolutionDpi })
        assertTrue(experimental.all { it.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 })
    }

    @Test
    fun `stream presets do not drift`() {
        val verified = StreamConfig.verified()
        assertEquals(16_384, verified.chunkSize)
        assertEquals(15_000, verified.transferTimeoutMs)
        assertEquals(0, verified.maxAmbiguousRetries)

        val reliable = StreamConfig.reliable()
        assertEquals(4_096, reliable.chunkSize)
        assertEquals(15_000, reliable.transferTimeoutMs)
        assertEquals(0, reliable.maxAmbiguousRetries)
        assertEquals(2L, reliable.interChunkPacingMs)

        val diagnostic = StreamConfig.diagnostic(16_384, 15_000)
        assertEquals(16_384, diagnostic.chunkSize)
        assertEquals(15_000, diagnostic.transferTimeoutMs)
    }

    @Test
    fun `mode9 identical row compresses to empty golden hash`() {
        val seed = ByteArray(64) { 0x55 }
        val current = seed.copyOf()
        val compressed = HpMode9Compressor.compress(current, seed, current.size)

        assertEquals(0, compressed.size)
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(compressed)
        )
    }

    @Test
    fun `mode9 compression is deterministic and round trips`() {
        val seed = ByteArray(128) { 0 }
        val current = ByteArray(128) { 0 }
        for (i in 10 until 40) current[i] = 0x7F
        current[100] = 0x01
        current[101] = 0x02

        val first = HpMode9Compressor.compress(current, seed, current.size)
        val second = HpMode9Compressor.compress(current, seed, current.size)

        assertArrayEquals(first, second)
        assertEquals(sha256(first), sha256(second))
        assertArrayEquals(current, HpMode9Compressor.decompress(first, seed, current.size))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
