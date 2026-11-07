package com.pocketprint.data.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.transport.PrinterConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Authoritative USB printer connection snapshot.
 *
 * [state] describes the physical/authorization link only. Job progress and
 * transfer errors are represented separately so the UI never shows
 * "Printer Disconnected" merely because one bulk transfer timed out.
 */
data class PrinterConnectionInfo(
    val state: PrinterConnectionState = PrinterConnectionState.NO_DEVICE,
    val deviceName: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
    val displayName: String? = null,
    val hasPermission: Boolean = false,
    val isPrinterClass: Boolean = false,
    val detail: String = "",
    val updatedAtMillis: Long = 0L
) {
    val isConnected: Boolean
        get() = state !in setOf(
            PrinterConnectionState.NO_DEVICE,
            PrinterConnectionState.DISCONNECTED
        )
}

/**
 * Single source of truth for printer attachment/permission/transfer state.
 *
 * Rules:
 * - Only real USB attach/detach/permission events move the link to
 *   NO_DEVICE / DEVICE_ATTACHED / DISCONNECTED / READY.
 * - A transfer failure may move the state to TRANSFER_ERROR or
 *   RECOVERY_REQUIRED, but never to DISCONNECTED while the device is still
 *   enumerated and authorized.
 * - Device references are never retained across detach.
 */
class PrinterConnectionManager(private val usbManager: UsbManager) {

    private val _state = MutableStateFlow(PrinterConnectionInfo())
    val state: StateFlow<PrinterConnectionInfo> = _state.asStateFlow()
    private var recoveryDeviceName: String? = null

    @Synchronized
    fun refresh(reason: String = "refresh") {
        val device = findPrimaryPrinter()
        if (device == null) {
            val current = _state.value
            if (current.state == PrinterConnectionState.DISCONNECTED && reason == "enumerate") {
                return
            }
            publish(
                PrinterConnectionInfo(
                    state = PrinterConnectionState.NO_DEVICE,
                    detail = "No USB printer enumerated ($reason)"
                )
            )
            return
        }
        if (recoveryDeviceName == device.deviceName) {
            publishForDevice(
                device,
                reason,
                forcedState = PrinterConnectionState.RECOVERY_REQUIRED,
                forcedDetail = "A partial or uncertain page may remain in the printer. Disconnect/reconnect or power-cycle before resubmitting."
            )
            return
        }
        publishForDevice(device, reason)
    }

    @Synchronized
    fun onDeviceAttached(device: UsbDevice) {
        recoveryDeviceName = null
        DiagnosticLogger.usb("[CONN] Device attached: ${device.deviceName}")
        publishForDevice(device, "attach")
    }

    @Synchronized
    fun onDeviceDetached(device: UsbDevice) {
        val current = _state.value
        val sameDevice = current.deviceName == null || current.deviceName == device.deviceName
        if (!sameDevice) {
            // A different USB device was removed; refresh to be safe.
            refresh("other-device-detach")
            return
        }
        DiagnosticLogger.usbWarn("[CONN] Printer detached: ${device.deviceName}")
        recoveryDeviceName = null
        publish(
            PrinterConnectionInfo(
                state = PrinterConnectionState.DISCONNECTED,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                displayName = device.productName,
                hasPermission = false,
                isPrinterClass = isPrinterDevice(device),
                detail = "USB device detached"
            )
        )
    }

    @Synchronized
    fun onPermissionResult(device: UsbDevice?, granted: Boolean) {
        if (device == null) {
            refresh("permission-result-null")
            return
        }
        if (granted) {
            publishForDevice(device, "permission-granted")
        } else {
            publish(
                PrinterConnectionInfo(
                    state = PrinterConnectionState.DEVICE_ATTACHED,
                    deviceName = device.deviceName,
                    vendorId = device.vendorId,
                    productId = device.productId,
                    displayName = device.productName,
                    hasPermission = false,
                    isPrinterClass = isPrinterDevice(device),
                    detail = "USB permission denied"
                )
            )
        }
    }

    /**
     * Applies a transport-level state transition, but refuses to downgrade a
     * physically present, authorized printer to DISCONNECTED.
     */
    @Synchronized
    fun onTransferState(state: PrinterConnectionState, detail: String = "") {
        val device = findPrimaryPrinter()
        val current = _state.value

        if (device == null) {
            publish(
                current.copy(
                    state = PrinterConnectionState.DISCONNECTED,
                    hasPermission = false,
                    detail = "Device no longer enumerated (${state.name})",
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
            return
        }

        if (state == PrinterConnectionState.RECOVERY_REQUIRED) {
            recoveryDeviceName = device.deviceName
        }

        val safeState = when (state) {
            PrinterConnectionState.NO_DEVICE,
            PrinterConnectionState.DISCONNECTED -> {
                if (usbManager.hasPermission(device)) {
                    PrinterConnectionState.READY
                } else {
                    PrinterConnectionState.DEVICE_ATTACHED
                }
            }
            else -> state
        }

        publishForDevice(
            device = device,
            reason = "transfer:${state.name}",
            forcedState = safeState,
            forcedDetail = detail
        )
    }

    @Synchronized
    fun markReady(detail: String = "Printer ready") {
        val device = findPrimaryPrinter()
        if (device == null) {
            refresh("mark-ready-no-device")
        } else if (recoveryDeviceName == device.deviceName) {
            refresh("mark-ready-blocked-by-recovery")
        } else {
            publishForDevice(device, "mark-ready", forcedState = PrinterConnectionState.READY, forcedDetail = detail)
        }
    }

    fun findPrimaryPrinter(): UsbDevice? {
        val devices = try {
            usbManager.deviceList.values
        } catch (e: Exception) {
            return null
        }
        return devices.firstOrNull { it.vendorId == HP_VENDOR_ID && isPrinterDevice(it) }
            ?: devices.firstOrNull { isPrinterDevice(it) }
    }

    fun findDevice(deviceName: String): UsbDevice? {
        return try {
            usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
        } catch (e: Exception) {
            null
        }
    }

    fun isDevicePresentAndAuthorized(deviceName: String?): Boolean {
        if (deviceName == null) return false
        val device = findDevice(deviceName) ?: return false
        return try {
            usbManager.hasPermission(device)
        } catch (e: Exception) {
            false
        }
    }

    fun isPrinterDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                return true
            }
        }
        return false
    }

    private fun publishForDevice(
        device: UsbDevice,
        reason: String,
        forcedState: PrinterConnectionState? = null,
        forcedDetail: String = ""
    ) {
        val hasPermission = try {
            usbManager.hasPermission(device)
        } catch (e: Exception) {
            false
        }
        val state = forcedState ?: when {
            hasPermission -> PrinterConnectionState.READY
            else -> PrinterConnectionState.DEVICE_ATTACHED
        }
        val detail = forcedDetail.ifBlank { defaultDetail(state, reason) }
        publish(
            PrinterConnectionInfo(
                state = state,
                deviceName = device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                displayName = device.productName,
                hasPermission = hasPermission,
                isPrinterClass = isPrinterDevice(device),
                detail = detail
            )
        )
    }

    private fun defaultDetail(state: PrinterConnectionState, reason: String): String = when (state) {
        PrinterConnectionState.READY -> "Printer authorized and ready"
        PrinterConnectionState.DEVICE_ATTACHED -> "USB permission required"
        PrinterConnectionState.INTERFACE_CLAIMED -> "USB interface claimed by printer"
        PrinterConnectionState.PRINTING -> "USB streaming in progress"
        PrinterConnectionState.PROCESSING -> "Printer is processing job"
        PrinterConnectionState.TRANSFER_ERROR -> "Last USB transfer failed; printer may still be ready"
        PrinterConnectionState.RECOVERY_REQUIRED -> "Last USB transfer outcome uncertain; manual recovery required"
        PrinterConnectionState.DISCONNECTED -> "Printer USB link lost"
        else -> reason
    }

    private fun publish(info: PrinterConnectionInfo) {
        val stamped = info.copy(updatedAtMillis = System.currentTimeMillis())
        val previous = _state.value
        _state.value = stamped
        if (previous.state != stamped.state || previous.deviceName != stamped.deviceName) {
            DiagnosticLogger.usb(
                "[CONN] ${previous.state} → ${stamped.state} " +
                "(${stamped.deviceName ?: "no-device"}): ${stamped.detail}"
            )
        }
    }

    companion object {
        const val HP_VENDOR_ID = 0x03F0
    }
}
