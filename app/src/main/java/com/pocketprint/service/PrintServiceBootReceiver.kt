package com.pocketprint.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pocketprint.data.logging.DiagnosticLogger

/** Restarts the persistent printer agent after the user has completed device boot. */
class PrintServiceBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { LocalPrintForegroundService.start(context) }
            .onSuccess {
                DiagnosticLogger.log("PRINT_SERVICE", "Persistent print agent restarted after boot")
            }
            .onFailure {
                DiagnosticLogger.log(
                    "PRINT_SERVICE",
                    "OS deferred print-agent restart after boot: ${it.message}"
                )
            }
    }
}
