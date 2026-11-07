package com.pocketprint.ui.discovery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.pocketprint.R
import com.pocketprint.data.discovery.model.ExperimentRunRecord
import com.pocketprint.data.discovery.model.FailureCategory
import com.pocketprint.data.discovery.model.PhysicalOutcome
import com.pocketprint.data.discovery.registry.CompatibilityStatus
import com.pocketprint.data.discovery.registry.FormatCategory
import com.pocketprint.data.discovery.registry.FormatRegistry
import com.pocketprint.databinding.ActivityDiscoveryDashboardBinding
import com.pocketprint.ui.main.MainViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DiscoveryDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiscoveryDashboardBinding
    private val viewModel: MainViewModel by viewModels()

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private var registryExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiscoveryDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.dashToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.dashToolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        setupButtons()
        observeViewModel()

        // Auto-apply IEEE 1284 registry update on open
        viewModel.applyIeee1284ToRegistry()
    }

    private fun setupButtons() {
        // Experiment controls
        binding.btnDashStart.setOnClickListener {
            if (viewModel.uiState.value.discoveryState.isPaused) {
                viewModel.resumeAutoDiscovery()
            } else {
                viewModel.startAutoDiscovery()
            }
        }
        binding.btnDashPause.setOnClickListener { viewModel.pauseAutoDiscovery() }
        binding.btnDashStop.setOnClickListener { viewModel.stopAutoDiscovery() }
        binding.btnDashRetry.setOnClickListener { viewModel.retryCurrentCandidate() }
        binding.btnDashNext.setOnClickListener {
            viewModel.advanceToNextCandidate(PhysicalOutcome.NOTHING_HAPPENED)
        }

        // Physical verification
        binding.btnDashVerifySuccess.setOnClickListener {
            viewModel.recordPhysicalVerification(PhysicalOutcome.SUCCESS_PAGE_PRINTED, "Operator confirmed page printed")
        }
        binding.btnDashVerifyBlank.setOnClickListener {
            viewModel.recordPhysicalVerification(PhysicalOutcome.BLANK_PAGE_PRINTED, "Paper fed but blank")
            viewModel.advanceToNextCandidate(PhysicalOutcome.BLANK_PAGE_PRINTED)
        }
        binding.btnDashVerifyNothing.setOnClickListener {
            viewModel.recordPhysicalVerification(PhysicalOutcome.NOTHING_HAPPENED, "Nothing happened")
            viewModel.advanceToNextCandidate(PhysicalOutcome.NOTHING_HAPPENED)
        }
        binding.btnDashVerifyPartial.setOnClickListener {
            viewModel.recordPhysicalVerification(PhysicalOutcome.PARTIAL_PRINT, "Partial output")
            viewModel.advanceToNextCandidate(PhysicalOutcome.PARTIAL_PRINT)
        }
        binding.btnDashVerifyError.setOnClickListener {
            viewModel.recordPhysicalVerification(PhysicalOutcome.PRINTER_HARDWARE_ERROR, "Printer error / flashing")
            viewModel.advanceToNextCandidate(PhysicalOutcome.PRINTER_HARDWARE_ERROR)
        }

        // Profile management
        binding.btnClearVerifiedProfile.setOnClickListener {
            viewModel.clearVerifiedProfile()
        }

        // Registry expand toggle
        binding.btnViewRegistry.setOnClickListener {
            registryExpanded = !registryExpanded
            if (registryExpanded) {
                binding.btnViewRegistry.text = "▲ Hide Format Registry"
                populateRegistryList()
                binding.layoutRegistryList.visibility = View.VISIBLE
            } else {
                binding.btnViewRegistry.text = "▼ Show Full Format Registry"
                binding.layoutRegistryList.visibility = View.GONE
            }
        }

        // Export / clear
        binding.btnExportHistory.setOnClickListener { exportHistory() }
        binding.btnDashCopyLogs.setOnClickListener { copyLogsToClipboard() }
        binding.btnDashClearHistory.setOnClickListener {
            viewModel.historyStore.clearHistory()
            updateHistoryList(emptyList())
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    val discovery = state.discoveryState
                    val isRunning = discovery.isRunning
                    val isPaused = discovery.isPaused

                    // ─ Printer banner ─
                    val printer = state.primaryPrinterDevice
                    if (printer != null) {
                        binding.tvDashPrinterName.text = "${printer.displayName} — ${if (printer.hasPermission) "Connected & Authorized" else "Permission Required"}"
                        binding.chipDashPrinterStatus.text = if (printer.hasPermission) "Ready" else "No Permission"
                        binding.chipDashPrinterStatus.setChipBackgroundColorResource(
                            if (printer.hasPermission) R.color.accent_green else R.color.accent_amber
                        )
                    } else {
                        binding.tvDashPrinterName.text = "No printer detected — connect OTG cable"
                        binding.chipDashPrinterStatus.text = "Offline"
                        binding.chipDashPrinterStatus.setChipBackgroundColorResource(R.color.accent_red)
                    }
                    val ieee = state.probeResult?.ieee1284DeviceId
                    if (!ieee.isNullOrBlank()) {
                        binding.tvDashIeee.visibility = View.VISIBLE
                        binding.tvDashIeee.text = "IEEE 1284: $ieee"
                    } else {
                        binding.tvDashIeee.visibility = View.GONE
                    }

                    // ─ Registry stats ─
                    val allEntries = FormatRegistry.getAllEntries()
                    binding.tvStatTotal.text = allEntries.size.toString()
                    binding.tvStatSupported.text = allEntries.count {
                        it.status in listOf(
                            CompatibilityStatus.ADVERTISED,
                            CompatibilityStatus.DOCUMENTED_SUPPORTED,
                            CompatibilityStatus.DRIVER_SUPPORTED,
                            CompatibilityStatus.PHYSICALLY_VERIFIED
                        )
                    }.toString()
                    binding.tvStatUnsupported.text = allEntries.count {
                        it.status == CompatibilityStatus.UNSUPPORTED
                    }.toString()
                    binding.tvStatTested.text = discovery.totalUsbFormatsTested.toString()

                    // ─ Experiment status chip ─
                    when {
                        discovery.verifiedProfile != null -> {
                            binding.chipExperimentStatus.text = "Verified ✓"
                            binding.chipExperimentStatus.setChipBackgroundColorResource(R.color.accent_green)
                        }
                        isRunning -> {
                            binding.chipExperimentStatus.text = "Testing…"
                            binding.chipExperimentStatus.setChipBackgroundColorResource(R.color.accent_amber)
                        }
                        isPaused -> {
                            binding.chipExperimentStatus.text = "Paused"
                            binding.chipExperimentStatus.setChipBackgroundColorResource(R.color.accent_amber)
                        }
                        discovery.awaitingPhysicalVerification -> {
                            binding.chipExperimentStatus.text = "Verify Output"
                            binding.chipExperimentStatus.setChipBackgroundColorResource(R.color.accent)
                        }
                        else -> {
                            binding.chipExperimentStatus.text = "Idle"
                            binding.chipExperimentStatus.setChipBackgroundColorResource(R.color.border_card)
                        }
                    }

                    // ─ Current candidate ─
                    val cand = discovery.activeCandidate
                    if (cand != null) {
                        binding.tvCurrentCandidate.text =
                            "Test ${discovery.candidateIndex + 1}/${discovery.totalCandidates}: ${cand.name}"
                    } else {
                        binding.tvCurrentCandidate.text =
                            "${viewModel.experimentManager.getCandidateList().size} candidates loaded. Tap ▶ Start to begin."
                    }
                    binding.tvCurrentStep.text = discovery.currentStepMessage

                    // ─ Progress bar ─
                    if (isRunning) {
                        binding.progressExperiment.visibility = View.VISIBLE
                        if (discovery.totalBytes > 0) {
                            binding.progressExperiment.isIndeterminate = false
                            binding.progressExperiment.progress =
                                (discovery.bytesTransferred * 100 / discovery.totalBytes).toInt()
                        } else {
                            binding.progressExperiment.isIndeterminate = true
                        }
                    } else {
                        binding.progressExperiment.visibility = View.GONE
                    }

                    // ─ Telemetry ─
                    val lastRun = discovery.lastRunRecord
                    if (lastRun != null) {
                        val speed = "%.1f KB/s".format(lastRun.transferSpeedKbps)
                        val portPre = lastRun.prePortStatus?.let { "0x%02X".format(it) } ?: "N/A"
                        val portPost = lastRun.postPortStatus?.let { "0x%02X".format(it) } ?: "N/A"
                        binding.tvDashTelemetry.text =
                            "Tx: ${lastRun.bytesTransferred}/${lastRun.totalBytes} B (${lastRun.transferDurationMs}ms @ $speed)\n" +
                            "Port: Pre=$portPre | Post=$portPost\n" +
                            "SHA: ${lastRun.payloadSha256.take(20)}…\n" +
                            "Result: ${lastRun.failureCategory.displayName}"
                    } else if (discovery.bytesTransferred > 0) {
                        binding.tvDashTelemetry.text =
                            "Streaming: ${discovery.bytesTransferred}/${discovery.totalBytes} B"
                    } else {
                        binding.tvDashTelemetry.text = "No transfer data yet."
                    }

                    if (discovery.hexHeaderSnippet.isNotBlank()) {
                        binding.tvDashHexHeader.visibility = View.VISIBLE
                        binding.tvDashHexHeader.text = "HEADER: ${discovery.hexHeaderSnippet}"
                    } else if (lastRun?.hexHeaderSnippet?.isNotBlank() == true) {
                        binding.tvDashHexHeader.visibility = View.VISIBLE
                        binding.tvDashHexHeader.text = "HEADER: ${lastRun.hexHeaderSnippet}"
                    } else {
                        binding.tvDashHexHeader.visibility = View.GONE
                    }

                    // ─ Printer response ─
                    if (lastRun != null) {
                        binding.tvDashPrinterResponse.visibility = View.VISIBLE
                        binding.tvDashPrinterResponse.text = "Printer: ${lastRun.usbStatusMessage}"
                    }

                    // ─ Physical verification card ─
                    binding.cardVerification.visibility =
                        if (discovery.awaitingPhysicalVerification) View.VISIBLE else View.GONE

                    // ─ Verified winner card ─
                    val profile = discovery.verifiedProfile
                    if (profile != null) {
                        binding.cardVerifiedWinner.visibility = View.VISIBLE
                        binding.tvVerifiedWinnerName.text = "${profile.profileName}"
                        binding.tvVerifiedWinnerDetails.text =
                            "Protocol: ${profile.candidate.protocol.displayName}\n" +
                            "Resolution: ${profile.candidate.resolutionDpi} DPI | ZLP: ${profile.candidate.sendZlpFlush}\n" +
                            "SHA: ${profile.verifiedSha256.take(20)}…\n" +
                            "Verified: ${dateFormat.format(Date(profile.verifiedTimestamp))}"
                    } else {
                        binding.cardVerifiedWinner.visibility = View.GONE
                    }

                    // ─ Button states ─
                    val canControl = state.canPrint
                    binding.btnDashStart.isEnabled = canControl && !isRunning
                    binding.btnDashStart.text = if (isPaused) "▶ Resume" else "▶ Start"
                    binding.btnDashPause.isEnabled = canControl && isRunning
                    binding.btnDashStop.isEnabled = canControl && (isRunning || isPaused)
                    binding.btnDashRetry.isEnabled = canControl && !isRunning && cand != null
                    binding.btnDashNext.isEnabled = canControl && !isRunning && discovery.awaitingPhysicalVerification

                    // ─ History ─
                    updateHistoryList(discovery.runHistory)
                }
            }
        }
    }

    private fun updateHistoryList(records: List<ExperimentRunRecord>) {
        if (records.isEmpty()) {
            binding.tvNoHistory.visibility = View.VISIBLE
            binding.layoutHistoryList.visibility = View.GONE
            return
        }
        binding.tvNoHistory.visibility = View.GONE
        binding.layoutHistoryList.visibility = View.VISIBLE
        binding.layoutHistoryList.removeAllViews()

        // Show most recent first
        records.reversed().forEach { record ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val outcomeIcon = when (record.physicalOutcome) {
                PhysicalOutcome.SUCCESS_PAGE_PRINTED -> "✓"
                PhysicalOutcome.BLANK_PAGE_PRINTED -> "○"
                PhysicalOutcome.PARTIAL_PRINT -> "◑"
                PhysicalOutcome.PRINTER_HARDWARE_ERROR -> "⚠"
                PhysicalOutcome.AWAITING_VERIFICATION -> "?"
                PhysicalOutcome.NOTHING_HAPPENED -> "✗"
                PhysicalOutcome.UNTESTED -> "—"
            }
            val outcomeColor = when (record.physicalOutcome) {
                PhysicalOutcome.SUCCESS_PAGE_PRINTED -> 0xFF4ADE80.toInt()
                PhysicalOutcome.BLANK_PAGE_PRINTED, PhysicalOutcome.PARTIAL_PRINT -> 0xFFFBBF24.toInt()
                PhysicalOutcome.PRINTER_HARDWARE_ERROR, PhysicalOutcome.NOTHING_HAPPENED -> 0xFFEF4444.toInt()
                else -> 0xFF64748B.toInt()
            }

            val titleTv = TextView(this).apply {
                text = "$outcomeIcon ${record.candidateName}"
                setTextColor(outcomeColor)
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val detailTv = TextView(this).apply {
                text = "${dateFormat.format(Date(record.timestamp))} | " +
                       "${record.bytesTransferred}B | ${record.transferDurationMs}ms | " +
                       if (record.failureCategory != FailureCategory.NONE) record.failureCategory.displayName
                       else record.physicalOutcome.displayName
                setTextColor(0xFF64748B.toInt())
                textSize = 10f
                 typeface = android.graphics.Typeface.MONOSPACE
            }

            row.addView(titleTv)
            row.addView(detailTv)

            // Thin divider
            val divider = View(this).apply {
                setBackgroundColor(0xFF334155.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).also { it.topMargin = 8 }
            }
            row.addView(divider)
            binding.layoutHistoryList.addView(row)
        }
    }

    private fun populateRegistryList() {
        binding.layoutRegistryList.removeAllViews()
        val grouped = FormatRegistry.getAllEntries().groupBy { it.category }

        FormatCategory.entries.forEach { cat ->
            val entries = grouped[cat] ?: return@forEach

            // Category header
            val catHeader = TextView(this).apply {
                text = "── ${cat.displayName} ──"
                setTextColor(0xFF38BDF8.toInt())
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 12, 0, 4)
            }
            binding.layoutRegistryList.addView(catHeader)

            entries.forEach { entry ->
                val statusColor = when (entry.status) {
                    CompatibilityStatus.PHYSICALLY_VERIFIED -> 0xFF4ADE80.toInt()
                    CompatibilityStatus.ADVERTISED -> 0xFF22C55E.toInt()
                    CompatibilityStatus.DOCUMENTED_SUPPORTED, CompatibilityStatus.DRIVER_SUPPORTED -> 0xFF38BDF8.toInt()
                    CompatibilityStatus.THEORETICALLY_COMPATIBLE -> 0xFFFBBF24.toInt()
                    CompatibilityStatus.UNSUPPORTED -> 0xFFEF4444.toInt()
                    CompatibilityStatus.NOT_INVESTIGATED -> 0xFF475569.toInt()
                }
                val statusIcon = when (entry.status) {
                    CompatibilityStatus.PHYSICALLY_VERIFIED -> "🏆"
                    CompatibilityStatus.ADVERTISED -> "✓"
                    CompatibilityStatus.DOCUMENTED_SUPPORTED -> "✓"
                    CompatibilityStatus.DRIVER_SUPPORTED -> "d"
                    CompatibilityStatus.THEORETICALLY_COMPATIBLE -> "~"
                    CompatibilityStatus.UNSUPPORTED -> "✗"
                    CompatibilityStatus.NOT_INVESTIGATED -> "?"
                }

                val entryView = TextView(this).apply {
                    text = "  $statusIcon ${entry.name}  [${entry.status.displayName}]"
                    setTextColor(statusColor)
                    textSize = 11f
                    setPadding(8, 2, 0, 2)
                }
                binding.layoutRegistryList.addView(entryView)
            }
        }
    }

    private fun exportHistory() {
        val sb = StringBuilder()
        sb.appendLine("PocketPrint Experiment History Export")
        sb.appendLine("Generated: ${dateFormat.format(Date())}")
        sb.appendLine("=".repeat(60))
        viewModel.experimentManager.getRunHistory().forEach { r ->
            sb.appendLine()
            sb.appendLine("Candidate: ${r.candidateName}")
            sb.appendLine("ID: ${r.candidateId}")
            sb.appendLine("Time: ${dateFormat.format(Date(r.timestamp))}")
            sb.appendLine("Transfer: ${r.bytesTransferred}/${r.totalBytes} B in ${r.transferDurationMs}ms @ ${"%.1f KB/s".format(r.transferSpeedKbps)}")
            sb.appendLine("Port Pre/Post: ${r.prePortStatus?.let { "0x%02X".format(it) } ?: "N/A"} / ${r.postPortStatus?.let { "0x%02X".format(it) } ?: "N/A"}")
            sb.appendLine("SHA-256: ${r.payloadSha256}")
            sb.appendLine("Failure: ${r.failureCategory.displayName}")
            sb.appendLine("Physical: ${r.physicalOutcome.displayName}")
            if (r.operatorNotes.isNotBlank()) sb.appendLine("Notes: ${r.operatorNotes}")
            sb.appendLine("Header: ${r.hexHeaderSnippet}")
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PocketPrint Discovery History")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(shareIntent, "Export History"))
    }

    private fun copyLogsToClipboard() {
        val text = viewModel.getLogsForExport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PocketPrint Logs", text))
        android.widget.Toast.makeText(this, "Logs copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DiscoveryDashboardActivity::class.java))
        }
    }
}
