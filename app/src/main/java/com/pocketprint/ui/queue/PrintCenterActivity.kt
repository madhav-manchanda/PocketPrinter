package com.pocketprint.ui.queue

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pocketprint.PocketPrintApp
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.quality.PrintQualityPreview
import com.pocketprint.data.print.quality.RasterDiagnosticStore
import com.pocketprint.data.print.quality.PageProcessingDecision
import com.pocketprint.data.queue.PrintOptionsJson
import com.pocketprint.databinding.ActivityPrintCenterBinding
import com.pocketprint.service.LocalPrintForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintCenterActivity : AppCompatActivity() {

    private data class PreviewResult(
        val pageCount: Int,
        val bitmap: Bitmap,
        val dpi: Int,
        val decision: PageProcessingDecision
    )

    private lateinit var binding: ActivityPrintCenterBinding
    private lateinit var app: PocketPrintApp
    private lateinit var queueAdapter: LocalQueueAdapter
    private var previewBitmap: Bitmap? = null
    private var previewUri: Uri? = null
    private var previewMime: String? = null
    private var previewName: String = "Document"
    private var previewOptions = PrintOptions()
    private var previewPage = 0
    private var previewPageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrintCenterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        app = application as PocketPrintApp

        queueAdapter = LocalQueueAdapter(
            onCancel = { LocalPrintForegroundService.cancel(this, it.id) },
            onRetry = {
                if (app.localPrintQueue.retrySafe(it.id) != null) LocalPrintForegroundService.start(this)
            }
        )
        binding.rvQueue.layoutManager = LinearLayoutManager(this)
        binding.rvQueue.adapter = queueAdapter
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.btnClearFinished.setOnClickListener { app.localPrintQueue.clearFinished() }
        binding.btnPreviousPage.setOnClickListener {
            if (previewPage > 0) {
                previewPage--
                renderPreview()
            }
        }
        binding.btnNextPage.setOnClickListener {
            if (previewPage + 1 < previewPageCount) {
                previewPage++
                renderPreview()
            }
        }

        previewUri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        previewMime = intent.getStringExtra(EXTRA_MIME)
        previewName = intent.getStringExtra(EXTRA_NAME) ?: "Document"
        previewOptions = intent.getStringExtra(EXTRA_OPTIONS)?.let {
            runCatching { PrintOptionsJson.decode(it) }.getOrNull()
        } ?: PrintOptions()
        if (previewUri != null) renderPreview()
        observeState()
    }

    override fun onDestroy() {
        previewBitmap?.recycle()
        previewBitmap = null
        super.onDestroy()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.localPrintQueue.jobs.collectLatest { jobs ->
                        queueAdapter.submitList(jobs)
                        binding.tvQueueEmpty.visibility = if (jobs.isEmpty()) View.VISIBLE else View.GONE
                        binding.rvQueue.visibility = if (jobs.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    app.printJobManager.statusManager.progressFlow.collectLatest { progress ->
                        val recentErrors = DiagnosticLogger.logsFlow.value
                            .filter { it.level.name == "ERROR" || it.level.name == "WARN" }
                            .takeLast(3)
                            .joinToString("\n") { it.displayString }
                        binding.tvDiagnostics.text = buildString {
                            appendLine("SERVICE: ${if (app.localPrintQueue.jobs.value.any { !it.state.isTerminal }) "ACTIVE" else "IDLE"}")
                            appendLine("STATE: ${progress.state.name}")
                            appendLine("DOCUMENT: ${progress.documentName.ifBlank { "-" }}")
                            appendLine("PAGE: ${progress.documentPage}/${progress.documentPageCount}")
                            appendLine("ITEM: ${progress.scheduledItem}/${progress.scheduledItemCount}")
                            appendLine("PAYLOAD: ${progress.bytesTransferred}/${progress.totalBytes}")
                            appendLine("OFFSET: ${progress.currentOffset}")
                            appendLine("CHUNK: ${progress.currentChunkIndex}/${progress.totalChunks}")
                            appendLine("RETRIES: ${progress.retryCount}")
                            appendLine("CONNECTION: ${progress.connectionState.ifBlank { "-" }}")
                            if (recentErrors.isNotBlank()) append("\nRECENT WARNINGS\n$recentErrors")
                        }
                    }
                }
            }
        }
    }

    private fun renderPreview() {
        val uri = previewUri ?: return
        binding.tvPreviewStatus.text = "Rendering ${previewName}, page ${previewPage + 1}..."
        binding.btnPreviousPage.isEnabled = false
        binding.btnNextPage.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val printerDpi = app.usbDeviceManager.deviceListFlow.value
                        .firstOrNull { it.isPrinterCandidate }
                        ?.let { app.profileStore.getVerifiedProfile(it.vendorId, it.productId) }
                        ?.candidate
                        ?.resolutionDpi
                        ?: previewOptions.dpi
                    val renderOptions = previewOptions.copy(dpi = printerDpi)
                    val count = app.pdfRendererService.getPageCount(uri, previewMime, renderOptions)
                    val finalBitmap = app.pdfRendererService.renderPageToBitmap(
                        uri,
                        previewPage.coerceIn(0, (count - 1).coerceAtLeast(0)),
                        renderOptions,
                        previewMime
                    )
                    val decision = PrintQualityPreview.applyInPlace(finalBitmap, renderOptions)
                    RasterDiagnosticStore.saveLatest(this@PrintCenterActivity, finalBitmap)
                    val displayBitmap = if (finalBitmap.width > MAX_PREVIEW_WIDTH) {
                        val displayHeight = finalBitmap.height * MAX_PREVIEW_WIDTH / finalBitmap.width
                        Bitmap.createScaledBitmap(
                            finalBitmap,
                            MAX_PREVIEW_WIDTH,
                            displayHeight,
                            true
                        ).also { finalBitmap.recycle() }
                    } else {
                        finalBitmap
                    }
                    PreviewResult(count, displayBitmap, printerDpi, decision)
                }
            }.onSuccess { result ->
                val count = result.pageCount
                val bitmap = result.bitmap
                val printerDpi = result.dpi
                previewPageCount = count
                previewBitmap?.recycle()
                previewBitmap = bitmap
                binding.ivPreview.setImageBitmap(bitmap)
                binding.tvPreviewStatus.text =
                    "$previewName | page ${previewPage + 1} of $count | " +
                        "${result.decision.selectedColorMode.displayName} at $printerDpi DPI | " +
                        "hybrid applied: ${result.decision.hybridApplied}"
                binding.btnPreviousPage.isEnabled = previewPage > 0
                binding.btnNextPage.isEnabled = previewPage + 1 < count
            }.onFailure { error ->
                binding.tvPreviewStatus.text = "Preview failed: ${error.message}"
            }
        }
    }

    companion object {
        private const val EXTRA_URI = "preview_uri"
        private const val EXTRA_MIME = "preview_mime"
        private const val EXTRA_NAME = "preview_name"
        private const val EXTRA_OPTIONS = "preview_options"
        private const val MAX_PREVIEW_WIDTH = 1200

        fun start(
            context: Context,
            uri: Uri? = null,
            name: String? = null,
            mimeType: String? = null,
            options: PrintOptions = PrintOptions()
        ) {
            context.startActivity(Intent(context, PrintCenterActivity::class.java).apply {
                putExtra(EXTRA_URI, uri?.toString())
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_MIME, mimeType)
                putExtra(EXTRA_OPTIONS, PrintOptionsJson.encode(options))
            })
        }
    }
}
