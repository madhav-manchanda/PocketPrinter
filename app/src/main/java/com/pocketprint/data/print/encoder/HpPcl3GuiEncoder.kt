package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * HP PCL3GUI raster encoder for HP Ink Tank 310 series (hpijvipav7 driver profile).
 *
 * Protocol confirmed from HPLIP hpcups source (Pcl3Gui.cpp / Pcl3Gui2.cpp):
 *
 *  Compression Mode 9 — Advanced Delta Row compression (seed-row based).
 *  Used by hpcups for the black (K) plane in monochrome DeskJet / Ink Tank jobs.
 *  Mode 2 (PackBits) is a DeskJet-500C era format; Ink Tank 310 firmware does not
 *  print with it. Mode 9 computes byte differences against a previous ("seed") row
 *  and encodes only the changed bytes, dramatically reducing bandwidth for text pages.
 *
 * Mode 9 wire format per row (ESC * b # V for non-last plane, ESC * b # W for last):
 *   Each changed byte run:
 *     [offset byte(s)] [count byte] [data bytes...]
 *   Where:
 *     offset = how many bytes to skip from current seed position (0–255 → 1 byte;
 *              if ≥ 255, write 0xFF + continue adding until <255)
 *     count  = (n - 1), 0-based, meaning 1..256 bytes of replacement data
 *   The seed row is zeroed on the first row, then updated to match the sent row.
 *   An all-seed-match row emits 0 bytes of compressed data (zero-length row).
 *
 * CRD block (ESC * g # W) — 10 bytes, Format 2, HP DeskJet layout:
 *   [0]   format    = 2
 *   [1]   nPlanes   = 1  (black only)
 *   [2-3] xDpi BE u16
 *   [4-5] yDpi BE u16
 *   [6-7] width px BE u16
 *   [8-9] height px BE u16
 *
 * Full stream structure:
 *   UEL + PJL envelope
 *   ESC E  (PCL reset)
 *   ESC & l # A  (paper size)
 *   ESC & l 0 O  (portrait)
 *   ESC & l 0 E  (top margin 0)
 *   ESC & l # X  (copies)
 *   ESC * t # R  (resolution)
 *   ESC * o 0 M  (quality: normal)
 *   ESC * g 10 W <crd>  (Configure Raster Data)
 *   ESC * r 0 F  (raster presentation)
 *   ESC * r # T  (source row count)
 *   ESC * r 2 A  (start raster at logical page top)
 *   ESC * b 9 M  (compression: Mode 9 delta-row)
 *   For each non-blank row:
 *     ESC * b # W <mode9-compressed-row>
 *   For each blank row skip:
 *     ESC * b # Y  (vertical skip count)
 *   ESC * r C  (end raster)
 *   FF  (form feed / page eject)
 *   ESC E  (PCL reset)
 *   UEL + PJL EOJ
 */
class HpPcl3GuiEncoder : PrinterDataEncoder {

    override val protocolName: String = "HP PCL3GUI / DeskJet Raster (Mode 9 Delta Row)"

    override fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        // ── 1. PJL envelope ──────────────────────────────────────────────────
        output.write("\u001B%-12345X@PJL\r\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL SET STRINGCODESET=UTF8\r\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL JOB NAME=\"PocketPrint\"\r\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL ENTER LANGUAGE=PCL3GUI\r\n".toByteArray(Charsets.US_ASCII))

        // ── 2. PCL reset & page geometry ─────────────────────────────────────
        output.write("\u001BE".toByteArray(Charsets.US_ASCII))
        output.write("\u001B&l${options.paperSize.codePcl}A".toByteArray(Charsets.US_ASCII))
        output.write("\u001B&l0O".toByteArray(Charsets.US_ASCII))
        output.write("\u001B&l0E".toByteArray(Charsets.US_ASCII))
        output.write("\u001B&l${options.copies}X".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*t${options.dpi}R".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*o0M".toByteArray(Charsets.US_ASCII))

        // ── 3. Configure Raster Data (CRD) ───────────────────────────────────
        val crd = buildCrdBlock(options.dpi)
        output.write("\u001B*g${crd.size}W".toByteArray(Charsets.US_ASCII))
        output.write(crd)
        DiagnosticLogger.usb("[PCL3GUI] CRD: ${crd.size}B → format=2, bpp=1, DPI=${options.dpi}")

        // ── 4. Raster start ───────────────────────────────────────────────────
        output.write("\u001B*r0F".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*r${height}T".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*r2A".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*b9M".toByteArray(Charsets.US_ASCII))   // Mode 9: delta-row
        DiagnosticLogger.usb("[PCL3GUI] Raster Mode 9 (delta-row), ${height} rows")

        // ── 5. Raster rows (Mode 9) ───────────────────────────────────────────
        val pixels = IntArray(width)
        val rawRow = ByteArray(bytesPerRow)
        val seedRow = ByteArray(bytesPerRow)   // starts all-zero (white)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            // Pack to 1-bit monochrome (1 = ink, 0 = white), MSB-first
            var packedIdx = 0
            var currentByte = 0
            var bitCount = 0

            for (x in 0 until width) {
                val pixel = pixels[x]
                val alpha = (pixel ushr 24) and 0xFF
                val r = (pixel ushr 16) and 0xFF
                val g = (pixel ushr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = (299 * r + 587 * g + 114 * b) / 1000
                val isBlack = alpha > 128 && lum < 160

                currentByte = (currentByte shl 1) or (if (isBlack) 1 else 0)
                bitCount++

                if (bitCount == 8) {
                    rawRow[packedIdx] = currentByte.toByte()
                    packedIdx++
                    currentByte = 0
                    bitCount = 0
                }
            }
            if (bitCount > 0) {
                currentByte = currentByte shl (8 - bitCount)
                rawRow[packedIdx] = currentByte.toByte()
            }

            // Mode 9 compress current row against seed
            val compressed = HpMode9Compressor.compress(rawRow, seedRow, bytesPerRow)

            // ESC * b # W <compressed>
            output.write("\u001B*b${compressed.size}W".toByteArray(Charsets.US_ASCII))
            if (compressed.isNotEmpty()) {
                output.write(compressed)
            }

            // Update seed row
            rawRow.copyInto(seedRow)
        }

        // ── 6. End raster & page eject ────────────────────────────────────────
        output.write("\u001B*rC".toByteArray(Charsets.US_ASCII))
        output.write(0x0C)

        // ── 7. PCL reset & PJL EOJ ───────────────────────────────────────────
        output.write("\u001BE".toByteArray(Charsets.US_ASCII))
        output.write("\u001B%-12345X@PJL\r\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL EOJ\r\n".toByteArray(Charsets.US_ASCII))
        output.write("\u001B%-12345X".toByteArray(Charsets.US_ASCII))

        output.flush()
        DiagnosticLogger.usb("[PCL3GUI] Encoding complete.")
    }

    // ── CRD block (8 bytes, Format 2, per-component layout) ──────────────────
    //
    // HP PCL3GUI ESC *g #W CRD per-component structure (confirmed from spec):
    //   byte 0:    format           = 2  (direct pixel, non-indexed)
    //   byte 1:    nComponents      = 1  (K plane only)
    //   --- per component (6 bytes each) ---
    //   byte 2:    bits_per_index   = 0  (unused for format 2)
    //   byte 3:    bits_per_pixel   = 1  (1-bit monochrome)
    //   bytes 4-5: xDPI (BE uint16) = 300
    //   bytes 6-7: yDPI (BE uint16) = 300
    //
    // Total: 2 + 6*1 = 8 bytes  →  ESC *g 8W
    //
    // Our earlier 10-byte version set bits_per_pixel=44 and yDPI=2480,
    // which caused the printer to silently reject every raster job.
    private fun buildCrdBlock(dpi: Int): ByteArray {
        val buf = ByteArray(8)
        buf[0] = 2                                           // format: direct pixel
        buf[1] = 1                                           // 1 colour component (K)
        buf[2] = 0                                           // bits per index = 0
        buf[3] = 1                                           // bits per pixel = 1 (mono)
        buf[4] = ((dpi shr 8) and 0xFF).toByte()            // xDPI high byte
        buf[5] = (dpi and 0xFF).toByte()                    // xDPI low byte
        buf[6] = ((dpi shr 8) and 0xFF).toByte()            // yDPI high byte
        buf[7] = (dpi and 0xFF).toByte()                    // yDPI low byte
        return buf
    }

    /**
     * Dumps the first [maxBytes] of [data] to logcat as hex.
     * Called from PrintJobManager after encoding, before transmitting.
     */
    fun dumpHex(data: ByteArray, maxBytes: Int = 256) {
        val sb = StringBuilder("[PCL3GUI] First ${minOf(maxBytes, data.size)} bytes hex:\n")
        val limit = minOf(maxBytes, data.size)
        for (i in 0 until limit) {
            sb.append(String.format("%02X ", data[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        DiagnosticLogger.usb(sb.toString())
    }
}
