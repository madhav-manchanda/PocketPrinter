package com.pocketprint.data.print.transport

import com.pocketprint.data.logging.DiagnosticLogger
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Process-wide ownership gate: only one document or diagnostic may own USB printing. */
object UsbJobArbiter {
    private val permit = Semaphore(1, true)

    fun acquire(owner: String, cancelled: () -> Boolean = { false }): Boolean {
        while (!cancelled()) {
            if (permit.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                DiagnosticLogger.usb("[ARBITER] USB print ownership acquired by $owner")
                return true
            }
        }
        return false
    }

    fun release(owner: String) {
        permit.release()
        DiagnosticLogger.usb("[ARBITER] USB print ownership released by $owner")
    }
}
