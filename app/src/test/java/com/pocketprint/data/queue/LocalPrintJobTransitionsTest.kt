package com.pocketprint.data.queue

import com.pocketprint.data.print.model.PrintOptions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPrintJobTransitionsTest {
    @Test
    fun `only queued and waiting jobs are runnable`() {
        assertTrue(LocalPrintJobState.QUEUED.isRunnable)
        assertTrue(LocalPrintJobState.WAITING_FOR_PRINTER.isRunnable)
        assertFalse(LocalPrintJobState.PREPARING.isRunnable)
        assertFalse(LocalPrintJobState.NEEDS_RECOVERY.isRunnable)
    }

    @Test
    fun `interrupted execution states require recovery`() {
        assertTrue(LocalPrintJobState.PREPARING.isInFlight)
        assertTrue(LocalPrintJobState.PRINTING.isInFlight)
        assertTrue(LocalPrintJobState.TRANSMISSION_COMPLETED.isInFlight)
        assertFalse(LocalPrintJobState.WAITING_FOR_PRINTER.isInFlight)
        assertFalse(LocalPrintJobState.COMPLETED.isInFlight)
    }

    @Test
    fun `normal execution path is accepted and recovery cannot be replayed`() {
        assertTrue(
            LocalPrintJobTransitions.canTransition(
                LocalPrintJobState.QUEUED,
                LocalPrintJobState.PREPARING
            )
        )
        assertTrue(
            LocalPrintJobTransitions.canTransition(
                LocalPrintJobState.PRINTING,
                LocalPrintJobState.TRANSMISSION_COMPLETED
            )
        )
        assertTrue(
            LocalPrintJobTransitions.canTransition(
                LocalPrintJobState.TRANSMISSION_COMPLETED,
                LocalPrintJobState.COMPLETED
            )
        )
        assertFalse(
            LocalPrintJobTransitions.canTransition(
                LocalPrintJobState.NEEDS_RECOVERY,
                LocalPrintJobState.QUEUED
            )
        )
    }

    @Test
    fun `retry is safe only before any page or byte is confirmed`() {
        assertTrue(LocalPrintJobTransitions.canRetrySafely(job(LocalPrintJobState.FAILED)))
        assertTrue(LocalPrintJobTransitions.canRetrySafely(job(LocalPrintJobState.CANCELLED)))
        assertFalse(LocalPrintJobTransitions.canRetrySafely(job(LocalPrintJobState.NEEDS_RECOVERY)))
        assertFalse(
            LocalPrintJobTransitions.canRetrySafely(
                job(LocalPrintJobState.FAILED).copy(bytesConfirmed = 1L, totalBytes = 10L)
            )
        )
        assertFalse(
            LocalPrintJobTransitions.canRetrySafely(
                job(LocalPrintJobState.FAILED).copy(completedPages = 1, totalPages = 2)
            )
        )
    }

    private fun job(state: LocalPrintJobState) = LocalPrintJob(
        id = "job-1",
        documentUri = "content://documents/1",
        displayName = "document.pdf",
        mimeType = "application/pdf",
        printOptions = PrintOptions(),
        state = state,
        createdAt = 1L,
        updatedAt = 1L
    )
}
