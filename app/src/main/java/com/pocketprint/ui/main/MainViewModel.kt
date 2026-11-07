package com.pocketprint.ui.main

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.device.DeviceCapabilities
import com.pocketprint.data.discovery.engine.ExperimentEngineState
import com.pocketprint.data.discovery.engine.ExperimentManager
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.PhysicalOutcome
import com.pocketprint.data.discovery.model.VerifiedPrinterProfile
import com.pocketprint.data.discovery.storage.ExperimentHistoryStore
import com.pocketprint.data.discovery.storage.PrinterProfileStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.model.LogEntry
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbProbeResult
import com.pocketprint.data.print.PrintJobManager
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.transport.PrinterConnectionState
import com.pocketprint.service.LocalPrintForegroundService
import com.pocketprint.data.print.quality.PrintQualityProfile
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.report.CompatibilityReportGenerator
import com.pocketprint.data.usb.PrinterConnectionInfo
import com.pocketprint.data.usb.UsbDeviceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SelectedDocumentInfo(
    val uri: Uri? = null,
    val name: String? = null,
    val mimeType: String? = null
)
data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
data class UsbStateTriple(
    val capabilities: DeviceCapabilities,
    val devices: List<UsbDeviceInfo>,
    val probeResult: UsbProbeResult?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PocketPrintApp
    private val usbDeviceManager: UsbDeviceManager = app.usbDeviceManager
    private val printJobManager: PrintJobManager = app.printJobManager
    val experimentManager: ExperimentManager = app.experimentManager
    val profileStore: PrinterProfileStore = app.profileStore
    val historyStore = app.historyStore

    val logs: StateFlow<List<LogEntry>> = DiagnosticLogger.logsFlow

    private val isScanningFlow = MutableStateFlow(false)
    private val selectedDocumentFlow = MutableStateFlow(SelectedDocumentInfo())
    private var lastPrintOptions = app.printPreferences.load()

    private val usbCompositeFlow = combine(
        usbDeviceManager.capabilitiesFlow,
        usbDeviceManager.deviceListFlow,
        usbDeviceManager.activeProbeResult
    ) { capabilities, devices, probeResult ->
        UsbStateTriple(capabilities, devices, probeResult)
    }

    private val appCompositeFlow = combine(
        usbCompositeFlow,
        isScanningFlow,
        printJobManager.statusManager.progressFlow,
        selectedDocumentFlow,
        usbDeviceManager.printerConnectionManager.state
    ) { usbState, isScanning, printProgress, selectedDocument, connectionInfo ->
        Tuple5(usbState, isScanning, printProgress, selectedDocument, connectionInfo)
    }

    val uiState: StateFlow<MainUiState> = combine(
        appCompositeFlow,
        experimentManager.engineState
    ) { appState, discoveryState ->
        val (usbState, isScanning, printProgress, selectedDocument, connectionInfo) = appState
        val candidatePrinter = usbState.devices.firstOrNull { it.isPrinterCandidate }

        // A job in flight implies the link was valid when it started. Only the
        // authoritative connection manager may declare a physical disconnect.
        val printingActive = when (printProgress.state) {
            PrintJobState.TRANSMITTING_USB,
            PrintJobState.FINALIZING,
            PrintJobState.TRANSFER_RECOVERING,
            PrintJobState.PRINTER_PROCESSING,
            PrintJobState.TRANSFER_ERROR -> true
            else -> false
        }
        val linkState = connectionInfo.state
        val isPrinterConnected = when (linkState) {
            PrinterConnectionState.NO_DEVICE,
            PrinterConnectionState.DISCONNECTED ->
                candidatePrinter != null || printingActive
            else -> true
        }

        val permStatus = when {
            candidatePrinter == null -> PermissionStatus.NOT_REQUESTED
            candidatePrinter.hasPermission -> PermissionStatus.GRANTED
            connectionInfo.state == PrinterConnectionState.PERMISSION_REQUIRED -> PermissionStatus.PENDING
            else -> PermissionStatus.NOT_REQUESTED
        }

        val banner = when {
            linkState == PrinterConnectionState.RECOVERY_REQUIRED ->
                "USB transfer outcome uncertain — recovery required (printer still connected)"
            linkState == PrinterConnectionState.TRANSFER_ERROR ->
                "USB transfer error — printer is still connected; job not replayed"
            linkState == PrinterConnectionState.DISCONNECTED ->
                "Printer disconnected during job — resubmit the document"
            connectionInfo.hasPermission || candidatePrinter?.hasPermission == true ->
                "Printer Connected & Authorized (${candidatePrinter?.displayName ?: connectionInfo.displayName})"
            else -> "Printer Connected — USB Permission Required"
        }

        // Check for verified profile
        val verified = if (candidatePrinter != null) {
            profileStore.getVerifiedProfile(candidatePrinter.vendorId, candidatePrinter.productId)
        } else null

        val updatedDiscovery = if (discoveryState.verifiedProfile == null && verified != null) {
            discoveryState.copy(verifiedProfile = verified)
        } else discoveryState

        MainUiState(
            capabilities = usbState.capabilities,
            isPrinterConnected = isPrinterConnected,
            primaryPrinterDevice = candidatePrinter,
            permissionStatus = permStatus,
            devices = usbState.devices,
            isScanning = isScanning,
            probeResult = usbState.probeResult,
            statusBannerText = banner,
            connectionState = connectionInfo.state,
            connectionDetail = connectionInfo.detail,
            printProgress = printProgress,
            selectedDocumentUri = selectedDocument.uri,
            selectedDocumentName = selectedDocument.name,
            selectedDocumentMimeType = selectedDocument.mimeType,
            discoveryState = updatedDiscovery
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    // USB monitoring is now always-on at the application level.
    // startUsbMonitoring() and stopUsbMonitoring() have been removed;
    // the USB hotplug receiver stays registered for the app process lifetime
    // regardless of Activity visibility or screen state.
    // ViewModel no longer controls USB monitoring lifecycle.

    fun scanDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            isScanningFlow.value = true
            DiagnosticLogger.usb("Scanning for connected USB devices...")
            usbDeviceManager.refreshDevices()
            isScanningFlow.value = false
        }
    }

    fun requestPermissionForDevice(device: UsbDeviceInfo) {
        DiagnosticLogger.usb("User requested permission for: ${device.displayName}")
        usbDeviceManager.requestPermission(device.deviceName)
    }

    fun requestPermissionForPrimary() {
        val target = uiState.value.primaryPrinterDevice
        if (target != null) {
            requestPermissionForDevice(target)
        } else {
            DiagnosticLogger.usbWarn("No USB device connected to request permission for")
        }
    }

    fun probeDevice(device: UsbDeviceInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            DiagnosticLogger.usb("Running interface claim and diagnostic probe on ${device.deviceName}...")
            usbDeviceManager.probeDevice(device.deviceName)
            usbDeviceManager.refreshDevices()
        }
    }

    fun probePrimaryDevice() {
        val target = uiState.value.primaryPrinterDevice
        if (target != null) {
            probeDevice(target)
        } else {
            DiagnosticLogger.usbWarn("Cannot probe: no USB device connected")
        }
    }

    fun selectDocument(uri: Uri, displayName: String, mimeType: String?) {
        selectedDocumentFlow.value = SelectedDocumentInfo(uri, displayName, mimeType)
        DiagnosticLogger.usb("Selected local document: $displayName (${mimeType ?: "unknown type"})")
    }

    fun getSelectedDocumentInfo(): SelectedDocumentInfo? {
        val info = selectedDocumentFlow.value
        return if (info.uri != null) info else null
    }

    fun getLastPrintOptions(): PrintOptions = lastPrintOptions

    fun printTestPage() {
        val targetInfo = uiState.value.primaryPrinterDevice
        if (targetInfo == null) {
            DiagnosticLogger.usbWarn("Cannot print: No printer connected")
            return
        }

        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName)
        if (rawDevice == null) {
            DiagnosticLogger.usbError("Cannot print: Raw UsbDevice not found at ${targetInfo.deviceName}")
            return
        }

        viewModelScope.launch {
            DiagnosticLogger.usb("Initiating Direct USB Test Page print job on ${targetInfo.displayName}...")
            printJobManager.printTestPage(rawDevice, targetInfo)
        }
    }

    fun printColorTestPage() {
        val targetInfo = uiState.value.primaryPrinterDevice
        if (targetInfo == null) {
            DiagnosticLogger.usbWarn("Cannot print: No printer connected")
            return
        }

        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName)
        if (rawDevice == null) {
            DiagnosticLogger.usbError("Cannot print: Raw UsbDevice not found at ${targetInfo.deviceName}")
            return
        }

        viewModelScope.launch {
            DiagnosticLogger.usb("Initiating Direct USB Full-Color Test Page print job on ${targetInfo.displayName}...")
            printJobManager.printColorTestPage(rawDevice, targetInfo)
        }
    }

    fun printGrayscaleTestPage() {
        val targetInfo = uiState.value.primaryPrinterDevice
        if (targetInfo == null) {
            DiagnosticLogger.usbWarn("Cannot print: No printer connected")
            return
        }
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName)
        if (rawDevice == null) {
            DiagnosticLogger.usbError("Cannot print: Raw UsbDevice not found at ${targetInfo.deviceName}")
            return
        }
        viewModelScope.launch {
            DiagnosticLogger.usb("Initiating grayscale hardware verification chart...")
            printJobManager.printGrayscaleTestPage(rawDevice, targetInfo)
        }
    }

    fun printQualityBenchmark(profile: PrintQualityProfile, colorMode: PrintColorMode) {
        val targetInfo = uiState.value.primaryPrinterDevice
        if (targetInfo == null) {
            DiagnosticLogger.usbWarn("Cannot print benchmark: no printer connected")
            return
        }
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        viewModelScope.launch {
            printJobManager.printQualityBenchmark(rawDevice, targetInfo, profile, colorMode)
        }
    }

    fun printSelectedDocument(options: PrintOptions) {
        val selectedDocument = selectedDocumentFlow.value
        val uri = selectedDocument.uri

        if (uri == null) {
            DiagnosticLogger.usbWarn("Cannot queue document: no local document selected")
            return
        }

        lastPrintOptions = options
        app.printPreferences.save(options)
        val job = app.localPrintQueue.enqueue(
            documentUri = uri.toString(),
            displayName = selectedDocument.name ?: "Document",
            mimeType = selectedDocument.mimeType ?: "application/octet-stream",
            printOptions = options
        )
        DiagnosticLogger.usb("Queued local document ${job.id}: ${job.displayName}")
        LocalPrintForegroundService.start(getApplication())
    }

    fun cancelPrintJob() {
        printJobManager.cancelJob()
    }

    fun startAutoDiscovery() {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        experimentManager.startAutoDiscovery(rawDevice)
    }

    fun advanceToNextCandidate(outcome: PhysicalOutcome = PhysicalOutcome.NOTHING_HAPPENED) {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        experimentManager.advanceToNextCandidate(rawDevice, outcome)
    }

    fun stopAutoDiscovery() {
        experimentManager.stopAutoDiscovery()
    }

    fun pauseAutoDiscovery() {
        experimentManager.pauseAutoDiscovery()
    }

    fun resumeAutoDiscovery() {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        experimentManager.resumeAutoDiscovery(rawDevice)
    }

    fun retryCurrentCandidate() {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        experimentManager.retryCurrentCandidate(rawDevice)
    }

    fun applyIeee1284ToRegistry() {
        val ieee = uiState.value.probeResult?.ieee1284DeviceId ?: return
        experimentManager.applyIeee1284Id(ieee)
    }

    fun runDiscoveryCandidate(candidate: DiscoveryCandidate) {
        val targetInfo = uiState.value.primaryPrinterDevice
        if (targetInfo == null) {
            DiagnosticLogger.log("DISCOVERY", "Cannot run candidate: No printer connected")
            return
        }

        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName)
        if (rawDevice == null) {
            DiagnosticLogger.log("DISCOVERY", "Cannot run candidate: Raw UsbDevice not found at ${targetInfo.deviceName}")
            return
        }

        viewModelScope.launch {
            DiagnosticLogger.log("DISCOVERY", "Launching candidate experiment: ${candidate.name}")
            experimentManager.executeCandidate(rawDevice, candidate)
        }
    }

    fun recordPhysicalVerification(outcome: PhysicalOutcome, notes: String = "") {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        val rawDevice = usbDeviceManager.getRawDevice(targetInfo.deviceName) ?: return
        val activeCand = uiState.value.discoveryState.activeCandidate ?: return

        experimentManager.recordPhysicalOutcome(rawDevice, activeCand, outcome, notes)
    }

    fun clearVerifiedProfile() {
        val targetInfo = uiState.value.primaryPrinterDevice ?: return
        profileStore.clearProfile(targetInfo.vendorId, targetInfo.productId)
    }

    fun generateCompatibilityReport(): String {
        val state = uiState.value
        val caps = state.capabilities ?: usbDeviceManager.capabilityManager.detectCapabilities()
        return CompatibilityReportGenerator.generateReport(
            capabilities = caps,
            devices = state.devices,
            lastProbeResult = state.probeResult
        )
    }

    fun clearLogs() {
        DiagnosticLogger.clear()
    }

    fun getLogsForExport(): String {
        return DiagnosticLogger.getAllLogsText()
    }
}
