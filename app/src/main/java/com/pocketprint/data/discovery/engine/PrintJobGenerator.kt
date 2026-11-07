package com.pocketprint.data.discovery.engine

import android.graphics.Bitmap
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.ValidationResult
import com.pocketprint.data.model.UsbDeviceInfo

/**
 * Common printing interface for candidate format generators.
 */
interface PrintJobGenerator {
    val formatId: String
    fun validateCapability(deviceInfo: UsbDeviceInfo): Boolean
    fun renderDocument(candidate: DiscoveryCandidate): Bitmap
    fun generatePayload(candidate: DiscoveryCandidate, bitmap: Bitmap): ByteArray
    fun validatePayload(candidate: DiscoveryCandidate, payload: ByteArray): ValidationResult
}
