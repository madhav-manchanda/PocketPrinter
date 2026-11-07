package com.pocketprint.data.model

data class UsbProbeResult(
    val devicePath: String,
    val canOpenConnection: Boolean,
    val canClaimInterface: Boolean,
    val claimedInterfaceId: Int?,
    val ieee1284DeviceId: String?,
    val manufacturerString: String?,
    val productString: String?,
    val serialNumberString: String?,
    val statusMessage: String,
    val isSuccess: Boolean
)
