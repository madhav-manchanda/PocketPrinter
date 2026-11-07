package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.print.model.PrintOptions
import java.io.OutputStream

interface PrinterDataEncoder {
    val protocolName: String

    /**
     * Encodes a rendered document bitmap into printer-ready stream bytes.
     */
    fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream)
}
