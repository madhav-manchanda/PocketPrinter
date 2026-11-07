package com.pocketprint.data.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pocketprint.data.device.DeviceCapabilities
import com.pocketprint.data.device.DeviceCapabilityManager
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbDeviceManager(private val context: Context) : UsbPermissionListener {

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val capabilityManager = DeviceCapabilityManager(context)
    val printerDetectionManager = PrinterDetectionManager()
    val connectionManager = UsbConnectionManager(usbManager, printerDetectionManager)
    val printerConnectionManager = PrinterConnectionManager(usbManager)

    private val permissionManager = UsbPermissionManager(context, usbManager, this)

    private val _capabilitiesFlow = MutableStateFlow(capabilityManager.detectCapabilities())
    val capabilitiesFlow: StateFlow<DeviceCapabilities> = _capabilitiesFlow.asStateFlow()

    private val _deviceListFlow = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val deviceListFlow: StateFlow<List<UsbDeviceInfo>> = _deviceListFlow.asStateFlow()

    private val _activeProbeResult = MutableStateFlow<UsbProbeResult?>(null)
    val activeProbeResult: StateFlow<UsbProbeResult?> = _activeProbeResult.asStateFlow()

    private var isHotplugReceiverRegistered = false

    private val hotplugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    DiagnosticLogger.usb("USB peripheral ATTACHED: ${device?.deviceName ?: "unknown"}")
                    if (device != null) {
                        logDeviceDetails(device)
                        printerConnectionManager.onDeviceAttached(device)
                    }
                    refreshDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    DiagnosticLogger.usb("USB peripheral DETACHED: ${device?.deviceName ?: "unknown"}")
                    _activeProbeResult.value = null
                    if (device != null) {
                        printerConnectionManager.onDeviceDetached(device)
                    } else {
                        printerConnectionManager.refresh("detach-unknown-device")
                    }
                    refreshDevices()
                }
            }
        }
    }

    override fun onPermissionGranted(device: UsbDevice) {
        printerConnectionManager.onPermissionResult(device, granted = true)
        refreshDevices()
    }

    override fun onPermissionDenied(device: UsbDevice?) {
        printerConnectionManager.onPermissionResult(device, granted = false)
        refreshDevices()
    }

    fun register() {
        _capabilitiesFlow.value = capabilityManager.detectCapabilities()
        permissionManager.register()

        if (!isHotplugReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    hotplugReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(hotplugReceiver, filter)
            }
            isHotplugReceiverRegistered = true
        }

        DiagnosticLogger.log("SYS", "Universal USB Host monitor active on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
        refreshDevices()
    }

    fun unregister() {
        permissionManager.unregister()

        // Deliberately do NOT unregister the hotplug BroadcastReceiver here.
        // USB monitoring is application-scoped and must remain active regardless
        // of Activity lifecycle, so attach/detach events are still received when
        // the Activity is in the background or the screen is off.
    }

    fun refreshDevices(): List<UsbDeviceInfo> {
        val deviceMap = usbManager.deviceList
        val list = mutableListOf<UsbDeviceInfo>()

        for ((_, device) in deviceMap) {
            val hasPermission = usbManager.hasPermission(device)
            val info = UsbDescriptorParser.parseDevice(device, hasPermission)
            list.add(info)
        }

        _deviceListFlow.value = list
        // Re-seed the authoritative link state on every enumeration so an
        // app restart or a missed broadcast cannot leave a stale connection.
        printerConnectionManager.refresh("enumerate")
        DiagnosticLogger.log("USB", "Enumerated ${list.size} USB device(s) on host bus")
        return list
    }

    fun requestPermission(devicePath: String) {
        val rawDevice = findRawDevice(devicePath)
        if (rawDevice == null) {
            DiagnosticLogger.usbError("Cannot request permission: device not found at $devicePath")
            return
        }
        permissionManager.requestPermission(rawDevice)
    }

    fun probeDevice(devicePath: String): UsbProbeResult {
        val rawDevice = findRawDevice(devicePath)
        if (rawDevice == null) {
            val res = UsbProbeResult(
                devicePath = devicePath,
                canOpenConnection = false,
                canClaimInterface = false,
                claimedInterfaceId = null,
                ieee1284DeviceId = null,
                manufacturerString = null,
                productString = null,
                serialNumberString = null,
                statusMessage = "Device not found on USB bus",
                isSuccess = false
            )
            _activeProbeResult.value = res
            return res
        }

        val result = connectionManager.probeDevice(rawDevice)
        _activeProbeResult.value = result
        return result
    }

    fun getRawDevice(devicePath: String): UsbDevice? {
        val map = usbManager.deviceList
        for ((_, dev) in map) {
            if (dev.deviceName == devicePath) {
                return dev
            }
        }
        return null
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Finds an attached printer device on the bus, regardless of permission state. */
    fun findAnyPrinterDevice(): UsbDevice? {
        val map = usbManager.deviceList
        return map.values.firstOrNull { device ->
            (0 until device.interfaceCount).any { index ->
                device.getInterface(index).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    /** Finds an authorized USB Printer Class device only. */
    fun findRawUsbDevice(): UsbDevice? {
        val map = usbManager.deviceList
        return map.values.firstOrNull { device ->
            usbManager.hasPermission(device) &&
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_PRINTER
                }
        }
    }

    private fun findRawDevice(devicePath: String): UsbDevice? = getRawDevice(devicePath)

    private fun logDeviceDetails(device: UsbDevice) {
        val vidHex = String.format("%04X", device.vendorId)
        val pidHex = String.format("%04X", device.productId)
        DiagnosticLogger.usb("Device name: ${device.deviceName}")
        DiagnosticLogger.usb("Vendor ID: 0x$vidHex (${device.vendorId})")
        DiagnosticLogger.usb("Product ID: 0x$pidHex (${device.productId})")
        DiagnosticLogger.usb("Interface count: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val classLabel = UsbDescriptorParser.getClassLabel(iface.interfaceClass)
            DiagnosticLogger.usb("Interface [${iface.id}] class: ${iface.interfaceClass} ($classLabel)")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                val dir = if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) "IN" else "OUT"
                val type = when (ep.type) {
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                    android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
                    else -> "ISOC"
                }
                DiagnosticLogger.usb("Endpoint: Addr 0x${String.format("%02X", ep.address)}, Type $type, Dir $dir, MaxPacket ${ep.maxPacketSize}")
            }
        }
    }
}
