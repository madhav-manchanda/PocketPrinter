package com.pocketprint.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.queue.LocalPrintJob
import com.pocketprint.data.queue.LocalPrintJobState
import com.pocketprint.data.usb.UsbDescriptorParser
import com.pocketprint.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Owns local queued printing independently of every Activity lifecycle. */
class LocalPrintForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var app: PocketPrintApp
    private lateinit var wakeLock: PowerManager.WakeLock
    private var queueLoop: Job? = null

    @Volatile
    private var activeJobId: String? = null

    override fun onCreate() {
        super.onCreate()
        app = application as PocketPrintApp
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PocketPrint:ActivePrint"
        ).apply { setReferenceCounted(false) }
        DiagnosticLogger.log("PRINT_SERVICE", "Local foreground print service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelJob(intent.getStringExtra(EXTRA_JOB_ID))
        }
        startInForeground("Checking local print queue")
        if (queueLoop?.isActive != true) {
            queueLoop = serviceScope.launch { processQueue() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeJobId != null) app.printJobManager.cancelJob()
        serviceScope.cancel()
        releaseWakeLock()
        DiagnosticLogger.log("PRINT_SERVICE", "Local foreground print service destroyed")
        super.onDestroy()
    }

    private suspend fun processQueue() {
        while (true) {
            val job = app.localPrintQueue.nextRunnable()
            if (job == null) {
                releaseWakeLock()
                val printerState = if (app.usbDeviceManager.findRawUsbDevice() != null) {
                    "printer ready"
                } else {
                    "waiting for USB printer"
                }
                updateNotification("Idle - monitoring queue, $printerState")
                delay(IDLE_POLL_MS)
                continue
            }

            app.usbDeviceManager.refreshDevices()
            val rawDevice = app.usbDeviceManager.findRawUsbDevice()
            if (rawDevice == null) {
                if (job.state == LocalPrintJobState.QUEUED) {
                    app.localPrintQueue.updateState(
                        job.id,
                        LocalPrintJobState.WAITING_FOR_PRINTER,
                        "Connect and authorize the USB printer"
                    )
                }
                updateNotification("${job.displayName}: waiting for USB printer")
                delay(PRINTER_POLL_MS)
                continue
            }

            processJob(job, rawDevice)
        }
    }

    private suspend fun processJob(job: LocalPrintJob, rawDevice: android.hardware.usb.UsbDevice) {
        activeJobId = job.id
        app.localPrintQueue.updateState(job.id, LocalPrintJobState.PREPARING)
        acquireWakeLock()
        updateNotification("Preparing ${job.displayName}", job.id)

        val progressObserver = serviceScope.launch {
            app.printJobManager.statusManager.progressFlow.collectLatest { progress ->
                if (activeJobId != job.id) return@collectLatest
                app.localPrintQueue.updateProgress(
                    id = job.id,
                    totalPages = progress.scheduledItemCount.takeIf { it > 0 },
                    currentPage = progress.scheduledItem.takeIf { it > 0 },
                    completedPages = progress.completedItems,
                    bytesConfirmed = progress.bytesTransferred,
                    totalBytes = progress.totalBytes,
                    progressPercent = progress.overallProgressPercent
                )
                val pageText = if (progress.documentPageCount > 0) {
                    " page ${progress.documentPage}/${progress.documentPageCount}"
                } else ""
                updateNotification(
                    "${job.displayName}: ${progress.overallProgressPercent}%$pageText",
                    job.id
                )
            }
        }

        try {
            app.localPrintQueue.updateState(job.id, LocalPrintJobState.PRINTING)
            val success = app.printJobManager.printPdfDocument(
                rawDevice = rawDevice,
                deviceInfo = UsbDescriptorParser.parseDevice(rawDevice, true),
                documentUri = Uri.parse(job.documentUri),
                options = job.printOptions,
                mimeType = job.mimeType,
                documentName = job.displayName
            )
            val progress = app.printJobManager.statusManager.progressFlow.value
            if (success) {
                app.localPrintQueue.updateState(job.id, LocalPrintJobState.TRANSMISSION_COMPLETED)
                app.localPrintQueue.updateState(job.id, LocalPrintJobState.COMPLETED)
            } else {
                finishFailedJob(job.id, progress.state, progress.message)
            }
        } catch (cancelled: CancellationException) {
            val progress = app.printJobManager.statusManager.progressFlow.value
            finishFailedJob(job.id, progress.state, progress.message)
            throw cancelled
        } catch (error: Exception) {
            DiagnosticLogger.usbError("Queued job ${job.id} failed: ${error.message}")
            val current = app.localPrintQueue.get(job.id)
            if (current?.state?.isTerminal == false) {
                app.localPrintQueue.updateState(
                    job.id,
                    LocalPrintJobState.FAILED,
                    error.message ?: "Unexpected print service error"
                )
            }
        } finally {
            progressObserver.cancel()
            activeJobId = null
            releaseWakeLock()
        }
    }

    private fun finishFailedJob(jobId: String, state: PrintJobState, detail: String) {
        val current = app.localPrintQueue.get(jobId) ?: return
        if (current.state.isTerminal) return
        if (state == PrintJobState.NEEDS_RECOVERY || current.bytesConfirmed > 0 || current.completedPages > 0) {
            app.localPrintQueue.updateState(
                jobId,
                LocalPrintJobState.NEEDS_RECOVERY,
                detail.ifBlank { "Partial or uncertain printer data; do not replay automatically" }
            )
        } else if (state == PrintJobState.CANCELLED) {
            app.localPrintQueue.cancel(jobId, detail)
        } else {
            app.localPrintQueue.updateState(
                jobId,
                LocalPrintJobState.FAILED,
                detail.ifBlank { "Document transmission failed before completion" }
            )
        }
    }

    private fun cancelJob(requestedJobId: String?) {
        val id = requestedJobId ?: activeJobId ?: return
        if (id == activeJobId) app.printJobManager.cancelJob()
        app.localPrintQueue.cancel(id, "Cancelled by operator")
    }

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        DiagnosticLogger.log("PRINT_SERVICE", "Wake lock acquired=${wakeLock.isHeld}")
    }

    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
            DiagnosticLogger.log("PRINT_SERVICE", "Wake lock released")
        }
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text, activeJobId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String, jobId: String? = activeJobId) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text, jobId))
    }

    private fun buildNotification(text: String, jobId: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("PocketPrint local queue")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (jobId != null) {
            val cancelIntent = Intent(this, LocalPrintForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            val cancelPending = PendingIntent.getService(
                this,
                jobId.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(0, "Cancel", cancelPending)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Local print queue",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Active local USB print jobs and page progress"
                        setShowBadge(false)
                    }
                )
        }
    }

    companion object {
        private const val CHANNEL_ID = "pocketprint_local_print_queue"
        private const val NOTIFICATION_ID = 4050
        private const val ACTION_PROCESS = "com.pocketprint.action.PROCESS_LOCAL_QUEUE"
        private const val ACTION_CANCEL = "com.pocketprint.action.CANCEL_LOCAL_JOB"
        private const val EXTRA_JOB_ID = "job_id"
        private const val PRINTER_POLL_MS = 3_000L
        private const val IDLE_POLL_MS = 10_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, LocalPrintForegroundService::class.java).apply {
                action = ACTION_PROCESS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context, jobId: String) {
            val intent = Intent(context, LocalPrintForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
