package com.pocketprint.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String = "USB",
    val message: String,
    val level: LogLevel = LogLevel.INFO
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    val displayString: String
        get() = "[$formattedTime] [$tag] $message"
}
