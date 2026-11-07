package com.pocketprint.ui.main

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import android.provider.Settings
import com.pocketprint.R
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.agent.DeviceAuthState
import com.pocketprint.data.agent.net.AgentApiClient
import com.pocketprint.service.PocketPrintAgentService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.EditText
import android.widget.LinearLayout
import android.text.InputType
import com.pocketprint.data.device.HostCompatibilityStatus
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.quality.PrintQualityProfile
import com.pocketprint.data.print.transport.PrinterConnectionState
import com.pocketprint.databinding.ActivityMainBinding
import com.pocketprint.ui.queue.PrintCenterActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var logAdapter: LogAdapter

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        DiagnosticLogger.log("UI", "Notification permission granted=$granted")
    }

    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some document providers grant only session-scoped access.
            }
            val displayName = queryFileName(uri) ?: "Selected document"
            val mimeType = contentResolver.getType(uri)
            if (requiresSourceAppRendering(displayName, mimeType)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Print from the source app")
                    .setMessage(
                        "PocketPrint will not rebuild Office or RTF files as plain text because that loses tables, images, fonts, and pagination. " +
                            "Open this file in its Office/RTF viewer, choose Print, and select PocketPrint. The source app will provide a layout-faithful PDF."
                    )
                    .setPositiveButton("OK", null)
                    .show()
                return@registerForActivityResult
            }
            viewModel.selectDocument(uri, displayName, mimeType)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupListeners()
        observeViewModel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        DiagnosticLogger.log("UI", "MainActivity started | Native PocketPrint PrintService active")
    }

    override fun onStart() {
        super.onStart()
        // USB monitoring is now always-on at the application level;
        // no need to start/stop it with Activity lifecycle.
        updateAgentCardUi()
    }

    override fun onResume() {
        super.onResume()
        updateAgentCardUi()
    }

    // removed: onStop() previously called viewModel.stopUsbMonitoring()
    // USB monitoring stays registered for the app process lifetime,
    // so USB attach/detach events are received regardless of Activity visibility.

    private fun setupRecyclerViews() {
        deviceAdapter = DeviceAdapter(
            onRequestPermission = { device ->
                viewModel.requestPermissionForDevice(device)
            },
            onProbeDevice = { device ->
                viewModel.probeDevice(device)
            }
        )

        binding.rvDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
            isNestedScrollingEnabled = false
        }

        logAdapter = LogAdapter()
        val logLayoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvLogs.apply {
            layoutManager = logLayoutManager
            adapter = logAdapter
        }
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            viewModel.scanDevices()
        }

        binding.btnRequestPermission.setOnClickListener {
            viewModel.requestPermissionForPrimary()
        }

        binding.btnProbe.setOnClickListener {
            viewModel.probePrimaryDevice()
        }

        binding.btnRefresh.setOnClickListener {
            viewModel.scanDevices()
        }

        binding.btnExportReport.setOnClickListener {
            exportCompatibilityReport()
        }

        binding.btnPrintTestPage.setOnClickListener {
            viewModel.printTestPage()
        }

        binding.btnPrintColorTestPage.setOnClickListener {
            viewModel.printColorTestPage()
        }

        binding.btnPrintGrayscaleTestPage.setOnClickListener {
            viewModel.printGrayscaleTestPage()
        }

        binding.btnPrintQualityBenchmark.setOnClickListener {
            showQualityBenchmarkDialog()
        }

        binding.btnExperimentalDpi.setOnClickListener {
            showExperimentalDpiDialog()
        }

        binding.btnSystemPrintService.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_PRINT_SETTINGS)
                startActivity(intent)
                DiagnosticLogger.log("UI", "Opened Android System Print Settings")
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Print Settings: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSelectDocument.setOnClickListener {
            documentPickerLauncher.launch(
                arrayOf(
                    "application/pdf",
                    "image/*",
                    "text/plain"
                )
            )
        }

        binding.btnPrintDocument.setOnClickListener {
            val documentInfo = viewModel.getSelectedDocumentInfo()
            if (documentInfo?.uri == null) {
                Toast.makeText(this, "Select a document first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PrintSettingsDialog.show(
                context = this,
                documentName = documentInfo.name ?: "Selected document",
                mimeType = documentInfo.mimeType,
                initial = viewModel.getLastPrintOptions(),
                onPreview = { options ->
                    PrintCenterActivity.start(
                        context = this,
                        uri = documentInfo.uri,
                        name = documentInfo.name,
                        mimeType = documentInfo.mimeType,
                        options = options
                    )
                },
                onPrint = viewModel::printSelectedDocument
            )
        }

        binding.btnCancelPrint.setOnClickListener {
            viewModel.cancelPrintJob()
        }

        binding.btnOpenPrintCenter.setOnClickListener {
            val document = viewModel.getSelectedDocumentInfo()
            PrintCenterActivity.start(
                context = this,
                uri = document?.uri,
                name = document?.name,
                mimeType = document?.mimeType,
                options = viewModel.getLastPrintOptions()
            )
        }

        // Phase 3 Discovery Handlers
        binding.btnAutoRunMatrix.setOnClickListener {
            viewModel.startAutoDiscovery()
            Toast.makeText(this, "Starting automated test sequence...", Toast.LENGTH_SHORT).show()
        }

        binding.btnRunDiscovery.setOnClickListener {
            val discovery = viewModel.uiState.value.discoveryState
            val candidate = discovery.activeCandidate ?: viewModel.experimentManager.getCandidateList().firstOrNull()
            if (candidate != null) {
                viewModel.runDiscoveryCandidate(candidate)
            }
        }

        binding.btnVerifySuccess.setOnClickListener {
            viewModel.recordPhysicalVerification(com.pocketprint.data.discovery.model.PhysicalOutcome.SUCCESS_PAGE_PRINTED, "Operator confirmed output printed")
            Toast.makeText(this, "Physical print confirmed! Profile locked and saved.", Toast.LENGTH_LONG).show()
        }

        binding.btnVerifyFailed.setOnClickListener {
            // Advances to next candidate automatically!
            viewModel.advanceToNextCandidate(com.pocketprint.data.discovery.model.PhysicalOutcome.NOTHING_HAPPENED)
            Toast.makeText(this, "Test failed. Advancing to next candidate...", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyLogs.setOnClickListener {
            val text = viewModel.getLogsForExport()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PocketPrint Logs", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Diagnostic logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            viewModel.clearLogs()
        }

        binding.btnOpenDashboard.setOnClickListener {
            com.pocketprint.ui.discovery.DiscoveryDashboardActivity.start(this)
        }

        binding.btnConfigureAgent.setOnClickListener {
            showVpsConfigDialog()
        }

        binding.btnToggleAgentService.setOnClickListener {
            toggleAgentService()
        }
    }

    private fun showVpsConfigDialog() {
        val authManager = (application as PocketPrintApp).authManager
        val apiClient = AgentApiClient()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val etUrl = EditText(this).apply {
            hint = "VPS URL (e.g. https://print.yourvps.com)"
            setText(authManager.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layout.addView(etUrl)

        val etDeviceName = EditText(this).apply {
            hint = "Device Name (e.g. Office Printer)"
            setText(authManager.deviceName)
        }
        layout.addView(etDeviceName)

        val savedPassword = authManager.getDevicePassword().orEmpty()
        val etPassword = EditText(this).apply {
            hint = "Printer password (set password for this printer)"
            setText(savedPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etPassword)

        val etConfirmPassword = EditText(this).apply {
            hint = "Confirm printer password"
            setText(savedPassword)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etConfirmPassword)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Configure Remote VPS Agent")
            .setMessage(
                "Enter your PocketPrint VPS URL and configure a printer password. " +
                    "Users connect to this printer from the web console using this password."
            )
            .setView(layout)
            .setPositiveButton("Test & Connect", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = etUrl.text.toString().trim().trimEnd('/')
                val name = etDeviceName.text.toString().trim()
                val pwd = etPassword.text.toString().trim()
                val confirmPwd = etConfirmPassword.text.toString().trim()
                val serverChanged = authManager.serverUrl != url

                if (url.isBlank()) {
                    etUrl.error = "VPS URL is required"
                    return@setOnClickListener
                }
                if (pwd.isNotBlank() && pwd != confirmPwd) {
                    etConfirmPassword.error = "Passwords do not match"
                    return@setOnClickListener
                }
                if (pwd.isNotBlank() && pwd.length < 6) {
                    etPassword.error = "Password must be at least 6 characters"
                    return@setOnClickListener
                }
                if ((authManager.deviceToken == null || serverChanged) && pwd.isBlank()) {
                    etPassword.error = "A printer password is required"
                    return@setOnClickListener
                }

                val connectButton = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                connectButton.isEnabled = false
                connectButton.text = "Checking server…"
                lifecycleScope.launch {
                    val check = apiClient.testConnection(url)
                    if (check.isFailure) {
                        connectButton.isEnabled = true
                        connectButton.text = "Test & Connect"
                        Toast.makeText(
                            this@MainActivity,
                            check.exceptionOrNull()?.message ?: "PocketPrint server verification failed",
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    try {
                        val app = application as PocketPrintApp
                        val needsPairing = pwd.isNotBlank() || serverChanged || authManager.deviceToken == null
                        var pairedToken: String? = null

                        if (needsPairing) {
                            val printer = app.usbDeviceManager.deviceListFlow.value.firstOrNull()
                            val pairResult = apiClient.pairDevice(
                                serverUrl = url,
                                deviceId = authManager.deviceId,
                                deviceName = name.ifBlank { authManager.deviceName },
                                devicePassword = pwd,
                                printerModel = printer?.productName ?: "HP Ink Tank 310 series",
                                vidPid = printer?.let { "%04X:%04X".format(it.vendorId, it.productId) } ?: "03F0:1053"
                            )
                            if (pairResult.isFailure) {
                                connectButton.isEnabled = true
                                connectButton.text = "Test & Connect"
                                Toast.makeText(
                                    this@MainActivity,
                                    pairResult.exceptionOrNull()?.message ?: "Pairing was rejected by the VPS",
                                    Toast.LENGTH_LONG
                                ).show()
                                return@launch
                            }
                            pairedToken = pairResult.getOrNull()
                        }

                        // Persist only after the backend has accepted the pairing code.
                        if (serverChanged) authManager.clearAuthentication()
                        authManager.serverUrl = url
                        if (name.isNotBlank()) authManager.deviceName = name
                        if (pairedToken != null) authManager.deviceToken = pairedToken
                        if (pwd.isNotBlank()) authManager.setDevicePassword(pwd)
                        PocketPrintAgentService.start(this@MainActivity)
                        updateAgentCardUi()
                        Toast.makeText(
                            this@MainActivity,
                            if (needsPairing) "PocketPrint server verified and printer paired"
                            else (check.getOrNull() ?: "PocketPrint server verified"),
                            Toast.LENGTH_SHORT
                        ).show()
                        dialog.dismiss()
                    } catch (e: Exception) {
                        connectButton.isEnabled = true
                        connectButton.text = "Test & Connect"
                        Toast.makeText(this@MainActivity, e.message ?: "Could not save VPS settings", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private var isAgentRunning = false

    private fun toggleAgentService() {
        val authManager = (application as PocketPrintApp).authManager
        if (authManager.serverUrl.isBlank() || (authManager.deviceToken == null && !authManager.isPasswordConfigured)) {
            Toast.makeText(this, "Configure the VPS URL and printer password first", Toast.LENGTH_SHORT).show()
            showVpsConfigDialog()
            return
        }

        if (isAgentRunning) {
            PocketPrintAgentService.stop(this)
            isAgentRunning = false
            Toast.makeText(this, "Background Agent stopped", Toast.LENGTH_SHORT).show()
        } else {
            PocketPrintAgentService.start(this)
            isAgentRunning = true
            Toast.makeText(this, "Background Agent started", Toast.LENGTH_SHORT).show()
        }
        updateAgentCardUi()
    }

    private fun updateAgentCardUi() {
        val authManager = (application as PocketPrintApp).authManager
        val queueStore = (application as PocketPrintApp).queueStore

        val url = authManager.serverUrl
        binding.tvAgentServerUrl.text = if (url.isNotBlank()) "VPS Server: $url" else "VPS Server: Not configured"

        val hasPwd = authManager.isPasswordConfigured
        val pwdLabel = if (hasPwd) "Encrypted & Set" else "Not configured"
        binding.tvAgentNodeInfo.text = "Device ID: ${authManager.deviceId.take(8)}... | Password: $pwdLabel"

        val authState = authManager.getAuthState()
        when (authState) {
            DeviceAuthState.AUTHENTICATED -> {
                binding.chipAgentStatus.text = "Authenticated / Ready"
                binding.chipAgentStatus.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.accent_green)
                binding.chipAgentStatus.setTextColor(Color.WHITE)
            }
            DeviceAuthState.CONFIGURED_LOCAL -> {
                binding.chipAgentStatus.text = "Connecting / Pairing..."
                binding.chipAgentStatus.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.accent)
                binding.chipAgentStatus.setTextColor(Color.WHITE)
            }
            DeviceAuthState.REVOKED -> {
                binding.chipAgentStatus.text = "Revoked by Server"
                binding.chipAgentStatus.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.accent_red)
                binding.chipAgentStatus.setTextColor(Color.WHITE)
            }
            else -> {
                binding.chipAgentStatus.text = "Setup Required"
                binding.chipAgentStatus.chipBackgroundColor = ContextCompat.getColorStateList(this, R.color.border_card)
                binding.chipAgentStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        val allJobs = queueStore.getAllJobs()
        val pendingCount = allJobs.count { it.status == PrintJobState.QUEUED || it.status == PrintJobState.DOWNLOADING || it.status == PrintJobState.READY }
        val completedCount = allJobs.count { it.status == PrintJobState.COMPLETED_SUCCESS }
        binding.tvAgentQueueStatus.text = "Queue: $pendingCount pending · $completedCount completed (${allJobs.size} total)"
        binding.btnToggleAgentService.text = if (isAgentRunning) "⏹ Stop Agent" else "▶ Start Agent"
    }

    private fun exportCompatibilityReport() {
        val report = viewModel.generateCompatibilityReport()

        // Copy to clipboard
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("PocketPrint Compatibility Report", report)
        clipboard.setPrimaryClip(clip)

        // Launch standard system share sheet
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketPrint USB Compatibility Report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        startActivity(Intent.createChooser(shareIntent, "Export Compatibility Report"))
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest { state ->
                        updateHostCapabilities(state)
                        updateStatusCard(state)
                        updatePrintEngineCard(state)
                        updateDiscoveryCard(state)
                        updateDevices(state)
                        updateProbeResult(state)
                    }
                }

                launch {
                    viewModel.logs.collectLatest { logEntries ->
                        logAdapter.submitList(logEntries) {
                            if (logEntries.isNotEmpty()) {
                                binding.rvLogs.scrollToPosition(logEntries.size - 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateDiscoveryCard(state: MainUiState) {
        val discovery = state.discoveryState
        val isRunning = discovery.isRunning

        binding.btnAutoRunMatrix.isEnabled = state.canPrint && !isRunning
        binding.btnRunDiscovery.isEnabled = state.canPrint && !isRunning
        binding.tvDiscoveryStatusText.text = discovery.currentStepMessage

        if (isRunning) {
            binding.progressDiscovery.visibility = View.VISIBLE
            if (discovery.totalBytes > 0) {
                binding.progressDiscovery.isIndeterminate = false
                val percent = (discovery.bytesTransferred * 100 / discovery.totalBytes).toInt()
                binding.progressDiscovery.progress = percent
            } else {
                binding.progressDiscovery.isIndeterminate = true
            }
            binding.chipDiscoveryStatus.text = "Testing..."
        } else {
            binding.progressDiscovery.visibility = View.GONE
            binding.chipDiscoveryStatus.text = if (discovery.verifiedProfile != null) "Verified" else "Ready"
        }

        // Active candidate details
        val cand = discovery.activeCandidate ?: viewModel.experimentManager.getCandidateList().firstOrNull()
        if (cand != null) {
            binding.tvDiscoveryCandidateInfo.text = "Candidate [${discovery.candidateIndex + 1}/${discovery.totalCandidates.coerceAtLeast(8)}]: ${cand.name}"
            binding.tvDiscoveryDetails.text = "Protocol: ${cand.protocol.displayName} | Resolution: ${cand.resolutionDpi} DPI | ZLP: ${cand.sendZlpFlush}"
        }

        // Live debugger telemetry
        val lastRun = discovery.lastRunRecord
        if (lastRun != null) {
            val speed = String.format("%.1f KB/s", lastRun.transferSpeedKbps)
            val duration = "${lastRun.transferDurationMs}ms"
            val portStatus = "Pre: ${lastRun.prePortStatus ?: "N/A"} | Post: ${lastRun.postPortStatus ?: "N/A"}"
            binding.tvDebugTelemetry.text = "Tx: ${lastRun.bytesTransferred}/${lastRun.totalBytes}B ($duration @ $speed)\nPort: $portStatus\nSHA: ${lastRun.payloadSha256.take(16)}..."
        } else if (discovery.bytesTransferred > 0) {
            binding.tvDebugTelemetry.text = "Streaming: ${discovery.bytesTransferred}/${discovery.totalBytes} B transferred"
        } else {
            binding.tvDebugTelemetry.text = "Debugger ready. Tap Auto-Test Sequence to begin."
        }

        if (discovery.hexHeaderSnippet.isNotBlank()) {
            binding.tvDebugHexSnippet.visibility = View.VISIBLE
            binding.tvDebugHexSnippet.text = "HEADER: ${discovery.hexHeaderSnippet}"
        } else if (lastRun?.hexHeaderSnippet?.isNotBlank() == true) {
            binding.tvDebugHexSnippet.visibility = View.VISIBLE
            binding.tvDebugHexSnippet.text = "HEADER: ${lastRun.hexHeaderSnippet}"
        } else {
            binding.tvDebugHexSnippet.visibility = View.GONE
        }

        // Physical verification prompt
        if (discovery.awaitingPhysicalVerification) {
            binding.layoutPhysicalVerification.visibility = View.VISIBLE
        } else {
            binding.layoutPhysicalVerification.visibility = View.GONE
        }

        // Verified profile active
        if (discovery.verifiedProfile != null) {
            binding.layoutVerifiedProfile.visibility = View.VISIBLE
            binding.tvVerifiedProfileName.text = "${discovery.verifiedProfile.profileName} (Verified: ${java.util.Date(discovery.verifiedProfile.verifiedTimestamp)})"
        } else {
            binding.layoutVerifiedProfile.visibility = View.GONE
        }
    }

    private fun updatePrintEngineCard(state: MainUiState) {
        val isPrinting = when (state.printProgress.state) {
            PrintJobState.PREPARING_DOCUMENT,
            PrintJobState.RENDERING_BITMAP,
            PrintJobState.ENCODING_RASTER,
            PrintJobState.TRANSMITTING_USB,
            PrintJobState.FINALIZING,
            PrintJobState.TRANSFER_RECOVERING,
            PrintJobState.PRINTER_PROCESSING -> true
            else -> false
        }
        val isCancellable = state.printProgress.state in setOf(
            PrintJobState.PREPARING_DOCUMENT,
            PrintJobState.RENDERING_BITMAP,
            PrintJobState.ENCODING_RASTER,
            PrintJobState.TRANSMITTING_USB,
            PrintJobState.TRANSFER_RECOVERING,
            PrintJobState.FINALIZING
        )

        // System Print Service settings button: always enabled
        binding.btnSystemPrintService.isEnabled = true
        // Direct USB button: needs both connection AND permission
        binding.btnPrintTestPage.isEnabled = state.canPrint && !isPrinting
        binding.btnPrintColorTestPage.isEnabled = state.canPrint && !isPrinting
        binding.btnPrintGrayscaleTestPage.isEnabled = state.canPrint && !isPrinting
        binding.btnPrintQualityBenchmark.isEnabled = state.canPrint && !isPrinting &&
            !state.discoveryState.isRunning
        binding.btnSelectDocument.isEnabled = !isPrinting
        binding.btnPrintDocument.isEnabled = state.selectedDocumentUri != null && !isPrinting
        binding.btnCancelPrint.visibility = if (isCancellable) View.VISIBLE else View.GONE

        if (state.selectedDocumentName != null) {
            binding.tvSelectedDocumentName.text = "Selected: ${state.selectedDocumentName}"
            binding.tvSelectedDocumentName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        } else {
            binding.tvSelectedDocumentName.text = "No document selected"
            binding.tvSelectedDocumentName.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        // Build detailed progress text
        val p = state.printProgress
        val progressDetail = buildString {
            append(p.message)
            if (p.scheduledItemCount > 0) {
                append("\nOverall: ${p.scheduledItem}/${p.scheduledItemCount} items")
                append(" (${p.overallProgressPercent}%)")
                if (p.documentPageCount > 0) {
                    append(" | document page ${p.documentPage}/${p.documentPageCount}")
                }
                if (p.copyCount > 1) append(" | copy ${p.copyNumber}/${p.copyCount}")
            }
            if (p.totalBytes > 0 && p.state == PrintJobState.TRANSMITTING_USB) {
                val remaining = p.totalBytes - p.bytesTransferred
                append("\n${p.bytesTransferred} / ${p.totalBytes} bytes")
                append(" — ${remaining}B remaining (${p.progressPercent}%)")
                if (p.transferSpeedBps > 0) {
                    append(" @ ${p.transferSpeedBps / 1024} KB/s")
                }
                if (p.totalChunks > 0) {
                    append("\nChunk ${p.currentChunkIndex}/${p.totalChunks}")
                    append(" (${p.currentChunkSize} B) at offset ${p.currentOffset} B")
                }
                if (p.lastTransferResult.isNotEmpty()) {
                    append(" | last: ${p.lastTransferResult}")
                }
            }
            if (p.retryCount > 0) {
                append("\nRetries: ${p.retryCount}")
            }
            if (p.portStatus.isNotEmpty()) {
                append(" | USB link: ${p.portStatus}")
            }
            if (state.connectionState != PrinterConnectionState.NO_DEVICE) {
                append("\nUSB link: ${state.connectionState.name}")
                if (state.connectionDetail.isNotEmpty()) append(" — ${state.connectionDetail}")
            }
        }
        binding.tvPrintProgressStatus.text = progressDetail

        when (state.printProgress.state) {
            PrintJobState.IDLE,
            PrintJobState.CANCELLED -> {
                binding.progressPrint.visibility = View.GONE
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }

            PrintJobState.QUEUED,
            PrintJobState.DOWNLOADING,
            PrintJobState.VALIDATING,
            PrintJobState.CONVERTING,
            PrintJobState.READY,
            PrintJobState.PREPARING_DOCUMENT,
            PrintJobState.RENDERING_BITMAP,
            PrintJobState.ENCODING_RASTER -> {
                binding.progressPrint.visibility = View.VISIBLE
                binding.progressPrint.isIndeterminate = true
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }

            PrintJobState.TRANSMITTING_USB -> {
                binding.progressPrint.visibility = View.VISIBLE
                binding.progressPrint.isIndeterminate = false
                binding.progressPrint.progress = if (state.printProgress.scheduledItemCount > 0) {
                    state.printProgress.overallProgressPercent
                } else {
                    state.printProgress.progressPercent
                }
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }

            PrintJobState.TRANSFER_RECOVERING,
            PrintJobState.FINALIZING -> {
                binding.progressPrint.visibility = View.VISIBLE
                binding.progressPrint.isIndeterminate = true
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }

            PrintJobState.TRANSFER_ERROR,
            PrintJobState.DISCONNECTED -> {
                binding.progressPrint.visibility = View.GONE
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            }

            PrintJobState.PRINTER_PROCESSING -> {
                binding.progressPrint.visibility = View.VISIBLE
                binding.progressPrint.isIndeterminate = true
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
            }

            PrintJobState.COMPLETED_SUCCESS -> {
                binding.progressPrint.visibility = View.GONE
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            }

            PrintJobState.NEEDS_RECOVERY -> {
                binding.progressPrint.visibility = View.GONE
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }

            PrintJobState.FAILED,
            PrintJobState.NEEDS_ATTENTION -> {
                binding.progressPrint.visibility = View.GONE
                binding.tvPrintProgressStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
            }
        }
    }

    private fun updateHostCapabilities(state: MainUiState) {
        val caps = state.capabilities ?: return
        binding.tvHostHardwareInfo.text = "Host: ${caps.manufacturer} ${caps.model} | Android ${caps.androidVersion} (API ${caps.apiLevel})"
        binding.tvHostCapabilitySummary.text = caps.statusSummary

        when (caps.compatibilityStatus) {
            HostCompatibilityStatus.FULLY_SUPPORTED -> {
                binding.chipHostUsbStatus.text = "USB Host OK"
                binding.chipHostUsbStatus.setChipBackgroundColorResource(R.color.accent_green)
            }
            HostCompatibilityStatus.USB_HOST_FEATURE_MISSING -> {
                binding.chipHostUsbStatus.text = "Feature Missing"
                binding.chipHostUsbStatus.setChipBackgroundColorResource(R.color.accent_amber)
            }
            HostCompatibilityStatus.USB_MANAGER_UNAVAILABLE -> {
                binding.chipHostUsbStatus.text = "Service Error"
                binding.chipHostUsbStatus.setChipBackgroundColorResource(R.color.accent_red)
            }
        }
    }

    private fun updateStatusCard(state: MainUiState) {
        val indicatorDrawable = binding.viewStatusIndicator.background as? GradientDrawable
            ?: ContextCompat.getDrawable(this, R.drawable.shape_status_indicator)?.mutate() as GradientDrawable

        val device = state.primaryPrinterDevice
        val hasPermission = device?.hasPermission == true || state.connectionState in setOf(
            PrinterConnectionState.READY,
            PrinterConnectionState.INTERFACE_CLAIMED,
            PrinterConnectionState.PRINTING,
            PrinterConnectionState.PROCESSING,
            PrinterConnectionState.TRANSFER_ERROR,
            PrinterConnectionState.RECOVERY_REQUIRED
        )

        when {
            state.connectionState == PrinterConnectionState.RECOVERY_REQUIRED -> {
                binding.tvStatusHeader.text = "Printer connected — recovery required"
                binding.tvStatusDetails.text = state.connectionDetail.ifBlank {
                    "The last USB transfer outcome is uncertain. Resubmit the document instead of replaying it."
                }
                indicatorDrawable.setColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
            state.connectionState == PrinterConnectionState.TRANSFER_ERROR -> {
                binding.tvStatusHeader.text = "Printer connected — USB transfer error"
                binding.tvStatusDetails.text = state.connectionDetail.ifBlank {
                    "One or more bulk transfers failed, but the printer is still attached. Retry the job."
                }
                indicatorDrawable.setColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
            state.isPrinterConnected -> {
                binding.tvStatusHeader.text = getString(R.string.status_connected)
                val base = device?.displayName ?: "USB Printer Connected"
                binding.tvStatusDetails.text = if (state.connectionDetail.isNotBlank()) {
                    "$base · ${state.connectionDetail}"
                } else {
                    base
                }
                indicatorDrawable.setColor(
                    ContextCompat.getColor(
                        this,
                        if (hasPermission) R.color.accent_green else R.color.accent_amber
                    )
                )
            }
            else -> {
                binding.tvStatusHeader.text = getString(R.string.status_disconnected)
                binding.tvStatusDetails.text = if (state.connectionState == PrinterConnectionState.DISCONNECTED) {
                    "Printer USB link was lost. Reconnect the OTG cable and retry."
                } else {
                    "No USB printer detected. Connect OTG cable."
                }
                indicatorDrawable.setColor(ContextCompat.getColor(this, R.color.accent_red))
            }
        }

        if (state.isPrinterConnected) {
            if (hasPermission) {
                binding.chipPermission.text = "Permission Granted"
                binding.chipPermission.setChipBackgroundColorResource(R.color.accent_green)
                binding.chipPermission.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                binding.btnRequestPermission.isEnabled = false
                binding.btnProbe.isEnabled = state.connectionState !in setOf(
                    PrinterConnectionState.RECOVERY_REQUIRED,
                    PrinterConnectionState.TRANSFER_ERROR
                )
            } else {
                binding.chipPermission.text = "Permission Required"
                binding.chipPermission.setChipBackgroundColorResource(R.color.accent_amber)
                binding.chipPermission.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                binding.btnRequestPermission.isEnabled = true
                binding.btnProbe.isEnabled = false
            }
        } else {
            binding.chipPermission.text = "No Device"
            binding.chipPermission.setChipBackgroundColorResource(R.color.border_card)
            binding.chipPermission.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnRequestPermission.isEnabled = false
            binding.btnProbe.isEnabled = false
        }
        binding.viewStatusIndicator.background = indicatorDrawable
    }

    private fun updateDevices(state: MainUiState) {
        if (state.devices.isEmpty()) {
            binding.tvEmptyDevices.visibility = View.VISIBLE
            binding.rvDevices.visibility = View.GONE
        } else {
            binding.tvEmptyDevices.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
            deviceAdapter.submitList(state.devices)
        }
    }

    private fun updateProbeResult(state: MainUiState) {
        val probe = state.probeResult
        if (probe != null) {
            binding.cardProbeResult.visibility = View.VISIBLE
            binding.tvProbeSummary.text = probe.statusMessage

            if (!probe.ieee1284DeviceId.isNullOrBlank()) {
                binding.tvProbeIeee1284.visibility = View.VISIBLE
                binding.tvProbeIeee1284.text = "IEEE 1284 ID: ${probe.ieee1284DeviceId}"
            } else {
                binding.tvProbeIeee1284.visibility = View.GONE
            }
        } else {
            binding.cardProbeResult.visibility = View.GONE
        }
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }

    private fun showQualityBenchmarkDialog() {
        val profiles = PrintQualityProfile.entries
        val choices = profiles.flatMap { profile ->
            listOf(
                profile to PrintColorMode.MONOCHROME_1BIT,
                profile to PrintColorMode.GRAYSCALE_8BIT,
                profile to PrintColorMode.COLOR_RGB
            )
        }
        val labels = choices.map { (profile, mode) ->
            "${profile.displayName} | ${mode.displayName}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Quality benchmark")
            .setMessage("Every option prints the same deterministic chart for physical A/B comparison.")
            .setItems(labels) { _, which ->
                val (profile, mode) = choices[which]
                viewModel.printQualityBenchmark(profile, mode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExperimentalDpiDialog() {
        val candidates = com.pocketprint.data.discovery.engine.CandidateGenerator
            .generateExperimentalDpiCandidates()
        val labels = candidates.map { "${it.resolutionDpi} DPI - ${it.name}" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Unverified DPI test")
            .setMessage(
                "600 and 1200 DPI are diagnostic PCL3GUI2 tests, not production settings. " +
                    "They print a short full-width ruler strip. Confirm the physical result only if dimensions, output, and page ejection are correct."
            )
            .setItems(labels) { _, which ->
                viewModel.runDiscoveryCandidate(candidates[which])
                Toast.makeText(
                    this,
                    "Sending experimental ${candidates[which].resolutionDpi} DPI strip...",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requiresSourceAppRendering(name: String, mimeType: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in setOf("rtf", "doc", "docx", "xls", "xlsx", "ppt", "pptx") ||
            mimeType in setOf(
                "application/rtf",
                "text/rtf",
                "application/msword",
                "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            )
    }
}
