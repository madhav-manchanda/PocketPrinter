package com.pocketprint.ui.main

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PageSet
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import com.pocketprint.databinding.DialogPrintSettingsBinding
import java.util.Locale

object PrintSettingsDialog {

    private data class MarginChoice(val label: String, val margins: PrintMargins)
    private data class DpiChoice(val dpi: Int, val label: String)

    fun show(
        context: Context,
        documentName: String,
        mimeType: String?,
        initial: PrintOptions,
        onPreview: (PrintOptions) -> Unit = {},
        onPrint: (PrintOptions) -> Unit
    ) {
        val binding = DialogPrintSettingsBinding.inflate(android.view.LayoutInflater.from(context))
        val paperSizes = PaperSize.entries.filter { it != PaperSize.CUSTOM }
        val orientations = PrintOrientation.entries
        val scalingModes = PrintScaling.entries
        val printingModes = PrintingMode.entries
        val pageSets = PageSet.entries
        val monochromeStrategies = MonochromeStrategy.entries
        val qualityProfiles = PrintQualityProfile.entries
        val dpiChoices = listOf(
            DpiChoice(300, "300 DPI - verified production"),
            DpiChoice(600, "600 DPI - experimental, requires verification"),
            DpiChoice(1200, "1200 DPI - validation strip only")
        )
        val marginChoices = listOf(
            MarginChoice("None (edge to edge)", PrintMargins.NONE),
            MarginChoice("Minimum (5 mm)", PrintMargins.MINIMUM),
            MarginChoice("Normal (10 mm)", PrintMargins.NORMAL),
            MarginChoice("Wide (20 mm)", PrintMargins.WIDE)
        )

        bindDropdown(binding.actPaperSize, paperSizes.map { it.displayName })
        bindDropdown(binding.actOrientation, orientations.map { it.displayName })
        bindDropdown(binding.actScaling, scalingModes.map { it.displayName })
        bindDropdown(binding.actColorMode, printingModes.map { it.displayName })
        bindDropdown(binding.actMargins, marginChoices.map { it.label })
        bindDropdown(binding.actPageSet, pageSets.map { it.displayName })
        bindDropdown(binding.actMonoStrategy, monochromeStrategies.map { it.displayName })
        bindDropdown(binding.actQualityProfile, qualityProfiles.map { it.displayName })
        bindDropdown(binding.actDpi, dpiChoices.map { it.label })

        binding.actPaperSize.setText(initial.paperSize.displayName, false)
        binding.actOrientation.setText(initial.orientation.displayName, false)
        binding.actScaling.setText(initial.scaling.displayName, false)
        binding.actColorMode.setText(initial.printingMode.displayName, false)
        binding.switchHybridProcessing.isChecked = initial.hybridProcessingEnabled
        binding.actMargins.setText(
            marginChoices.firstOrNull { it.margins == initial.margins }?.label
                ?: marginChoices[1].label,
            false
        )
        binding.actPageSet.setText(initial.pageSet.displayName, false)
        binding.actMonoStrategy.setText(initial.monochromeStrategy.displayName, false)
        binding.etMonoThreshold.setText(initial.monochromeThreshold.toString())
        binding.actQualityProfile.setText(
            initial.qualityProfile.takeIf { it in qualityProfiles }?.displayName
                ?: PrintQualityProfile.NORMAL.displayName,
            false
        )
        binding.actDpi.setText(
            dpiChoices.firstOrNull { it.dpi == initial.dpi }?.label ?: dpiChoices.first().label,
            false
        )
        binding.etPageRange.setText(initial.pageRange)
        binding.etCopies.setText(initial.copies.coerceIn(1, 99).toString())
        binding.checkCollate.isChecked = initial.collate
        binding.tvDocumentName.text = documentName
        binding.tvDocumentType.text = describeMimeType(mimeType)

        val isSinglePage = mimeType?.startsWith("image/") == true &&
            !mimeType.equals("image/tiff", ignoreCase = true)
        if (isSinglePage) {
            binding.etPageRange.setText("")
            binding.layoutPageRange.isEnabled = false
            binding.layoutPageRange.helperText = "This document renders as one page"
        }

        fun currentPaper(): PaperSize = paperSizes.firstOrNull {
            it.displayName == binding.actPaperSize.text.toString()
        } ?: initial.paperSize.takeIf { it != PaperSize.CUSTOM } ?: PaperSize.A4

        fun currentOrientation(): PrintOrientation = orientations.firstOrNull {
            it.displayName == binding.actOrientation.text.toString()
        } ?: initial.orientation

        fun currentScaling(): PrintScaling = scalingModes.firstOrNull {
            it.displayName == binding.actScaling.text.toString()
        } ?: initial.scaling

        fun currentPrintingMode(): PrintingMode = printingModes.firstOrNull {
            it.displayName == binding.actColorMode.text.toString()
        } ?: initial.printingMode

        fun currentColorMode(): PrintColorMode = when (currentPrintingMode()) {
            PrintingMode.AUTO -> PrintColorMode.MONOCHROME_1BIT
            PrintingMode.BLACK_AND_WHITE -> PrintColorMode.MONOCHROME_1BIT
            PrintingMode.GRAYSCALE -> PrintColorMode.GRAYSCALE_8BIT
            PrintingMode.COLOR -> PrintColorMode.COLOR_RGB
        }

        fun currentMargins(): PrintMargins = marginChoices.firstOrNull {
            it.label == binding.actMargins.text.toString()
        }?.margins ?: PrintMargins.MINIMUM

        fun currentPageSet(): PageSet = pageSets.firstOrNull {
            it.displayName == binding.actPageSet.text.toString()
        } ?: initial.pageSet

        fun currentMonochromeStrategy(): MonochromeStrategy = monochromeStrategies.firstOrNull {
            it.displayName == binding.actMonoStrategy.text.toString()
        } ?: initial.monochromeStrategy

        fun currentQualityProfile(): PrintQualityProfile = qualityProfiles.firstOrNull {
            it.displayName == binding.actQualityProfile.text.toString()
        } ?: PrintQualityProfile.NORMAL

        fun currentDpi(): Int = dpiChoices.firstOrNull {
            it.label == binding.actDpi.text.toString()
        }?.dpi ?: 300

        fun updateSummary() {
            val preview = initial.copy(
                paperSize = currentPaper(),
                orientation = currentOrientation(),
                scaling = currentScaling(),
                margins = currentMargins(),
                colorMode = currentColorMode()
            )
            val orientation = preview.orientation.displayName.lowercase(Locale.US)
            val scaling = when (preview.scaling) {
                PrintScaling.FIT_TO_PAGE -> "Fit to page"
                PrintScaling.FILL_PAGE -> "Fill page"
                PrintScaling.ACTUAL_SIZE -> "Actual size"
            }
            binding.tvPrintPreviewSummary.text = String.format(
                Locale.US,
                "%s %s | %d DPI | Printable area %.0f x %.0f mm | %s",
                preview.paperSize.name,
                orientation,
                maxOf(currentDpi(), currentQualityProfile().targetDpi),
                preview.printableWidthMm,
                preview.printableHeightMm,
                scaling
            )
            binding.tvGrayscaleNote.visibility = if (
                currentPrintingMode() == PrintingMode.GRAYSCALE
            ) View.VISIBLE else View.GONE
            val showMonochrome = currentPrintingMode() == PrintingMode.AUTO ||
                currentPrintingMode() == PrintingMode.BLACK_AND_WHITE
            binding.layoutMonoStrategy.visibility = if (showMonochrome) View.VISIBLE else View.GONE
            binding.layoutMonoThreshold.visibility = if (showMonochrome) View.VISIBLE else View.GONE
            binding.tvHybridNote.visibility = if (binding.switchHybridProcessing.isChecked) {
                View.VISIBLE
            } else {
                View.GONE
            }
            val copies = binding.etCopies.text?.toString()?.toIntOrNull() ?: 1
            binding.checkCollate.isEnabled = copies > 1
        }

        listOf(
            binding.actPaperSize,
            binding.actOrientation,
            binding.actScaling,
            binding.actMargins,
            binding.actColorMode,
            binding.actPageSet,
            binding.actMonoStrategy,
            binding.actQualityProfile,
            binding.actDpi
        ).forEach { dropdown -> dropdown.setOnItemClickListener { _, _, _, _ -> updateSummary() } }
        binding.actQualityProfile.setOnItemClickListener { _, _, _, _ ->
            val targetDpi = currentQualityProfile().targetDpi
            binding.actDpi.setText(
                dpiChoices.first { it.dpi == targetDpi }.label,
                false
            )
            updateSummary()
        }
        binding.etCopies.doAfterTextChanged { updateSummary() }
        binding.switchHybridProcessing.setOnCheckedChangeListener { _, _ -> updateSummary() }
        updateSummary()

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Print settings")
            .setView(binding.root)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Preview", null)
            .setPositiveButton("Add to queue", null)
            .create()

        dialog.setOnShowListener {
            fun selectedOptions(copies: Int): PrintOptions {
                val quality = currentQualityProfile()
                return initial.copy(
                paperSize = currentPaper(),
                dpi = maxOf(currentDpi(), quality.targetDpi),
                printingMode = currentPrintingMode(),
                hybridProcessingEnabled = binding.switchHybridProcessing.isChecked,
                colorMode = currentColorMode(),
                orientation = currentOrientation(),
                scaling = currentScaling(),
                margins = currentMargins(),
                pageRange = if (isSinglePage) "" else binding.etPageRange.text?.toString()?.trim().orEmpty(),
                pageSet = if (isSinglePage) PageSet.ALL else currentPageSet(),
                qualityProfile = quality,
                monochromeStrategy = currentMonochromeStrategy(),
                monochromeThreshold = binding.etMonoThreshold.text?.toString()?.toIntOrNull()
                    ?.coerceIn(0, 255) ?: initial.monochromeThreshold,
                copies = copies,
                collate = binding.checkCollate.isChecked
            )
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                binding.layoutDpi.error = null
                val copies = binding.etCopies.text?.toString()?.toIntOrNull()?.coerceIn(1, 99) ?: 1
                onPreview(selectedOptions(copies))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val copies = binding.etCopies.text?.toString()?.toIntOrNull()
                if (copies == null || copies !in 1..99) {
                    binding.layoutCopies.error = "Enter a value from 1 to 99"
                    return@setOnClickListener
                }
                binding.layoutCopies.error = null
                binding.layoutDpi.error = null
                val threshold = binding.etMonoThreshold.text?.toString()?.toIntOrNull()
                if (threshold == null || threshold !in 0..255) {
                    binding.layoutMonoThreshold.error = "Enter a value from 0 to 255"
                    return@setOnClickListener
                }
                binding.layoutMonoThreshold.error = null
                val options = selectedOptions(copies)
                dialog.dismiss()
                onPrint(options)
            }
        }
        dialog.show()
    }

    private fun bindDropdown(view: AutoCompleteTextView, labels: List<String>) {
        view.setAdapter(ArrayAdapter(view.context, android.R.layout.simple_list_item_1, labels))
        view.setOnClickListener { view.showDropDown() }
    }

    private fun describeMimeType(mimeType: String?): String = when {
        mimeType == "application/pdf" -> "PDF document"
        mimeType?.startsWith("image/") == true -> "Image document"
        mimeType?.startsWith("text/") == true -> "Text document"
        else -> mimeType ?: "Document"
    }
}
