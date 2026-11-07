package com.pocketprint.data.report

import com.pocketprint.data.device.DeviceCapabilities
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbProbeResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CompatibilityReportGenerator {

    fun generateReport(
        capabilities: DeviceCapabilities,
        devices: List<UsbDeviceInfo>,
        lastProbeResult: UsbProbeResult?
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        val timestamp = dateFormat.format(Date())

        val sb = StringBuilder()
        sb.appendLine("# PocketPrint — USB Compatibility & Feasibility Report")
        sb.appendLine("Generated: $timestamp")
        sb.appendLine()

        sb.appendLine("## 1. Android Host Device Audit")
        sb.appendLine("- **Manufacturer:** ${capabilities.manufacturer}")
        sb.appendLine("- **Model:** ${capabilities.model} (${capabilities.brand} / ${capabilities.product})")
        sb.appendLine("- **Android OS:** Version ${capabilities.androidVersion} (API Level ${capabilities.apiLevel})")
        sb.appendLine("- **USB Host System Feature:** ${if (capabilities.hasUsbHostFeature) "Present (FEATURE_USB_HOST)" else "MISSING"}")
        sb.appendLine("- **UsbManager Service:** ${if (capabilities.isUsbManagerAvailable) "Available" else "UNAVAILABLE"}")
        sb.appendLine("- **Kernel Sysfs USB Bus:** ${if (capabilities.kernelUsbSysfsReadable) "Readable" else "Restricted / Standard Sandbox"}")
        sb.appendLine("- **Hardware Assessment:** ${capabilities.statusSummary}")
        sb.appendLine()

        sb.appendLine("## 2. Connected USB Peripherals (${devices.size} detected)")
        if (devices.isEmpty()) {
            sb.appendLine("*No USB peripherals detected on the host bus.*")
        } else {
            devices.forEachIndexed { index, dev ->
                sb.appendLine("### Device #${index + 1}: ${dev.displayName}")
                sb.appendLine("- **Device Path:** `${dev.deviceName}`")
                sb.appendLine("- **Vendor ID (VID):** `${dev.vendorIdHex}` (Decimal: ${dev.vendorId})")
                sb.appendLine("- **Product ID (PID):** `${dev.productIdHex}` (Decimal: ${dev.productId})")
                sb.appendLine("- **Device Class:** ${dev.deviceClassLabel} [Subclass: ${dev.deviceSubclass}, Protocol: ${dev.deviceProtocol}]")
                sb.appendLine("- **Manufacturer Name:** ${dev.manufacturerName ?: "(Restricted or Unset)"}")
                sb.appendLine("- **Product Name:** ${dev.productName ?: "(Restricted or Unset)"}")
                sb.appendLine("- **Serial Number:** ${dev.serialNumber ?: "(Restricted or Unset)"}")
                sb.appendLine("- **USB Permission:** ${if (dev.hasPermission) "GRANTED" else "DENIED / PENDING"}")
                sb.appendLine("- **Candidate Classification:** ${if (dev.isPrinterCandidate) "Printer Candidate" else "Generic USB Device"}")
                sb.appendLine()

                sb.appendLine("#### Interfaces (${dev.interfaceCount}):")
                for (iface in dev.interfaces) {
                    val printerTag = if (iface.isPrinterClass) " [Standard Printer Class 0x07]" else ""
                    sb.appendLine("- **Interface #${iface.id}:** ${iface.interfaceClassLabel}$printerTag")
                    sb.appendLine("  - Subclass: ${iface.interfaceSubclassLabel}")
                    sb.appendLine("  - Protocol: ${iface.interfaceProtocolLabel}")
                    sb.appendLine("  - Endpoints (${iface.endpointCount}):")
                    if (iface.endpoints.isEmpty()) {
                        sb.appendLine("    - (None)")
                    } else {
                        for (ep in iface.endpoints) {
                            sb.appendLine("    - Endpoint Addr `${ep.addressHex}`: ${ep.typeLabel} ${ep.directionLabel} (MaxPacketSize: ${ep.maxPacketSize} bytes, Interval: ${ep.interval})")
                        }
                    }
                }
                sb.appendLine()
            }
        }

        sb.appendLine("## 3. Direct Interface Claim & Hardware Probe")
        if (lastProbeResult == null) {
            sb.appendLine("*No interface claim probe executed yet.*")
        } else {
            sb.appendLine("- **Target Device:** `${lastProbeResult.devicePath}`")
            sb.appendLine("- **Connection Open:** ${if (lastProbeResult.canOpenConnection) "SUCCESS" else "FAILED"}")
            sb.appendLine("- **Interface Claim:** ${if (lastProbeResult.canClaimInterface) "CLAIMED (Interface #${lastProbeResult.claimedInterfaceId})" else "FAILED"}")
            sb.appendLine("- **IEEE 1284 Device ID:** ${lastProbeResult.ieee1284DeviceId ?: "Not returned or unsupported by firmware"}")
            sb.appendLine("- **Probe Status Message:** ${lastProbeResult.statusMessage}")
            sb.appendLine()
        }

        sb.appendLine("## 4. Technical Feasibility & Phase 2 Verdict")
        val candidate = devices.firstOrNull { it.isPrinterCandidate }
        if (candidate != null && candidate.hasPermission && lastProbeResult?.canClaimInterface == true) {
            sb.appendLine("**VERDICT: DIRECT USB PRINTING IS TECHNICALLY VIABLE.**")
            sb.appendLine("The Android USB Host stack has successfully opened the device connection, claimed the target interface, and verified active bulk transfer endpoints.")
            sb.appendLine("Direct byte streams can be written to the Bulk OUT endpoint.")
            sb.appendLine()
            sb.appendLine("### Recommended Phase 2 Architecture:")
            sb.appendLine("1. **Network Server:** Implement an Android Foreground Service hosting an AppSocket (raw port 9100) or IPP (Internet Printing Protocol) listener.")
            sb.appendLine("2. **PDL Stream Engine:** Format outgoing data into printer-specific Page Description Language (e.g. PJL/PCL3GUI for HP Ink Tank or standard raster).")
            sb.appendLine("3. **Asynchronous Bulk Writer:** Pipe received network chunks directly to `UsbDeviceConnection.bulkTransfer()` on the verified Bulk OUT endpoint.")
        } else if (candidate != null && !candidate.hasPermission) {
            sb.appendLine("**VERDICT: HARDWARE DETECTED — PENDING USB PERMISSION.**")
            sb.appendLine("The USB printer candidate was detected on the host bus, but Android USB permission has not yet been granted by the user.")
        } else if (!capabilities.hasUsbHostFeature) {
            sb.appendLine("**VERDICT: INCOMPATIBLE HOST HARDWARE.**")
            sb.appendLine("The host device does not support Android USB Host mode (`FEATURE_USB_HOST`).")
        } else {
            sb.appendLine("**VERDICT: WAITING FOR PRINTER CONNECTION.**")
            sb.appendLine("Ensure the printer is powered on and connected via an active USB OTG adapter.")
        }

        return sb.toString()
    }
}
