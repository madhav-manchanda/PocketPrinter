package com.pocketprint

import com.pocketprint.data.print.transport.PrinterPortStatus
import com.pocketprint.data.print.transport.StreamConfig
import com.pocketprint.data.print.transport.StreamProgressCallback
import com.pocketprint.data.print.transport.StreamResult
import com.pocketprint.data.print.transport.StreamSnapshot
import com.pocketprint.data.print.transport.TransferResultKind
import com.pocketprint.data.print.transport.UsbBulkChannel
import com.pocketprint.data.print.transport.UsbStreamEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests the REAL [UsbStreamEngine] state machine through a fake bulk channel.
 * No Android SDK calls are involved.
 */
class UsbStreamingEngineTest {

    private class FakeChannel(
        private val handler: (offset: Int, length: Int, channel: FakeChannel) -> Int
    ) : UsbBulkChannel {
        var attached = true
        var permission = true
        var claimed = true
        var portStatus: PrinterPortStatus? = PrinterPortStatus(0x18)
        var calls = 0
        val requestedOffsets = mutableListOf<Int>()
        val requestedLengths = mutableListOf<Int>()

        override fun bulkTransfer(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            calls++
            requestedOffsets.add(offset)
            requestedLengths.add(length)
            return handler(offset, length, this)
        }

        override fun isDeviceAttached(): Boolean = attached
        override fun hasPermission(): Boolean = permission
        override fun isInterfaceClaimed(): Boolean = claimed
        override fun readPortStatus(): PrinterPortStatus? = portStatus
    }

    private fun fastConfig(
        chunkSize: Int = 4096,
        maxNoProgressRetries: Int = 4,
        maxAmbiguousRetries: Int = 0,
        maxConsecutiveRetries: Int = 12,
        interChunkPacingMs: Long = 0L
    ) = StreamConfig(
        chunkSize = chunkSize,
        transferTimeoutMs = 1_000,
        maxNoProgressRetries = maxNoProgressRetries,
        maxAmbiguousRetries = maxAmbiguousRetries,
        retryBackoffMs = 0L,
        interChunkPacingMs = interChunkPacingMs,
        maxConsecutiveRetries = maxConsecutiveRetries
    )

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `perfect transfer confirms every byte with correct chunking`() {
        val data = ByteArray(65536) { it.toByte() }
        val channel = FakeChannel { _, length, _ -> length }

        val result = UsbStreamEngine(channel, fastConfig()).stream(data)

        assertTrue(result is StreamResult.Completed)
        assertEquals(65536L, (result as StreamResult.Completed).totalConfirmed)
        assertEquals(16, channel.calls)
        assertEquals(List(16) { it * 4096 }, channel.requestedOffsets)
        assertTrue(channel.requestedLengths.all { it == 4096 })
    }

    @Test
    fun `small payload under one chunk succeeds`() {
        val channel = FakeChannel { _, length, _ -> length }
        val result = UsbStreamEngine(channel, fastConfig()).stream(ByteArray(512))
        assertTrue(result is StreamResult.Completed)
        assertEquals(512L, (result as StreamResult.Completed).totalConfirmed)
        assertEquals(1, channel.calls)
    }

    @Test
    fun `large 2MB colour payload streams correctly`() {
        val size = 2 * 1024 * 1024
        val channel = FakeChannel { _, length, _ -> length }
        val result = UsbStreamEngine(channel, fastConfig()).stream(ByteArray(size))
        assertTrue(result is StreamResult.Completed)
        assertEquals(size.toLong(), (result as StreamResult.Completed).totalConfirmed)
        assertEquals(size / 4096, channel.calls)
    }

    @Test
    fun `verified 16KB diagnostic profile works`() {
        val channel = FakeChannel { _, length, _ -> length }
        val result = UsbStreamEngine(channel, fastConfig(chunkSize = 16_384)).stream(ByteArray(65_536))
        assertTrue(result is StreamResult.Completed)
        assertEquals(4, channel.calls)
        assertEquals(List(4) { it * 16_384 }, channel.requestedOffsets)
    }

    // ── Partial writes (old silent data-loss bug) ────────────────────────────

    @Test
    fun `partial writes advance offset by confirmed bytes only and never skip data`() {
        val channel = FakeChannel { _, length, _ -> minOf(length, 1000) }
        val result = UsbStreamEngine(channel, fastConfig()).stream(ByteArray(8192))

        assertTrue("Expected Completed, got $result", result is StreamResult.Completed)
        assertEquals(8192L, (result as StreamResult.Completed).totalConfirmed)

        // Each call must resume exactly where the previous confirmed bytes ended.
        var expectedOffset = 0
        for (i in channel.requestedOffsets.indices) {
            assertEquals(
                "Chunk $i started at the wrong offset",
                expectedOffset,
                channel.requestedOffsets[i]
            )
            expectedOffset += minOf(channel.requestedLengths[i], 1000)
        }
        assertEquals(8192, expectedOffset)
    }

    // ── Zero-byte returns ────────────────────────────────────────────────────

    @Test
    fun `zero-byte return is retried without advancing`() {
        var attempts = 0
        val channel = FakeChannel { _, length, _ ->
            attempts++
            if (attempts <= 2) 0 else length
        }
        val result = UsbStreamEngine(
            channel,
            fastConfig(maxNoProgressRetries = 3)
        ).stream(ByteArray(4096))

        assertTrue("Expected Completed, got $result", result is StreamResult.Completed)
        assertEquals(4096L, (result as StreamResult.Completed).totalConfirmed)
        assertEquals(3, channel.calls)
        assertTrue(channel.requestedOffsets.all { it == 0 })
    }

    @Test
    fun `persistent zero-byte returns exhaust budget without advancing`() {
        val channel = FakeChannel { _, _, _ -> 0 }
        val result = UsbStreamEngine(
            channel,
            fastConfig(maxNoProgressRetries = 3)
        ).stream(ByteArray(4096))

        assertTrue(result is StreamResult.TransferFailed)
        val f = result as StreamResult.TransferFailed
        assertEquals(0L, f.failureOffset)
        assertFalse(f.ambiguous)
        assertFalse(f.recoveryRequired)
        assertEquals(TransferResultKind.NO_PROGRESS, f.lastResult)
        assertEquals(4, channel.calls) // initial + 3 retries
    }

    // ── rc = -1 (the real Ink Tank failure) ──────────────────────────────────

    @Test
    fun `rc minus one at offset 24576 aborts as ambiguous by default`() {
        // CHUNK=8192, fail on the 4th request => offset 24576.
        val channel = FakeChannel { offset, length, _ ->
            if (offset == 24576) -1 else length
        }
        val result = UsbStreamEngine(
            channel,
            fastConfig(chunkSize = 8192, maxAmbiguousRetries = 0)
        ).stream(ByteArray(65536))

        assertTrue(result is StreamResult.TransferFailed)
        val f = result as StreamResult.TransferFailed
        assertEquals(24576L, f.failureOffset)
        assertEquals(24576L, f.totalConfirmed)
        assertTrue("rc=-1 must be treated as byte-commit ambiguous", f.ambiguous)
        assertTrue("Job must require recovery", f.recoveryRequired)
        assertTrue(f.connectionValid)
        assertEquals(TransferResultKind.TIMEOUT, f.lastResult)
        // The failed chunk must NOT be re-requested automatically.
        assertEquals(4, channel.calls)
    }

    @Test
    fun `rc minus one can retry same chunk only when explicitly opted in`() {
        var failedOnce = false
        val channel = FakeChannel { _, length, _ ->
            if (!failedOnce) {
                failedOnce = true
                -1
            } else {
                length
            }
        }
        val result = UsbStreamEngine(
            channel,
            fastConfig(maxAmbiguousRetries = 1, maxConsecutiveRetries = 4)
        ).stream(ByteArray(8192))

        assertTrue("Expected Completed with explicit ambiguous retry, got $result", result is StreamResult.Completed)
        assertEquals(8192L, (result as StreamResult.Completed).totalConfirmed)
        // Same offset repeated exactly once, by explicit operator configuration.
        assertEquals(listOf(0, 0, 4096), channel.requestedOffsets)
    }

    @Test
    fun `persistent rc minus one terminates within bounded budget`() {
        val channel = FakeChannel { _, _, _ -> -1 }
        val result = UsbStreamEngine(
            channel,
            fastConfig(maxAmbiguousRetries = 2, maxConsecutiveRetries = 3)
        ).stream(ByteArray(65536))

        assertTrue(result is StreamResult.TransferFailed)
        val f = result as StreamResult.TransferFailed
        assertTrue(f.ambiguous)
        assertTrue(channel.calls <= 4)
    }

    // ── Disconnect / invalid returns / cancellation ──────────────────────────

    @Test
    fun `physical disconnect is marked ambiguous and unrecoverable automatically`() {
        val channel = FakeChannel { offset, length, ch ->
            if (offset >= 16384) {
                ch.attached = false
                -1
            } else {
                length
            }
        }
        val result = UsbStreamEngine(channel, fastConfig()).stream(ByteArray(32768))

        assertTrue(result is StreamResult.TransferFailed)
        val f = result as StreamResult.TransferFailed
        assertFalse(f.connectionValid)
        assertTrue(f.ambiguous)
        assertTrue(f.recoveryRequired)
        assertEquals(TransferResultKind.DISCONNECTED, f.lastResult)
    }

    @Test
    fun `return larger than requested is rejected as protocol error`() {
        val channel = FakeChannel { _, length, _ -> length + 1 }
        val result = UsbStreamEngine(channel, fastConfig()).stream(ByteArray(4096))
        assertTrue(result is StreamResult.TransferFailed)
        val f = result as StreamResult.TransferFailed
        assertEquals(TransferResultKind.INVALID_RETURN, f.lastResult)
        assertTrue(f.ambiguous)
        assertEquals(1, channel.calls)
    }

    @Test
    fun `cancellation stops between chunks without losing confirmed bytes`() {
        lateinit var engine: UsbStreamEngine
        val channel = FakeChannel { _, length, ch ->
            if (ch.calls == 2) engine.cancel()
            length
        }
        engine = UsbStreamEngine(channel, fastConfig())
        val result = engine.stream(ByteArray(65536))

        assertTrue(result is StreamResult.Cancelled)
        assertEquals(8192L, (result as StreamResult.Cancelled).confirmedSoFar)
        assertEquals(2, channel.calls)
    }

    @Test
    fun `cancel signal is observed`() {
        var cancel = false
        val channel = FakeChannel { _, length, _ ->
            if (channelCallsRef.get() >= 1) cancel = true
            channelCallsRef.incrementAndGet()
            length
        }
        val result = UsbStreamEngine(channel, fastConfig()).stream(
            ByteArray(65536),
            cancelSignal = { cancel }
        )
        assertTrue(result is StreamResult.Cancelled)
    }

    private val channelCallsRef = java.util.concurrent.atomic.AtomicInteger(0)

    // ── Snapshots / config ───────────────────────────────────────────────────

    @Test
    fun `snapshots carry chunk offset result and total diagnostics`() {
        val snapshots = mutableListOf<StreamSnapshot>()
        val channel = FakeChannel { _, length, _ -> length }
        val result = UsbStreamEngine(channel, fastConfig()).stream(
            data = ByteArray(8192),
            callback = object : StreamProgressCallback {
                override fun onProgress(snapshot: StreamSnapshot) {
                    snapshots.add(snapshot)
                }
            }
        )

        assertTrue(result is StreamResult.Completed)
        assertEquals(2, snapshots.size)
        assertEquals(1, snapshots[0].chunkIndex)
        assertEquals(2, snapshots[0].totalChunks)
        assertEquals(4096, snapshots[0].currentChunkSize)
        assertEquals(0L, snapshots[0].offset)
        assertEquals(4096L, snapshots[0].confirmedBytes)
        assertEquals(50, snapshots[0].percentComplete)
        assertEquals(TransferResultKind.CONFIRMED, snapshots[0].lastResult)
        assertEquals(8192L, snapshots[1].confirmedBytes)
        assertEquals(100, snapshots[1].percentComplete)
        assertEquals(0L, snapshots[1].remainingBytes)
    }

    @Test
    fun `invalid config values are rejected`() {
        try {
            StreamConfig(chunkSize = 0)
            fail("chunkSize=0 must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            StreamConfig(transferTimeoutMs = 0)
            fail("transferTimeoutMs=0 must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals(4, StreamConfig(chunkSize = 4096).totalChunks(16_384))
        assertEquals(0, StreamConfig(chunkSize = 4096).totalChunks(0))
    }
}
