package com.pocketprint.data.discovery.model

import java.security.MessageDigest

/**
 * Protocol family for candidate experiments.
 */
enum class DiscoveryProtocol(val displayName: String) {
    PCL3GUI2_K_MODE9("PCL3GUI2 K-only Mode 9 (Pcl3Gui2 — HPLIP Ink Tank path)"),
    PCL3GUI2_COLOR_MODE10("PCL3GUI2 Dual-Plane Color (K Mode 9 + RGB Mode 10 — HPLIP Ink Tank path)"),
    PCL3GUI_MODE0("PCL3GUI Mode 0 (Uncompressed Raster)"),
    PCL3GUI_MODE2("PCL3GUI Mode 2 (PackBits / TIFF Compression)"),
    PCL3GUI_MODE9("PCL3GUI Mode 9 (Delta Row + 8-byte CRD)"),
    PCL_SIMPLE_RASTER("Standard PCL3 Simple Raster (ESC * r 1 A)"),
    PCLM_RAW("PCLm Direct Stream (Validated PDF)"),
    PCLM_WRAPPED("PCLm Raster with PJL Framing")
}

enum class DiscoveryColorMode(val displayName: String, val bitsPerPixel: Int) {
    MONOCHROME_1BIT("Monochrome 1-bit", 1),
    GRAYSCALE_8BIT("Grayscale 8-bit", 8),
    COLOR_24BIT_RGB("Full Color 24-bit RGB", 24)
}

enum class FramingStyle(val displayName: String) {
    PJL_ENTER_PCL3GUI("PJL Envelope (@PJL ENTER LANGUAGE=PCL3GUI)"),
    RAW_UEL("Raw UEL Escape (\\x1B%-12345X)"),
    NONE("Direct Stream without PJL")
}

enum class PhysicalOutcome(val displayName: String) {
    UNTESTED("Not Yet Executed"),
    AWAITING_VERIFICATION("Transmitted — Awaiting Physical Confirmation"),
    SUCCESS_PAGE_PRINTED("Physical Page Successfully Printed"),
    BLANK_PAGE_PRINTED("Paper Fed but Page Blank"),
    PARTIAL_PRINT("Partial Output or Cut Off"),
    PRINTER_HARDWARE_ERROR("Printer Error / Blinking Light / Jam"),
    NOTHING_HAPPENED("Nothing Happened (Data Ignored)")
}

enum class FailureCategory(val displayName: String) {
    NONE("No Failure"),
    USB_CONNECTION_FAILURE("USB Connection Failure"),
    USB_PERMISSION_DENIED("USB Permission Denied"),
    INTERFACE_CLAIM_FAILURE("Interface Claim Failure"),
    USB_TRANSFER_ERROR("Bulk Transfer Timeout / I/O Error"),
    UNSUPPORTED_PROTOCOL("Protocol Rejected by Firmware"),
    INVALID_PAYLOAD("Payload Validation Error"),
    PRINTER_BUSY("Printer Device Busy"),
    PRINTER_ERROR_STATE("Printer Hardware Error State"),
    PHYSICAL_VERIFICATION_FAILED("Physical Output Rejected by Operator")
}

/**
 * Encapsulates an automated candidate configuration for physical testing.
 */
data class DiscoveryCandidate(
    val candidateId: String,
    val name: String,
    val protocol: DiscoveryProtocol,
    val colorMode: DiscoveryColorMode,
    val resolutionDpi: Int,
    val framingStyle: FramingStyle,
    val sendZlpFlush: Boolean,
    val performSoftReset: Boolean,
    val usbChunkSize: Int = 16384,
    val transferTimeoutMs: Int = 15000,
    val priority: Int = 100
)

/**
 * Pre-transmission validation outcome.
 */
data class ValidationResult(
    val isValid: Boolean,
    val byteCount: Int,
    val sha256Hex: String,
    val message: String
) {
    companion object {
        fun invalid(message: String): ValidationResult =
            ValidationResult(isValid = false, byteCount = 0, sha256Hex = "", message = message)

        fun valid(bytes: ByteArray, message: String): ValidationResult {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            val hex = hash.joinToString("") { "%02x".format(it) }
            return ValidationResult(isValid = true, byteCount = bytes.size, sha256Hex = hex, message = message)
        }
    }
}

/**
 * Record of an executed physical experiment attempt.
 */
data class ExperimentRunRecord(
    val candidateId: String,
    val candidateName: String,
    val timestamp: Long,
    val prePortStatus: Byte?,
    val postPortStatus: Byte?,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val transferDurationMs: Long,
    val payloadSha256: String,
    val usbStatusMessage: String,
    val hexHeaderSnippet: String = "",
    val failureCategory: FailureCategory = FailureCategory.NONE,
    var physicalOutcome: PhysicalOutcome = PhysicalOutcome.UNTESTED,
    var operatorNotes: String = ""
) {
    val transferSpeedKbps: Double
        get() = if (transferDurationMs > 0) (bytesTransferred / 1024.0) / (transferDurationMs / 1000.0) else 0.0
}

/**
 * Saved profile once physical output is confirmed.
 */
data class VerifiedPrinterProfile(
    val profileName: String,
    val vendorId: Int,
    val productId: Int,
    val printerModel: String,
    val candidate: DiscoveryCandidate,
    val verifiedTimestamp: Long,
    val verifiedSha256: String
)
