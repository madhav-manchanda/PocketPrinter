package com.pocketprint.data.print

import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintProgress
import com.pocketprint.data.print.transport.PrinterConnectionState
import com.pocketprint.data.print.transport.StreamSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PrintStatusManager {

    private val _progressFlow = MutableStateFlow(PrintProgress())
    val progressFlow: StateFlow<PrintProgress> = _progressFlow.asStateFlow()

    fun beginDocument(
        documentName: String,
        documentPageCount: Int,
        scheduledItemCount: Int,
        copyCount: Int
    ) {
        val connection = _progressFlow.value.connectionState
        _progressFlow.value = PrintProgress(
            state = PrintJobState.PREPARING_DOCUMENT,
            message = "Preparing $documentName",
            connectionState = connection,
            documentName = documentName,
            documentPageCount = documentPageCount,
            scheduledItemCount = scheduledItemCount,
            copyCount = copyCount
        )
    }

    fun beginScheduledItem(
        documentPage: Int,
        scheduledItem: Int,
        copyNumber: Int
    ) {
        val current = _progressFlow.value
        val completed = (scheduledItem - 1).coerceAtLeast(0)
        _progressFlow.value = current.copy(
            documentPage = documentPage,
            scheduledItem = scheduledItem,
            completedItems = completed,
            copyNumber = copyNumber,
            bytesTransferred = 0,
            totalBytes = 0,
            progressPercent = 0,
            retryCount = 0,
            currentChunkIndex = 0,
            totalChunks = 0,
            currentChunkSize = 0,
            currentOffset = 0,
            lastTransferResult = "",
            overallProgressPercent = overallPercent(completed, current.scheduledItemCount, 0)
        )
    }

    fun completeScheduledItem() {
        val current = _progressFlow.value
        val completed = current.scheduledItem.coerceAtMost(current.scheduledItemCount)
        _progressFlow.value = current.copy(
            completedItems = completed,
            progressPercent = 100,
            overallProgressPercent = overallPercent(completed, current.scheduledItemCount, 0)
        )
    }

    fun updateState(state: PrintJobState, message: String, progressPercent: Int = 0) {
        val current = _progressFlow.value
        _progressFlow.value = current.copy(
            state = state,
            message = message,
            progressPercent = progressPercent,
            overallProgressPercent = if (state == PrintJobState.COMPLETED_SUCCESS) {
                100
            } else {
                current.overallProgressPercent
            }
        )
        if (state == PrintJobState.FAILED || state == PrintJobState.NEEDS_RECOVERY) {
            DiagnosticLogger.usbError("[PRINT] [$state] $message")
        } else {
            DiagnosticLogger.usb("[PRINT] [$state] $message")
        }
    }

    fun updateTransferProgress(
        bytesTransferred: Long,
        totalBytes: Long,
        retryCount: Int = 0,
        lastChunkMs: Long = 0
    ) {
        val percent = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0
        val speedBps = if (lastChunkMs > 0) (bytesTransferred * 1000 / lastChunkMs) else 0L
        val remaining = totalBytes - bytesTransferred
        val message = buildString {
            append("Sending print data: $percent% ")
            append("($bytesTransferred / $totalBytes bytes, ")
            append("${remaining}B remaining")
            if (retryCount > 0) append(", retry $retryCount")
            append(")")
        }
        _progressFlow.value = _progressFlow.value.copy(
            state = PrintJobState.TRANSMITTING_USB,
            bytesTransferred = bytesTransferred,
            totalBytes = totalBytes,
            progressPercent = percent,
            message = message,
            retryCount = retryCount,
            lastChunkMs = lastChunkMs,
            transferSpeedBps = speedBps
        )
    }

    /**
     * Applies a complete engine snapshot. This is the authoritative progress
     * update for the UI and includes chunk/offset/retry diagnostics.
     */
    fun updateSnapshot(snapshot: StreamSnapshot) {
        val percent = snapshot.percentComplete
        val message = buildString {
            append("Sending print data: $percent% ")
            append("(${snapshot.confirmedBytes} / ${snapshot.totalBytes} bytes, ")
            append("${snapshot.remainingBytes}B remaining, ")
            append("chunk ${snapshot.chunkIndex}/${snapshot.totalChunks} ")
            append("@ ${snapshot.currentChunkSize}B")
            if (snapshot.retryCount > 0) append(", retry ${snapshot.retryCount}")
            append(")")
        }
        _progressFlow.value = _progressFlow.value.copy(
            state = PrintJobState.TRANSMITTING_USB,
            bytesTransferred = snapshot.confirmedBytes,
            totalBytes = snapshot.totalBytes,
            progressPercent = percent,
            message = message,
            retryCount = snapshot.retryCount,
            lastChunkMs = snapshot.chunkMs,
            transferSpeedBps = snapshot.speedBps,
            currentChunkIndex = snapshot.chunkIndex,
            totalChunks = snapshot.totalChunks,
            currentChunkSize = snapshot.currentChunkSize,
            currentOffset = snapshot.offset,
            lastTransferResult = snapshot.lastResult.name,
            elapsedMs = snapshot.elapsedMs,
            overallProgressPercent = overallPercent(
                _progressFlow.value.completedItems,
                _progressFlow.value.scheduledItemCount,
                percent
            )
        )
    }

    fun updateConnectionState(state: PrinterConnectionState) {
        _progressFlow.value = _progressFlow.value.copy(
            connectionState = state.name
        )
        DiagnosticLogger.usb("[PRINT] Connection state → $state")
    }

    fun updatePortStatus(status: String) {
        _progressFlow.value = _progressFlow.value.copy(portStatus = status)
    }

    fun markRecoveryRequired(detail: String) {
        _progressFlow.value = _progressFlow.value.copy(
            state = PrintJobState.NEEDS_RECOVERY,
            recoveryRequired = true,
            detail = detail,
            message = detail
        )
    }

    fun reset() {
        val connection = _progressFlow.value.connectionState
        _progressFlow.value = PrintProgress(
            state = PrintJobState.IDLE,
            message = "Ready",
            connectionState = connection
        )
    }

    private fun overallPercent(completed: Int, total: Int, currentPercent: Int): Int {
        if (total <= 0) return currentPercent.coerceIn(0, 100)
        val scaled = completed * 100L + currentPercent.coerceIn(0, 100)
        return (scaled / total).toInt().coerceIn(0, 100)
    }
}
