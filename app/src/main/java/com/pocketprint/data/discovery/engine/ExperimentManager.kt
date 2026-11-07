package com.pocketprint.data.discovery.engine

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.ExperimentRunRecord
import com.pocketprint.data.discovery.model.FailureCategory
import com.pocketprint.data.discovery.model.PhysicalOutcome
import com.pocketprint.data.discovery.model.VerifiedPrinterProfile
import com.pocketprint.data.discovery.registry.CompatibilityStatus
import com.pocketprint.data.discovery.registry.FormatRegistry
import com.pocketprint.data.discovery.storage.ExperimentHistoryStore
import com.pocketprint.data.discovery.storage.PrinterProfileStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.transport.StreamConfig
import com.pocketprint.data.print.transport.StreamProgressCallback
import com.pocketprint.data.print.transport.StreamResult
import com.pocketprint.data.print.transport.StreamSnapshot
import com.pocketprint.data.print.transport.UsbStreamWriter
import com.pocketprint.data.print.transport.UsbJobArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class ExperimentEngineState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val isAutoBatchActive: Boolean = false,
    val activeCandidate: DiscoveryCandidate? = null,
    val candidateIndex: Int = 0,
    val totalCandidates: Int = 0,
    val currentStepMessage: String = "Idle â€” Ready to Discover",
    val bytesTransferred: Long = 0,
    val totalBytes: Long = 0,
    val lastRunRecord: ExperimentRunRecord? = null,
    val verifiedProfile: VerifiedPrinterProfile? = null,
    val awaitingPhysicalVerification: Boolean = false,
    val hexHeaderSnippet: String = "",
    val runHistory: List<ExperimentRunRecord> = emptyList(),
    val totalFormatsRegistered: Int = 0,
    val totalUsbFormatsTested: Int = 0,
    val ieee1284Id: String = ""
)

/**
 * Controlled experimental engine for automated, comprehensive printer protocol discovery.
 * Implements Phases 2â€“8:
 * - Format registry evaluation
 * - Sequential controlled USB experiments (one at a time)
 * - Per-candidate USB telemetry logging
 * - Physical outcome verification
 * - Experiment history persistence
 * - Automatic verified-profile storage and reuse
 * - Pause / resume / stop / retry controls
 */
class ExperimentManager(
    private val usbManager: UsbManager,
    private val profileStore: PrinterProfileStore,
    private val historyStore: ExperimentHistoryStore? = null
) {

    private val isExecuting = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile
    private var activeWriter: UsbStreamWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val candidates: List<DiscoveryCandidate> = CandidateGenerator.generateCandidates()
    private val runHistory = mutableListOf<ExperimentRunRecord>()

    private val _engineState = MutableStateFlow(
        ExperimentEngineState(
            totalCandidates = candidates.size,
            totalFormatsRegistered = FormatRegistry.getAllEntries().size
        )
    )
    val engineState: StateFlow<ExperimentEngineState> = _engineState.asStateFlow()

    // Persisted run history loaded on init
    init {
        val saved = historyStore?.loadRecords() ?: emptyList()
        runHistory.addAll(saved)
        _engineState.value = _engineState.value.copy(runHistory = runHistory.toList())
    }

    fun getCandidateList(): List<DiscoveryCandidate> = candidates
    fun getRunHistory(): List<ExperimentRunRecord> = runHistory.toList()

    fun applyIeee1284Id(ieee1284: String) {
        FormatRegistry.evaluatePrinterDevice(ieee1284)
        DiagnosticLogger.log("DISCOVERY", "Registry updated from IEEE 1284: '$ieee1284'")
        _engineState.value = _engineState.value.copy(ieee1284Id = ieee1284)
    }

    // â”€â”€â”€ Auto Batch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun startAutoDiscovery(device: UsbDevice) {
        if (_engineState.value.isPaused) {
            resumeAutoDiscovery(device)
            return
        }
        DiagnosticLogger.log("DISCOVERY", "â–¶ Starting sequential discovery: ${candidates.size} candidates")
        _engineState.value = _engineState.value.copy(
            isAutoBatchActive = true,
            isPaused = false,
            candidateIndex = 0,
            currentStepMessage = "Starting Test 1 of ${candidates.size}â€¦"
        )
        runCandidateAtIndex(device, 0)
    }

    fun pauseAutoDiscovery() {
        _engineState.value = _engineState.value.copy(
            isPaused = true,
            isAutoBatchActive = false,
            currentStepMessage = "â¸ Paused â€” printer ready check required before resuming"
        )
        DiagnosticLogger.log("DISCOVERY", "â¸ Auto-discovery paused by operator")
    }

    fun resumeAutoDiscovery(device: UsbDevice) {
        val idx = _engineState.value.candidateIndex
        DiagnosticLogger.log("DISCOVERY", "â–¶ Resuming from candidate ${idx + 1} of ${candidates.size}")
        _engineState.value = _engineState.value.copy(
            isPaused = false,
            isAutoBatchActive = true,
            currentStepMessage = "Resuming Test ${idx + 1}â€¦"
        )
        runCandidateAtIndex(device, idx)
    }

    fun stopAutoDiscovery() {
        cancelRequested.set(true)
        activeWriter?.cancel()
        _engineState.value = _engineState.value.copy(
            isAutoBatchActive = false,
            isPaused = false,
            currentStepMessage = "â¹ Discovery stopped by operator."
        )
        DiagnosticLogger.log("DISCOVERY", "â¹ Auto-discovery stopped")
    }

    fun advanceToNextCandidate(device: UsbDevice, previousOutcome: PhysicalOutcome = PhysicalOutcome.NOTHING_HAPPENED) {
        val lastCand = _engineState.value.activeCandidate
        if (lastCand != null) {
            recordPhysicalOutcome(device, lastCand, previousOutcome, "Operator advanced to next test")
        }

        val nextIndex = _engineState.value.candidateIndex + 1
        if (nextIndex < candidates.size) {
            _engineState.value = _engineState.value.copy(candidateIndex = nextIndex)
            DiagnosticLogger.log("DISCOVERY", "â­ Advancing to candidate ${nextIndex + 1}/${candidates.size}: ${candidates[nextIndex].name}")
            runCandidateAtIndex(device, nextIndex)
        } else {
            DiagnosticLogger.log("DISCOVERY", "âœ“ All ${candidates.size} discovery candidates tested. Review history.")
            _engineState.value = _engineState.value.copy(
                isRunning = false,
                isAutoBatchActive = false,
                awaitingPhysicalVerification = false,
                currentStepMessage = "âœ“ All ${candidates.size} tests completed. No verified profile yet."
            )
        }
    }

    fun retryCurrentCandidate(device: UsbDevice) {
        val idx = _engineState.value.candidateIndex
        DiagnosticLogger.log("DISCOVERY", "â†© Retrying candidate ${idx + 1}/${candidates.size}")
        runCandidateAtIndex(device, idx)
    }

    fun runCandidateAtIndex(device: UsbDevice, index: Int) {
        if (index in candidates.indices) {
            scope.launch {
                executeCandidate(device, candidates[index])
            }
        }
    }

    // â”€â”€â”€ Core Experiment â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    suspend fun executeCandidate(
        device: UsbDevice,
        candidate: DiscoveryCandidate
    ): ExperimentRunRecord = withContext(Dispatchers.IO) {

        if (!isExecuting.compareAndSet(false, true)) {
            DiagnosticLogger.log("DISCOVERY", "Experiment rejected: another experiment is actively running")
            return@withContext ExperimentRunRecord(
                candidateId = candidate.candidateId,
                candidateName = candidate.name,
                timestamp = System.currentTimeMillis(),
                prePortStatus = null,
                postPortStatus = null,
                bytesTransferred = 0,
                totalBytes = 0,
                transferDurationMs = 0,
                payloadSha256 = "",
                usbStatusMessage = "Rejected: engine busy",
                failureCategory = FailureCategory.PRINTER_BUSY,
                physicalOutcome = PhysicalOutcome.UNTESTED
            )
        }

        var connection: UsbDeviceConnection? = null
        var claimedInterface: UsbInterface? = null
        var preStatus: Byte? = null
        var postStatus: Byte? = null
        var bytesWritten = 0L
        var totalPayloadSize = 0L
        var sha256 = ""
        var hexHeader = ""
        val startTime = System.currentTimeMillis()
        var failureCat = FailureCategory.NONE
        var statusMsg = "Completed transmission"
        cancelRequested.set(false)
        val arbiterOwner = "discovery-${candidate.candidateId}-${System.nanoTime()}"
        if (!UsbJobArbiter.acquire(arbiterOwner) { cancelRequested.get() }) {
            isExecuting.set(false)
            return@withContext ExperimentRunRecord(
                candidateId = candidate.candidateId,
                candidateName = candidate.name,
                timestamp = System.currentTimeMillis(),
                prePortStatus = null,
                postPortStatus = null,
                bytesTransferred = 0,
                totalBytes = 0,
                transferDurationMs = 0,
                payloadSha256 = "",
                usbStatusMessage = "Cancelled while waiting for USB ownership",
                failureCategory = FailureCategory.PRINTER_BUSY,
                physicalOutcome = PhysicalOutcome.UNTESTED
            )
        }

        try {
            _engineState.value = _engineState.value.copy(
                isRunning = true,
                activeCandidate = candidate,
                candidateIndex = candidates.indexOf(candidate).coerceAtLeast(
                    _engineState.value.candidateIndex
                ),
                currentStepMessage = "Step 1/6: Checking USB permissionâ€¦",
                bytesTransferred = 0,
                totalBytes = 0,
                awaitingPhysicalVerification = false
            )

            // 1. Permission check
            if (!usbManager.hasPermission(device)) {
                failureCat = FailureCategory.USB_PERMISSION_DENIED
                statusMsg = "USB permission not granted for ${device.deviceName}"
                throw IllegalStateException(statusMsg)
            }

            // 2. Open connection
            connection = usbManager.openDevice(device)
                ?: throw IllegalStateException("UsbManager.openDevice returned null").also {
                    failureCat = FailureCategory.USB_CONNECTION_FAILURE
                    statusMsg = "Failed to open USB connection"
                }

            // 3. Locate Printer Interface (class 7, subclass 1, protocol 2)
            var printerInterface: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER &&
                    iface.interfaceSubclass == 1 &&
                    iface.interfaceProtocol == 2
                ) {
                    printerInterface = iface
                    break
                }
            }
            if (printerInterface == null) {
                failureCat = FailureCategory.INTERFACE_CLAIM_FAILURE
                statusMsg = "No suitable printer interface on device"
                throw IllegalStateException(statusMsg)
            }

            if (!connection.claimInterface(printerInterface, true)) {
                failureCat = FailureCategory.INTERFACE_CLAIM_FAILURE
                statusMsg = "Failed to claim USB interface #${printerInterface.id}"
                throw IllegalStateException(statusMsg)
            }
            claimedInterface = printerInterface

            // 4. Pre-flight status
            preStatus = readPortStatus(connection, printerInterface)
            DiagnosticLogger.log("DISCOVERY", "[Pre-Flight] Port status: 0x%02X (iface #${printerInterface.id})".format(preStatus ?: 0))
            decodePortStatus(preStatus)

            // 5. Generate & validate payload
            _engineState.value = _engineState.value.copy(
                currentStepMessage = "Step 2/6: Rendering test document (${candidate.resolutionDpi} DPI)â€¦"
            )
            val testBitmap = if (candidate.candidateId.endsWith("_EXPERIMENTAL")) {
                PayloadGenerator.createDpiValidationBitmap(candidate)
            } else {
                PayloadGenerator.createTestBitmap(candidate)
            }
            val payload = PayloadGenerator.generatePayload(candidate, testBitmap)
            testBitmap.recycle()
            totalPayloadSize = payload.size.toLong()
            hexHeader = payload.take(32).joinToString(" ") { "%02X".format(it) }

            _engineState.value = _engineState.value.copy(
                currentStepMessage = "Step 3/6: Validating payload (${payload.size} bytes, SHA-256)â€¦",
                hexHeaderSnippet = hexHeader
            )
            val validation = CandidateValidator.validate(candidate, payload)
            sha256 = validation.sha256Hex
            if (!validation.isValid) {
                failureCat = FailureCategory.INVALID_PAYLOAD
                statusMsg = "Payload validation failed: ${validation.message}"
                throw IllegalArgumentException(statusMsg)
            }
            DiagnosticLogger.log("DISCOVERY", "Payload valid: ${payload.size} B, SHA-256: ${sha256.take(16)}â€¦")

            // 6. Locate Bulk OUT endpoint
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until printerInterface.endpointCount) {
                val ep = printerInterface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    bulkOut = ep; break
                }
            }
            if (bulkOut == null) {
                failureCat = FailureCategory.USB_TRANSFER_ERROR
                statusMsg = "No Bulk OUT endpoint on interface #${printerInterface.id}"
                throw IllegalStateException(statusMsg)
            }

            // 7. Chunked USB transmission
            _engineState.value = _engineState.value.copy(
                currentStepMessage = "Step 4/6: Transmitting ${payload.size} B â†’ Bulk OUT 0x%02Xâ€¦".format(bulkOut.address),
                totalBytes = totalPayloadSize
            )
            DiagnosticLogger.log("DISCOVERY", "TX: ${payload.size} B via endpoint 0x%02X, chunk=${candidate.usbChunkSize} B, timeout=${candidate.transferTimeoutMs}ms".format(bulkOut.address))

            val writer = UsbStreamWriter(
                connection = connection,
                bulkOut = bulkOut,
                usbManager = usbManager,
                device = device,
                interfaceId = printerInterface.id,
                config = StreamConfig.diagnostic(
                    candidate.usbChunkSize,
                    candidate.transferTimeoutMs
                ),
                claimedProvider = { claimedInterface === printerInterface }
            )
            activeWriter = writer

            val streamResult = writer.stream(
                data = payload,
                callback = object : StreamProgressCallback {
                    override fun onProgress(snapshot: StreamSnapshot) {
                        bytesWritten = snapshot.confirmedBytes
                        _engineState.value = _engineState.value.copy(
                            bytesTransferred = snapshot.confirmedBytes,
                            currentStepMessage =
                                "Step 4/6: Streaming ${snapshot.percentComplete}% " +
                                "(chunk ${snapshot.chunkIndex}/${snapshot.totalChunks}, " +
                                "${snapshot.retryCount} retries)â€¦"
                        )
                    }

                    override fun onRetry(
                        offset: Long,
                        retryNumber: Int,
                        maxRetries: Int,
                        reason: String
                    ) {
                        _engineState.value = _engineState.value.copy(
                            currentStepMessage =
                                "USB stall at ${offset}B â€” retry $retryNumber/$maxRetries: $reason"
                        )
                    }

                    override fun onRecovery(message: String) {
                        _engineState.value = _engineState.value.copy(currentStepMessage = message)
                    }
                },
                cancelSignal = { cancelRequested.get() }
            )

            when (streamResult) {
                is StreamResult.Completed -> {
                    bytesWritten = streamResult.totalConfirmed
                    DiagnosticLogger.log(
                        "DISCOVERY",
                        "TX complete: $bytesWritten/$totalPayloadSize B in ${streamResult.elapsedMs}ms"
                    )
                }
                is StreamResult.TransferFailed -> {
                    failureCat = if (streamResult.connectionValid) {
                        FailureCategory.USB_TRANSFER_ERROR
                    } else {
                        FailureCategory.USB_CONNECTION_FAILURE
                    }
                    statusMsg = buildString {
                        append("USB stream failed at ${streamResult.failureOffset}B ")
                        append("after ${streamResult.retryCount} retries")
                        if (streamResult.connectionValid) append(" (connection still valid)") else append(" (connection lost)")
                        if (streamResult.ambiguous) append(" â€” outcome ambiguous, no automatic replay")
                        if (streamResult.detail.isNotBlank()) append(": ${streamResult.detail}")
                    }
                    throw IllegalStateException(statusMsg)
                }
                is StreamResult.Cancelled -> {
                    bytesWritten = streamResult.confirmedSoFar
                    failureCat = FailureCategory.PRINTER_BUSY
                    statusMsg = "Transfer cancelled after ${streamResult.confirmedSoFar} bytes"
                    throw IllegalStateException(statusMsg)
                }
            }
            activeWriter = null

            // 8. ZLP flush
            _engineState.value = _engineState.value.copy(currentStepMessage = "Step 5/6: Flushing ZLPâ€¦")
            if (candidate.sendZlpFlush) {
                val zlpResult = connection.bulkTransfer(bulkOut, ByteArray(0), 0, 2000)
                DiagnosticLogger.log("DISCOVERY", "ZLP flush result: $zlpResult")
                if (zlpResult < 0) {
                    failureCat = FailureCategory.USB_TRANSFER_ERROR
                    throw IllegalStateException(
                        "Payload delivered but ZLP finalization failed; output is uncertain"
                    )
                }
            }

            // 9. Soft reset
            if (candidate.performSoftReset) {
                val resetResult = connection.controlTransfer(0x21, 0x02, 0, printerInterface.id, null, 0, 1000)
                DiagnosticLogger.log("DISCOVERY", "Soft reset (0x21,0x02) result: $resetResult")
            }

            // 10. Post-flight status
            _engineState.value = _engineState.value.copy(currentStepMessage = "Step 6/6: Reading post-transfer port statusâ€¦")
            postStatus = readPortStatus(connection, printerInterface)
            DiagnosticLogger.log("DISCOVERY", "[Post-Flight] Port status: 0x%02X".format(postStatus ?: 0))
            decodePortStatus(postStatus)

        } catch (e: Exception) {
            DiagnosticLogger.log("DISCOVERY", "Experiment exception: ${e.javaClass.simpleName}: ${e.message}")
            if (failureCat == FailureCategory.NONE) failureCat = FailureCategory.USB_TRANSFER_ERROR
            statusMsg = e.message ?: "Unknown error"
        } finally {
            activeWriter = null
            try {
                claimedInterface?.let { connection?.releaseInterface(it) }
                connection?.close()
            } catch (ignored: Exception) {}
            isExecuting.set(false)
            UsbJobArbiter.release(arbiterOwner)
        }

        val duration = System.currentTimeMillis() - startTime
        val record = ExperimentRunRecord(
            candidateId = candidate.candidateId,
            candidateName = candidate.name,
            timestamp = System.currentTimeMillis(),
            prePortStatus = preStatus,
            postPortStatus = postStatus,
            bytesTransferred = bytesWritten,
            totalBytes = totalPayloadSize,
            transferDurationMs = duration,
            payloadSha256 = sha256,
            usbStatusMessage = statusMsg,
            hexHeaderSnippet = hexHeader,
            failureCategory = failureCat,
            physicalOutcome = if (failureCat == FailureCategory.NONE)
                PhysicalOutcome.AWAITING_VERIFICATION else PhysicalOutcome.NOTHING_HAPPENED
        )

        runHistory.add(record)
        historyStore?.saveRecord(record)

        _engineState.value = _engineState.value.copy(
            isRunning = false,
            currentStepMessage = if (failureCat == FailureCategory.NONE)
                "âœ“ Transmitted $bytesWritten B in ${duration}ms. â† Did a page print?"
            else
                "âœ— ${failureCat.displayName}: $statusMsg",
            lastRunRecord = record,
            awaitingPhysicalVerification = (failureCat == FailureCategory.NONE),
            runHistory = runHistory.toList(),
            totalUsbFormatsTested = runHistory.size
        )

        return@withContext record
    }

    // â”€â”€â”€ Physical Outcome â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    fun recordPhysicalOutcome(
        device: UsbDevice,
        candidate: DiscoveryCandidate,
        outcome: PhysicalOutcome,
        notes: String = ""
    ) {
        val lastRecord = _engineState.value.lastRunRecord ?: return
        lastRecord.physicalOutcome = outcome
        lastRecord.operatorNotes = notes
        historyStore?.saveRecord(lastRecord)

        DiagnosticLogger.log("DISCOVERY", "Operator verified [${candidate.candidateId}]: ${outcome.displayName}")

        if (outcome == PhysicalOutcome.SUCCESS_PAGE_PRINTED) {
            val profile = VerifiedPrinterProfile(
                profileName = "${device.productName ?: "HP Ink Tank 310"} â€” Verified USB Profile",
                vendorId = device.vendorId,
                productId = device.productId,
                printerModel = device.productName ?: "HP Ink Tank 310 series",
                candidate = candidate,
                verifiedTimestamp = System.currentTimeMillis(),
                verifiedSha256 = lastRecord.payloadSha256
            )
            profileStore.saveVerifiedProfile(profile)

            // Update registry entry
            FormatRegistry.updateStatus(
                candidate.protocol.name,
                CompatibilityStatus.PHYSICALLY_VERIFIED,
                "Verified by operator â€” physical page printed"
            )

            _engineState.value = _engineState.value.copy(
                verifiedProfile = profile,
                isAutoBatchActive = false,
                awaitingPhysicalVerification = false,
                currentStepMessage = "ðŸŽ‰ SUCCESS: Profile '${profile.profileName}' saved and active!",
                runHistory = runHistory.toList()
            )
        } else {
            _engineState.value = _engineState.value.copy(
                awaitingPhysicalVerification = false,
                currentStepMessage = "âœ— Candidate rejected: ${outcome.displayName}",
                runHistory = runHistory.toList()
            )
        }
    }

    // â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun readPortStatus(connection: UsbDeviceConnection, iface: UsbInterface): Byte? {
        val buf = ByteArray(1)
        // GET_PORT_STATUS: bmRequestType=0xA1, bRequest=0x01, wValue=0, wIndex=ifaceId
        val rc = connection.controlTransfer(0xA1, 0x01, 0, iface.id, buf, 1, 1000)
        return if (rc >= 1) buf[0] else null
    }

    private fun decodePortStatus(status: Byte?) {
        if (status == null) return
        val s = status.toInt() and 0xFF
        val paperOut    = (s and 0x20) != 0
        val selected    = (s and 0x10) != 0
        val notError    = (s and 0x08) != 0
        DiagnosticLogger.log("DISCOVERY", "  Port Status 0x%02X: PaperOut=$paperOut, Selected=$selected, NoError=$notError".format(s))
    }

    fun getRegistryFormatCount(): Int = FormatRegistry.getAllEntries().size
    fun getRegistrySupportedCount(): Int = FormatRegistry.getAllEntries().count {
        it.status in listOf(
            CompatibilityStatus.ADVERTISED,
            CompatibilityStatus.DOCUMENTED_SUPPORTED,
            CompatibilityStatus.DRIVER_SUPPORTED,
            CompatibilityStatus.PHYSICALLY_VERIFIED
        )
    }
    fun getRegistryUnsupportedCount(): Int = FormatRegistry.getAllEntries().count {
        it.status == CompatibilityStatus.UNSUPPORTED
    }
}
