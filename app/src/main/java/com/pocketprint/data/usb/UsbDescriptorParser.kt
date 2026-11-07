package com.pocketprint.data.usb

import android.hardware.usb.UsbConstants as AndroidUsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbEndpointInfo
import com.pocketprint.data.model.UsbInterfaceInfo

object UsbDescriptorParser {

    fun parseDevice(device: UsbDevice, hasPermission: Boolean): UsbDeviceInfo {
        val interfaces = mutableListOf<UsbInterfaceInfo>()
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            interfaces.add(parseInterface(iface))
        }

        val isHp = device.vendorId == UsbConstants.VID_HEWLETT_PACKARD
        val isPrinterCandidate = isHp ||
                device.deviceClass == UsbConstants.CLASS_PRINTER ||
                interfaces.any { it.isPrinterClass || it.hasBulkPrintEndpoints }

        var manufacturer: String? = null
        var product: String? = null
        var serial: String? = null
        var version: String? = null

        if (hasPermission) {
            try {
                manufacturer = device.manufacturerName
                product = device.productName
                serial = device.serialNumber
                version = device.version
            } catch (e: SecurityException) {
                // Permission might have just been revoked or system rejected
            }
        }

        return UsbDeviceInfo(
            deviceName = device.deviceName,
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            vendorIdHex = String.format("0x%04X", device.vendorId),
            productId = device.productId,
            productIdHex = String.format("0x%04X", device.productId),
            deviceClass = device.deviceClass,
            deviceClassHex = String.format("0x%02X", device.deviceClass),
            deviceClassLabel = getClassLabel(device.deviceClass),
            deviceSubclass = device.deviceSubclass,
            deviceProtocol = device.deviceProtocol,
            manufacturerName = manufacturer,
            productName = product,
            serialNumber = serial,
            version = version,
            interfaceCount = device.interfaceCount,
            interfaces = interfaces,
            hasPermission = hasPermission,
            isHpDevice = isHp,
            isPrinterCandidate = isPrinterCandidate
        )
    }

    private fun parseInterface(iface: UsbInterface): UsbInterfaceInfo {
        val endpoints = mutableListOf<UsbEndpointInfo>()
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            endpoints.add(parseEndpoint(ep))
        }

        val isPrinter = iface.interfaceClass == UsbConstants.CLASS_PRINTER

        return UsbInterfaceInfo(
            id = iface.id,
            interfaceClass = iface.interfaceClass,
            interfaceClassHex = String.format("0x%02X", iface.interfaceClass),
            interfaceClassLabel = getClassLabel(iface.interfaceClass),
            interfaceSubclass = iface.interfaceSubclass,
            interfaceSubclassLabel = getInterfaceSubclassLabel(iface.interfaceClass, iface.interfaceSubclass),
            interfaceProtocol = iface.interfaceProtocol,
            interfaceProtocolLabel = getInterfaceProtocolLabel(iface.interfaceClass, iface.interfaceProtocol),
            name = iface.name,
            endpointCount = iface.endpointCount,
            endpoints = endpoints,
            isPrinterClass = isPrinter
        )
    }

    private fun parseEndpoint(ep: UsbEndpoint): UsbEndpointInfo {
        val dirLabel = when (ep.direction) {
            AndroidUsbConstants.USB_DIR_IN -> "IN"
            AndroidUsbConstants.USB_DIR_OUT -> "OUT"
            else -> "UNKNOWN(${ep.direction})"
        }

        val typeLabel = when (ep.type) {
            AndroidUsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
            AndroidUsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
            AndroidUsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
            AndroidUsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
            else -> "UNKNOWN(${ep.type})"
        }

        return UsbEndpointInfo(
            address = ep.address,
            endpointNumber = ep.endpointNumber,
            direction = ep.direction,
            directionLabel = dirLabel,
            type = ep.type,
            typeLabel = typeLabel,
            maxPacketSize = ep.maxPacketSize,
            interval = ep.interval
        )
    }

    fun getClassLabel(classId: Int): String {
        return when (classId) {
            UsbConstants.CLASS_PER_INTERFACE -> "Per-Interface (0x00)"
            UsbConstants.CLASS_AUDIO -> "Audio (0x01)"
            UsbConstants.CLASS_COMM -> "Communications / CDC (0x02)"
            UsbConstants.CLASS_HID -> "Human Interface Device / HID (0x03)"
            UsbConstants.CLASS_PHYSICAL -> "Physical (0x05)"
            UsbConstants.CLASS_STILL_IMAGE -> "Still Image / Scanner (0x06)"
            UsbConstants.CLASS_PRINTER -> "Printer (0x07)"
            UsbConstants.CLASS_MASS_STORAGE -> "Mass Storage (0x08)"
            UsbConstants.CLASS_HUB -> "USB Hub (0x09)"
            UsbConstants.CLASS_CDC_DATA -> "CDC Data (0x0A)"
            UsbConstants.CLASS_SMART_CARD -> "Smart Card (0x0B)"
            UsbConstants.CLASS_VIDEO -> "Video (0x0E)"
            UsbConstants.CLASS_WIRELESS_CONTROLLER -> "Wireless Controller (0xE0)"
            UsbConstants.CLASS_MISCELLANEOUS -> "Miscellaneous (0xEF)"
            UsbConstants.CLASS_APPLICATION_SPECIFIC -> "App Specific (0xFE)"
            UsbConstants.CLASS_VENDOR_SPECIFIC -> "Vendor Specific (0xFF)"
            else -> String.format("Class 0x%02X", classId)
        }
    }

    private fun getInterfaceSubclassLabel(classId: Int, subclassId: Int): String {
        if (classId == UsbConstants.CLASS_PRINTER) {
            return when (subclassId) {
                UsbConstants.SUBCLASS_PRINTER -> "Printers (0x01)"
                else -> String.format("Unknown Subclass (0x%02X)", subclassId)
            }
        }
        return String.format("Subclass 0x%02X", subclassId)
    }

    private fun getInterfaceProtocolLabel(classId: Int, protocolId: Int): String {
        if (classId == UsbConstants.CLASS_PRINTER) {
            return when (protocolId) {
                UsbConstants.PROTOCOL_PRINTER_UNIDIRECTIONAL -> "Unidirectional (0x01)"
                UsbConstants.PROTOCOL_PRINTER_BIDIRECTIONAL -> "Bi-directional (0x02)"
                UsbConstants.PROTOCOL_PRINTER_1284_4 -> "IEEE 1284.4 1284.4 4-wire (0x03)"
                UsbConstants.PROTOCOL_PRINTER_VENDOR -> "Vendor-specific (0xFF)"
                else -> String.format("Protocol 0x%02X", protocolId)
            }
        }
        return String.format("Protocol 0x%02X", protocolId)
    }

    /**
     * Parses the IEEE 1284 Device ID raw bytes received from GET_DEVICE_ID control transfer.
     * The first 2 bytes are big-endian length prefix.
     */
    fun parse1284DeviceId(bytes: ByteArray, length: Int): String? {
        if (length < 2) return null
        // Big endian length prefix
        val stringLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val actualLength = Math.min(stringLength, length - 2)
        if (actualLength <= 0) return null

        return try {
            String(bytes, 2, actualLength, Charsets.US_ASCII).trim()
        } catch (e: Exception) {
            null
        }
    }
}
