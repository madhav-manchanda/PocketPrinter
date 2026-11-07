package com.pocketprint.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.agent.model.RemotePrintJob
import com.pocketprint.data.agent.net.AgentApiClient
import com.pocketprint.data.agent.storage.RemoteJobQueueStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.usb.UsbDescriptorParser
import com.pocketprint.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Core printer-side agent background service.
 * Maintains outbound connection with the VPS, polls/receives print jobs,
 * performs preflight checks, manages lease renewal and cancellation lifecycles,
 * and streams documents directly to the USB printer via the verified native engine.
 */
class PocketPrintAgentService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var agentLoopJob: Job? = null
    private val isProcessingJob = AtomicBoolean(false)

    private lateinit var queueStore: RemoteJobQueueStore
    private val apiClient = AgentApiClient()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    DiagnosticLogger.log("AGENT_SVC", "USB Device attached: ${device?.productName ?: "Unknown"}")
                    updateNotification("Printer connected: ${device?.productName ?: "Ready"}")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    DiagnosticLogger.log("AGENT_SVC", "USB Device detached")
                    updateNotification("Printer disconnected. Waiting for USB OTG connection...")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        queueStore = RemoteJobQueueStore(this)
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }

        // Reconcile any interrupted jobs from previous process lifetime
        reconcileStartupInterruptedJobs()

        DiagnosticLogger.log("AGENT_SVC", "PocketPrintAgentService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotif = buildNotification("Agent active · Monitoring VPS & USB Printer")
        startForeground(NOTIFICATION_ID, initialNotif)

        startAgentLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignored
        }
        DiagnosticLogger.log("AGENT_SVC", "PocketPrintAgentService stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reconcileStartupInterruptedJobs() {
        val recovered = queueStore.recoverInterruptedJobs()
        if (recovered.isNotEmpty()) {
            DiagnosticLogger.log("AGENT_SVC", "Recovered ${recovered.size} interrupted job(s) from previous session")
        }
        // Clean up any lingering cache files that are no longer part of active jobs
        try {
            val activeJobIds = queueStore.getAllJobs().filter {
                it.status != PrintJobState.COMPLETED_SUCCESS &&
                it.status != PrintJobState.FAILED &&
                it.status != PrintJobState.CANCELLED
            }.map { it.jobId }.toSet()

            val cacheDir = File(cacheDir, "remote_jobs")
            if (cacheDir.exists()) {
                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && !file.name.endsWith(".lock")) {
                        val fileJobId = file.name.substringBefore('.')
                        if (!activeJobIds.contains(fileJobId)) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            DiagnosticLogger.log("AGENT_SVC", "Cache cleanup error: ${e.message}")
        }
    }

    private fun startAgentLoop() {
        if (agentLoopJob?.isActive == true) {
            DiagnosticLogger.log("AGENT_SVC", "Agent loop already running; ignoring redundant trigger")
            return
        }
        agentLoopJob = serviceScope.launch {
            var consecutiveErrors = 0

            while (isActive) {
                try {
                    val authManager = (application as PocketPrintApp).authManager
                    val serverUrl = authManager.serverUrl

                    if (serverUrl.isBlank() || (authManager.deviceToken == null && !authManager.isPasswordConfigured)) {
                        updateNotification("Waiting for VPS and password setup...")
                        delay(5000)
                        continue
                    }

                    // 1. Initial pairing if token is absent
                    var token = authManager.deviceToken
                    if (token == null) {
                        updateNotification("Registering node with VPS...")
                        val connectedPrinter = (application as PocketPrintApp).usbDeviceManager.deviceListFlow.value.firstOrNull()
                        val printerModel = connectedPrinter?.productName ?: "HP Ink Tank 310 series"
                        val vidPid = connectedPrinter?.let { "%04X:%04X".format(it.vendorId, it.productId) } ?: "03F0:1053"

                        val pairResult = apiClient.pairDevice(
                            serverUrl = serverUrl,
                            deviceId = authManager.deviceId,
                            deviceName = authManager.deviceName,
                            devicePassword = authManager.getDevicePassword().orEmpty(),
                            printerModel = printerModel,
                            vidPid = vidPid
                        )

                        if (pairResult.isSuccess) {
                            token = pairResult.getOrNull()
                            authManager.deviceToken = token
                            DiagnosticLogger.log("AGENT_SVC", "Pairing completed successfully; device credential saved")
                        } else {
                            val err = pairResult.exceptionOrNull()?.message ?: "Pairing failed"
                            updateNotification("Pairing error: $err")
                            delay(10000)
                            continue
                        }
                    }

                    // 2. Heartbeat check
                    val app = application as PocketPrintApp
                    val anyPrinter = app.usbDeviceManager.findAnyPrinterDevice()
                    val hasPerm = anyPrinter != null && app.usbDeviceManager.hasPermission(anyPrinter)
                    val isConnected = anyPrinter != null && hasPerm
                    val verifiedProfile = anyPrinter?.let { app.profileStore.getVerifiedProfile(it.vendorId, it.productId) }
                    val profileName = verifiedProfile?.profileName ?: "HPLIP Pcl3Gui2 Default"

                    val hbResult = apiClient.sendHeartbeat(
                        serverUrl = serverUrl,
                        deviceId = authManager.deviceId,
                        deviceToken = token!!,
                        isPrinterConnected = isConnected,
                        printerStatus = when {
                            anyPrinter == null -> "WAITING_USB"
                            !hasPerm -> "WAITING_PERMISSION"
                            else -> "ONLINE"
                        },
                        activeProfile = profileName
                    )

                    val pendingCount = if (hbResult.isSuccess) {
                        consecutiveErrors = 0
                        val statusText = when {
                            anyPrinter == null -> "Online · Plug in USB printer"
                            !hasPerm -> "Online · USB permission required"
                            else -> "Online · Ready: ${anyPrinter.productName ?: "HP Printer"}"
                        }
                        updateNotification(statusText)
                        hbResult.getOrDefault(0)
                    } else {
                        val error = hbResult.exceptionOrNull()
                        val errMsg = error?.message.orEmpty()
                        if (errMsg.contains("401") || errMsg.contains("403")) {
                            DiagnosticLogger.log("AGENT_SVC", "Token rejected by VPS ($errMsg). Clearing cached token to auto-repair with remembered password.")
                            authManager.deviceToken = null
                            delay(1000)
                            continue
                        }
                        consecutiveErrors++
                        val backoffSec = (consecutiveErrors * 5).coerceAtMost(60)
                        updateNotification("Connection issue. Retrying in ${backoffSec}s...")
                        delay(backoffSec * 1000L)
                        continue
                    }

                    // 3. Process jobs if pending or idle
                    if (!isProcessingJob.get() && (pendingCount > 0 || isConnected)) {
                        val nextJobResult = apiClient.fetchNextJob(serverUrl, authManager.deviceId, token)
                        if (nextJobResult.isFailure) {
                            val jobErr = nextJobResult.exceptionOrNull()?.message.orEmpty()
                            if (jobErr.contains("401") || jobErr.contains("403")) {
                                DiagnosticLogger.log("AGENT_SVC", "fetchNextJob rejected by VPS ($jobErr). Clearing cached token to auto-repair.")
                                authManager.deviceToken = null
                                delay(1000)
                                continue
                            }
                        }
                        val job = nextJobResult.getOrNull()
                        if (job != null) {
                            if (isProcessingJob.compareAndSet(false, true)) {
                                try {
                                    processRemoteJob(job, serverUrl, token)
                                } finally {
                                    isProcessingJob.set(false)
                                }
                            }
                        }
                    }

                    delay(6000) // Polling interval
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticLogger.log("AGENT_SVC", "Agent loop error: ${e.message}")
                    delay(5000)
                }
            }
        }
    }

    private suspend fun processRemoteJob(job: RemotePrintJob, serverUrl: String, token: String) {
        val app = application as PocketPrintApp

        // ── Phase 0: Preflight checks BEFORE starting transmission ──────────
        val anyPrinter = app.usbDeviceManager.findAnyPrinterDevice()
        if (anyPrinter == null) {
            DiagnosticLogger.log("AGENT_SVC", "Preflight failure for job ${job.jobId}: printer not connected")
            queueStore.updateJobStatus(job.jobId, PrintJobState.FAILED, message = "Printer not connected: preflight check failed")
            apiClient.failJob(
                serverUrl = serverUrl,
                jobId = job.jobId,
                deviceToken = token,
                leaseToken = job.leaseToken,
                errorMessage = "Printer not connected: preflight check failed",
                uncertain = false,
                idempotencyKey = "preflight-missing-printer-${job.jobId}"
            )
            updateNotification("Print rejected: USB printer not connected")
            return
        }

        if (!app.usbDeviceManager.hasPermission(anyPrinter)) {
            DiagnosticLogger.log("AGENT_SVC", "Preflight failure for job ${job.jobId}: USB permission missing")
            queueStore.updateJobStatus(job.jobId, PrintJobState.FAILED, message = "USB permission not granted")
            apiClient.failJob(
                serverUrl = serverUrl,
                jobId = job.jobId,
                deviceToken = token,
                leaseToken = job.leaseToken,
                errorMessage = "USB permission not granted for printer",
                uncertain = false,
                idempotencyKey = "preflight-no-perm-${job.jobId}"
            )
            updateNotification("Print rejected: USB permission required")
            return
        }

        val rawDevice = app.usbDeviceManager.findRawUsbDevice() ?: anyPrinter

        DiagnosticLogger.log("AGENT_SVC", "Starting remote job: ${job.jobId} (${job.filename})")
        queueStore.saveJob(job)

        // Cancellation and lease state flags
        val isCancelRequested = AtomicBoolean(false)
        val isLeaseLost = AtomicBoolean(false)

        // ── Phase 1: Background Lease Renewal Coroutine ──────────────────────
        // Periodically renews lease every 15s. If lease is revoked/lost or user
        // requests cancel, signals cancellation to the print manager immediately.
        val leaseRenewalScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val leaseRenewalJob = leaseRenewalScope.launch {
            while (isActive) {
                delay(15_000)
                if (!isActive) break

                val renewal = apiClient.renewLease(serverUrl, job.jobId, token, job.leaseToken)
                if (renewal.isLeaseLost) {
                    DiagnosticLogger.log("AGENT_SVC", "Lease lost or revoked for job ${job.jobId}: ${renewal.errorMessage}")
                    isLeaseLost.set(true)
                    app.printJobManager.cancelJob()
                    break
                }
                if (renewal.cancelRequested) {
                    DiagnosticLogger.log("AGENT_SVC", "User cancellation detected via lease renewal for job ${job.jobId}")
                    isCancelRequested.set(true)
                    app.printJobManager.cancelJob()
                    break
                }
            }
        }

        val cacheDir = File(cacheDir, "remote_jobs").apply { mkdirs() }
        val ext = job.filename.substringAfterLast('.', "pdf")
        val localFile = File(cacheDir, "${job.jobId}.$ext")

        try {
            // ── Phase 2: Document Download & Integrity Validation ───────────
            updateNotification("Downloading: ${job.filename}...")
            apiClient.updateJobProgress(
                serverUrl = serverUrl,
                jobId = job.jobId,
                deviceToken = token,
                leaseToken = job.leaseToken,
                status = PrintJobState.DOWNLOADING,
                progressPercent = 10,
                message = "Downloading document",
                idempotencyKey = "progress-dl-${job.jobId}"
            )

            val downloadResult = apiClient.downloadJobDocument(
                serverUrl = serverUrl,
                jobId = job.jobId,
                deviceToken = token,
                leaseToken = job.leaseToken,
                expectedSha256 = job.sha256,
                destinationFile = localFile
            ) { downloaded, total ->
                if (total > 0) {
                    val pct = ((downloaded * 100) / total).toInt()
                    queueStore.updateJobStatus(job.jobId, PrintJobState.DOWNLOADING, progress = pct)
                }
            }

            if (downloadResult.isFailure) {
                val err = downloadResult.exceptionOrNull()?.message ?: "Download failed"
                DiagnosticLogger.log("AGENT_SVC", "Download failure for job ${job.jobId}: $err")
                leaseRenewalJob.cancel()
                queueStore.updateJobStatus(job.jobId, PrintJobState.FAILED, message = err)
                apiClient.failJob(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    errorMessage = err,
                    uncertain = false,
                    idempotencyKey = "fail-download-${job.jobId}"
                )
                return
            }

            job.localFilePath = localFile.absolutePath
            queueStore.saveJob(job)

            // ── Phase 3: Document Rendering Preflight ────────────────────────
            val fileUri = Uri.fromFile(localFile)
            val printOptions = job.toPrintOptions()
            val totalPages = try {
                app.pdfRendererService.getPageCount(fileUri, job.mimeType, printOptions)
            } catch (e: Exception) {
                -1
            }

            if (totalPages <= 0) {
                val err = "Document validation failed: unable to render pages or format unsupported"
                DiagnosticLogger.log("AGENT_SVC", "Rendering preflight failed for job ${job.jobId}: $err")
                leaseRenewalJob.cancel()
                queueStore.updateJobStatus(job.jobId, PrintJobState.FAILED, message = err)
                apiClient.failJob(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    errorMessage = err,
                    uncertain = false,
                    idempotencyKey = "fail-render-preflight-${job.jobId}"
                )
                return
            }

            // Check if cancellation arrived during download/validation
            if (isCancelRequested.get()) {
                leaseRenewalJob.cancel()
                DiagnosticLogger.log("AGENT_SVC", "Job ${job.jobId} cancelled before USB transmission")
                queueStore.updateJobStatus(job.jobId, PrintJobState.CANCELLED, message = "Cancelled before USB transmission")
                apiClient.acknowledgeCancel(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    uncertain = false,
                    message = "Cancelled before USB transmission",
                    idempotencyKey = "cancel-ack-pre-usb-${job.jobId}"
                )
                updateNotification("Cancelled: ${job.filename}")
                return
            }

            // ── Phase 4: USB Transmission & Page-level Tracking ──────────────
            updateNotification("Printing: ${job.filename}...")
            val progressResp = apiClient.updateJobProgress(
                serverUrl = serverUrl,
                jobId = job.jobId,
                deviceToken = token,
                leaseToken = job.leaseToken,
                status = PrintJobState.TRANSMITTING_USB,
                progressPercent = 25,
                message = "Sending to USB printer",
                idempotencyKey = "progress-transmitting-${job.jobId}"
            )
            if (progressResp.cancelRequested) {
                isCancelRequested.set(true)
                app.printJobManager.cancelJob()
            }
            if (progressResp.isLeaseLost) {
                isLeaseLost.set(true)
                app.printJobManager.cancelJob()
            }

            val deviceInfo = UsbDescriptorParser.parseDevice(rawDevice, true)

            // Collect status manager updates to forward progress & page events
            val progressMonitorJob = serviceScope.launch {
                var lastItem = -1
                var lastCompleted = -1
                var lastPct = 25

                app.printJobManager.statusManager.progressFlow.collect { progress ->
                    // 1. Page scheduled event
                    if (progress.scheduledItem > 0 && progress.scheduledItem != lastItem) {
                        lastItem = progress.scheduledItem
                        val pageEventId = "page-start-${job.jobId}-${job.attempt}-${progress.scheduledItem}"
                        apiClient.reportPageEvent(
                            serverUrl = serverUrl,
                            jobId = job.jobId,
                            deviceToken = token,
                            leaseToken = job.leaseToken,
                            eventId = pageEventId,
                            eventType = "PAGE_PRINTING",
                            documentPageNumber = progress.documentPage,
                            scheduledPageNumber = progress.scheduledItem,
                            copyNumber = progress.copyNumber,
                            pageState = "PRINTING",
                            message = "Rendering and streaming page ${progress.scheduledItem}"
                        )
                    }

                    // 2. Page completed event
                    if (progress.completedItems > 0 && progress.completedItems != lastCompleted) {
                        lastCompleted = progress.completedItems
                        val pageEventId = "page-done-${job.jobId}-${job.attempt}-${progress.completedItems}"
                        apiClient.reportPageEvent(
                            serverUrl = serverUrl,
                            jobId = job.jobId,
                            deviceToken = token,
                            leaseToken = job.leaseToken,
                            eventId = pageEventId,
                            eventType = "PAGE_TRANSMISSION_COMPLETED",
                            documentPageNumber = progress.documentPage,
                            scheduledPageNumber = progress.completedItems,
                            copyNumber = progress.copyNumber,
                            pageState = "TRANSMISSION_COMPLETED",
                            message = "Page ${progress.completedItems} delivered to USB transport"
                        )
                    }

                    // 3. Overall progress milestones
                    val currentPct = progress.overallProgressPercent.coerceIn(0, 99)
                    if (currentPct >= lastPct + 10) {
                        lastPct = currentPct
                        val report = apiClient.updateJobProgress(
                            serverUrl = serverUrl,
                            jobId = job.jobId,
                            deviceToken = token,
                            leaseToken = job.leaseToken,
                            status = PrintJobState.TRANSMITTING_USB,
                            progressPercent = currentPct,
                            message = "Printing page ${progress.scheduledItem} of ${progress.scheduledItemCount}",
                            idempotencyKey = "prog-${job.jobId}-$currentPct"
                        )
                        if (report.cancelRequested) {
                            isCancelRequested.set(true)
                            app.printJobManager.cancelJob()
                        }
                        if (report.isLeaseLost) {
                            isLeaseLost.set(true)
                            app.printJobManager.cancelJob()
                        }
                    }
                }
            }

            val printSuccess = try {
                app.printJobManager.printPdfDocument(
                    rawDevice = rawDevice,
                    deviceInfo = deviceInfo,
                    documentUri = fileUri,
                    options = printOptions,
                    mimeType = job.mimeType
                )
            } finally {
                progressMonitorJob.cancel()
                try {
                    progressMonitorJob.join()
                } catch (_: Exception) {}
                leaseRenewalJob.cancel()
            }

            // ── Phase 5: Outcome Resolution ──────────────────────────────────
            if (isLeaseLost.get()) {
                // Lease was lost/expired on VPS. Do NOT attempt to complete or modify job.
                DiagnosticLogger.log("AGENT_SVC", "Job ${job.jobId} aborted: lease was lost during printing")
                queueStore.updateJobStatus(job.jobId, PrintJobState.NEEDS_RECOVERY, message = "Lease lost on VPS during printing")
                updateNotification("Print paused: lease expired")
                return
            }

            if (isCancelRequested.get()) {
                val progress = app.printJobManager.statusManager.progressFlow.value
                val bytesSent = progress.bytesTransferred
                val uncertain = bytesSent > 0 || progress.completedItems > 0 || progress.recoveryRequired

                DiagnosticLogger.log("AGENT_SVC", "Job ${job.jobId} cancelled. Bytes sent=$bytesSent, uncertain=$uncertain")
                apiClient.acknowledgeCancel(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    uncertain = uncertain,
                    message = if (uncertain) "Job cancelled mid-transmission after $bytesSent bytes" else "Job cancelled before USB bytes",
                    idempotencyKey = "cancel-ack-final-${job.jobId}"
                )
                val finalState = if (uncertain) PrintJobState.NEEDS_RECOVERY else PrintJobState.CANCELLED
                queueStore.updateJobStatus(job.jobId, finalState, message = "Cancelled by user")
                updateNotification("Cancelled: ${job.filename}")
                return
            }

            if (printSuccess) {
                DiagnosticLogger.log("AGENT_SVC", "Job ${job.jobId} USB transmission completed successfully!")
                queueStore.updateJobStatus(job.jobId, PrintJobState.COMPLETED_SUCCESS, progress = 100)
                apiClient.completeJob(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    bytesTransferred = localFile.length(),
                    idempotencyKey = "comp-final-${job.jobId}"
                )
                updateNotification("Printed: ${job.filename}")
            } else {
                val progress = app.printJobManager.statusManager.progressFlow.value
                val needsRecovery = progress.state == PrintJobState.NEEDS_RECOVERY ||
                    progress.recoveryRequired ||
                    progress.bytesTransferred > 0 ||
                    progress.completedItems > 0

                val err = progress.message.ifBlank {
                    if (needsRecovery) {
                        "USB transfer outcome uncertain; automatic replay is disabled. Resubmit the document."
                    } else {
                        "USB transmission or printer formatting error"
                    }
                }
                val finalState = if (needsRecovery) PrintJobState.NEEDS_RECOVERY else PrintJobState.FAILED

                DiagnosticLogger.log("AGENT_SVC", "Job ${job.jobId} ended as ${finalState.name} (uncertain=$needsRecovery): $err")
                queueStore.updateJobStatus(job.jobId, finalState, message = err)

                apiClient.failJob(
                    serverUrl = serverUrl,
                    jobId = job.jobId,
                    deviceToken = token,
                    leaseToken = job.leaseToken,
                    errorMessage = err,
                    uncertain = needsRecovery,
                    idempotencyKey = "fail-final-${job.jobId}"
                )
                updateNotification("Print failed for: ${job.filename}")
            }

        } finally {
            leaseRenewalJob.cancel()
            try {
                if (localFile.exists()) {
                    localFile.delete()
                }
            } catch (e: Exception) {
                // Ignored
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PocketPrint Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection with the VPS for remote printing"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketPrint Remote Agent")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    companion object {
        const val CHANNEL_ID = "pocketprint_agent_channel"
        const val NOTIFICATION_ID = 4040

        fun start(context: Context) {
            val intent = Intent(context, PocketPrintAgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PocketPrintAgentService::class.java)
            context.stopService(intent)
        }
    }
}
