package com.pocketprint.data.print

import android.content.Context
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Minimal durable record of in-flight jobs.
 *
 * Purpose: if the process dies mid-transfer, the job must NOT be replayed
 * automatically on next start. Playback could duplicate already-committed
 * bytes in the printer FIFO. On startup the journal converts every open job
 * to [PrintJobState.NEEDS_RECOVERY].
 */
data class JournaledJob(
    val jobId: String,
    val label: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val totalBytes: Long,
    val confirmedBytes: Long,
    val state: PrintJobState,
    val ambiguous: Boolean
)

class PrintJobJournal(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun startJob(
        jobId: String = UUID.randomUUID().toString(),
        label: String,
        totalBytes: Long
    ): String {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(key(jobId, FIELD_LABEL), label)
            .putLong(key(jobId, FIELD_CREATED), now)
            .putLong(key(jobId, FIELD_UPDATED), now)
            .putLong(key(jobId, FIELD_TOTAL), totalBytes)
            .putLong(key(jobId, FIELD_CONFIRMED), 0L)
            .putString(key(jobId, FIELD_STATE), PrintJobState.QUEUED.name)
            .putBoolean(key(jobId, FIELD_AMBIGUOUS), false)
            .putStringSet(KEY_OPEN_JOBS, openJobIds() + jobId)
            .apply()
        return jobId
    }

    @Synchronized
    fun updateJob(
        jobId: String,
        confirmedBytes: Long,
        totalBytes: Long,
        state: PrintJobState,
        ambiguous: Boolean = false
    ) {
        if (!openJobIds().contains(jobId)) return
        prefs.edit()
            .putLong(key(jobId, FIELD_UPDATED), System.currentTimeMillis())
            .putLong(key(jobId, FIELD_TOTAL), totalBytes)
            .putLong(key(jobId, FIELD_CONFIRMED), confirmedBytes)
            .putString(key(jobId, FIELD_STATE), state.name)
            .putBoolean(key(jobId, FIELD_AMBIGUOUS), ambiguous)
            .apply()
    }

    @Synchronized
    fun finishJob(jobId: String, finalState: PrintJobState) {
        if (jobId.isBlank()) return
        DiagnosticLogger.usb("[JOURNAL] Job $jobId finished as ${finalState.name}")
        readJob(jobId)?.let { appendHistory(it.copy(
            updatedAtMillis = System.currentTimeMillis(),
            state = finalState,
            ambiguous = finalState == PrintJobState.NEEDS_RECOVERY
        )) }
        prefs.edit()
            .putStringSet(KEY_OPEN_JOBS, openJobIds() - jobId)
            .remove(key(jobId, FIELD_LABEL))
            .remove(key(jobId, FIELD_CREATED))
            .remove(key(jobId, FIELD_UPDATED))
            .remove(key(jobId, FIELD_TOTAL))
            .remove(key(jobId, FIELD_CONFIRMED))
            .remove(key(jobId, FIELD_STATE))
            .remove(key(jobId, FIELD_AMBIGUOUS))
            .apply()
    }

    /**
     * Returns interrupted jobs and marks them NEEDS_RECOVERY in durable
     * storage. Never schedules a replay.
     */
    @Synchronized
    fun recoverInterruptedJobs(): List<JournaledJob> {
        val recovered = mutableListOf<JournaledJob>()
        val now = System.currentTimeMillis()
        for (jobId in openJobIds()) {
            val stateName = prefs.getString(key(jobId, FIELD_STATE), null)
            val state = runCatching { PrintJobState.valueOf(stateName ?: "") }
                .getOrDefault(PrintJobState.NEEDS_RECOVERY)
            val acceptableToRecover = state == PrintJobState.QUEUED ||
                state == PrintJobState.TRANSMITTING_USB ||
                state == PrintJobState.FINALIZING ||
                state == PrintJobState.PRINTER_PROCESSING ||
                state == PrintJobState.TRANSFER_RECOVERING ||
                state == PrintJobState.TRANSFER_ERROR

            if (!acceptableToRecover) {
                finishJob(jobId, state)
                continue
            }

            val job = JournaledJob(
                jobId = jobId,
                label = prefs.getString(key(jobId, FIELD_LABEL), "Interrupted job") ?: "Interrupted job",
                createdAtMillis = prefs.getLong(key(jobId, FIELD_CREATED), now),
                updatedAtMillis = prefs.getLong(key(jobId, FIELD_UPDATED), now),
                totalBytes = prefs.getLong(key(jobId, FIELD_TOTAL), 0L),
                confirmedBytes = prefs.getLong(key(jobId, FIELD_CONFIRMED), 0L),
                state = PrintJobState.NEEDS_RECOVERY,
                ambiguous = true
            )
            recovered.add(job)

            // Persist NEEDS_RECOVERY so a crash loop cannot accidentally
            // turn this into an automatic replay.
            prefs.edit()
                .putString(key(jobId, FIELD_STATE), PrintJobState.NEEDS_RECOVERY.name)
                .putBoolean(key(jobId, FIELD_AMBIGUOUS), true)
                .putLong(key(jobId, FIELD_UPDATED), now)
                .apply()
        }
        if (recovered.isNotEmpty()) {
            DiagnosticLogger.usbWarn(
                "[JOURNAL] Recovered ${recovered.size} interrupted job(s); marked NEEDS_RECOVERY (no auto-replay)"
            )
        }
        return recovered
    }

    @Synchronized
    fun getOpenJobs(): List<JournaledJob> {
        val now = System.currentTimeMillis()
        return openJobIds().mapNotNull { jobId ->
            val stateName = prefs.getString(key(jobId, FIELD_STATE), null) ?: return@mapNotNull null
            JournaledJob(
                jobId = jobId,
                label = prefs.getString(key(jobId, FIELD_LABEL), "Print job") ?: "Print job",
                createdAtMillis = prefs.getLong(key(jobId, FIELD_CREATED), now),
                updatedAtMillis = prefs.getLong(key(jobId, FIELD_UPDATED), now),
                totalBytes = prefs.getLong(key(jobId, FIELD_TOTAL), 0L),
                confirmedBytes = prefs.getLong(key(jobId, FIELD_CONFIRMED), 0L),
                state = runCatching { PrintJobState.valueOf(stateName) }
                    .getOrDefault(PrintJobState.NEEDS_RECOVERY),
                ambiguous = prefs.getBoolean(key(jobId, FIELD_AMBIGUOUS), false)
            )
        }
    }

    @Synchronized
    fun getHistoryJobs(): List<JournaledJob> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        JournaledJob(
                            jobId = item.getString("jobId"),
                            label = item.optString("label", "Print job"),
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                            updatedAtMillis = item.optLong("updatedAtMillis", 0L),
                            totalBytes = item.optLong("totalBytes", 0L),
                            confirmedBytes = item.optLong("confirmedBytes", 0L),
                            state = runCatching {
                                PrintJobState.valueOf(item.optString("state"))
                            }.getOrDefault(PrintJobState.FAILED),
                            ambiguous = item.optBoolean("ambiguous", false)
                        )
                    )
                }
            }.sortedByDescending { it.updatedAtMillis }
        }.getOrElse {
            DiagnosticLogger.usbWarn("[JOURNAL] Could not read job history: ${it.message}")
            emptyList()
        }
    }

    @Synchronized
    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    @Synchronized
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    private fun openJobIds(): Set<String> {
        return prefs.getStringSet(KEY_OPEN_JOBS, emptySet())?.toSet() ?: emptySet()
    }

    private fun key(jobId: String, field: String) = "$jobId.$field"

    private fun readJob(jobId: String): JournaledJob? {
        if (!openJobIds().contains(jobId)) return null
        val now = System.currentTimeMillis()
        return JournaledJob(
            jobId = jobId,
            label = prefs.getString(key(jobId, FIELD_LABEL), "Print job") ?: "Print job",
            createdAtMillis = prefs.getLong(key(jobId, FIELD_CREATED), now),
            updatedAtMillis = prefs.getLong(key(jobId, FIELD_UPDATED), now),
            totalBytes = prefs.getLong(key(jobId, FIELD_TOTAL), 0L),
            confirmedBytes = prefs.getLong(key(jobId, FIELD_CONFIRMED), 0L),
            state = runCatching {
                PrintJobState.valueOf(prefs.getString(key(jobId, FIELD_STATE), "") ?: "")
            }.getOrDefault(PrintJobState.FAILED),
            ambiguous = prefs.getBoolean(key(jobId, FIELD_AMBIGUOUS), false)
        )
    }

    private fun appendHistory(job: JournaledJob) {
        val jobs = (listOf(job) + getHistoryJobs().filterNot { it.jobId == job.jobId }).take(50)
        val array = JSONArray()
        jobs.forEach { item ->
            array.put(JSONObject().apply {
                put("jobId", item.jobId)
                put("label", item.label)
                put("createdAtMillis", item.createdAtMillis)
                put("updatedAtMillis", item.updatedAtMillis)
                put("totalBytes", item.totalBytes)
                put("confirmedBytes", item.confirmedBytes)
                put("state", item.state.name)
                put("ambiguous", item.ambiguous)
            })
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "pocketprint_print_job_journal"
        private const val KEY_OPEN_JOBS = "open_jobs"
        private const val KEY_HISTORY = "job_history"
        private const val FIELD_LABEL = "label"
        private const val FIELD_CREATED = "created"
        private const val FIELD_UPDATED = "updated"
        private const val FIELD_TOTAL = "total"
        private const val FIELD_CONFIRMED = "confirmed"
        private const val FIELD_STATE = "state"
        private const val FIELD_AMBIGUOUS = "ambiguous"
    }
}
