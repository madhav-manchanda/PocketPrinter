package com.pocketprint.data.queue

import com.pocketprint.data.print.model.PrintOptions

/** Durable states used only by the local print queue. */
enum class LocalPrintJobState {
    QUEUED,
    WAITING_FOR_PRINTER,
    PREPARING,
    PRINTING,
    TRANSMISSION_COMPLETED,
    COMPLETED,
    FAILED,
    CANCELLED,
    NEEDS_RECOVERY;

    val isRunnable: Boolean
        get() = this == QUEUED || this == WAITING_FOR_PRINTER

    /** A process interruption in these states has an uncertain print outcome. */
    val isInFlight: Boolean
        get() = this == PREPARING || this == PRINTING || this == TRANSMISSION_COMPLETED

    val isFinished: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isTerminal: Boolean
        get() = isFinished || this == NEEDS_RECOVERY
}

/**
 * Persistent local print job. [documentUri] is the URI's string form so it can
 * be restored without depending on an in-memory Android Uri instance.
 * [currentPage] is one-based while zero means that no page is active.
 */
data class LocalPrintJob(
    val id: String,
    val documentUri: String,
    val displayName: String,
    val mimeType: String,
    val printOptions: PrintOptions,
    val state: LocalPrintJobState = LocalPrintJobState.QUEUED,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val completedPages: Int = 0,
    val bytesConfirmed: Long = 0L,
    val totalBytes: Long = 0L,
    val progressPercent: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val failureDetail: String? = null,
    val recoveryDetail: String? = null
) {
    init {
        require(id.isNotBlank()) { "Print job id must not be blank" }
        require(documentUri.isNotBlank()) { "Document URI must not be blank" }
        require(displayName.isNotBlank()) { "Display name must not be blank" }
        require(mimeType.isNotBlank()) { "MIME type must not be blank" }
        require(totalPages >= 0) { "Total pages must not be negative" }
        require(currentPage >= 0) { "Current page must not be negative" }
        require(completedPages >= 0) { "Completed pages must not be negative" }
        require(totalPages == 0 || currentPage <= totalPages) {
            "Current page must not exceed total pages"
        }
        require(totalPages == 0 || completedPages <= totalPages) {
            "Completed pages must not exceed total pages"
        }
        require(bytesConfirmed >= 0L) { "Confirmed bytes must not be negative" }
        require(totalBytes >= 0L) { "Total bytes must not be negative" }
        require(totalBytes == 0L || bytesConfirmed <= totalBytes) {
            "Confirmed bytes must not exceed total bytes"
        }
        require(progressPercent in 0..100) { "Progress must be between 0 and 100" }
        require(createdAt >= 0L) { "Created timestamp must not be negative" }
        require(updatedAt >= createdAt) { "Updated timestamp must not precede creation" }
    }
}

/** Pure transition rules shared by the store and local unit tests. */
object LocalPrintJobTransitions {
    fun canTransition(from: LocalPrintJobState, to: LocalPrintJobState): Boolean {
        if (from == to) return true
        return when (from) {
            LocalPrintJobState.QUEUED -> to in setOf(
                LocalPrintJobState.WAITING_FOR_PRINTER,
                LocalPrintJobState.PREPARING,
                LocalPrintJobState.FAILED,
                LocalPrintJobState.CANCELLED
            )
            LocalPrintJobState.WAITING_FOR_PRINTER -> to in setOf(
                LocalPrintJobState.QUEUED,
                LocalPrintJobState.PREPARING,
                LocalPrintJobState.FAILED,
                LocalPrintJobState.CANCELLED
            )
            LocalPrintJobState.PREPARING -> to in setOf(
                LocalPrintJobState.WAITING_FOR_PRINTER,
                LocalPrintJobState.PRINTING,
                LocalPrintJobState.FAILED,
                LocalPrintJobState.CANCELLED,
                LocalPrintJobState.NEEDS_RECOVERY
            )
            LocalPrintJobState.PRINTING -> to in setOf(
                LocalPrintJobState.TRANSMISSION_COMPLETED,
                LocalPrintJobState.FAILED,
                LocalPrintJobState.CANCELLED,
                LocalPrintJobState.NEEDS_RECOVERY
            )
            LocalPrintJobState.TRANSMISSION_COMPLETED -> to in setOf(
                LocalPrintJobState.COMPLETED,
                LocalPrintJobState.FAILED,
                LocalPrintJobState.NEEDS_RECOVERY
            )
            LocalPrintJobState.COMPLETED,
            LocalPrintJobState.FAILED,
            LocalPrintJobState.CANCELLED,
            LocalPrintJobState.NEEDS_RECOVERY -> false
        }
    }

    fun requireTransition(from: LocalPrintJobState, to: LocalPrintJobState) {
        check(canTransition(from, to)) { "Invalid local print job transition: $from -> $to" }
    }

    /** A full replay is safe only if nothing was confirmed as sent or printed. */
    fun canRetrySafely(job: LocalPrintJob): Boolean {
        val retryableState = job.state == LocalPrintJobState.FAILED ||
            job.state == LocalPrintJobState.CANCELLED
        return retryableState && job.bytesConfirmed == 0L && job.completedPages == 0
    }
}
