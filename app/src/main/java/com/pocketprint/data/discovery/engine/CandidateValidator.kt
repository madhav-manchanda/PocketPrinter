package com.pocketprint.data.discovery.engine

import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.model.FramingStyle
import com.pocketprint.data.discovery.model.ValidationResult

/**
 * Validates generated print payloads before any transmission over USB.
 * Rejects corrupt, malformed, or unverified byte buffers locally.
 */
object CandidateValidator {

    private const val MIN_PAYLOAD_SIZE = 128
    private const val MAX_PAYLOAD_SIZE = 25 * 1024 * 1024 // 25 MB max

    fun validate(candidate: DiscoveryCandidate, payload: ByteArray): ValidationResult {
        if (payload.size < MIN_PAYLOAD_SIZE) {
            return ValidationResult.invalid("Payload too small (${payload.size} bytes). Minimum is $MIN_PAYLOAD_SIZE bytes.")
        }
        if (payload.size > MAX_PAYLOAD_SIZE) {
            return ValidationResult.invalid("Payload exceeds safety threshold (${payload.size} bytes). Maximum is $MAX_PAYLOAD_SIZE bytes.")
        }

        val textSuffix = String(payload.takeLast(minOf(256, payload.size)).toByteArray(), Charsets.ISO_8859_1)
        val uel = "\u001b%-12345X".toByteArray(Charsets.ISO_8859_1)
        val pjlEnter = "@PJL ENTER LANGUAGE=PCL3GUI".toByteArray(Charsets.ISO_8859_1)

        // 1. Framing style validation
        when (candidate.framingStyle) {
            FramingStyle.PJL_ENTER_PCL3GUI -> {
                if (!payloadContains(payload, uel)) {
                    return ValidationResult.invalid("Missing standard PJL UEL header marker")
                }
                if (!payloadContains(payload, pjlEnter)) {
                    return ValidationResult.invalid("Missing @PJL ENTER LANGUAGE=PCL3GUI transition command")
                }
                if (!textSuffix.contains("\u001b%-12345X")) {
                    return ValidationResult.invalid("Missing terminal UEL job separator")
                }
            }
            FramingStyle.RAW_UEL -> {
                if (!payloadContains(payload, uel) &&
                    !payloadStartsWith(payload, byteArrayOf(0x1B, 'E'.code.toByte()))
                ) {
                    return ValidationResult.invalid("Missing UEL or printer reset (ESC E) prefix")
                }
                if (!textSuffix.contains("\u001b%-12345X") && !textSuffix.contains("\u001bE")) {
                    return ValidationResult.invalid("Missing UEL or printer reset (ESC E) suffix")
                }
            }
            FramingStyle.NONE -> {
                if (!payloadStartsWith(payload, byteArrayOf(0x1B))) {
                    return ValidationResult.invalid("Direct PCL stream must begin with ESC command")
                }
            }
        }

        // 2. Protocol-specific raster command validation
        when (candidate.protocol) {
            DiscoveryProtocol.PCL3GUI_MODE9 -> {
                if (!payloadContains(payload, "\u001b*b9M".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCL3GUI Mode 9 candidate must specify ESC * b 9 M compression")
                }
                if (!payloadContains(payload, "\u001b*g8W".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCL3GUI Mode 9 requires 8-byte Configure Raster Data (ESC * g 8 W)")
                }
            }
            DiscoveryProtocol.PCL3GUI_MODE2 -> {
                if (!payloadContains(payload, "\u001b*b2M".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCL3GUI Mode 2 candidate must specify ESC * b 2 M (TIFF PackBits)")
                }
            }
            DiscoveryProtocol.PCL3GUI_MODE0 -> {
                if (!payloadContains(payload, "\u001b*b0M".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCL3GUI Mode 0 candidate must specify ESC * b 0 M (Uncompressed)")
                }
            }
            DiscoveryProtocol.PCL_SIMPLE_RASTER -> {
                if (!payloadContains(payload, "\u001b*r1A".toByteArray(Charsets.ISO_8859_1)) &&
                    !payloadContains(payload, "\u001b*r0A".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("Standard PCL3 requires start raster graphics command (ESC * r 1 A)")
                }
                if (!payloadContains(payload, "\u001b*rB".toByteArray(Charsets.ISO_8859_1)) &&
                    !payloadContains(payload, "\u001b*rC".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("Standard PCL3 requires end raster graphics command (ESC * r B)")
                }
            }
            DiscoveryProtocol.PCLM_RAW,
            DiscoveryProtocol.PCLM_WRAPPED -> {
                if (!payloadContains(payload, "%PDF-".toByteArray(Charsets.ISO_8859_1)) &&
                    !payloadContains(payload, "/PCLm".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCLm candidate must contain valid PDF / PCLm stream marker")
                }
            }
            DiscoveryProtocol.PCL3GUI2_K_MODE9 -> {
                // Pcl3Gui2 stream: requires Mode 9 compression command and 18-byte CRD marker.
                // The CRD sequence starts with ESC*g12W (0x1b 0x2a 0x67 0x31 0x32 0x57).
                if (!payloadContains(payload, "\u001b*b9M".toByteArray(Charsets.ISO_8859_1))) {
                    return ValidationResult.invalid("PCL3GUI2 K-only Mode 9 must contain ESC*b9M compression command")
                }
                // ESC*g12W — 6-byte CRD command prefix from crd_sequence_k
                val crdMarker = byteArrayOf(0x1b, 0x2a, 0x67, 0x31, 0x32, 0x57)
                if (!payloadContains(payload, crdMarker)) {
                    return ValidationResult.invalid("PCL3GUI2 K-only Mode 9 must contain 18-byte CRD block (ESC*g12W)")
                }
            }
            DiscoveryProtocol.PCL3GUI2_COLOR_MODE10 -> {
                // Pcl3Gui2 Dual-Plane Color stream: requires Mode 10 compression command and 26-byte CRD marker.
                // The CRD sequence starts with ESC*g20W (0x1b 0x2a 0x67 0x32 0x30 0x57).
                val crdMarker = byteArrayOf(0x1b, 0x2a, 0x67, 0x32, 0x30, 0x57)
                if (!payloadContains(payload, crdMarker)) {
                    return ValidationResult.invalid("PCL3GUI2 Dual-Plane Color must contain 26-byte CRD block (ESC*g20W)")
                }
                // Mode 10 is declared in the CRD component. HPLIP's dual-plane
                // stream uses V/W row records and does not emit ESC*b10M.
            }
        }

        return ValidationResult.valid(payload, "Payload passed structural validation: ${payload.size} bytes")
    }

    private fun payloadContains(payload: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.isEmpty() || payload.size < pattern.size) return false
        val max = payload.size - pattern.size  // search entire payload (PCL3GUI2 has 10k null prefix)
        for (i in 0..max) {
            var match = true
            for (j in pattern.indices) {
                if (payload[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return true
        }
        return false
    }

    private fun payloadStartsWith(payload: ByteArray, pattern: ByteArray): Boolean {
        if (payload.size < pattern.size) return false
        return pattern.indices.all { payload[it] == pattern[it] }
    }
}
