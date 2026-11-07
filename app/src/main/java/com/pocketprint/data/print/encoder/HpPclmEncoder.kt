package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * HP PCLm encoder for USB printing on HP Ink Tank 310 and similar modern HP inkjet printers.
 *
 * PCLm is a minimal PDF 1.7 subset defined by HP (~2011). The HP Print Service Plugin
 * native lib (libioshpmp.so) uses PCLmGenerator (C++) with the following confirmed PDF
 * object layout, reconstructed from the binary's embedded format strings:
 *
 *   Object 1:  Catalog  → /Pages 2 0 R
 *   Object 2:  Pages    → /Kids [3 0 R]
 *   Object 3:  Page     → /MediaBox [0 0 W H] (integer pixels × scale factor)
 *                          /Resources << /XObject << /image 4 0 R >> >>
 *                          /Contents 5 0 R
 *   Object 4:  Image Transform Form XObject (content stream: draws each strip)
 *              For each strip s:
 *                %f 0 0 %f 0 0 cm  q /Image{s} Do Q
 *   Object 5:  Page content stream → "q /image Do Q"  (calls object 4)
 *   Object 6+: Strip XObjects (one per strip, /Subtype /Image)
 *
 * Wait — after further analysis the HP structure is simpler: there is no Form XObject wrapper.
 * The page content stream directly contains the per-strip draw commands, and each strip is an
 * Image XObject referenced directly from the page Resources. The "q /image Do Q" string is
 * a legacy single-image path; the multi-strip path uses "/Image%d Do Q" per strip.
 *
 * Confirmed from libioshpmp.so strings:
 *   "/Image%d %d 0 R"           ← XObject dict entry per strip
 *   "%f 0 0 %f 0 0 cm"          ← scale transform (no Y translation in this call)
 *   "/Image%d Do Q"             ← draw strip in content stream
 *   "/MediaBox [ 0 0 %d %d ]"   ← integer point dimensions
 *   "/BitsPerComponent 8"       ← always 8-bit
 *   "/Filter /FlateDecode"      ← mono strips
 *   "/Filter /DCTDecode"        ← color strips
 *   "/ColorSpace /DeviceGray"   ← mono
 *   "/ColorSpace /DeviceRGB"    ← color
 *
 * The MediaBox uses integer *points* (1pt = 1/72 inch). Strip Y position in content stream:
 * strips are drawn top-to-bottom. PDF coordinate origin is bottom-left so strip 0 (top of page)
 * is placed at y = pageHeightPt - stripHeightPt, strip 1 at y = pageHeightPt - 2*stripHeightPt,
 * etc. The cm transform for each strip is:
 *   [stripWidthPt] 0 0 [stripHeightPt] 0 [yOffset] cm
 *
 * Note: The "%f 0 0 %f 0 0 cm" string is the Image Transform object (a separate content
 * stream that scales to full page size), used for single-image passthrough. For raster strips
 * the per-strip "q [w] 0 0 [h] 0 [y] cm /Image%d Do Q" form is used.
 */
class HpPclmEncoder : PrinterDataEncoder {

    override val protocolName: String = "HP PCLm (PDF raster strips, USB)"

    companion object {
        /** Strip height in pixel rows. HP uses 16; confirmed from HP source. */
        private const val STRIP_HEIGHT = 16

        /** Deflate level for Flate-compressed mono strips. */
        private const val FLATE_LEVEL = Deflater.BEST_SPEED

        /** Convert pixel count to PDF points (1 pt = 1/72 in). */
        private fun px2pt(px: Int, dpi: Int): Double = px * 72.0 / dpi

        /** Round a point value to nearest integer (HP uses %d for MediaBox). */
        private fun px2ptInt(px: Int, dpi: Int): Int = Math.round(px * 72.0 / dpi).toInt()
    }

    override fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        val width  = bitmap.width
        val height = bitmap.height
        val dpi    = options.dpi
        val mono   = options.isMonochrome

        // PDF MediaBox dimensions in integer points
        val pageWidthPt  = px2ptInt(width, dpi)
        val pageHeightPt = px2ptInt(height, dpi)

        val numStrips = (height + STRIP_HEIGHT - 1) / STRIP_HEIGHT

        DiagnosticLogger.usb(
            "[PCLm] ${width}×${height}px @${dpi}dpi → ${pageWidthPt}×${pageHeightPt}pt " +
            "mono=$mono strips=$numStrips"
        )

        // ── Compress all strips first (we need sizes before writing PDF objects) ──
        val strips: List<ByteArray> = buildStrips(bitmap, width, height, numStrips, mono)
        DiagnosticLogger.usb(
            "[PCLm] Strips compressed: total=${strips.sumOf { it.size }} bytes"
        )

        // ── Build PDF ──────────────────────────────────────────────────────────
        // Object numbering (HP confirmed layout):
        //   1 = Catalog
        //   2 = Pages
        //   3 = Page
        //   4 = Page content stream  ("q /Image0 Do Q\nq /Image1 Do Q\n..." for each strip)
        //   5..5+numStrips-1 = Strip Image XObjects
        //
        // The page Resources XObject dict maps /Image0.../ImageN → strip object IDs.
        // The page content stream places each strip with:
        //   q [wPt] 0 0 [hPt] 0 [yPt] cm /Image{n} Do Q

        val stripObjBase = 5 // first strip object ID

        // Build the page content stream bytes first (needs strip geometry)
        val contentBytes = buildContentStream(numStrips, width, height, dpi, pageWidthPt, pageHeightPt)

        // Build the XObject resource entries for the page
        val xobjEntries = StringBuilder()
        for (s in 0 until numStrips) {
            xobjEntries.append("/Image$s ${stripObjBase + s} 0 R ")
        }

        // ── Accumulate PDF bytes and track xref offsets ───────────────────────
        val pdf = ByteArrayOutputStream(2 * 1024 * 1024)

        // xrefTable[i] = byte offset of object (i+1), 1-based
        val totalObjects = 4 + numStrips // obj 1,2,3,4 + N strips
        val xrefTable    = IntArray(totalObjects) { 0 }

        // ── PDF file header ───────────────────────────────────────────────────
        pdf.writeStr("%PDF-1.7\n")
        // PCLm metadata comment block (must appear before first object per HP spec)
        pdf.writeStr("%% PCLmS-Job-Ticket\n")
        pdf.writeStr("%%      job-ticket-version: 0.1\n")
        pdf.writeStr("%%      pclm-compression-method: ${if (mono) "FLATE" else "JPEG"}\n")
        pdf.writeStr("%%      pclm-raster-back-side: Normal\n")
        pdf.writeStr("%%      media-size-name: ${options.paperSize.name}\n")
        pdf.writeStr("%% PCLmS-Job-Ticket-End\n")
        // Binary marker (4 bytes > 127) so PDF is treated as binary by transport
        pdf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte()))
        pdf.writeStr("\n")

        // ── Object 1: Catalog ─────────────────────────────────────────────────
        pdf.writeStr("%%============= PCLm: FileBody: Object 1 - Catalog\n")
        xrefTable[0] = pdf.size()
        pdf.writeStr("1 0 obj\n")
        pdf.writeStr("<< /Type /Catalog /Pages 2 0 R >>\n")
        pdf.writeStr("endobj\n")

        // ── Object 2: Page Tree ───────────────────────────────────────────────
        pdf.writeStr("%%============= PCLm: FileBody: Object 2 - page tree\n")
        xrefTable[1] = pdf.size()
        pdf.writeStr("2 0 obj\n")
        pdf.writeStr("<< /Type /Pages /Kids [3 0 R] /Count 1 >>\n")
        pdf.writeStr("endobj\n")

        // ── Object 3: Page ────────────────────────────────────────────────────
        pdf.writeStr("%%============= PCLm: FileBody: Object 3 - page object\n")
        xrefTable[2] = pdf.size()
        pdf.writeStr("3 0 obj\n")
        pdf.writeStr("<< /Type /Page\n")
        pdf.writeStr("   /Parent 2 0 R\n")
        pdf.writeStr("   /MediaBox [ 0 0 $pageWidthPt $pageHeightPt ]\n")
        pdf.writeStr("   /Resources <<\n")
        pdf.writeStr("      /XObject << $xobjEntries>>\n")
        pdf.writeStr("   >>\n")
        pdf.writeStr("   /Contents 4 0 R\n")
        pdf.writeStr(">>\n")
        pdf.writeStr("endobj\n")

        // ── Object 4: Page content stream ─────────────────────────────────────
        pdf.writeStr("%%============= PCLm: FileBody: Page Content Stream object\n")
        xrefTable[3] = pdf.size()
        pdf.writeStr("4 0 obj\n")
        pdf.writeStr("<< /Length ${contentBytes.size} >>\n")
        pdf.writeStr("stream\n")
        pdf.write(contentBytes)
        pdf.writeStr("\nendstream\n")
        pdf.writeStr("endobj\n")

        // ── Objects 5..5+N-1: Strip Image XObjects ────────────────────────────
        for (s in 0 until numStrips) {
            val stripData = strips[s]
            val stripY    = s * STRIP_HEIGHT
            val stripH    = minOf(STRIP_HEIGHT, height - stripY)
            val colorSpace = if (mono) "/DeviceGray" else "/DeviceRGB"
            val filter     = if (mono) "/FlateDecode" else "/DCTDecode"

            xrefTable[4 + s] = pdf.size()
            val section = if (mono) "zlib Image" else if (filter == "/DCTDecode") "jpeg Image" else "RLE Image"
            pdf.writeStr("%%============= PCLm: FileBody: Strip Stream: $section\n")
            xrefTable[4 + s] = pdf.size()
            pdf.writeStr("${stripObjBase + s} 0 obj\n")
            pdf.writeStr("<< /Type /XObject\n")
            pdf.writeStr("   /Subtype /Image\n")
            pdf.writeStr("   /Width $width\n")
            pdf.writeStr("   /Height $stripH\n")
            pdf.writeStr("   /ColorSpace $colorSpace\n")
            pdf.writeStr("   /BitsPerComponent 8\n")
            pdf.writeStr("   /Filter $filter\n")
            pdf.writeStr("   /Length ${stripData.size}\n")
            pdf.writeStr(">>\n")
            pdf.writeStr("stream\n")
            pdf.write(stripData)
            pdf.writeStr("\nendstream\n")
            pdf.writeStr("endobj\n")
        }

        // ── Cross-reference table ─────────────────────────────────────────────
        pdf.writeStr("%%============= PCLm: cross-reference section: object 0, ${totalObjects + 1} entries\n")
        val xrefOffset = pdf.size()
        pdf.writeStr("xref\n")
        pdf.writeStr("0 ${totalObjects + 1}\n")
        // Object 0: free head
        pdf.writeStr("0000000000 65535 f \n")
        for (offset in xrefTable) {
            pdf.writeStr("${offset.toString().padStart(10, '0')} 00000 n \n")
        }

        // ── Trailer ───────────────────────────────────────────────────────────
        pdf.writeStr("%%============= PCLm: File Trailer\n")
        pdf.writeStr("trailer\n")
        pdf.writeStr("<< /Size ${totalObjects + 1} /Root 1 0 R >>\n")
        pdf.writeStr("startxref\n")
        pdf.writeStr("$xrefOffset\n")
        pdf.writeStr("%%EOF\n")

        val finalBytes = pdf.toByteArray()
        DiagnosticLogger.usb(
            "[PCLm] PDF built: ${finalBytes.size} bytes | " +
            "objs=${totalObjects} | strips=$numStrips | " +
            "${pageWidthPt}×${pageHeightPt}pt"
        )

        output.write(finalBytes)
        output.flush()
        DiagnosticLogger.usb("[PCLm] Encoding complete.")
    }

    // ── Page content stream ───────────────────────────────────────────────────

    /**
     * Builds the PDF page content stream.
     *
     * For each strip, emits:
     *   q [wPt] 0 0 [hPt] 0 [yPt] cm
     *   /Image{n} Do
     *   Q
     *
     * PDF y-axis is bottom-up. Strip 0 (topmost pixel rows) has the highest PDF y-offset.
     * Strip 0 bottom-left corner y = pageHeightPt - stripHeightPt(strip0).
     * Strip 1 bottom-left corner y = pageHeightPt - stripHeightPt(strip0) - stripHeightPt(strip1).
     * etc.
     *
     * The cm transform: [wPt] 0 0 [hPt] 0 [yPt] cm
     *   scales the unit square to [wPt × hPt] and places its bottom-left at (0, yPt).
     */
    private fun buildContentStream(
        numStrips: Int,
        width: Int,
        height: Int,
        dpi: Int,
        pageWidthPt: Int,
        pageHeightPt: Int
    ): ByteArray {
        val sb = StringBuilder()
        var yPxFromBottom = height // start from bottom of page (PDF origin)

        for (s in 0 until numStrips) {
            val stripH = minOf(STRIP_HEIGHT, height - s * STRIP_HEIGHT)
            val wPt = px2pt(width, dpi)
            val hPt = px2pt(stripH, dpi)
            yPxFromBottom -= stripH
            val yPt = px2pt(yPxFromBottom, dpi)

            sb.append("q\n")
            sb.append("${fmtF(wPt)} 0 0 ${fmtF(hPt)} 0 ${fmtF(yPt)} cm\n")
            sb.append("/Image$s Do\n")
            sb.append("Q\n")
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    // ── Strip encoder ─────────────────────────────────────────────────────────

    /**
     * Renders each strip of [STRIP_HEIGHT] rows from [bitmap] into compressed bytes.
     *
     * Mono: 8-bit grayscale (0=black, 255=white), Flate compressed.
     * Color: RGB JPEG, Android Bitmap.compress at quality 90.
     */
    private fun buildStrips(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        numStrips: Int,
        mono: Boolean
    ): List<ByteArray> {
        val result = ArrayList<ByteArray>(numStrips)
        val pixels = IntArray(width * STRIP_HEIGHT)

        for (s in 0 until numStrips) {
            val stripY = s * STRIP_HEIGHT
            val stripH = minOf(STRIP_HEIGHT, height - stripY)

            if (mono) {
                bitmap.getPixels(pixels, 0, width, 0, stripY, width, stripH)
                val gray = ByteArray(width * stripH)
                for (i in 0 until width * stripH) {
                    val px = pixels[i]
                    val r  = (px ushr 16) and 0xFF
                    val g  = (px ushr 8)  and 0xFF
                    val b  =  px          and 0xFF
                    // BT.601 luma: 0=black, 255=white
                    gray[i] = ((299 * r + 587 * g + 114 * b) / 1000).toByte()
                }
                val compressed = ByteArrayOutputStream()
                val deflater = DeflaterOutputStream(compressed, Deflater(FLATE_LEVEL, false))
                deflater.write(gray)
                deflater.finish()
                deflater.flush()
                result.add(compressed.toByteArray())
            } else {
                val strip = Bitmap.createBitmap(bitmap, 0, stripY, width, stripH)
                val jpeg  = ByteArrayOutputStream()
                strip.compress(Bitmap.CompressFormat.JPEG, 90, jpeg)
                strip.recycle()
                result.add(jpeg.toByteArray())
            }
        }
        return result
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Format a double for PDF — up to 3 decimal places, no trailing zeros. */
    private fun fmtF(v: Double): String {
        val s = "%.3f".format(v)
        return s.trimEnd('0').trimEnd('.')
    }

    private fun ByteArrayOutputStream.writeStr(s: String) {
        write(s.toByteArray(Charsets.US_ASCII))
    }

    /**
     * Dumps the first [maxBytes] of [data] to logcat as hex.
     * Called from PrintJobManager after encoding, before transmitting.
     */
    fun dumpHex(data: ByteArray, maxBytes: Int = 256) {
        val sb    = StringBuilder("[PCLm] First ${minOf(maxBytes, data.size)} bytes hex:\n")
        val limit = minOf(maxBytes, data.size)
        for (i in 0 until limit) {
            sb.append("%02X ".format(data[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        DiagnosticLogger.usb(sb.toString())
    }
}
