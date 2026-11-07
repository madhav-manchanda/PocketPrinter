package com.pocketprint.data.print.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import com.pocketprint.data.logging.DiagnosticLogger

/**
 * Parsed USB Printer Class GET_PORT_STATUS byte (documented in USB Printer Class
 * Specification 1.1, section 4.2 / 6.1).
 */
data class PrinterPortStatus(val raw: Int) {
    val paperEmpty: Boolean get() = (raw and 0x20) != 0
    val selected: Boolean get() = (raw and 0x10) != 0
    val notError: Boolean get() = (raw and 0x08) != 0

    /** True when the printer reports no paper-out, selected/online and no error. */
    val isNominal: Boolean get() = !paperEmpty && selected && notError

    /**
     * A recoverable busy/backpressure condition: the printer is online and has
     * paper, but is not yet ready to accept a new bulk packet (it is likely
     * moving already-received data into the print engine).
     */
    val isRecoverableBusy: Boolean get() = selected && !paperEmpty

    fun describe(): String = buildString {
        append("0x%02X".format(raw))
        if (paperEmpty) append(" PAPER_EMPTY")
        if (!selected) append(" NOT_SELECTED")
        if (!notError) append(" ERROR")
        if (isNominal) append(" NOMINAL")
    }
}

/** What happened to the most recent chunk request. */
enum class TransferResultKind {
    NONE,
    CONFIRMED,
    PARTIAL,
    NO_PROGRESS,
    TIMEOUT,
    DISCONNECTED,
    CANCELLED,
    INVALID_RETURN
}

/**
 * Immutable snapshot delivered to the UI after every progress event.
 * All offsets are absolute byte offsets within the job payload.
 */
data class StreamSnapshot(
    val offset: Long,
    val confirmedBytes: Long,
    val totalBytes: Long,
    val remainingBytes: Long,
    val percentComplete: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val currentChunkSize: Int,
    val retryCount: Int,
    val lastResult: TransferResultKind,
    val chunkMs: Long,
    val elapsedMs: Long,
    val speedBps: Long
)

/**
 * Streaming configuration.
 *
 * [chunkSize] is the number of bytes requested per synchronous bulkTransfer.
 * [transferTimeoutMs] is the per-call timeout, not the whole-job timeout.
 * [maxNoProgressRetries] applies to rc == 0 only (safe: zero bytes committed).
 * [maxAmbiguousRetries] applies to rc < 0 (timeout/error). It defaults to zero
 * because Android does not report how many bytes were committed before a
 * timeout; re-sending that chunk can duplicate bytes in the printer stream.
 */
data class StreamConfig(
    val chunkSize: Int = VERIFIED_CHUNK_SIZE,
    val transferTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    val maxNoProgressRetries: Int = DEFAULT_MAX_NO_PROGRESS_RETRIES,
    val maxAmbiguousRetries: Int = DEFAULT_MAX_AMBIGUOUS_RETRIES,
    val retryBackoffMs: Long = DEFAULT_RETRY_BACKOFF_MS,
    val interChunkPacingMs: Long = 0L,
    val maxConsecutiveRetries: Int = DEFAULT_MAX_CONSECUTIVE_RETRIES
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0" }
        require(transferTimeoutMs > 0) { "transferTimeoutMs must be > 0" }
        require(maxNoProgressRetries >= 0) { "maxNoProgressRetries must be >= 0" }
        require(maxAmbiguousRetries >= 0) { "maxAmbiguousRetries must be >= 0" }
        require(retryBackoffMs >= 0) { "retryBackoffMs must be >= 0" }
        require(interChunkPacingMs >= 0) { "interChunkPacingMs must be >= 0" }
        require(maxConsecutiveRetries >= 0) { "maxConsecutiveRetries must be >= 0" }
    }

    fun totalChunks(totalBytes: Int): Int {
        if (totalBytes <= 0) return 0
        return ((totalBytes.toLong() + chunkSize - 1) / chunkSize).toInt()
    }

    companion object {
        /** Previously verified diagnostic configuration (small pages). */
        const val VERIFIED_CHUNK_SIZE = 16_384

        /**
         * Conservative production chunk size. The Ink Tank 310 has a small
         * USB receive FIFO; 4 KB keeps backpressure below the timeout ceiling.
         */
        const val RELIABLE_CHUNK_SIZE = 4_096

        /** Optional middle ground. */
        const val COMPAT_CHUNK_SIZE = 8_192

        const val DEFAULT_TIMEOUT_MS = 15_000
        const val DEFAULT_MAX_NO_PROGRESS_RETRIES = 4
        const val DEFAULT_MAX_AMBIGUOUS_RETRIES = 0
        const val DEFAULT_RETRY_BACKOFF_MS = 200L
        const val DEFAULT_MAX_CONSECUTIVE_RETRIES = 12

        /** Verified diagnostic profile: preserve 16 KB / 15 s. */
        fun verified(): StreamConfig = StreamConfig(
            chunkSize = VERIFIED_CHUNK_SIZE,
            transferTimeoutMs = DEFAULT_TIMEOUT_MS
        )

        /** Reliable large-document profile: 4 KB with tiny inter-chunk pacing. */
        fun reliable(): StreamConfig = StreamConfig(
            chunkSize = RELIABLE_CHUNK_SIZE,
            transferTimeoutMs = DEFAULT_TIMEOUT_MS,
            maxNoProgressRetries = DEFAULT_MAX_NO_PROGRESS_RETRIES,
            maxAmbiguousRetries = 0,
            retryBackoffMs = DEFAULT_RETRY_BACKOFF_MS,
            interChunkPacingMs = 2L
        )

        /** Production streaming profile for large and color rasters with paper-feed tolerance and busy retries. */
        fun production(isColorOrLarge: Boolean = true): StreamConfig = StreamConfig(
            chunkSize = RELIABLE_CHUNK_SIZE,
            transferTimeoutMs = if (isColorOrLarge) 60_000 else 45_000,
            maxNoProgressRetries = 6,
            maxAmbiguousRetries = 5,
            retryBackoffMs = 1000L,
            interChunkPacingMs = 2L,
            maxConsecutiveRetries = 24
        )

        fun diagnostic(chunkSize: Int, timeoutMs: Int): StreamConfig = StreamConfig(
            chunkSize = chunkSize,
            transferTimeoutMs = timeoutMs
        )
    }
}

interface StreamProgressCallback {
    fun onProgress(snapshot: StreamSnapshot) {}
    fun onRetry(offset: Long, retryNumber: Int, maxRetries: Int, reason: String) {}
    fun onRecovery(message: String) {}
    fun onPrinterStatus(status: PrinterPortStatus?) {}
    fun onConnectionStateChange(state: PrinterConnectionState) {}
}

/**
 * Abstraction around a USB Bulk OUT pipe plus connection/status probes.
 * Kept free of Android types so the streaming state machine is unit-testable.
 */
interface UsbBulkChannel {
    fun bulkTransfer(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int
    fun isDeviceAttached(): Boolean
    fun hasPermission(): Boolean
    fun isInterfaceClaimed(): Boolean

    /** USB Printer Class GET_PORT_STATUS, or null when unsupported. */
    fun readPortStatus(): PrinterPortStatus?
}

/** Logging seam so the streaming state machine stays JVM-testable. */
interface StreamLogger {
    fun info(message: String) {}
    fun warn(message: String) {}
    fun error(message: String) {}
}

object NoopStreamLogger : StreamLogger

sealed class StreamResult {
    /** Every requested byte was confirmed by bulkTransfer. */
    data class Completed(
        val totalConfirmed: Long,
        val elapsedMs: Long
    ) : StreamResult()

    data class TransferFailed(
        val failureOffset: Long,
        val totalConfirmed: Long,
        val retryCount: Int,
        val connectionValid: Boolean,
        /**
         * True when the failed request may already have committed some bytes
         * in the printer's receive FIFO. In that case the same chunk MUST NOT
         * be resent automatically, and the job needs operator recovery.
         */
        val ambiguous: Boolean,
        /** True when safe continuation cannot be established. */
        val recoveryRequired: Boolean,
        val lastResult: TransferResultKind,
        val portStatus: PrinterPortStatus? = null,
        val detail: String = ""
    ) : StreamResult()

    data class Cancelled(
        val confirmedSoFar: Long,
        val recoveryRequired: Boolean = confirmedSoFar > 0
    ) : StreamResult()
}

/**
 * Reliable, single-threaded USB bulk-OUT streaming state machine.
 *
 * Guarantees:
 * - Exactly one bulkTransfer is in flight at a time.
 * - The offset advances only by the positive return value of bulkTransfer.
 * - Partial writes are fully accounted for.
 * - rc == 0 is retried with bounded backoff (zero bytes committed => safe).
 * - rc < 0 is treated as byte-commit uncertain. By default the engine aborts
 *   with [StreamResult.TransferFailed.ambiguous] = true instead of blindly
 *   resending the same chunk.
 * - No confirmed byte is ever transmitted twice.
 * - Cancellation is observed between transfers and during backoff.
 * - All retry counters are bounded.
 */
class UsbStreamEngine(
    private val channel: UsbBulkChannel,
    private val config: StreamConfig = StreamConfig(),
    private val logger: StreamLogger = NoopStreamLogger
) {
    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    fun stream(
        data: ByteArray,
        callback: StreamProgressCallback? = null,
        cancelSignal: () -> Boolean = { false }
    ): StreamResult {
        val totalBytes = data.size.toLong()
        val totalChunks = config.totalChunks(data.size)
        val startMs = System.currentTimeMillis()

        var confirmedOffset = 0L
        var consecutiveRetries = 0
        var noProgressRetries = 0
        var ambiguousRetries = 0
        var lastResult = TransferResultKind.NONE
        var chunkIndex = 0

        if (totalBytes == 0L) {
            return StreamResult.Completed(0L, 0L)
        }

        while (confirmedOffset < totalBytes) {
            if (isCancelled(cancelSignal)) {
                return StreamResult.Cancelled(confirmedOffset)
            }

            val remaining = (totalBytes - confirmedOffset).toInt()
            val sendLength = minOf(config.chunkSize, remaining)
            val srcOffset = confirmedOffset.toInt()
            val chunkStart = System.currentTimeMillis()
            chunkIndex++

            val rc = try {
                channel.bulkTransfer(data, srcOffset, sendLength, config.transferTimeoutMs)
            } catch (e: Exception) {
                logger.error(
                    "[STREAM] bulkTransfer threw at offset $confirmedOffset: ${e.javaClass.simpleName}: ${e.message}"
                )
                -1
            }
            val chunkMs = System.currentTimeMillis() - chunkStart

            when {
                rc < 0 -> {
                    val attached = safe { channel.isDeviceAttached() }
                    val permission = safe { channel.hasPermission() }
                    val claimed = safe { channel.isInterfaceClaimed() }
                    val connectionValid = attached && permission && claimed
                    val status = if (connectionValid) safeNull { channel.readPortStatus() } else null
                    callback?.onPrinterStatus(status)

                    lastResult = if (connectionValid) {
                        TransferResultKind.TIMEOUT
                    } else {
                        TransferResultKind.DISCONNECTED
                    }
                    consecutiveRetries++
                    val reason = when {
                        !connectionValid -> "USB device detached or interface no longer claimed"
                        status == null -> "bulk transfer timed out; printer status unavailable"
                        !status.isNominal -> "bulk transfer timed out; printer reports ${status.describe()}"
                        else -> "bulk transfer timed out; printer reports ${status.describe()} (completion uncertain)"
                    }
                    logger.warn(
                        "[STREAM] rc=$rc at offset=$confirmedOffset len=$sendLength " +
                        "chunk=${chunkIndex}/${totalChunks} timeout=${config.transferTimeoutMs}ms " +
                        "connValid=$connectionValid attached=$attached perm=$permission claimed=$claimed " +
                        "port=${status?.describe() ?: "n/a"}"
                    )
                    if (!connectionValid) {
                        return StreamResult.TransferFailed(
                            failureOffset = confirmedOffset,
                            totalConfirmed = confirmedOffset,
                            retryCount = consecutiveRetries,
                            connectionValid = false,
                            ambiguous = true,
                            recoveryRequired = true,
                            lastResult = TransferResultKind.DISCONNECTED,
                            portStatus = status,
                            detail = reason
                        )
                    }

                    if (consecutiveRetries > config.maxConsecutiveRetries) {
                        return StreamResult.TransferFailed(
                            failureOffset = confirmedOffset,
                            totalConfirmed = confirmedOffset,
                            retryCount = consecutiveRetries,
                            connectionValid = true,
                            ambiguous = true,
                            recoveryRequired = true,
                            lastResult = lastResult,
                            portStatus = status,
                            detail = "Retry budget exhausted: $reason"
                        )
                    }

                    // Only retry an ambiguous rc=-1 when the operator/diagnostic
                    // configuration explicitly allows the duplication risk.
                    if (ambiguousRetries < config.maxAmbiguousRetries) {
                        ambiguousRetries++
                        chunkIndex--
                        callback?.onRetry(
                            confirmedOffset,
                            ambiguousRetries,
                            config.maxAmbiguousRetries,
                            reason
                        )
                        val backoff = backoffFor(consecutiveRetries)
                        callback?.onRecovery(
                            "Recovering USB transfer at ${confirmedOffset}B: " +
                            "$reason. Waiting ${backoff}ms (attempt $ambiguousRetries/${config.maxAmbiguousRetries})."
                        )
                        if (!sleepInterruptibly(backoff, cancelSignal)) {
                            return StreamResult.Cancelled(confirmedOffset)
                        }
                        // Repeat the SAME chunk because this path is explicitly opt-in.
                        continue
                    }

                    return StreamResult.TransferFailed(
                        failureOffset = confirmedOffset,
                        totalConfirmed = confirmedOffset,
                        retryCount = consecutiveRetries,
                        connectionValid = true,
                        ambiguous = true,
                        recoveryRequired = true,
                        lastResult = lastResult,
                        portStatus = status,
                        detail = reason
                    )
                }

                rc == 0 -> {
                    lastResult = TransferResultKind.NO_PROGRESS
                    consecutiveRetries++
                    noProgressRetries++
                    logger.warn(
                        "[STREAM] Zero-byte return at offset=$confirmedOffset len=$sendLength " +
                        "(noProgress=$noProgressRetries/$config.maxNoProgressRetries)"
                    )
                    val attached = safe { channel.isDeviceAttached() }
                    val permission = safe { channel.hasPermission() }
                    val claimed = safe { channel.isInterfaceClaimed() }
                    if (!attached || !permission || !claimed) {
                        return StreamResult.TransferFailed(
                            failureOffset = confirmedOffset,
                            totalConfirmed = confirmedOffset,
                            retryCount = consecutiveRetries,
                            connectionValid = false,
                            ambiguous = false,
                            recoveryRequired = confirmedOffset > 0,
                            lastResult = TransferResultKind.DISCONNECTED,
                            detail = "USB device detached after zero-byte return"
                        )
                    }

                    if (noProgressRetries > config.maxNoProgressRetries ||
                        consecutiveRetries > config.maxConsecutiveRetries
                    ) {
                        return StreamResult.TransferFailed(
                            failureOffset = confirmedOffset,
                            totalConfirmed = confirmedOffset,
                            retryCount = consecutiveRetries,
                            connectionValid = true,
                            ambiguous = false,
                            recoveryRequired = confirmedOffset > 0,
                            lastResult = TransferResultKind.NO_PROGRESS,
                            detail = "Zero-byte retry budget exhausted"
                        )
                    }

                    val backoff = backoffFor(noProgressRetries)
                    callback?.onRetry(
                        confirmedOffset,
                        noProgressRetries,
                        config.maxNoProgressRetries,
                        "bulkTransfer returned 0 (no progress)"
                    )
                    callback?.onRecovery(
                        "Printer not accepting data at ${confirmedOffset}B. " +
                        "Waiting ${backoff}ms (attempt $noProgressRetries/${config.maxNoProgressRetries})."
                    )
                    if (!sleepInterruptibly(backoff, cancelSignal)) {
                        return StreamResult.Cancelled(confirmedOffset)
                    }
                }

                rc > sendLength -> {
                    // Defensive: a USB stack must never claim more than requested.
                    lastResult = TransferResultKind.INVALID_RETURN
                    logger.error(
                        "[STREAM] Invalid bulkTransfer rc=$rc > requested=$sendLength at offset=$confirmedOffset"
                    )
                    return StreamResult.TransferFailed(
                        failureOffset = confirmedOffset,
                        totalConfirmed = confirmedOffset,
                        retryCount = consecutiveRetries,
                        connectionValid = true,
                        ambiguous = true,
                        recoveryRequired = true,
                        lastResult = TransferResultKind.INVALID_RETURN,
                        detail = "bulkTransfer returned more bytes than requested"
                    )
                }

                else -> {
                    // rc > 0 and rc <= sendLength: advance by the ACTUAL value only.
                    val endOffset = confirmedOffset + rc
                    confirmedOffset = minOf(endOffset, totalBytes)
                    lastResult = if (rc < sendLength) {
                        TransferResultKind.PARTIAL
                    } else {
                        TransferResultKind.CONFIRMED
                    }

                    if (rc < sendLength) {
                        logger.warn(
                            "[STREAM] Partial write at offset=$srcOffset requested=$sendLength " +
                            "confirmed=$rc; next offset=$confirmedOffset"
                        )
                    }

                    // Progress resets consecutive and no-progress budgets.
                    consecutiveRetries = 0
                    noProgressRetries = 0
                    ambiguousRetries = 0

                    if (config.interChunkPacingMs > 0) {
                        if (!sleepInterruptibly(config.interChunkPacingMs, cancelSignal)) {
                            return StreamResult.Cancelled(confirmedOffset)
                        }
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = (now - startMs).coerceAtLeast(1L)
                    val speed = (confirmedOffset * 1000L) / elapsed
                    val percent = if (totalBytes > 0) {
                        ((confirmedOffset * 100L) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        100
                    }

                    callback?.onProgress(
                        StreamSnapshot(
                            offset = srcOffset.toLong(),
                            confirmedBytes = confirmedOffset,
                            totalBytes = totalBytes,
                            remainingBytes = (totalBytes - confirmedOffset).coerceAtLeast(0L),
                            percentComplete = percent,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks,
                            currentChunkSize = sendLength,
                            retryCount = consecutiveRetries,
                            lastResult = lastResult,
                            chunkMs = chunkMs,
                            elapsedMs = elapsed,
                            speedBps = speed
                        )
                    )
                }
            }
        }

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
        check(confirmedOffset == totalBytes) {
            "USB stream integrity failure: confirmed=$confirmedOffset total=$totalBytes"
        }
        logger.info("[STREAM] Complete: $confirmedOffset / $totalBytes bytes confirmed in ${elapsed}ms")
        return StreamResult.Completed(confirmedOffset, elapsed)
    }

    private fun isCancelled(cancelSignal: () -> Boolean): Boolean {
        return cancelled || safe { cancelSignal() }
    }

    private fun backoffFor(attempt: Int): Long {
        val multiplier = 1L shl (attempt - 1).coerceIn(0, 4)
        return config.retryBackoffMs * multiplier
    }

    /** Sleep in small slices so cancellation is responsive. */
    private fun sleepInterruptibly(totalMs: Long, cancelSignal: () -> Boolean): Boolean {
        var remaining = totalMs
        while (remaining > 0) {
            if (isCancelled(cancelSignal)) return false
            val slice = minOf(remaining, 25L)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            remaining -= slice
        }
        return !isCancelled(cancelSignal)
    }

    private inline fun safe(block: () -> Boolean): Boolean {
        return try {
            block()
        } catch (e: Exception) {
            false
        }
    }

    private inline fun <T> safeNull(block: () -> T?): T? {
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Android-backed wrapper around [UsbStreamEngine].
 *
 * The connection is owned by the caller; this class never opens, closes or
 * re-claims it. It only issues sequential bulk transfers and documented
 * GET_PORT_STATUS requests.
 */
class UsbStreamWriter(
    private val connection: UsbDeviceConnection,
    private val bulkOut: UsbEndpoint,
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val interfaceId: Int,
    private val config: StreamConfig = StreamConfig(),
    private val claimedProvider: () -> Boolean = { true }
) {
    private val engine = UsbStreamEngine(AndroidBulkChannel(), config, AndroidStreamLogger)
    val chunkSize: Int get() = config.chunkSize
    val transferTimeoutMs: Int get() = config.transferTimeoutMs

    fun cancel() = engine.cancel()

    fun stream(
        data: ByteArray,
        callback: StreamProgressCallback? = null,
        cancelSignal: () -> Boolean = { false }
    ): StreamResult = engine.stream(data, callback, cancelSignal)

    private inner class AndroidBulkChannel : UsbBulkChannel {
        override fun bulkTransfer(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            return connection.bulkTransfer(bulkOut, buffer, offset, length, timeoutMs)
        }

        override fun isDeviceAttached(): Boolean {
            return try {
                usbManager.deviceList.values.any { it.deviceId == device.deviceId }
            } catch (e: Exception) {
                false
            }
        }

        override fun hasPermission(): Boolean {
            return try {
                usbManager.hasPermission(device)
            } catch (e: Exception) {
                false
            }
        }

        override fun isInterfaceClaimed(): Boolean {
            return try {
                claimedProvider()
            } catch (e: Exception) {
                false
            }
        }

        override fun readPortStatus(): PrinterPortStatus? {
            val buf = ByteArray(1)
            val rc = connection.controlTransfer(
                0xA1, 0x01, 0x0000, interfaceId, buf, 1, PORT_STATUS_TIMEOUT_MS
            )
            return if (rc >= 1) PrinterPortStatus(buf[0].toInt() and 0xFF) else null
        }
    }

    private object AndroidStreamLogger : StreamLogger {
        override fun info(message: String) = DiagnosticLogger.usb(message)
        override fun warn(message: String) = DiagnosticLogger.usbWarn(message)
        override fun error(message: String) = DiagnosticLogger.usbError(message)
    }

    companion object {
        const val PORT_STATUS_TIMEOUT_MS = 2_000
    }
}
