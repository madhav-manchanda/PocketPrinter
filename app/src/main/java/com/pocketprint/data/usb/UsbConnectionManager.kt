package com.pocketprint.data.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.model.UsbProbeResult

class UsbConnectionManager(
    private val usbManager: UsbManager,
    private val printerDetectionManager: PrinterDetectionManager
) {

    /**
     * Executes a safe, read-only hardware probe to verify whether the USB interface can be claimed
     * and queried without sending arbitrary or unverified print bytes.
     */
    fun probeDevice(device: UsbDevice): UsbProbeResult {
        val devicePath = device.deviceName

        if (!usbManager.hasPermission(device)) {
            DiagnosticLogger.usbWarn("Cannot probe $devicePath: Permission not granted")
            return UsbProbeResult(
                devicePath = devicePath,
                canOpenConnection = false,
                canClaimInterface = false,
                claimedInterfaceId = null,
                ieee1284DeviceId = null,
                manufacturerString = null,
                productString = null,
                serialNumberString = null,
                statusMessage = "USB Permission required before opening connection",
                isSuccess = false
            )
        }

        var connection: UsbDeviceConnection? = null
        var claimedInterface: UsbInterface? = null
        var canClaim = false
        var ieee1284String: String? = null
        var mfgStr: String? = null
        var prodStr: String? = null
        var serialStr: String? = null

        try {
            connection = usbManager.openDevice(device)
            if (connection == null) {
                DiagnosticLogger.usbError("UsbManager.openDevice($devicePath) returned null")
                return UsbProbeResult(
                    devicePath = devicePath,
                    canOpenConnection = false,
                    canClaimInterface = false,
                    claimedInterfaceId = null,
                    ieee1284DeviceId = null,
                    manufacturerString = null,
                    productString = null,
                    serialNumberString = null,
                    statusMessage = "Failed to open UsbDeviceConnection (system returned null)",
                    isSuccess = false
                )
            }

            DiagnosticLogger.usb("UsbDeviceConnection established (FD: ${connection.fileDescriptor})")

            // Read string descriptors safely
            try {
                mfgStr = device.manufacturerName
                prodStr = device.productName
                serialStr = device.serialNumber
                DiagnosticLogger.usb("Descriptors -> Mfg: '$mfgStr', Product: '$prodStr', Serial: '$serialStr'")
            } catch (e: Exception) {
                DiagnosticLogger.usbWarn("Failed reading string descriptors: ${e.message}")
            }

            // Identify best candidate interface (Printer class 7 or bulk endpoints)
            val targetInterface = printerDetectionManager.findPrinterInterface(device)

            if (targetInterface != null) {
                canClaim = connection.claimInterface(targetInterface, true)
                if (canClaim) {
                    claimedInterface = targetInterface
                    DiagnosticLogger.usb("Interface ${targetInterface.id} (Class 0x${String.format("%02X", targetInterface.interfaceClass)}) claimed successfully!")

                    // Safe read-only standard IEEE 1284 GET_DEVICE_ID class request
                    if (targetInterface.interfaceClass == UsbConstants.CLASS_PRINTER) {
                        try {
                            val buffer = ByteArray(1024)
                            val bytesRead = connection.controlTransfer(
                                UsbConstants.REQ_TYPE_PRINTER_GET_DEVICE_ID,
                                UsbConstants.REQ_PRINTER_GET_DEVICE_ID,
                                0,
                                targetInterface.id,
                                buffer,
                                buffer.size,
                                2500
                            )

                            if (bytesRead > 2) {
                                ieee1284String = UsbDescriptorParser.parse1284DeviceId(buffer, bytesRead)
                                DiagnosticLogger.usb("IEEE 1284 Device ID query returned: $ieee1284String")
                            } else {
                                DiagnosticLogger.usb("Device ID request returned $bytesRead bytes (standard 1284 ID not populated)")
                            }
                        } catch (e: Exception) {
                            DiagnosticLogger.usbWarn("IEEE 1284 control transfer exception: ${e.message}")
                        }
                    }
                } else {
                    DiagnosticLogger.usbError("Failed to claim interface ${targetInterface.id}: Interface locked by kernel or another service")
                }
            } else {
                DiagnosticLogger.usbWarn("No suitable interface found on device to claim")
            }

            val statusMsg = when {
                canClaim && !ieee1284String.isNullOrBlank() -> 
                    "Direct USB interface claim confirmed and IEEE 1284 device profile retrieved."
                canClaim -> 
                    "Direct USB interface claim confirmed. Hardware bulk transfer pipes are accessible."
                else -> 
                    "Connection opened, but interface claim was rejected."
            }

            return UsbProbeResult(
                devicePath = devicePath,
                canOpenConnection = true,
                canClaimInterface = canClaim,
                claimedInterfaceId = claimedInterface?.id,
                ieee1284DeviceId = ieee1284String,
                manufacturerString = mfgStr,
                productString = prodStr,
                serialNumberString = serialStr,
                statusMessage = statusMsg,
                isSuccess = canClaim
            )

        } catch (e: Exception) {
            DiagnosticLogger.usbError("Unexpected error during USB connection probe: ${e.message}")
            return UsbProbeResult(
                devicePath = devicePath,
                canOpenConnection = connection != null,
                canClaimInterface = false,
                claimedInterfaceId = null,
                ieee1284DeviceId = null,
                manufacturerString = mfgStr,
                productString = prodStr,
                serialNumberString = serialStr,
                statusMessage = "Probe error: ${e.message}",
                isSuccess = false
            )
        } finally {
            // Guarantee resource release
            if (claimedInterface != null && connection != null) {
                try {
                    connection.releaseInterface(claimedInterface)
                    DiagnosticLogger.usb("Released interface ${claimedInterface.id} cleanly")
                } catch (e: Exception) {
                    // Ignore
                }
            }
            if (connection != null) {
                try {
                    connection.close()
                    DiagnosticLogger.usb("Closed UsbDeviceConnection cleanly")
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}
