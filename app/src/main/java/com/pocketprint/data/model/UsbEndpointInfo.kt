package com.pocketprint.data.model

data class UsbEndpointInfo(
    val address: Int,
    val endpointNumber: Int,
    val direction: Int,
    val directionLabel: String,
    val type: Int,
    val typeLabel: String,
    val maxPacketSize: Int,
    val interval: Int
) {
    val addressHex: String = String.format("0x%02X", address)
    val summary: String = "$typeLabel $directionLabel (Addr: $addressHex, MaxPkt: $maxPacketSize bytes)"
}
