package com.pocketprint.data.queue

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.pocketprint.data.print.model.PrintOptions
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** SQLite-backed FIFO queue for print jobs originating on this device. */
class LocalPrintQueueStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    private val _jobs = MutableStateFlow<List<LocalPrintJob>>(emptyList())
    val jobs: StateFlow<List<LocalPrintJob>> = _jobs.asStateFlow()

    init {
        refreshJobs()
    }

    override fun onCreate(database: SQLiteDatabase) {
        createSchema(database)
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version < 1) {
            createSchema(database)
            version = 1
        }
        if (version != newVersion) {
            throw SQLiteException("No local print queue migration from $oldVersion to $newVersion")
        }
    }

    @Synchronized
    fun enqueue(
        documentUri: String,
        displayName: String,
        mimeType: String,
        printOptions: PrintOptions,
        totalPages: Int = 0,
        totalBytes: Long = 0L,
        id: String = UUID.randomUUID().toString()
    ): LocalPrintJob {
        val now = System.currentTimeMillis()
        val job = LocalPrintJob(
            id = id,
            documentUri = documentUri,
            displayName = displayName,
            mimeType = mimeType,
            printOptions = printOptions,
            totalPages = totalPages,
            totalBytes = totalBytes,
            createdAt = now,
            updatedAt = now
        )
        inTransaction { database ->
            database.insertOrThrow(TABLE_JOBS, null, job.toContentValues())
        }
        refreshJobs()
        return job
    }

    @Synchronized
    fun nextRunnable(): LocalPrintJob? {
        val states = listOf(LocalPrintJobState.QUEUED, LocalPrintJobState.WAITING_FOR_PRINTER)
        return queryJobs(
            database = readableDatabase,
            selection = "$COLUMN_STATE IN (?, ?)",
            selectionArgs = states.map { it.name }.toTypedArray(),
            limit = "1"
        ).firstOrNull()
    }

    @Synchronized
    fun get(id: String): LocalPrintJob? = queryById(readableDatabase, id)

    @Synchronized
    fun getAll(): List<LocalPrintJob> = queryJobs(readableDatabase)

    @Synchronized
    fun updateProgress(
        id: String,
        totalPages: Int? = null,
        currentPage: Int? = null,
        completedPages: Int? = null,
        bytesConfirmed: Long? = null,
        totalBytes: Long? = null,
        progressPercent: Int? = null
    ): LocalPrintJob? {
        val mutation = inTransaction { database ->
            val existing = queryById(database, id)
                ?: return@inTransaction Mutation<LocalPrintJob?>(null, changed = false)
            val updated = existing.copy(
                totalPages = totalPages ?: existing.totalPages,
                currentPage = currentPage ?: existing.currentPage,
                completedPages = completedPages ?: existing.completedPages,
                bytesConfirmed = bytesConfirmed ?: existing.bytesConfirmed,
                totalBytes = totalBytes ?: existing.totalBytes,
                progressPercent = progressPercent ?: existing.progressPercent,
                updatedAt = nextTimestamp(existing)
            )
            updateJob(database, updated)
            Mutation(updated, changed = true)
        }
        if (mutation.changed) refreshJobs()
        return mutation.value
    }

    @Synchronized
    fun updateState(
        id: String,
        state: LocalPrintJobState,
        detail: String? = null
    ): LocalPrintJob? {
        val mutation = inTransaction { database ->
            val existing = queryById(database, id)
                ?: return@inTransaction Mutation<LocalPrintJob?>(null, changed = false)
            LocalPrintJobTransitions.requireTransition(existing.state, state)
            val normalizedDetail = detail?.trim()?.takeIf { it.isNotEmpty() }
            val updated = existing.copy(
                state = state,
                progressPercent = if (state == LocalPrintJobState.COMPLETED) 100 else existing.progressPercent,
                updatedAt = nextTimestamp(existing),
                failureDetail = if (state == LocalPrintJobState.FAILED) normalizedDetail else null,
                recoveryDetail = if (state == LocalPrintJobState.NEEDS_RECOVERY) {
                    normalizedDetail ?: DEFAULT_RECOVERY_DETAIL
                } else {
                    null
                }
            )
            updateJob(database, updated)
            Mutation(updated, changed = true)
        }
        if (mutation.changed) refreshJobs()
        return mutation.value
    }

    /**
     * Cancels work that has not entered an ambiguous in-flight state. An
     * in-flight cancellation becomes NEEDS_RECOVERY rather than claiming that
     * the printer did not receive data.
     */
    @Synchronized
    fun cancel(id: String, detail: String? = null): LocalPrintJob? {
        val mutation = inTransaction { database ->
            val existing = queryById(database, id)
                ?: return@inTransaction Mutation<LocalPrintJob?>(null, changed = false)
            if (existing.state.isTerminal) {
                return@inTransaction Mutation(existing, changed = false)
            }

            val ambiguous = existing.bytesConfirmed > 0L || existing.completedPages > 0
            val targetState = if (ambiguous) {
                LocalPrintJobState.NEEDS_RECOVERY
            } else {
                LocalPrintJobState.CANCELLED
            }
            LocalPrintJobTransitions.requireTransition(existing.state, targetState)
            val normalizedDetail = detail?.trim()?.takeIf { it.isNotEmpty() }
            val updated = existing.copy(
                state = targetState,
                updatedAt = nextTimestamp(existing),
                failureDetail = null,
                recoveryDetail = if (ambiguous) {
                    normalizedDetail ?: CANCELLATION_RECOVERY_DETAIL
                } else {
                    null
                }
            )
            updateJob(database, updated)
            Mutation(updated, changed = true)
        }
        if (mutation.changed) refreshJobs()
        return mutation.value
    }

    /** Returns null and leaves the row unchanged when replay cannot be proven safe. */
    @Synchronized
    fun retrySafe(id: String): LocalPrintJob? {
        val mutation = inTransaction { database ->
            val existing = queryById(database, id)
                ?: return@inTransaction Mutation<LocalPrintJob?>(null, changed = false)
            if (!LocalPrintJobTransitions.canRetrySafely(existing)) {
                return@inTransaction Mutation<LocalPrintJob?>(null, changed = false)
            }
            val updated = existing.copy(
                state = LocalPrintJobState.QUEUED,
                currentPage = 0,
                completedPages = 0,
                bytesConfirmed = 0L,
                progressPercent = 0,
                updatedAt = nextTimestamp(existing),
                failureDetail = null,
                recoveryDetail = null
            )
            updateJob(database, updated)
            Mutation(updated, changed = true)
        }
        if (mutation.changed) refreshJobs()
        return mutation.value
    }

    /**
     * Marks interrupted work as ambiguous. No recovered row is changed back to
     * a runnable state, so calling this method can never replay a print.
     */
    @Synchronized
    fun recoverInterruptedJobs(): List<LocalPrintJob> {
        val recovered = inTransaction { database ->
            val interruptedStates = LocalPrintJobState.entries.filter { it.isInFlight }
            val placeholders = interruptedStates.joinToString(",") { "?" }
            val jobs = queryJobs(
                database = database,
                selection = "$COLUMN_STATE IN ($placeholders)",
                selectionArgs = interruptedStates.map { it.name }.toTypedArray()
            )
            jobs.map { job ->
                val updated = job.copy(
                    state = LocalPrintJobState.NEEDS_RECOVERY,
                    updatedAt = nextTimestamp(job),
                    failureDetail = null,
                    recoveryDetail = "App stopped while the job was ${job.state.name}; " +
                        "the print outcome is uncertain and the job was not replayed."
                )
                updateJob(database, updated)
                updated
            }
        }
        if (recovered.isNotEmpty()) refreshJobs()
        return recovered
    }

    /** Removes COMPLETED, FAILED, and CANCELLED rows, retaining recovery cases. */
    @Synchronized
    fun clearFinished(): Int {
        val finishedStates = LocalPrintJobState.entries.filter { it.isFinished }
        val placeholders = finishedStates.joinToString(",") { "?" }
        val deleted = inTransaction { database ->
            database.delete(
                TABLE_JOBS,
                "$COLUMN_STATE IN ($placeholders)",
                finishedStates.map { it.name }.toTypedArray()
            )
        }
        if (deleted > 0) refreshJobs()
        return deleted
    }

    private fun refreshJobs() {
        _jobs.value = queryJobs(readableDatabase)
    }

    private fun queryById(database: SQLiteDatabase, id: String): LocalPrintJob? {
        return queryJobs(
            database = database,
            selection = "$COLUMN_ID = ?",
            selectionArgs = arrayOf(id),
            limit = "1"
        ).firstOrNull()
    }

    private fun queryJobs(
        database: SQLiteDatabase,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        limit: String? = null
    ): List<LocalPrintJob> {
        return database.query(
            TABLE_JOBS,
            JOB_COLUMNS,
            selection,
            selectionArgs,
            null,
            null,
            "$COLUMN_QUEUE_POSITION ASC",
            limit
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toLocalPrintJob())
            }
        }
    }

    private fun Cursor.toLocalPrintJob(): LocalPrintJob {
        val storedState = getString(getColumnIndexOrThrow(COLUMN_STATE))
        val parsedState = runCatching { LocalPrintJobState.valueOf(storedState) }.getOrNull()
        val optionsResult = runCatching {
            PrintOptionsJson.decode(getString(getColumnIndexOrThrow(COLUMN_PRINT_OPTIONS_JSON)))
        }
        val state = if (parsedState == null || optionsResult.isFailure) {
            LocalPrintJobState.NEEDS_RECOVERY
        } else {
            parsedState
        }
        val storedRecoveryDetail = nullableString(COLUMN_RECOVERY_DETAIL)
        return LocalPrintJob(
            id = getString(getColumnIndexOrThrow(COLUMN_ID)),
            documentUri = getString(getColumnIndexOrThrow(COLUMN_DOCUMENT_URI)),
            displayName = getString(getColumnIndexOrThrow(COLUMN_DISPLAY_NAME)),
            mimeType = getString(getColumnIndexOrThrow(COLUMN_MIME_TYPE)),
            printOptions = optionsResult.getOrDefault(PrintOptions()),
            state = state,
            totalPages = getInt(getColumnIndexOrThrow(COLUMN_TOTAL_PAGES)),
            currentPage = getInt(getColumnIndexOrThrow(COLUMN_CURRENT_PAGE)),
            completedPages = getInt(getColumnIndexOrThrow(COLUMN_COMPLETED_PAGES)),
            bytesConfirmed = getLong(getColumnIndexOrThrow(COLUMN_BYTES_CONFIRMED)),
            totalBytes = getLong(getColumnIndexOrThrow(COLUMN_TOTAL_BYTES)),
            progressPercent = getInt(getColumnIndexOrThrow(COLUMN_PROGRESS_PERCENT)),
            createdAt = getLong(getColumnIndexOrThrow(COLUMN_CREATED_AT)),
            updatedAt = getLong(getColumnIndexOrThrow(COLUMN_UPDATED_AT)),
            failureDetail = nullableString(COLUMN_FAILURE_DETAIL),
            recoveryDetail = if (state == LocalPrintJobState.NEEDS_RECOVERY) {
                storedRecoveryDetail ?: when {
                    optionsResult.isFailure ->
                        "Stored print options are invalid; replay is disabled."
                    parsedState == null ->
                        "Stored queue state '$storedState' is not recognized; replay is disabled."
                    else -> DEFAULT_RECOVERY_DETAIL
                }
            } else {
                storedRecoveryDetail
            }
        )
    }

    private fun Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun updateJob(database: SQLiteDatabase, job: LocalPrintJob) {
        val changed = database.update(
            TABLE_JOBS,
            job.toContentValues(),
            "$COLUMN_ID = ?",
            arrayOf(job.id)
        )
        check(changed == 1) { "Local print job ${job.id} disappeared during update" }
    }

    private fun LocalPrintJob.toContentValues(): ContentValues = ContentValues().apply {
        put(COLUMN_ID, id)
        put(COLUMN_DOCUMENT_URI, documentUri)
        put(COLUMN_DISPLAY_NAME, displayName)
        put(COLUMN_MIME_TYPE, mimeType)
        put(COLUMN_PRINT_OPTIONS_JSON, PrintOptionsJson.encode(printOptions))
        put(COLUMN_STATE, state.name)
        put(COLUMN_TOTAL_PAGES, totalPages)
        put(COLUMN_CURRENT_PAGE, currentPage)
        put(COLUMN_COMPLETED_PAGES, completedPages)
        put(COLUMN_BYTES_CONFIRMED, bytesConfirmed)
        put(COLUMN_TOTAL_BYTES, totalBytes)
        put(COLUMN_PROGRESS_PERCENT, progressPercent)
        put(COLUMN_CREATED_AT, createdAt)
        put(COLUMN_UPDATED_AT, updatedAt)
        if (failureDetail == null) putNull(COLUMN_FAILURE_DETAIL) else put(COLUMN_FAILURE_DETAIL, failureDetail)
        if (recoveryDetail == null) putNull(COLUMN_RECOVERY_DETAIL) else put(COLUMN_RECOVERY_DETAIL, recoveryDetail)
    }

    private fun nextTimestamp(job: LocalPrintJob): Long {
        val nextValue = if (job.updatedAt == Long.MAX_VALUE) Long.MAX_VALUE else job.updatedAt + 1L
        return maxOf(System.currentTimeMillis(), nextValue)
    }

    private inline fun <T> inTransaction(block: (SQLiteDatabase) -> T): T {
        val database = writableDatabase
        database.beginTransaction()
        return try {
            val result = block(database)
            database.setTransactionSuccessful()
            result
        } finally {
            database.endTransaction()
        }
    }

    private fun createSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE $TABLE_JOBS (
                $COLUMN_QUEUE_POSITION INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_ID TEXT NOT NULL UNIQUE,
                $COLUMN_DOCUMENT_URI TEXT NOT NULL,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_MIME_TYPE TEXT NOT NULL,
                $COLUMN_PRINT_OPTIONS_JSON TEXT NOT NULL,
                $COLUMN_STATE TEXT NOT NULL,
                $COLUMN_TOTAL_PAGES INTEGER NOT NULL DEFAULT 0 CHECK ($COLUMN_TOTAL_PAGES >= 0),
                $COLUMN_CURRENT_PAGE INTEGER NOT NULL DEFAULT 0 CHECK ($COLUMN_CURRENT_PAGE >= 0),
                $COLUMN_COMPLETED_PAGES INTEGER NOT NULL DEFAULT 0 CHECK ($COLUMN_COMPLETED_PAGES >= 0),
                $COLUMN_BYTES_CONFIRMED INTEGER NOT NULL DEFAULT 0 CHECK ($COLUMN_BYTES_CONFIRMED >= 0),
                $COLUMN_TOTAL_BYTES INTEGER NOT NULL DEFAULT 0 CHECK ($COLUMN_TOTAL_BYTES >= 0),
                $COLUMN_PROGRESS_PERCENT INTEGER NOT NULL DEFAULT 0
                    CHECK ($COLUMN_PROGRESS_PERCENT BETWEEN 0 AND 100),
                $COLUMN_CREATED_AT INTEGER NOT NULL,
                $COLUMN_UPDATED_AT INTEGER NOT NULL,
                $COLUMN_FAILURE_DETAIL TEXT,
                $COLUMN_RECOVERY_DETAIL TEXT
            )
            """.trimIndent()
        )
        database.execSQL(
            "CREATE INDEX $INDEX_STATE_POSITION ON $TABLE_JOBS " +
                "($COLUMN_STATE, $COLUMN_QUEUE_POSITION)"
        )
    }

    private data class Mutation<T>(val value: T, val changed: Boolean)

    companion object {
        private const val DATABASE_NAME = "pocketprint_local_print_queue.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_JOBS = "local_print_jobs"
        private const val INDEX_STATE_POSITION = "index_local_print_jobs_state_position"
        private const val COLUMN_QUEUE_POSITION = "queue_position"
        private const val COLUMN_ID = "id"
        private const val COLUMN_DOCUMENT_URI = "document_uri"
        private const val COLUMN_DISPLAY_NAME = "display_name"
        private const val COLUMN_MIME_TYPE = "mime_type"
        private const val COLUMN_PRINT_OPTIONS_JSON = "print_options_json"
        private const val COLUMN_STATE = "state"
        private const val COLUMN_TOTAL_PAGES = "total_pages"
        private const val COLUMN_CURRENT_PAGE = "current_page"
        private const val COLUMN_COMPLETED_PAGES = "completed_pages"
        private const val COLUMN_BYTES_CONFIRMED = "bytes_confirmed"
        private const val COLUMN_TOTAL_BYTES = "total_bytes"
        private const val COLUMN_PROGRESS_PERCENT = "progress_percent"
        private const val COLUMN_CREATED_AT = "created_at_ms"
        private const val COLUMN_UPDATED_AT = "updated_at_ms"
        private const val COLUMN_FAILURE_DETAIL = "failure_detail"
        private const val COLUMN_RECOVERY_DETAIL = "recovery_detail"

        private val JOB_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_DOCUMENT_URI,
            COLUMN_DISPLAY_NAME,
            COLUMN_MIME_TYPE,
            COLUMN_PRINT_OPTIONS_JSON,
            COLUMN_STATE,
            COLUMN_TOTAL_PAGES,
            COLUMN_CURRENT_PAGE,
            COLUMN_COMPLETED_PAGES,
            COLUMN_BYTES_CONFIRMED,
            COLUMN_TOTAL_BYTES,
            COLUMN_PROGRESS_PERCENT,
            COLUMN_CREATED_AT,
            COLUMN_UPDATED_AT,
            COLUMN_FAILURE_DETAIL,
            COLUMN_RECOVERY_DETAIL
        )

        private const val DEFAULT_RECOVERY_DETAIL =
            "The print outcome is uncertain. Review the printer before submitting a new job."
        private const val CANCELLATION_RECOVERY_DETAIL =
            "Cancellation occurred after work entered an in-flight state; replay is disabled."
    }
}
