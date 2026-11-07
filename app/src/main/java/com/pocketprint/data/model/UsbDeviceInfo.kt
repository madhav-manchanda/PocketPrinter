package com.pocketprint.data.model

data class UsbDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val vendorIdHex: String,
    val productId: Int,
    val productIdHex: String,
    val deviceClass: Int,
    val deviceClassHex: String,
    val deviceClassLabel: String,
    val deviceSubclass: Int,
    val deviceProtocol: Int,
    val manufacturerName: String?,
    val productName: String?,
    val serialNumber: String?,
    val version: String?,
    val interfaceCount: Int,
    val interfaces: List<UsbInterfaceInfo>,
    val hasPermission: Boolean,
    val isHpDevice: Boolean,
    val isPrinterCandidate: Boolean,
    val probeResult: UsbProbeResult? = null
) {
    val displayName: String
        get() {
            return when {
                !productName.isNullOrBlank() && !manufacturerName.isNullOrBlank() -> "$manufacturerName $productName"
                !productName.isNullOrBlank() -> productName
                isHpDevice -> "HP USB Printer / Device ($vendorIdHex:$productIdHex)"
                isPrinterCandidate -> "USB Printer Device ($vendorIdHex:$productIdHex)"
                else -> "USB Device ($vendorIdHex:$productIdHex)"
            }
        }

    val primaryPrinterInterface: UsbInterfaceInfo?
        get() = interfaces.firstOrNull { it.isPrinterClass } ?: interfaces.firstOrNull { it.hasBulkPrintEndpoints }
}
