package com.pocketprint.data.device

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.File

enum class HostCompatibilityStatus {
    FULLY_SUPPORTED,
    USB_HOST_FEATURE_MISSING,
    USB_MANAGER_UNAVAILABLE
}

data class DeviceCapabilities(
    val manufacturer: String,
    val model: String,
    val brand: String,
    val product: String,
    val androidVersion: String,
    val apiLevel: Int,
    val hasUsbHostFeature: Boolean,
    val isUsbManagerAvailable: Boolean,
    val kernelUsbSysfsReadable: Boolean,
    val compatibilityStatus: HostCompatibilityStatus,
    val statusSummary: String
)

class DeviceCapabilityManager(private val context: Context) {

    fun detectCapabilities(): DeviceCapabilities {
        val packageManager = context.packageManager
        val hasUsbHost = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val isUsbManagerAvailable = usbManager != null

        // Check whether kernel exposes sysfs USB bus directory
        val usbDir = File("/sys/bus/usb/devices")
        val sysfsReadable = usbDir.exists() && usbDir.canRead()

        val status = when {
            !isUsbManagerAvailable -> HostCompatibilityStatus.USB_MANAGER_UNAVAILABLE
            !hasUsbHost -> HostCompatibilityStatus.USB_HOST_FEATURE_MISSING
            else -> HostCompatibilityStatus.FULLY_SUPPORTED
        }

        val summary = when (status) {
            HostCompatibilityStatus.FULLY_SUPPORTED -> 
                "USB Host API supported natively on this Android device."
            HostCompatibilityStatus.USB_HOST_FEATURE_MISSING -> 
                "Warning: System does not report 'android.hardware.usb.host' feature flag."
            HostCompatibilityStatus.USB_MANAGER_UNAVAILABLE -> 
                "Critical: Android UsbManager system service is unavailable."
        }

        return DeviceCapabilities(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            brand = Build.BRAND,
            product = Build.PRODUCT,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            hasUsbHostFeature = hasUsbHost,
            isUsbManagerAvailable = isUsbManagerAvailable,
            kernelUsbSysfsReadable = sysfsReadable,
            compatibilityStatus = status,
            statusSummary = summary
        )
    }
}
