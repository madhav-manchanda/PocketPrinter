package com.pocketprint.service

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.discovery.engine.CandidateGenerator
import com.pocketprint.data.discovery.engine.PayloadGenerator
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.storage.PrinterProfileStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.PdfRendererService
import com.pocketprint.data.print.encoder.HpPcl3Gui2ColorEncoder
import com.pocketprint.data.print.encoder.HpPcl3Gui2Encoder
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.transport.StreamConfig
import com.pocketprint.data.print.transport.StreamProgressCallback
import com.pocketprint.data.print.transport.StreamResult
import com.pocketprint.data.print.transport.StreamSnapshot
import com.pocketprint.data.print.transport.UsbStreamWriter
import com.pocketprint.data.print.transport.UsbJobArbiter
import com.pocketprint.data.print.util.PageRangeParser
import com.pocketprint.data.print.util.PrintPageScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Native Android PrintService for PocketPrint.
 * Integrates directly into Android's system printing architecture (Settings -> Printing).
 * Completely eliminates any dependency on third-party print plugins (e.g. HP Print Service).
 * Any Android application (Chrome, Drive, Files, WhatsApp, etc.) can print directly to
 * connected USB printers through PocketPrint.
 */
class PocketPrintService : PrintService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var usbManager: UsbManager
    private lateinit var profileStore: PrinterProfileStore
    @Volatile
    private var activeWriter: UsbStreamWriter? = null

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        profileStore = PrinterProfileStore(this)
        DiagnosticLogger.log("PRINT_SERVICE", "PocketPrint Native PrintService created and registered into Android OS")
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                DiagnosticLogger.log("PRINT_SERVICE", "System requested printer discovery")
                discoverAndPublishPrinters()
            }

            override fun onStopPrinterDiscovery() {
                DiagnosticLogger.log("PRINT_SERVICE", "System stopped printer discovery")
            }

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                discoverAndPublishPrinters()
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                discoverAndPublishPrinters()
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {}

            override fun onDestroy() {}

            private fun discoverAndPublishPrinters() {
                val printers = mutableListOf<PrinterInfo>()
                val deviceList = usbManager.deviceList

                for ((_, device) in deviceList) {
                    if (isPrinterDevice(device)) {
                        val printerId = generatePrinterId(device.deviceName)
                        val displayName = device.productName ?: "USB Printer (${device.deviceName})"
                        val candidate = profileStore.getVerifiedProfile(device.vendorId, device.productId)?.candidate
                            ?: CandidateGenerator.generateCandidates().first()

                        val caps = PrinterCapabilitiesInfo.Builder(printerId).apply {
                            addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                            addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                            addMediaSize(PrintAttributes.MediaSize.NA_LEGAL, false)
                            addResolution(
                                PrintAttributes.Resolution(
                                    "${candidate.resolutionDpi}dpi",
                                    "${candidate.resolutionDpi} DPI",
                                    candidate.resolutionDpi,
                                    candidate.resolutionDpi
                                ),
                                true
                            )
                            setColorModes(
                                PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                                PrintAttributes.COLOR_MODE_MONOCHROME
                            )
                            setMinMargins(PrintAttributes.Margins(197, 197, 197, 197))
                        }.build()

                        val hasPermission = usbManager.hasPermission(device)
                        val status = if (hasPermission) PrinterInfo.STATUS_IDLE else PrinterInfo.STATUS_UNAVAILABLE
                        val description = if (hasPermission) "PocketPrint Direct USB (Ready)" else "Permission required in PocketPrint app"

                        val printerInfo = PrinterInfo.Builder(printerId, displayName, status)
                            .setDescription(description)
                            .setCapabilities(caps)
                            .build()

                        printers.add(printerInfo)
                    }
                }

                if (printers.isNotEmpty()) {
                    addPrinters(printers)
                    DiagnosticLogger.log("PRINT_SERVICE", "Published ${printers.size} USB printer(s) to Android system: ${printers.map { it.name }}")
                }
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        DiagnosticLogger.log("PRINT_SERVICE", "Cancel requested for print job: ${printJob.id}")
        activeWriter?.cancel()
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        DiagnosticLogger.log("PRINT_SERVICE", "Print job queued from system: ${printJob.id} ('${printJob.document.info.name}')")

        serviceScope.launch {
            processQueuedJob(printJob)
        }
    }

    private suspend fun processQueuedJob(printJob: PrintJob) = withContext(Dispatchers.IO) {
        val targetDevice = findConnectedPrinter()
        if (targetDevice == null) {
            DiagnosticLogger.log("PRINT_SERVICE", "Failed: No USB printer connected")
            printJob.fail("No USB printer connected. Connect printer via OTG cable.")
            return@withContext
        }

        if (!usbManager.hasPermission(targetDevice)) {
            DiagnosticLogger.log("PRINT_SERVICE", "Failed: USB permission missing for ${targetDevice.deviceName}")
            printJob.fail("USB permission required. Open PocketPrint app and grant permission.")
            return@withContext
        }

        val arbiterOwner = "android-print-${printJob.id}"
        if (!UsbJobArbiter.acquire(arbiterOwner) { printJob.isCancelled }) {
            printJob.cancel()
            return@withContext
        }

        printJob.start()
        DiagnosticLogger.log("PRINT_SERVICE", "Started print job: ${printJob.id}")

        var connection: UsbDeviceConnection? = null
        var claimedInterface: UsbInterface? = null
        var pfd: ParcelFileDescriptor? = null

        try {
            // 1. Resolve configuration candidate
            val verified = profileStore.getVerifiedProfile(targetDevice.vendorId, targetDevice.productId)
            val candidate: DiscoveryCandidate = verified?.candidate ?: CandidateGenerator.generateCandidates().first()
            val options = AndroidPrintOptionsMapper.from(printJob.info, candidate.resolutionDpi)
            DiagnosticLogger.log("PRINT_SERVICE", "Using profile candidate: ${candidate.name} (ZLP: ${candidate.sendZlpFlush})")
            DiagnosticLogger.log(
                "PRINT_SERVICE",
                "System options: ${options.paperSize.name}, ${options.orientation.name}, " +
                    "${options.colorMode.name}, ${options.dpi} DPI, copies=${options.copies}, " +
                    "range='${options.pageRange.ifBlank { "all" }}'"
            )

            // 2. Open USB connection
            connection = usbManager.openDevice(targetDevice)
                ?: throw IllegalStateException("Could not open USB connection to ${targetDevice.deviceName}")

            val (printerInterface, bulkOut) = findBulkOutEndpoint(targetDevice)
                ?: throw IllegalStateException("No printer Bulk OUT endpoint found")

            if (!connection.claimInterface(printerInterface, true)) {
                throw IllegalStateException("Failed to claim USB interface #${printerInterface.id}")
            }
            claimedInterface = printerInterface

            // 3. Render and stream PDF document
            pfd = printJob.document.data
                ?: throw IllegalStateException("PrintJob document data stream is null")

            val rendererService = PdfRendererService(this@PocketPrintService)
            PdfRenderer(pfd).use { pdfRenderer ->
                val pageCount = pdfRenderer.pageCount
                val targetPages = PageRangeParser.parse(options.pageRange, pageCount)
                if (targetPages.isEmpty()) {
                    throw IllegalArgumentException("No valid pages selected for a $pageCount-page document")
                }
                val schedule = PrintPageScheduler.schedule(targetPages, options.copies, options.collate)
                DiagnosticLogger.log(
                    "PRINT_SERVICE",
                    "Rendering ${targetPages.size} selected page(s), ${schedule.size} transmission(s) at ${options.dpi} DPI..."
                )

                for ((sequenceIndex, scheduledPage) in schedule.withIndex()) {
                    if (printJob.isCancelled) throw PrintCancelledException("Cancelled before next page")
                    val pageIndex = scheduledPage.pageIndex
                    val page = pdfRenderer.openPage(pageIndex)
                    val bitmap = try {
                        rendererService.renderPdfPageToBitmap(page, options)
                    } finally {
                        page.close()
                    }

                    val payload = try {
                        when {
                            options.colorMode == PrintColorMode.COLOR_RGB -> {
                                val output = ByteArrayOutputStream()
                                HpPcl3Gui2ColorEncoder().apply {
                                    encodeJobStart(options.dpi, bitmap.width, output)
                                    encodeRasterPage(bitmap, options.dpi, output)
                                    encodeJobEnd(output)
                                }
                                output.toByteArray()
                            }
                            candidate.protocol == DiscoveryProtocol.PCL3GUI2_K_MODE9 ||
                                candidate.protocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10 -> {
                                val output = ByteArrayOutputStream()
                                HpPcl3Gui2Encoder().apply {
                                    encodeJobStart(options.dpi, bitmap.width, output)
                                    encodeRasterPage(bitmap, options.dpi, output)
                                    encodeJobEnd(output)
                                }
                                output.toByteArray()
                            }
                            else -> PayloadGenerator.generatePayload(candidate, bitmap)
                        }
                    } finally {
                        bitmap.recycle()
                    }

                    DiagnosticLogger.log(
                        "PRINT_SERVICE",
                        "Item ${sequenceIndex + 1}/${schedule.size}: document page ${pageIndex + 1}, " +
                            "copy ${scheduledPage.copyNumber}/${options.copies}, ${payload.size} bytes"
                    )

                    // Stream via the shared, failure-safe USB engine (production profile).
                    val writer = UsbStreamWriter(
                        connection = connection,
                        bulkOut = bulkOut,
                        usbManager = usbManager,
                        device = targetDevice,
                        interfaceId = printerInterface.id,
                        config = StreamConfig.reliable(),
                        claimedProvider = { claimedInterface === printerInterface }
                    )
                    activeWriter = writer
                    val streamResult = writer.stream(
                        data = payload,
                        callback = object : StreamProgressCallback {
                            override fun onProgress(snapshot: StreamSnapshot) {
                                if (snapshot.percentComplete % 10 == 0) {
                                    DiagnosticLogger.log(
                                        "PRINT_SERVICE",
                                        "Item ${sequenceIndex + 1}/${schedule.size}: ${snapshot.percentComplete}% " +
                                            "(${snapshot.confirmedBytes}/${snapshot.totalBytes} B, " +
                                            "chunk ${snapshot.chunkIndex}/${snapshot.totalChunks}, " +
                                            "retry ${snapshot.retryCount})"
                                    )
                                }
                            }

                            override fun onRetry(
                                offset: Long,
                                retryNumber: Int,
                                maxRetries: Int,
                                reason: String
                            ) {
                                DiagnosticLogger.log(
                                    "PRINT_SERVICE",
                                    "USB stall at ${offset}B - retry $retryNumber/$maxRetries: $reason"
                                )
                            }
                        }
                    )
                    activeWriter = null
                    when (streamResult) {
                        is StreamResult.Completed -> {
                            DiagnosticLogger.log(
                                "PRINT_SERVICE",
                                "Item ${sequenceIndex + 1}/${schedule.size} transmitted: ${streamResult.totalConfirmed} bytes"
                            )
                            if (candidate.sendZlpFlush) {
                                val zlp = connection.bulkTransfer(bulkOut, ByteArray(0), 0, 2000)
                                DiagnosticLogger.log("PRINT_SERVICE", "Page ZLP flush result: $zlp")
                                if (zlp < 0) {
                                    throw IllegalStateException(
                                        "Page payload was delivered, but ZLP finalization failed; output is uncertain"
                                    )
                                }
                            }
                            if (candidate.performSoftReset) {
                                val reset = connection.controlTransfer(
                                    0x21,
                                    0x02,
                                    0,
                                    printerInterface.id,
                                    null,
                                    0,
                                    1000
                                )
                                DiagnosticLogger.log("PRINT_SERVICE", "Page soft reset result: $reset")
                            }
                            Thread.sleep(500)
                        }
                        is StreamResult.TransferFailed -> {
                            val note = if (streamResult.ambiguous) {
                                " Transfer outcome ambiguous; job must not be replayed automatically."
                            } else ""
                            throw IllegalStateException(
                                "USB stream failed at ${streamResult.failureOffset}B " +
                                    "after ${streamResult.retryCount} retries.$note " +
                                    streamResult.detail
                            )
                        }
                        is StreamResult.Cancelled -> throw PrintCancelledException(
                            "Cancelled after ${streamResult.confirmedSoFar} bytes"
                        )
                    }
                }
            }

            printJob.complete()
            DiagnosticLogger.log("PRINT_SERVICE", "Print job successfully completed: ${printJob.id}")

        } catch (e: PrintCancelledException) {
            DiagnosticLogger.log("PRINT_SERVICE", "Print job cancelled: ${e.message}")
            printJob.cancel()
        } catch (e: Exception) {
            DiagnosticLogger.log("PRINT_SERVICE", "Print job error: ${e.message}")
            printJob.fail("Print failed: ${e.message}")
        } finally {
            activeWriter = null
            try {
                pfd?.close()
                claimedInterface?.let { connection?.releaseInterface(it) }
                connection?.close()
            } catch (ignored: Exception) {}
            UsbJobArbiter.release(arbiterOwner)
        }
    }

    private fun findConnectedPrinter(): UsbDevice? {
        val deviceList = usbManager.deviceList
        // Priority to HP (0x03F0) or printer class (7)
        return deviceList.values.firstOrNull { it.vendorId == 0x03F0 && isPrinterDevice(it) }
            ?: deviceList.values.firstOrNull { isPrinterDevice(it) }
    }

    private fun isPrinterDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
        }
        return false
    }

    private fun findBulkOutEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        return Pair(iface, ep)
                    }
                }
            }
        }
        return null
    }

    private class PrintCancelledException(message: String) : Exception(message)
}
