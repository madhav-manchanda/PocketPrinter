package com.pocketprint.data.print.transport

import android.hardware.usb.UsbConstants as AndroidUsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.usb.UsbConstants

/**
 * Connection state for the printer USB link.
 *
 * These states are ORTHOGONAL to print-job states. A TRANSFER_ERROR does not
 * mean the printer is physically disconnected — it means one chunk failed.
 * The UI must not conflate the two.
 */
enum class PrinterConnectionState {
    /** No USB device enumerated on the host bus. */
    NO_DEVICE,
    /** A device is attached but no permission has been granted. */
    DEVICE_ATTACHED,
    /** Permission request is in flight. */
    PERMISSION_REQUIRED,
    /** Permission is held; interface not yet claimed. */
    PERMISSION_GRANTED,
    /** Interface is successfully claimed; printer is ready. */
    INTERFACE_CLAIMED,
    /** Ready — permission held, interface can be claimed on demand. */
    READY,
    /** A print job is actively streaming chunks. */
    PRINTING,
    /** All bytes transmitted; printer is processing internally. */
    PROCESSING,
    /** A single bulk transfer returned -1; the connection may still be valid. */
    TRANSFER_ERROR,
    /** Physical detach confirmed via ACTION_USB_DEVICE_DETACHED. */
    DISCONNECTED,
    /** Transfer outcome was ambiguous; manual intervention is required. */
    RECOVERY_REQUIRED
}

/**
 * Result of a print streaming session, surfaced to job management and UI.
 */
sealed class PrintTransmitResult {
    data class Ok(val totalBytes: Long) : PrintTransmitResult()
    data class Failed(
        val failureOffset: Long,
        val totalBytes: Long,
        val retryCount: Int,
        val connectionValid: Boolean,
        /** True when duplicate bytes MAY have reached the printer FIFO. */
        val ambiguous: Boolean,
        val message: String,
        /** True when the job must not be replayed automatically. */
        val recoveryRequired: Boolean = ambiguous,
        val portStatus: PrinterPortStatus? = null
    ) : PrintTransmitResult()
    data class Cancelled(
        val confirmedBytes: Long,
        val recoveryRequired: Boolean = confirmedBytes > 0
    ) : PrintTransmitResult()
}

/**
 * Callback issued during streaming for UI progress updates.
 */
interface TransmitProgressListener {
    /**
     * Called after every confirmed chunk.
     * All parameters are safe to read on the calling (IO) thread;
     * post to the main thread before updating UI.
     */
    fun onProgress(
        confirmedBytes: Long,
        totalBytes: Long,
        percentComplete: Int,
        chunkMs: Long,
        retryCount: Int
    )
    fun onRetry(confirmedBytes: Long, retryNumber: Int, maxRetries: Int)
    fun onRecovery(message: String)
    fun onConnectionStateChange(state: PrinterConnectionState)

    /** Called after all payload bytes are confirmed and before ZLP/reset finalization. */
    fun onFinalizing() {}

    /** Optional richer snapshot for the full engine diagnostics UI. */
    fun onSnapshot(snapshot: StreamSnapshot) {}
}

/**
 * USB print transport built on [UsbStreamEngine].
 *
 * The transport owns connection open/claim/release for the duration of one
 * [transmit] call. It never retries an ambiguous failed chunk by default and
 * never sends job-finalization control requests (ZLP / soft reset) after a
 * failed stream, because doing so can mutate printer state while the job is
 * incomplete.
 */
class UsbPrintTransport(
    private val usbManager: UsbManager
) {
    @Volatile
    private var activeWriter: UsbStreamWriter? = null

    @Volatile
    private var cancelled = false

    /** Requests cancellation of the in-flight transfer, if any. */
    fun cancelActiveTransfer() {
        cancelled = true
        activeWriter?.cancel()
    }

    /**
     * Streams [payload] to the printer's Bulk OUT endpoint.
     *
     * @param config chunk/timeout/retry policy. Defaults to the reliable 4 KB
     *   profile for production jobs.
     */
    fun transmit(
        device: UsbDevice,
        payload: ByteArray,
        listener: TransmitProgressListener? = null,
        config: StreamConfig = StreamConfig.reliable()
    ): PrintTransmitResult {
        cancelled = false

        if (!usbManager.hasPermission(device)) {
            listener?.onConnectionStateChange(PrinterConnectionState.PERMISSION_REQUIRED)
            return failure(
                payload = payload,
                connectionValid = false,
                message = "USB permission not granted for ${device.deviceName}"
            )
        }

        listener?.onConnectionStateChange(PrinterConnectionState.PERMISSION_GRANTED)

        if (payload.isEmpty()) {
            // Nothing to send; an empty payload is not a printable job.
            return failure(
                payload = payload,
                connectionValid = true,
                message = "Print payload is empty"
            )
        }

        var connection: UsbDeviceConnection? = null
        var claimedInterface: UsbInterface? = null

        try {
            connection = usbManager.openDevice(device)
                ?: return failure(
                    payload = payload,
                    connectionValid = false,
                    message = "UsbManager.openDevice() returned null for ${device.deviceName}"
                )

            DiagnosticLogger.usb("[TRANSPORT] Connection opened (FD: ${connection.fileDescriptor})")

            val endpoints = findPrinterEndpoints(device)
                ?: return failure(
                    payload = payload,
                    connectionValid = false,
                    message = "No USB printer interface/Bulk OUT endpoint on ${device.deviceName}"
                )

            val (targetInterface, bulkOut, bulkIn) = endpoints

            DiagnosticLogger.usb(
                "[TRANSPORT] Interface #${targetInterface.id} " +
                "(Class 0x${String.format("%02X", targetInterface.interfaceClass)})"
            )
            DiagnosticLogger.usb(
                "[TRANSPORT] Bulk OUT 0x${String.format("%02X", bulkOut.address)} " +
                "(MaxPacket: ${bulkOut.maxPacketSize}B, chunk=${config.chunkSize}B, timeout=${config.transferTimeoutMs}ms)"
            )
            bulkIn?.let {
                DiagnosticLogger.usb(
                    "[TRANSPORT] Bulk IN  0x${String.format("%02X", it.address)} " +
                    "(MaxPacket: ${it.maxPacketSize}B)"
                )
            }

            val claimed = connection.claimInterface(targetInterface, true)
            if (!claimed) {
                return failure(
                    payload = payload,
                    connectionValid = true,
                    message = "Failed to claim interface #${targetInterface.id}. Locked by kernel or another service."
                )
            }
            claimedInterface = targetInterface
            listener?.onConnectionStateChange(PrinterConnectionState.INTERFACE_CLAIMED)

            var interfaceStillClaimed = true
            val writer = UsbStreamWriter(
                connection = connection,
                bulkOut = bulkOut,
                usbManager = usbManager,
                device = device,
                interfaceId = targetInterface.id,
                config = config,
                claimedProvider = { interfaceStillClaimed }
            )
            activeWriter = writer

            listener?.onConnectionStateChange(PrinterConnectionState.PRINTING)

            val streamResult = writer.stream(
                data = payload,
                callback = object : StreamProgressCallback {
                    override fun onProgress(snapshot: StreamSnapshot) {
                        listener?.onProgress(
                            snapshot.confirmedBytes,
                            snapshot.totalBytes,
                            snapshot.percentComplete,
                            snapshot.chunkMs,
                            snapshot.retryCount
                        )
                        listener?.onSnapshot(snapshot)
                    }

                    override fun onRetry(offset: Long, retryNumber: Int, maxRetries: Int, reason: String) {
                        listener?.onRetry(offset, retryNumber, maxRetries)
                        listener?.onConnectionStateChange(PrinterConnectionState.TRANSFER_ERROR)
                    }

                    override fun onRecovery(message: String) {
                        listener?.onRecovery(message)
                    }

                    override fun onPrinterStatus(status: PrinterPortStatus?) {
                        if (status != null) {
                            DiagnosticLogger.usb("[TRANSPORT] Port status during transfer: ${status.describe()}")
                        }
                    }
                },
                cancelSignal = { cancelled }
            )

            when (streamResult) {
                is StreamResult.Completed -> {
                    // Success only: now it is safe to finalize the job.
                    listener?.onFinalizing()
                    if (config.interChunkPacingMs > 0) {
                        Thread.sleep(config.interChunkPacingMs)
                    }
                    if (cancelled) {
                        val recoveryRequired = streamResult.totalConfirmed > 0
                        listener?.onConnectionStateChange(
                            if (recoveryRequired) PrinterConnectionState.RECOVERY_REQUIRED
                            else PrinterConnectionState.READY
                        )
                        return PrintTransmitResult.Cancelled(
                            streamResult.totalConfirmed,
                            recoveryRequired
                        )
                    }

                    if (!finalizeSuccessfulJob(connection, bulkOut, targetInterface, bulkIn, streamResult.totalConfirmed)) {
                        listener?.onConnectionStateChange(PrinterConnectionState.RECOVERY_REQUIRED)
                        return PrintTransmitResult.Failed(
                            failureOffset = streamResult.totalConfirmed,
                            totalBytes = payload.size.toLong(),
                            retryCount = 0,
                            connectionValid = true,
                            ambiguous = true,
                            message = "Payload bytes were confirmed, but required ZLP finalization failed; do not replay automatically",
                            recoveryRequired = true
                        )
                    }
                    listener?.onConnectionStateChange(PrinterConnectionState.PROCESSING)
                    return PrintTransmitResult.Ok(streamResult.totalConfirmed)
                }

                is StreamResult.TransferFailed -> {
                    // Do NOT send ZLP / soft reset / drain delay here.
                    val state = when {
                        streamResult.recoveryRequired && streamResult.connectionValid ->
                            PrinterConnectionState.RECOVERY_REQUIRED
                        streamResult.connectionValid -> PrinterConnectionState.TRANSFER_ERROR
                        else -> PrinterConnectionState.DISCONNECTED
                    }
                    listener?.onConnectionStateChange(state)
                    return PrintTransmitResult.Failed(
                        failureOffset = streamResult.failureOffset,
                        totalBytes = payload.size.toLong(),
                        retryCount = streamResult.retryCount,
                        connectionValid = streamResult.connectionValid,
                        ambiguous = streamResult.ambiguous,
                        message = buildFailureMessage(streamResult),
                        recoveryRequired = streamResult.recoveryRequired,
                        portStatus = streamResult.portStatus
                    )
                }

                is StreamResult.Cancelled -> {
                    listener?.onConnectionStateChange(
                        if (streamResult.recoveryRequired) PrinterConnectionState.RECOVERY_REQUIRED
                        else PrinterConnectionState.READY
                    )
                    return PrintTransmitResult.Cancelled(
                        streamResult.confirmedSoFar,
                        streamResult.recoveryRequired
                    )
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.usbError("[TRANSPORT] Unexpected exception: ${e.message}")
            return failure(
                payload = payload,
                connectionValid = false,
                message = "Transport exception: ${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            activeWriter = null
            if (claimedInterface != null && connection != null) {
                try {
                    connection.releaseInterface(claimedInterface)
                    DiagnosticLogger.usb("[TRANSPORT] Interface #${claimedInterface.id} released")
                } catch (e: Exception) {
                    DiagnosticLogger.usbWarn("[TRANSPORT] Error releasing interface: ${e.message}")
                }
            }
            try {
                connection?.close()
                DiagnosticLogger.usb("[TRANSPORT] Connection closed")
            } catch (e: Exception) {
                DiagnosticLogger.usbWarn("[TRANSPORT] Error closing connection: ${e.message}")
            }
        }
    }

    // ── Legacy adapter ───────────────────────────────────────────────────────

    @Deprecated("Use transmit() which returns PrintTransmitResult")
    fun sendPrintStream(
        device: UsbDevice,
        stream: java.io.InputStream,
        totalBytes: Long,
        listener: ((bytesSent: Long, total: Long) -> Unit)? = null
    ) {
        val payload = stream.readBytes()
        val result = transmit(
            device = device,
            payload = payload,
            listener = if (listener != null) object : TransmitProgressListener {
                override fun onProgress(
                    confirmedBytes: Long, totalBytes: Long, percentComplete: Int,
                    chunkMs: Long, retryCount: Int
                ) { listener(confirmedBytes, totalBytes) }
                override fun onRetry(confirmedBytes: Long, retryNumber: Int, maxRetries: Int) {}
                override fun onRecovery(message: String) {}
                override fun onConnectionStateChange(state: PrinterConnectionState) {}
            } else null
        )
        if (result is PrintTransmitResult.Failed) {
            throw IllegalStateException(result.message)
        }
    }

    // ── Post-transfer finalization (success only) ────────────────────────────

    private fun finalizeSuccessfulJob(
        connection: UsbDeviceConnection,
        bulkOut: UsbEndpoint,
        iface: UsbInterface,
        bulkIn: UsbEndpoint?,
        totalConfirmed: Long = 0L
    ): Boolean {
        // In USB 2.0 Bulk transfer specification:
        // A transfer is terminated when a packet has length < maxPacketSize.
        // If totalConfirmed is an exact non-zero multiple of maxPacketSize,
        // send a Zero-Length Packet (ZLP) so the hardware endpoint knows data ended.
        // If totalConfirmed % maxPacketSize != 0, a short packet has already terminated the transfer.
        val maxPacketSize = bulkOut.maxPacketSize.coerceAtLeast(64)
        if (totalConfirmed > 0 && (totalConfirmed % maxPacketSize) == 0L) {
            val zlpRc = try {
                connection.bulkTransfer(bulkOut, ByteArray(0), 0, 1000)
            } catch (e: Exception) {
                -1
            }
            DiagnosticLogger.usb("[TRANSPORT] ZLP flush sent for boundary multiple ($totalConfirmed B), rc=$zlpRc")
        }

        // Let the printer buffer drain before closing/releasing interface.
        try {
            Thread.sleep(300)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        readAndLogPortStatus(connection, iface, bulkIn)
        return true
    }

    // ── Port status ───────────────────────────────────────────────────────────

    private fun readAndLogPortStatus(
        connection: UsbDeviceConnection,
        iface: UsbInterface,
        bulkIn: UsbEndpoint?
    ) {
        val statusBuf = ByteArray(1)
        val rc = connection.controlTransfer(
            0xA1, 0x01, 0x0000, iface.id, statusBuf, 1, 2000
        )
        if (rc < 0) {
            DiagnosticLogger.usbWarn("[TRANSPORT] GET_PORT_STATUS failed (rc=$rc)")
            if (bulkIn != null) {
                val bulkBuf = ByteArray(16)
                val br = connection.bulkTransfer(bulkIn, bulkBuf, bulkBuf.size, 1000)
                if (br > 0) {
                    val hex = bulkBuf.take(br).joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }
                    DiagnosticLogger.usb("[TRANSPORT] Bulk IN ($br bytes): $hex")
                }
            }
            return
        }
        val status = PrinterPortStatus(statusBuf[0].toInt() and 0xFF)
        DiagnosticLogger.usb("[TRANSPORT] Port Status: ${status.describe()}")
    }

    // ── Endpoint discovery ────────────────────────────────────────────────────

    private fun findPrinterEndpoints(
        device: UsbDevice
    ): Triple<UsbInterface, UsbEndpoint, UsbEndpoint?>? {
        // Pass 1: USB Printer Class (0x07)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.CLASS_PRINTER) {
                val epOut = findBulkEndpoint(iface, out = true)
                if (epOut != null) {
                    val epIn = findBulkEndpoint(iface, out = false)
                    return Triple(iface, epOut, epIn)
                }
            }
        }
        return null
    }

    private fun findBulkEndpoint(iface: UsbInterface, out: Boolean): UsbEndpoint? {
        val dir = if (out) AndroidUsbConstants.USB_DIR_OUT else AndroidUsbConstants.USB_DIR_IN
        for (j in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(j)
            if (ep.type == AndroidUsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == dir) {
                return ep
            }
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun failure(
        payload: ByteArray,
        connectionValid: Boolean,
        message: String,
        ambiguous: Boolean = false,
        recoveryRequired: Boolean = false
    ): PrintTransmitResult.Failed = PrintTransmitResult.Failed(
        failureOffset = 0,
        totalBytes = payload.size.toLong(),
        retryCount = 0,
        connectionValid = connectionValid,
        ambiguous = ambiguous,
        message = message,
        recoveryRequired = recoveryRequired
    )

    private fun buildFailureMessage(r: StreamResult.TransferFailed): String {
        val ambiguityNote = if (r.ambiguous)
            " Transfer outcome ambiguous — do NOT replay this job automatically."
        else ""
        val connNote = if (!r.connectionValid) " Connection lost." else ""
        val portNote = r.portStatus?.let { " Printer: ${it.describe()}." } ?: ""
        return "USB stream failed at offset ${r.failureOffset}B " +
               "after ${r.retryCount} retries.$connNote$portNote$ambiguityNote " +
               if (r.detail.isNotBlank()) "(${r.detail})" else ""
    }
}
