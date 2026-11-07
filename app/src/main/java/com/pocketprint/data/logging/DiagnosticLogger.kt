package com.pocketprint.data.logging

import android.util.Log
import com.pocketprint.data.model.LogEntry
import com.pocketprint.data.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

object DiagnosticLogger {
    private const val TAG = "PocketPrint"
    private const val MAX_LOGS = 500

    private val logList = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun log(tag: String = "USB", message: String, level: LogLevel = LogLevel.INFO) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            level = level
        )

        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, "[${entry.tag}] ${entry.message}")
            LogLevel.INFO -> Log.i(TAG, "[${entry.tag}] ${entry.message}")
            LogLevel.WARN -> Log.w(TAG, "[${entry.tag}] ${entry.message}")
            LogLevel.ERROR -> Log.e(TAG, "[${entry.tag}] ${entry.message}")
        }

        synchronized(logList) {
            if (logList.size >= MAX_LOGS) {
                logList.removeAt(0)
            }
            logList.add(entry)
            _logsFlow.value = ArrayList(logList)
        }
    }

    fun usb(message: String) = log("USB", message, LogLevel.INFO)
    fun usbWarn(message: String) = log("USB", message, LogLevel.WARN)
    fun usbError(message: String) = log("USB", message, LogLevel.ERROR)

    fun clear() {
        synchronized(logList) {
            logList.clear()
            _logsFlow.value = emptyList()
        }
        log("SYS", "Diagnostic logs cleared", LogLevel.INFO)
    }

    fun getAllLogsText(): String {
        return synchronized(logList) {
            logList.joinToString("\n") { it.displayString }
        }
    }
}
