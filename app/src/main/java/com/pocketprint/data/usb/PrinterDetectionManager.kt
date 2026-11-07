package com.pocketprint.data.usb

import android.hardware.usb.UsbConstants as AndroidUsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbEndpointInfo
import com.pocketprint.data.model.UsbInterfaceInfo

enum class PrinterClassification {
    STANDARD_PRINTER_CLASS,
    VENDOR_SPECIFIC_CANDIDATE,
    NON_PRINTER_USB_DEVICE
}

class PrinterDetectionManager {

    fun classifyDevice(device: UsbDeviceInfo): PrinterClassification {
        // 1. Check if device class is Printer (0x07) or any interface has class 0x07
        if (device.deviceClass == UsbConstants.CLASS_PRINTER ||
            device.interfaces.any { it.isPrinterClass }
        ) {
            return PrinterClassification.STANDARD_PRINTER_CLASS
        }

        // 2. Check if any interface has both Bulk OUT (print data) and Bulk IN (status telemetry)
        val hasBulkPair = device.interfaces.any { iface ->
            val hasBulkOut = iface.endpoints.any { it.typeLabel == "BULK" && it.directionLabel == "OUT" }
            val hasBulkIn = iface.endpoints.any { it.typeLabel == "BULK" && it.directionLabel == "IN" }
            hasBulkOut && hasBulkIn
        }

        // 3. Check for vendor-specific class with bulk endpoints or printer-like descriptors
        if (hasBulkPair) {
            return PrinterClassification.VENDOR_SPECIFIC_CANDIDATE
        }

        return PrinterClassification.NON_PRINTER_USB_DEVICE
    }

    fun isPrinterCandidate(device: UsbDeviceInfo): Boolean {
        val classification = classifyDevice(device)
        return classification == PrinterClassification.STANDARD_PRINTER_CLASS ||
                classification == PrinterClassification.VENDOR_SPECIFIC_CANDIDATE
    }

    /**
     * Inspects raw UsbDevice to find the best candidate interface for printer communication.
     */
    fun findPrinterInterface(device: UsbDevice): UsbInterface? {
        // Priority 1: Standard USB Printer Class (0x07)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.CLASS_PRINTER) {
                return iface
            }
        }

        // Priority 2: Any interface containing Bulk OUT and Bulk IN
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var hasBulkOut = false
            var hasBulkIn = false
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.type == AndroidUsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == AndroidUsbConstants.USB_DIR_OUT) hasBulkOut = true
                    if (ep.direction == AndroidUsbConstants.USB_DIR_IN) hasBulkIn = true
                }
            }
            if (hasBulkOut && hasBulkIn) {
                return iface
            }
        }

        // Fallback: First interface if available
        return if (device.interfaceCount > 0) device.getInterface(0) else null
    }
}
