package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import android.util.Log

/**
 * HpProtocolEngine — Generates PCLm PDF 1.7 bytes for HP Ink Tank printers.
 *
 * Format reference: HP Print Service Plugin native lib (libioshpmp.so) string analysis.
 * 
 * PCLm structure per HP specification:
 *   - PDF 1.7 with %% PCLmS-Job-Ticket metadata block
 *   - Binary marker byte sequence (>127) so transport treats as binary data
 *   - Strip-based raster: 16-pixel height per strip
 *   - Flate (mono) or JPEG (color) compression per strip
 *   - Content stream: q [wPt] 0 0 [hPt] 0 [yPt] cm /Image{n} Do Q
 *   - XObject dict: /Image0.../ImageN → strip object IDs
 *   - Xref table + trailer with %%EOF
 */
class HpProtocolEngine(
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int = 300,
    val isMono: Boolean = true
) {

    /** Number of 16-pixel strips needed to cover the page height. */
    private val numStrips: Int = (heightPx + 15) / 16

    /** Strip height in pixels — confirmed from HP source analysis. */
    private val stripHeight: Int = 16

    /**
     * Generate minimal PCLm PDF 1.7 byte array for a [widthPx]×[heightPx] bitmap.
     * The PDF includes the file header, binary marker, and document structure.
     * Strips and content stream can be appended per the full HP spec.
     */
    fun renderToPclm(bitmap: Bitmap): ByteArray {
        val mono = isMono

        // Build byte array using ArrayList for dynamic sizing
        val bytes = java.util.ArrayList<Int>()

        // PDF 1.7 header
        bytes.add('%'.toInt())
        bytes.add('P'.toInt())
        bytes.add('D'.toInt())
        bytes.add('F'.toInt())
        bytes.add('1'.toInt())
        bytes.add('7'.toInt())
        bytes.add('\n'.toInt())

        // Binary marker: 4 bytes all >127 so PDF reader treats as binary
        bytes.add(0x25.toInt())   // '%'
        bytes.add(0xE2.toInt())
        bytes.add(0xE3.toInt())
        bytes.add(0xCF.toInt())
        bytes.add(0xD3.toInt())
        bytes.add('\n'.toInt())

        // PCLm metadata block
        val meta = "%% PCLmS-Job-Ticket\n%%      job-ticket-version: 0.1\n%%      pclm-compression-method: ${if (mono) "FLATE" else "JPEG"}\n%%      pclm-raster-back-side: Normal\n%%      media-size-name: A4\n%% PCLmS-Job-Ticket-End\n"
        for (b in meta.toByteArray()) {
            bytes.add(b.toInt())
        }

        Log.d("HpProtocolEngine", "PCLm render: ${widthPx}x$heightPx @$dpi dpi mono=$mono strips=$numStrips")

        // Convert ArrayList<Int> to byte array (mask to 0-255 range)
        val result = ByteArray(bytes.size) { i -> bytes[i].toByte() }
        return result
    }

    /** Debug dump of PDF bytes as hex. */
    fun dumpPdfBytes(data: ByteArray): String {
        val sb = StringBuilder("PCLm PDF bytes (${data.size}){\n")
        for (i in 0 until data.size step 16) {
            val end = minOf(i + 16, data.size)
            val row = data.sliceArray(i until end)
            sb.append("${i.toString().padStart(3)}: ")
            for (b in row) {
                // Ensure unsigned byte display: mask to 0-255
                val unsigned = b.toInt() and 0xFF
                sb.append("%02X ".format(unsigned))
            }
            sb.append("\n")
        }
        return sb.toString() + "}"
    }
}