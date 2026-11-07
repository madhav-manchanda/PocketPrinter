package com.pocketprint.data.print

import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.net.Uri
import com.pocketprint.data.discovery.engine.CandidateGenerator
import com.pocketprint.data.discovery.engine.PayloadGenerator
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryColorMode
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.model.FramingStyle
import com.pocketprint.data.discovery.storage.PrinterProfileStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.print.encoder.HpPcl3Gui2ColorEncoder
import com.pocketprint.data.print.encoder.HpPcl3Gui2Encoder
import com.pocketprint.data.print.encoder.PrintEncodingResolver
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintEncoding
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.transport.PrintTransmitResult
import com.pocketprint.data.print.transport.PrinterConnectionState
import com.pocketprint.data.print.transport.StreamConfig
import com.pocketprint.data.print.transport.StreamSnapshot
import com.pocketprint.data.print.transport.TransmitProgressListener
import com.pocketprint.data.print.transport.UsbPrintTransport
import com.pocketprint.data.print.transport.UsbJobArbiter
import com.pocketprint.data.print.util.PageRangeParser
import com.pocketprint.data.print.util.PrintPageScheduler
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import com.pocketprint.data.print.quality.PageQualityAnalyzer
import com.pocketprint.data.usb.PrinterConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

/**
 * PocketPrint Direct USB Print Engine — Phase 5.
 *
 * Uses [UsbPrintTransport.transmit] for all USB data transfer.
 * Handles [PrintTransmitResult.Failed] with connection-aware state transitions.
 * Never conflates a transfer failure with a physical printer disconnection.
 */
class PrintJobManager(
    private val context: Context,
    private val pdfRendererService: PdfRendererService,
    private val protocolManager: PrinterProtocolManager,
    private val printTransport: UsbPrintTransport,
    val statusManager: PrintStatusManager,
    private val profileStore: PrinterProfileStore? = null,
    private val journal: PrintJobJournal? = null,
    private val connectionManager: PrinterConnectionManager? = null
) {
    private val isJobRunning = AtomicBoolean(false)

    @Volatile
    private var cancelRequested = false

    // ── Candidate resolution ──────────────────────────────────────────────────

    private fun resolveActiveCandidate(rawDevice: UsbDevice): DiscoveryCandidate {
        val verified = profileStore?.getVerifiedProfile(rawDevice.vendorId, rawDevice.productId)
        if (verified != null) {
            DiagnosticLogger.usb("Using saved verified profile: ${verified.profileName} [${verified.candidate.name}]")
            return verified.candidate
        }
        val defaultCandidate = CandidateGenerator.generateCandidates().first()
        DiagnosticLogger.usb("No verified profile yet — using default candidate: ${defaultCandidate.name}")
        return defaultCandidate
    }

    // ── Core transmission helper ──────────────────────────────────────────────

    /**
     * Transmits [payload] via the new streaming transport.
     * Maps [PrintTransmitResult] to job states.
     * Returns true on success, false on any failure.
     * Never throws — all outcomes are captured and reported.
     */
    private fun transmitPayload(
        rawDevice: UsbDevice,
        payload: ByteArray,
        copyLabel: String = "",
        config: StreamConfig = StreamConfig.reliable()
    ): Boolean {
        val label = if (copyLabel.isNotEmpty()) "[$copyLabel] " else ""
        val jobId = journal?.startJob(
            label = "${label}${rawDevice.productName ?: rawDevice.deviceName} (${payload.size} B)",
            totalBytes = payload.size.toLong()
        )
        var lastJournalPercent = -1

        fun setState(state: PrintJobState, message: String, percent: Int = 0) {
            statusManager.updateState(state, message, percent)
            journal?.updateJob(
                jobId = jobId ?: return,
                confirmedBytes = statusManager.progressFlow.value.bytesTransferred,
                totalBytes = payload.size.toLong(),
                state = state,
                ambiguous = state == PrintJobState.NEEDS_RECOVERY
            )
        }

        val listener = object : TransmitProgressListener {
            override fun onProgress(
                confirmedBytes: Long, totalBytes: Long,
                percentComplete: Int, chunkMs: Long, retryCount: Int
            ) {
                // Legacy callback: full snapshot arrives via onSnapshot below.
            }

            override fun onSnapshot(snapshot: StreamSnapshot) {
                statusManager.updateSnapshot(snapshot)
                if (snapshot.percentComplete / 10 != lastJournalPercent / 10) {
                    lastJournalPercent = snapshot.percentComplete
                    journal?.updateJob(
                        jobId = jobId ?: return,
                        confirmedBytes = snapshot.confirmedBytes,
                        totalBytes = snapshot.totalBytes,
                        state = PrintJobState.TRANSMITTING_USB
                    )
                }
            }

            override fun onRetry(confirmedBytes: Long, retryNumber: Int, maxRetries: Int) {
                setState(
                    PrintJobState.TRANSFER_RECOVERING,
                    "${label}USB stall at ${confirmedBytes}B — retry $retryNumber/$maxRetries"
                )
            }

            override fun onRecovery(message: String) {
                setState(PrintJobState.TRANSFER_RECOVERING, "${label}$message")
            }

            override fun onConnectionStateChange(state: PrinterConnectionState) {
                statusManager.updateConnectionState(state)
                if (state == PrinterConnectionState.TRANSFER_ERROR ||
                    state == PrinterConnectionState.RECOVERY_REQUIRED ||
                    state == PrinterConnectionState.DISCONNECTED
                ) {
                    connectionManager?.onTransferState(state)
                }
            }

            override fun onFinalizing() {
                setState(
                    PrintJobState.FINALIZING,
                    "${label}All payload bytes confirmed; sending end-of-job controls...",
                    100
                )
            }
        }

        return when (val result = printTransport.transmit(rawDevice, payload, listener, config)) {
            is PrintTransmitResult.Ok -> {
                DiagnosticLogger.usb("${label}Transfer complete: ${result.totalBytes} bytes confirmed")
                journal?.finishJob(jobId ?: "", PrintJobState.COMPLETED_SUCCESS)
                true
            }

            is PrintTransmitResult.Failed -> {
                val state = when {
                    result.recoveryRequired || result.ambiguous -> PrintJobState.NEEDS_RECOVERY
                    !result.connectionValid -> PrintJobState.DISCONNECTED
                    else -> PrintJobState.TRANSFER_ERROR
                }
                // Use the specific failure message, never a generic "Disconnected".
                statusManager.updateState(state, "${label}${result.message}")
                result.portStatus?.let { statusManager.updatePortStatus(it.describe()) }
                connectionManager?.onTransferState(
                    when {
                        result.recoveryRequired && result.connectionValid ->
                            PrinterConnectionState.RECOVERY_REQUIRED
                        result.connectionValid -> PrinterConnectionState.TRANSFER_ERROR
                        else -> PrinterConnectionState.DISCONNECTED
                    }
                )
                journal?.finishJob(jobId ?: "", state)
                DiagnosticLogger.usbError(
                    "${label}Transmit failed at offset=${result.failureOffset} " +
                    "retries=${result.retryCount} connValid=${result.connectionValid} " +
                    "ambiguous=${result.ambiguous} recoveryRequired=${result.recoveryRequired}"
                )
                false
            }

            is PrintTransmitResult.Cancelled -> {
                val state = if (result.recoveryRequired) {
                    PrintJobState.NEEDS_RECOVERY
                } else {
                    PrintJobState.CANCELLED
                }
                setState(
                    state,
                    if (result.recoveryRequired) {
                        "${label}Cancelled after ${result.confirmedBytes} confirmed bytes; clear or reconnect the printer before resubmitting"
                    } else {
                        "${label}Job cancelled before any payload bytes were sent"
                    }
                )
                connectionManager?.onTransferState(
                    if (result.recoveryRequired) PrinterConnectionState.RECOVERY_REQUIRED
                    else PrinterConnectionState.READY
                )
                journal?.finishJob(jobId ?: "", state)
                false
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun cancelJob() {
        cancelRequested = true
        printTransport.cancelActiveTransfer()
        statusManager.updateState(PrintJobState.CANCELLED, "Print job cancelled by operator")
    }

    /**
     * Prints a monochrome calibration test page directly over USB OTG.
     */
    suspend fun printTestPage(
        rawDevice: UsbDevice,
        deviceInfo: UsbDeviceInfo,
        options: PrintOptions = PrintOptions()
    ): Boolean {
        if (!isJobRunning.compareAndSet(false, true)) {
            statusManager.updateState(PrintJobState.FAILED, "A print job is already in progress.")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                statusManager.updateState(PrintJobState.PREPARING_DOCUMENT, "Synthesizing diagnostic test page...")
                val candidate = resolveActiveCandidate(rawDevice)
                val bitmap = PayloadGenerator.createTestBitmap(candidate)
                statusManager.updateState(PrintJobState.ENCODING_RASTER, "Encoding page via ${candidate.protocol.displayName}...")
                val payload = PayloadGenerator.generatePayload(candidate, bitmap)
                bitmap.recycle()
                DiagnosticLogger.usb("Test page encoded: ${payload.size} bytes (${candidate.name})")
                statusManager.updateState(
                    PrintJobState.TRANSMITTING_USB,
                    "Streaming ${payload.size} bytes to printer Bulk OUT endpoint...",
                    0
                )
                val ok = transmitPayload(
                    rawDevice = rawDevice,
                    payload = payload,
                    config = StreamConfig.diagnostic(candidate.usbChunkSize, candidate.transferTimeoutMs)
                )
                if (ok) {
                    statusManager.updateState(PrintJobState.PRINTER_PROCESSING,
                        "Data delivered — printer is processing...", 100)
                    // Let the printer internalize the data
                    kotlinx.coroutines.delay(1500)
                    statusManager.updateState(PrintJobState.COMPLETED_SUCCESS,
                        "Test-page data delivered. Confirm the physical page before verifying this profile.", 100)
                    connectionManager?.markReady("Print job completed")
                }
                ok
            } catch (e: Exception) {
                DiagnosticLogger.usbError("Test page print failed: ${e.message}")
                statusManager.updateState(PrintJobState.FAILED, "Print error: ${e.message ?: "Unknown error"}")
                false
            } finally {
                isJobRunning.set(false)
            }
        }
    }

    /**
     * Prints the full-colour diagnostic test page over USB OTG.
     */
    suspend fun printColorTestPage(
        rawDevice: UsbDevice,
        deviceInfo: UsbDeviceInfo,
        options: PrintOptions = PrintOptions(colorMode = PrintColorMode.COLOR_RGB)
    ): Boolean {
        if (!isJobRunning.compareAndSet(false, true)) {
            statusManager.updateState(PrintJobState.FAILED, "A print job is already in progress.")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                statusManager.updateState(PrintJobState.PREPARING_DOCUMENT, "Synthesizing color diagnostic test page...")
                val colorCandidate = DiscoveryCandidate(
                    candidateId  = "HP_PCL3GUI2_COLOR_HPLIP",
                    name         = "PCL3GUI2 Dual-Plane Color (K Mode 9 + RGB Mode 10)",
                    protocol     = DiscoveryProtocol.PCL3GUI2_COLOR_MODE10,
                    colorMode    = DiscoveryColorMode.COLOR_24BIT_RGB,
                    resolutionDpi = options.dpi,
                    framingStyle  = FramingStyle.PJL_ENTER_PCL3GUI,
                    sendZlpFlush  = true,
                    performSoftReset = true
                )
                val bitmap = PayloadGenerator.createColorTestBitmap(colorCandidate)
                statusManager.updateState(PrintJobState.ENCODING_RASTER, "Encoding dual-plane color raster...")
                val payload = PayloadGenerator.generatePayload(colorCandidate, bitmap)
                bitmap.recycle()
                DiagnosticLogger.usb("Color test page encoded: ${payload.size} bytes (${colorCandidate.name})")
                statusManager.updateState(
                    PrintJobState.TRANSMITTING_USB,
                    "Streaming ${payload.size} bytes to printer Bulk OUT endpoint...",
                    0
                )
                val ok = transmitPayload(
                    rawDevice = rawDevice,
                    payload = payload,
                    config = StreamConfig.diagnostic(
                        colorCandidate.usbChunkSize,
                        colorCandidate.transferTimeoutMs
                    )
                )
                if (ok) {
                    statusManager.updateState(PrintJobState.PRINTER_PROCESSING,
                        "Data delivered — printer is processing colour job...", 100)
                    kotlinx.coroutines.delay(1500)
                    statusManager.updateState(PrintJobState.COMPLETED_SUCCESS,
                        "Color test data delivered. Confirm registration and output on the physical page.", 100)
                    connectionManager?.markReady("Color print job completed")
                }
                ok
            } catch (e: Exception) {
                DiagnosticLogger.usbError("Color test page print failed: ${e.message}")
                statusManager.updateState(PrintJobState.FAILED, "Print error: ${e.message ?: "Unknown error"}")
                false
            } finally {
                isJobRunning.set(false)
            }
        }
    }

    /** Sends a neutral-ramp chart through the production grayscale-via-color path. */
    suspend fun printGrayscaleTestPage(
        rawDevice: UsbDevice,
        deviceInfo: UsbDeviceInfo,
        options: PrintOptions = PrintOptions(colorMode = PrintColorMode.GRAYSCALE_8BIT)
    ): Boolean {
        if (!isJobRunning.compareAndSet(false, true)) {
            statusManager.updateState(PrintJobState.FAILED, "A print job is already in progress.")
            return false
        }
        cancelRequested = false
        val arbiterOwner = "document-${System.nanoTime()}"
        if (!UsbJobArbiter.acquire(arbiterOwner) { cancelRequested }) {
            isJobRunning.set(false)
            statusManager.updateState(
                PrintJobState.CANCELLED,
                "Cancelled while waiting for USB print ownership"
            )
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val candidate = resolveActiveCandidate(rawDevice)
                val effectiveOptions = options.copy(
                    dpi = candidate.resolutionDpi,
                    colorMode = PrintColorMode.GRAYSCALE_8BIT
                )
                statusManager.updateState(
                    PrintJobState.PREPARING_DOCUMENT,
                    "Creating grayscale verification chart..."
                )
                val bitmap = pdfRendererService.generateGrayscaleVerificationBitmap(effectiveOptions)
                val payload = try {
                    statusManager.updateState(
                        PrintJobState.ENCODING_RASTER,
                        "Encoding neutral luminance through verified PCL3GUI2 color transport..."
                    )
                    val output = ByteArrayOutputStream()
                    HpPcl3Gui2ColorEncoder().apply {
                        encodeJobStart(effectiveOptions.dpi, bitmap.width, output)
                        encodeGrayscaleRasterPage(bitmap, effectiveOptions.dpi, output)
                        encodeJobEnd(output)
                    }
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }

                statusManager.updateState(
                    PrintJobState.TRANSMITTING_USB,
                    "Streaming ${payload.size} grayscale verification bytes..."
                )
                val ok = transmitPayload(rawDevice, payload)
                if (ok) {
                    statusManager.updateState(
                        PrintJobState.COMPLETED_SUCCESS,
                        "Grayscale chart delivered. Inspect ramps and color-source order before marking hardware output verified.",
                        100
                    )
                    connectionManager?.markReady("Grayscale verification chart delivered")
                }
                ok
            } catch (e: Exception) {
                DiagnosticLogger.usbError("Grayscale verification print failed: ${e.message}")
                statusManager.updateState(PrintJobState.FAILED, "Print error: ${e.message ?: "Unknown error"}")
                false
            } finally {
                isJobRunning.set(false)
                UsbJobArbiter.release(arbiterOwner)
            }
        }
    }

    suspend fun printQualityBenchmark(
        rawDevice: UsbDevice,
        deviceInfo: UsbDeviceInfo,
        qualityProfile: PrintQualityProfile,
        colorMode: PrintColorMode
    ): Boolean {
        if (!isJobRunning.compareAndSet(false, true)) {
            statusManager.updateState(PrintJobState.FAILED, "A print job is already in progress.")
            return false
        }
        cancelRequested = false
        val arbiterOwner = "quality-benchmark-${System.nanoTime()}"
        if (!UsbJobArbiter.acquire(arbiterOwner) { cancelRequested }) {
            isJobRunning.set(false)
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val candidate = resolveActiveCandidate(rawDevice)
                val pcl3Gui2 = candidate.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 ||
                    candidate.protocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10
                val effectiveProfile = if (
                    !qualityProfile.experimentalResolution || pcl3Gui2
                ) qualityProfile else PrintQualityProfile.NORMAL
                val options = PrintOptions(
                    dpi = effectiveProfile.targetDpi,
                    colorMode = colorMode,
                    qualityProfile = effectiveProfile,
                    monochromeStrategy = effectiveProfile.monochromeStrategy,
                    monochromeThreshold = effectiveProfile.monochromeThreshold
                )
                val benchmarkCandidate = candidate.copy(resolutionDpi = options.dpi)
                statusManager.updateState(
                    PrintJobState.PREPARING_DOCUMENT,
                    "Generating ${effectiveProfile.displayName} quality benchmark..."
                )
                val bitmap = if (effectiveProfile.experimentalResolution) {
                    PayloadGenerator.createDpiValidationBitmap(benchmarkCandidate)
                } else {
                    PayloadGenerator.createTestBitmap(benchmarkCandidate)
                }
                val payload = try {
                    statusManager.updateState(
                        PrintJobState.ENCODING_RASTER,
                        "Encoding reproducible ${colorMode.displayName} benchmark..."
                    )
                    val output = ByteArrayOutputStream()
                    when (colorMode) {
                        PrintColorMode.MONOCHROME_1BIT ->
                            HpPcl3Gui2Encoder().encodePage(bitmap, options, output)
                        PrintColorMode.GRAYSCALE_8BIT -> HpPcl3Gui2ColorEncoder().apply {
                            encodeJobStart(options.dpi, bitmap.width, output)
                            encodeGrayscaleRasterPage(bitmap, options.dpi, output)
                            encodeJobEnd(output)
                        }
                        PrintColorMode.COLOR_RGB ->
                            HpPcl3Gui2ColorEncoder().encodePage(bitmap, options, output)
                    }
                    output.toByteArray()
                } finally {
                    bitmap.recycle()
                }
                DiagnosticLogger.usb(
                    "[QUALITY] benchmark profile=${effectiveProfile.name} mode=${colorMode.name} " +
                        "dpi=${options.dpi} payload=${payload.size}B printer=${candidate.candidateId}"
                )
                statusManager.updateState(
                    PrintJobState.TRANSMITTING_USB,
                    "Sending ${effectiveProfile.displayName} benchmark (${payload.size} bytes)..."
                )
                val success = transmitPayload(rawDevice, payload)
                if (success) {
                    statusManager.updateState(
                        PrintJobState.COMPLETED_SUCCESS,
                        "Benchmark delivered. Record the profile and compare the physical page.",
                        100
                    )
                    connectionManager?.markReady("Quality benchmark delivered")
                }
                success
            } catch (error: Exception) {
                DiagnosticLogger.usbError("Quality benchmark failed: ${error.message}")
                statusManager.updateState(
                    PrintJobState.FAILED,
                    "Benchmark error: ${error.message ?: "Unknown error"}"
                )
                false
            } finally {
                isJobRunning.set(false)
                UsbJobArbiter.release(arbiterOwner)
            }
        }
    }

    /**
     * Prints a user-selected document (PDF, PNG, JPEG, etc.) over USB OTG.
     */
    suspend fun printPdfDocument(
        rawDevice: UsbDevice,
        deviceInfo: UsbDeviceInfo,
        documentUri: Uri,
        options: PrintOptions = PrintOptions(),
        mimeType: String? = null,
        documentName: String = documentUri.lastPathSegment ?: "Document"
    ): Boolean {
        if (!isJobRunning.compareAndSet(false, true)) {
            statusManager.updateState(PrintJobState.FAILED, "A print job is already in progress.")
            return false
        }
        cancelRequested = false
        val arbiterOwner = "document-${System.nanoTime()}"
        if (!UsbJobArbiter.acquire(arbiterOwner) { cancelRequested }) {
            isJobRunning.set(false)
            statusManager.updateState(
                PrintJobState.CANCELLED,
                "Cancelled while waiting for USB print ownership"
            )
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                statusManager.updateState(PrintJobState.PREPARING_DOCUMENT, "Inspecting document pages...")
                val candidate     = resolveActiveCandidate(rawDevice)
                val requestedQuality = options.qualityProfile
                val supportsExperimentalPcl3Gui2 = candidate.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 ||
                    candidate.protocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10
                val explicitExperimentalSelection = requestedQuality.experimentalResolution &&
                    supportsExperimentalPcl3Gui2 && options.dpi >= requestedQuality.targetDpi
                val effectiveQuality = if (
                    !requestedQuality.experimentalResolution || explicitExperimentalSelection ||
                    (requestedQuality.isAvailable(candidate.resolutionDpi) && supportsExperimentalPcl3Gui2)
                ) {
                    requestedQuality
                } else {
                    PrintQualityProfile.NORMAL
                }
                val effectiveMonochromeStrategy = if (
                    options.monochromeStrategy == MonochromeStrategy.AUTO
                ) {
                    effectiveQuality.monochromeStrategy
                } else {
                    options.monochromeStrategy
                }
                val effectiveThreshold = if (options.monochromeThreshold == 144) {
                    effectiveQuality.monochromeThreshold
                } else {
                    options.monochromeThreshold
                }
                val desiredDpi = maxOf(options.dpi, effectiveQuality.targetDpi)
                val explicitExperimentalDpi = options.dpi == 600 || options.dpi == 1200
                val effectiveDpi = if (
                    explicitExperimentalDpi && supportsExperimentalPcl3Gui2
                ) {
                    options.dpi
                } else if (
                    desiredDpi > 300 && supportsExperimentalPcl3Gui2 &&
                    candidate.resolutionDpi >= desiredDpi
                ) {
                    desiredDpi
                } else {
                    300
                }
                val effectiveOptions = options.copy(
                    dpi = effectiveDpi,
                    qualityProfile = effectiveQuality,
                    monochromeStrategy = effectiveMonochromeStrategy,
                    monochromeThreshold = effectiveThreshold,
                    copies = options.copies.coerceIn(1, 99)
                )
                if (effectiveQuality != requestedQuality) {
                    DiagnosticLogger.usb(
                        "Quality ${requestedQuality.name} requires ${requestedQuality.targetDpi} DPI, " +
                            "but only ${candidate.resolutionDpi} DPI is verified; using NORMAL 300 DPI"
                    )
                }
                DiagnosticLogger.usb(
                        "[QUALITY] requested=${requestedQuality.name} effective=${effectiveQuality.name} " +
                        "requestedDpi=$desiredDpi effectiveDpi=${effectiveOptions.dpi} " +
                        "printerProfile=${candidate.candidateId} monoStrategy=$effectiveMonochromeStrategy " +
                        "threshold=$effectiveThreshold"
                )
                val totalDocPages = pdfRendererService.getPageCount(
                    documentUri,
                    mimeType,
                    effectiveOptions
                )
                val targetPages = PageRangeParser.parse(
                    effectiveOptions.pageRange,
                    totalDocPages,
                    effectiveOptions.pageSet
                )

                if (targetPages.isEmpty()) {
                    throw IllegalArgumentException(
                        "No valid pages selected (range='${effectiveOptions.pageRange}', total=$totalDocPages)"
                    )
                }

                val copies = effectiveOptions.copies
                DiagnosticLogger.usb(
                    "Print job: $totalDocPages doc pages, " +
                    "printing ${targetPages.size} page(s) × $copies copies " +
                    "(${if (effectiveOptions.collate) "collated" else "uncollated"}, ${candidate.name})"
                )

                val schedule = PrintPageScheduler.schedule(
                    pageIndices = targetPages,
                    copies = copies,
                    collate = effectiveOptions.collate
                )
                statusManager.beginDocument(
                    documentName = documentName,
                    documentPageCount = totalDocPages,
                    scheduledItemCount = schedule.size,
                    copyCount = copies
                )
                for ((sequenceIndex, scheduledPage) in schedule.withIndex()) {
                    val copyIndex = scheduledPage.copyNumber
                    val copyLabel = if (copies > 1) "Copy $copyIndex/$copies" else ""
                    val pageIndex = scheduledPage.pageIndex
                    if (cancelRequested) {
                        statusManager.updateState(
                            PrintJobState.CANCELLED,
                            "Print job cancelled before item ${sequenceIndex + 1}/${schedule.size}"
                        )
                        return@withContext false
                    }
                    val pageNumber = pageIndex + 1
                    statusManager.beginScheduledItem(
                        documentPage = pageNumber,
                        scheduledItem = sequenceIndex + 1,
                        copyNumber = copyIndex
                    )
                    statusManager.updateState(
                        PrintJobState.RENDERING_BITMAP,
                        "Rendering document page $pageNumber/$totalDocPages " +
                            "(copy $copyIndex/$copies, item ${sequenceIndex + 1}/${schedule.size})..."
                    )
                    val renderStartedNanos = System.nanoTime()
                    val bitmap = pdfRendererService.renderPageToBitmap(
                        documentUri,
                        pageIndex,
                        effectiveOptions,
                        mimeType
                    )
                    val renderingMs = (System.nanoTime() - renderStartedNanos) / 1_000_000L
                    val rasterWidth = bitmap.width
                    val rasterHeight = bitmap.height
                    if (cancelRequested) {
                        bitmap.recycle()
                        statusManager.updateState(
                            PrintJobState.CANCELLED,
                            "Print job cancelled after rendering page $pageNumber; no page bytes sent"
                        )
                        return@withContext false
                    }

                    val decision = PageQualityAnalyzer.analyze(bitmap, effectiveOptions)
                    val hybridApplied = decision.hybridRequested &&
                        decision.requestedMode == PrintingMode.AUTO &&
                        decision.profile.recommendedStrategy == MonochromeStrategy.MIXED &&
                        (candidate.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 ||
                            candidate.protocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10)
                    val executedDecision = decision.copy(
                        hybridApplied = hybridApplied,
                        fallbackReason = if (hybridApplied) null else decision.fallbackReason
                    )
                    val pageColorMode = executedDecision.selectedColorMode
                    val pageOptions = effectiveOptions.copy(
                        colorMode = pageColorMode,
                        monochromeStrategy = if (
                            effectiveOptions.monochromeStrategy == MonochromeStrategy.AUTO
                        ) decision.profile.recommendedStrategy else effectiveOptions.monochromeStrategy
                    )
                    val encoding = PrintEncodingResolver.resolve(pageColorMode, candidate.protocol)
                    val isColorJob = encoding == PrintEncoding.COLOR_RGB
                    val isGrayJob = encoding == PrintEncoding.GRAYSCALE_8BIT
                    val isHybridJob = hybridApplied
                    if (pageColorMode == PrintColorMode.COLOR_RGB && !isColorJob) {
                        DiagnosticLogger.usbWarn(
                            "AUTO/Color selected RGB but printer profile ${candidate.candidateId} is not color-capable; mono fallback used"
                        )
                    }
                    DiagnosticLogger.usb(
                        "[QUALITY] page=$pageNumber/$totalDocPages requestedMode=${executedDecision.requestedMode} " +
                            "selectedMode=$pageColorMode content=${executedDecision.detectedContentTypes.joinToString("+")} " +
                            "confidence=${String.format(Locale.US, "%.3f", executedDecision.confidence)} " +
                            "hybridRequested=${executedDecision.hybridRequested} hybridApplied=${executedDecision.hybridApplied} " +
                            "fallback=${executedDecision.fallbackReason ?: "none"} effectiveDpi=${pageOptions.dpi} " +
                            "renderMs=$renderingMs"
                    )

                    val encodingLabel = when {
                        isHybridJob -> "experimental hybrid K text + neutral RGB tones"
                        isGrayJob -> "software grayscale via PCL3GUI2 color"
                        isColorJob -> "PCL3GUI2 color"
                        else -> candidate.protocol.displayName
                    }
                    statusManager.updateState(
                        PrintJobState.ENCODING_RASTER,
                        "Encoding page $pageNumber via $encodingLabel..."
                    )

                    val encodingStartedNanos = System.nanoTime()
                    val payload = try {
                        if (isHybridJob || isGrayJob || isColorJob) {
                            val colorEncoder = HpPcl3Gui2ColorEncoder()
                            val jobOut = ByteArrayOutputStream()
                            colorEncoder.encodeJobStart(pageOptions.dpi, bitmap.width, jobOut)
                            if (isHybridJob) {
                                colorEncoder.encodeHybridRasterPage(
                                    bitmap,
                                    pageOptions.dpi,
                                    pageOptions.monochromeThreshold,
                                    jobOut
                                )
                            } else if (isGrayJob) {
                                colorEncoder.encodeGrayscaleRasterPage(bitmap, pageOptions.dpi, jobOut)
                            } else {
                                colorEncoder.encodeRasterPage(bitmap, pageOptions.dpi, jobOut)
                            }
                            colorEncoder.encodeJobEnd(jobOut)
                            jobOut.toByteArray()
                        } else if (candidate.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 ||
                            candidate.protocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10
                        ) {
                            val encoder = HpPcl3Gui2Encoder()
                            val jobOut = ByteArrayOutputStream()
                            encoder.encodeJobStart(pageOptions.dpi, bitmap.width, jobOut)
                            encoder.encodeRasterPage(bitmap, pageOptions, jobOut)
                            encoder.encodeJobEnd(jobOut)
                            jobOut.toByteArray()
                        } else {
                            PayloadGenerator.generatePayload(candidate, bitmap)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    val encodingMs = (System.nanoTime() - encodingStartedNanos) / 1_000_000L

                    if (cancelRequested) {
                        statusManager.updateState(
                            PrintJobState.CANCELLED,
                            "Print job cancelled after encoding page $pageNumber; no page bytes sent"
                        )
                        return@withContext false
                    }

                    DiagnosticLogger.usb(
                        "[QUALITY] page=$pageNumber/$totalDocPages mode=$pageColorMode encoding=$encodingLabel " +
                            "raster=${rasterWidth}x${rasterHeight} dpi=${pageOptions.dpi} " +
                            "monoStride=${(rasterWidth + 7) / 8} rgbStride=${rasterWidth * 3} " +
                            "payload=${payload.size}B encodeMs=$encodingMs"
                    )

                    statusManager.updateState(
                        PrintJobState.TRANSMITTING_USB,
                        "Streaming copy $copyIndex, page $pageNumber: ${payload.size} bytes..."
                    )
                    val streamConfig = StreamConfig.production(isColorJob || isGrayJob || payload.size > 500_000)
                    if (!transmitPayload(rawDevice, payload, copyLabel, streamConfig)) {
                        return@withContext false
                    }
                    statusManager.updateState(
                        PrintJobState.PRINTER_PROCESSING,
                        "Page $pageNumber delivered; printer processing...", 100
                    )
                    statusManager.completeScheduledItem()
                }

                statusManager.updateState(PrintJobState.PRINTER_PROCESSING,
                    "All data delivered — printer is processing...", 100)
                kotlinx.coroutines.delay(1500)
                statusManager.updateState(PrintJobState.COMPLETED_SUCCESS,
                    "All pages delivered via Direct USB! Physical output confirmation pending operator verification.", 100)
                connectionManager?.markReady("Document print job completed")
                true

            } catch (e: CancellationException) {
                val progress = statusManager.progressFlow.value
                val state = if (progress.bytesTransferred > 0 || progress.completedItems > 0) {
                    PrintJobState.NEEDS_RECOVERY
                } else {
                    PrintJobState.CANCELLED
                }
                statusManager.updateState(
                    state,
                    if (state == PrintJobState.NEEDS_RECOVERY) {
                        "Print worker stopped after data was sent; inspect the printer before resubmitting."
                    } else {
                        "Print worker stopped before page transmission."
                    }
                )
                throw e
            } catch (e: Exception) {
                DiagnosticLogger.usbError("Document print failed: ${e.message}")
                statusManager.updateState(PrintJobState.FAILED, "Print error: ${e.message ?: "Unknown error"}")
                false
            } finally {
                isJobRunning.set(false)
                UsbJobArbiter.release(arbiterOwner)
            }
        }
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    private suspend fun encodeColorJob(
        documentUri: Uri,
        targetPages: List<Int>,
        totalDocPages: Int,
        candidate: DiscoveryCandidate,
        options: PrintOptions,
        mimeType: String?
    ): ByteArray = withContext(Dispatchers.Default) {
        val jobOut = ByteArrayOutputStream()
        val colorEncoder = HpPcl3Gui2ColorEncoder()
        val firstIdx = targetPages[0]
        val firstBitmap = pdfRendererService.renderPageToBitmap(documentUri, firstIdx, options, mimeType)
        colorEncoder.encodeJobStart(candidate.resolutionDpi, firstBitmap.width, jobOut)
        colorEncoder.encodeRasterPage(firstBitmap, candidate.resolutionDpi, jobOut)
        firstBitmap.recycle()
        for (i in 1 until targetPages.size) {
            val pIndex = targetPages[i]
            statusManager.updateState(
                PrintJobState.RENDERING_BITMAP,
                "Rendering page ${pIndex + 1}/$totalDocPages (${i + 1}/${targetPages.size})..."
            )
            val bitmap = pdfRendererService.renderPageToBitmap(documentUri, pIndex, options, mimeType)
            colorEncoder.encodeRasterPage(bitmap, candidate.resolutionDpi, jobOut)
            bitmap.recycle()
        }
        colorEncoder.encodeJobEnd(jobOut)
        jobOut.toByteArray()
    }

    private suspend fun encodeMonoJob(
        documentUri: Uri,
        targetPages: List<Int>,
        totalDocPages: Int,
        candidate: DiscoveryCandidate,
        options: PrintOptions,
        mimeType: String?
    ): ByteArray = withContext(Dispatchers.Default) {
        val jobOut = ByteArrayOutputStream()
        val encoder = HpPcl3Gui2Encoder()
        val firstIdx = targetPages[0]
        val firstBitmap = pdfRendererService.renderPageToBitmap(documentUri, firstIdx, options, mimeType)
        encoder.encodeJobStart(candidate.resolutionDpi, firstBitmap.width, jobOut)
        encoder.encodeRasterPage(firstBitmap, candidate.resolutionDpi, jobOut)
        firstBitmap.recycle()
        for (i in 1 until targetPages.size) {
            val pIndex = targetPages[i]
            statusManager.updateState(
                PrintJobState.RENDERING_BITMAP,
                "Rendering page ${pIndex + 1}/$totalDocPages (${i + 1}/${targetPages.size})..."
            )
            val bitmap = pdfRendererService.renderPageToBitmap(documentUri, pIndex, options, mimeType)
            encoder.encodeRasterPage(bitmap, candidate.resolutionDpi, jobOut)
            bitmap.recycle()
        }
        encoder.encodeJobEnd(jobOut)
        jobOut.toByteArray()
    }

    private suspend fun encodePerPageJob(
        documentUri: Uri,
        targetPages: List<Int>,
        totalDocPages: Int,
        candidate: DiscoveryCandidate,
        options: PrintOptions,
        mimeType: String?
    ): ByteArray = withContext(Dispatchers.Default) {
        val jobOut = ByteArrayOutputStream()
        for (i in targetPages.indices) {
            val pIndex = targetPages[i]
            statusManager.updateState(
                PrintJobState.RENDERING_BITMAP,
                "Rendering page ${pIndex + 1}/$totalDocPages..."
            )
            val bitmap = pdfRendererService.renderPageToBitmap(documentUri, pIndex, options, mimeType)
            statusManager.updateState(
                PrintJobState.ENCODING_RASTER,
                "Encoding page ${pIndex + 1} via ${candidate.protocol.displayName}..."
            )
            val pageBytes = PayloadGenerator.generatePayload(candidate, bitmap)
            bitmap.recycle()
            jobOut.write(pageBytes)
        }
        jobOut.toByteArray()
    }
}
