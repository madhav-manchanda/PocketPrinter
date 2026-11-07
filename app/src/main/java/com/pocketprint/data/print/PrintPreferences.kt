package com.pocketprint.data.print

import android.content.Context
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.queue.PrintOptionsJson

/** Single persisted source for user print defaults and overrides. */
class PrintPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): PrintOptions {
        val stored = preferences.getString(KEY_OPTIONS, null) ?: return PrintOptions()
        return runCatching { PrintOptionsJson.decode(stored) }
            .onFailure {
                DiagnosticLogger.usbWarn("Stored print preferences were invalid; approved defaults restored")
            }
            .getOrDefault(PrintOptions())
    }

    fun save(options: PrintOptions) {
        preferences.edit().putString(KEY_OPTIONS, PrintOptionsJson.encode(options)).apply()
    }

    val hasExplicitSelection: Boolean
        get() = preferences.contains(KEY_OPTIONS)

    companion object {
        private const val PREFS_NAME = "pocketprint_print_preferences"
        private const val KEY_OPTIONS = "print_options_v2"
    }
}
