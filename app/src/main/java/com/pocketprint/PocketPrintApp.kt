package com.pocketprint

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import com.pocketprint.data.agent.DeviceAuthManager
import com.pocketprint.data.agent.storage.RemoteJobQueueStore
import com.pocketprint.data.discovery.engine.ExperimentManager
import com.pocketprint.data.discovery.storage.ExperimentHistoryStore
import com.pocketprint.data.discovery.storage.PrinterProfileStore
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.PdfRendererService
import com.pocketprint.data.print.PrintJobJournal
import com.pocketprint.data.print.PrintJobManager
import com.pocketprint.data.print.PrintStatusManager
import com.pocketprint.data.print.PrintPreferences
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.PrinterProtocolManager
import com.pocketprint.data.print.transport.UsbPrintTransport
import com.pocketprint.data.queue.LocalPrintQueueStore
import com.pocketprint.data.usb.UsbDeviceManager
import com.pocketprint.service.PocketPrintAgentService

class PocketPrintApp : Application() {

    lateinit var usbDeviceManager: UsbDeviceManager
        private set

    lateinit var printJobManager: PrintJobManager
        private set

    lateinit var profileStore: PrinterProfileStore
        private set

    lateinit var historyStore: ExperimentHistoryStore
        private set

    lateinit var experimentManager: ExperimentManager
        private set

    lateinit var authManager: DeviceAuthManager
        private set

    lateinit var queueStore: RemoteJobQueueStore
        private set

    lateinit var printJobJournal: PrintJobJournal
        private set

    lateinit var localPrintQueue: LocalPrintQueueStore
        private set

    lateinit var pdfRendererService: PdfRendererService
        private set

    lateinit var printPreferences: PrintPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        authManager = DeviceAuthManager(this)
        printPreferences = PrintPreferences(this)
        queueStore = RemoteJobQueueStore(this)
        queueStore.recoverInterruptedJobs()

        printJobJournal = PrintJobJournal(this)
        val interruptedJobs = printJobJournal.recoverInterruptedJobs()

        localPrintQueue = LocalPrintQueueStore(this)
        localPrintQueue.recoverInterruptedJobs()

        usbDeviceManager = UsbDeviceManager(this)
        // Register USB hotplug monitoring once at application scope so attach/detach
        // events are received regardless of Activity visibility or screen state.
        usbDeviceManager.register()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        pdfRendererService = PdfRendererService(this)
        val protocolManager = PrinterProtocolManager()
        val printTransport = UsbPrintTransport(usbManager)
        val printStatusManager = PrintStatusManager()

        profileStore = PrinterProfileStore(this)
        historyStore = ExperimentHistoryStore(this)

        printJobManager = PrintJobManager(
            context = this,
            pdfRendererService = pdfRendererService,
            protocolManager = protocolManager,
            printTransport = printTransport,
            statusManager = printStatusManager,
            profileStore = profileStore,
            journal = printJobJournal,
            connectionManager = usbDeviceManager.printerConnectionManager
        )

        if (interruptedJobs.isNotEmpty()) {
            printStatusManager.updateState(
                PrintJobState.NEEDS_RECOVERY,
                "${interruptedJobs.size} interrupted print job(s) need recovery. " +
                "Resubmit the document; automatic replay is disabled."
            )
        }

        experimentManager = ExperimentManager(usbManager, profileStore, historyStore)

        // Start background agent service if VPS connection is configured
        if (authManager.serverUrl.isNotBlank() && (authManager.deviceToken != null || authManager.isPasswordConfigured)) {
            PocketPrintAgentService.start(this)
        }

        DiagnosticLogger.log(
            "INIT",
            "PocketPrint initialized on ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        )
    }

    companion object {
        lateinit var instance: PocketPrintApp
            private set
    }
}
