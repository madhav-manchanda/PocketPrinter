package com.pocketprint.ui.main

import android.net.Uri
import com.pocketprint.data.device.DeviceCapabilities
import com.pocketprint.data.device.HostCompatibilityStatus
import com.pocketprint.data.discovery.engine.ExperimentEngineState
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbProbeResult
import com.pocketprint.data.print.model.PrintProgress
import com.pocketprint.data.print.transport.PrinterConnectionState

data class MainUiState(
    val capabilities: DeviceCapabilities? = null,
    val isPrinterConnected: Boolean = false,
    val primaryPrinterDevice: UsbDeviceInfo? = null,
    val permissionStatus: PermissionStatus = PermissionStatus.NOT_REQUESTED,
    val devices: List<UsbDeviceInfo> = emptyList(),
    val isScanning: Boolean = false,
    val probeResult: UsbProbeResult? = null,
    val statusBannerText: String = "Printer Disconnected",
    val connectionState: PrinterConnectionState = PrinterConnectionState.NO_DEVICE,
    val connectionDetail: String = "",
    val printProgress: PrintProgress = PrintProgress(),
    val selectedDocumentUri: Uri? = null,
    val selectedDocumentName: String? = null,
    val selectedDocumentMimeType: String? = null,
    val discoveryState: ExperimentEngineState = ExperimentEngineState()
) {
    val isHostCompatible: Boolean
        get() = capabilities?.compatibilityStatus == HostCompatibilityStatus.FULLY_SUPPORTED

    val canPrint: Boolean
        get() = primaryPrinterDevice != null &&
            primaryPrinterDevice.hasPermission &&
            connectionState != PrinterConnectionState.RECOVERY_REQUIRED
}

enum class PermissionStatus {
    NOT_REQUESTED,
    PENDING,
    GRANTED,
    DENIED
}
