package com.pocketprint.service

import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintJobInfo
import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintingMode

/** Maps Android's system print dialog selections into PocketPrint options. */
internal object AndroidPrintOptionsMapper {

    fun from(info: PrintJobInfo, verifiedDpi: Int): PrintOptions {
        val attributes = info.attributes
        val mediaSize = attributes?.mediaSize
        val portraitMedia = mediaSize?.asPortrait()
        val paperSize = when (portraitMedia?.id) {
            PrintAttributes.MediaSize.NA_LETTER.id -> PaperSize.LETTER
            PrintAttributes.MediaSize.NA_LEGAL.id -> PaperSize.LEGAL
            else -> PaperSize.A4
        }
        val orientation = if (mediaSize?.isPortrait == false) {
            PrintOrientation.LANDSCAPE
        } else {
            PrintOrientation.PORTRAIT
        }
        val colorMode = if (attributes?.colorMode == PrintAttributes.COLOR_MODE_COLOR) {
            PrintColorMode.COLOR_RGB
        } else {
            PrintColorMode.MONOCHROME_1BIT
        }
        // Android source apps already lay out the spool PDF inside the selected
        // printable margins. Applying them again here would shrink it twice.
        val margins = PrintMargins.NONE

        return PrintOptions(
            paperSize = paperSize,
            dpi = verifiedDpi,
            printingMode = if (colorMode == PrintColorMode.COLOR_RGB) {
                PrintingMode.COLOR
            } else {
                PrintingMode.BLACK_AND_WHITE
            },
            colorMode = colorMode,
            orientation = orientation,
            margins = margins,
            pageRange = toPageRange(info.pages),
            copies = info.copies.coerceIn(1, 99),
            collate = true
        )
    }

    private fun toPageRange(ranges: Array<PageRange>?): String {
        if (ranges.isNullOrEmpty() || ranges.any { it == PageRange.ALL_PAGES }) return ""
        return ranges.joinToString(",") { range ->
            val start = range.start + 1
            val end = range.end + 1
            if (start == end) "$start" else "$start-$end"
        }
    }
}
