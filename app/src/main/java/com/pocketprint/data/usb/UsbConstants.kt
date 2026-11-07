package com.pocketprint.data.usb

object UsbConstants {
    const val ACTION_USB_PERMISSION = "com.pocketprint.USB_PERMISSION"

    // HP Vendor ID (Hewlett-Packard)
    const val VID_HEWLETT_PACKARD = 0x03F0

    // USB Base Class Codes
    const val CLASS_PER_INTERFACE = 0x00
    const val CLASS_AUDIO = 0x01
    const val CLASS_COMM = 0x02
    const val CLASS_HID = 0x03
    const val CLASS_PHYSICAL = 0x05
    const val CLASS_STILL_IMAGE = 0x06
    const val CLASS_PRINTER = 0x07
    const val CLASS_MASS_STORAGE = 0x08
    const val CLASS_HUB = 0x09
    const val CLASS_CDC_DATA = 0x0A
    const val CLASS_SMART_CARD = 0x0B
    const val CLASS_CONTENT_SECURITY = 0x0D
    const val CLASS_VIDEO = 0x0E
    const val CLASS_PERSONAL_HEALTHCARE = 0x0F
    const val CLASS_AUDIO_VIDEO = 0x10
    const val CLASS_BILLBOARD = 0x11
    const val CLASS_USB_TYPE_C_BRIDGE = 0x12
    const val CLASS_DIAGNOSTIC_DEVICE = 0xDC
    const val CLASS_WIRELESS_CONTROLLER = 0xE0
    const val CLASS_MISCELLANEOUS = 0xEF
    const val CLASS_APPLICATION_SPECIFIC = 0xFE
    const val CLASS_VENDOR_SPECIFIC = 0xFF

    // USB Printer Class (0x07) Subclasses
    const val SUBCLASS_PRINTER = 0x01

    // USB Printer Class (0x07) Protocols
    const val PROTOCOL_PRINTER_UNIDIRECTIONAL = 0x01
    const val PROTOCOL_PRINTER_BIDIRECTIONAL = 0x02
    const val PROTOCOL_PRINTER_1284_4 = 0x03
    const val PROTOCOL_PRINTER_VENDOR = 0xFF

    // USB Printer Class-Specific Requests (USB Device Class Definition for Printing Devices v1.1)
    // bmRequestType: 0xA1 = Direction: Device-to-Host (0x80) | Type: Class (0x20) | Recipient: Interface (0x01)
    const val REQ_TYPE_PRINTER_GET_DEVICE_ID = 0xA1
    const val REQ_PRINTER_GET_DEVICE_ID = 0x00

    // bmRequestType: 0xA1
    const val REQ_PRINTER_GET_PORT_STATUS = 0x01

    // bmRequestType: 0x21 = Direction: Host-to-Device (0x00) | Type: Class (0x20) | Recipient: Interface (0x01)
    const val REQ_TYPE_PRINTER_SOFT_RESET = 0x21
    const val REQ_PRINTER_SOFT_RESET = 0x02
}
