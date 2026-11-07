/*
 * HpPcl3Gui2Encoder.kt
 *
 * Ported from HPLIP hpcups — prnt/hpcups/Pcl3Gui2.cpp and
 * prnt/hpcups/PrinterCommands.h (Distrotech/hplip, BSD 3-Clause).
 *
 * Copyright (c) 1994-2009 Hewlett-Packard Development Company, L.P.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   1. Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright notice,
 *      this list of conditions and the following disclaimer in the documentation
 *      and/or other materials provided with the distribution.
 *   3. Neither the name of Hewlett-Packard nor the names of its contributors
 *      may be used to endorse or promote products derived from this software
 *      without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING BUT NOT LIMITED TO THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *
 * ----- Porting notes -----
 *
 * Target: HP Ink Tank 310 series (VID 0x03F0, PID 0x1053)
 * IEEE-1284 ID: CMD:PCL3GUI,PJL,Automatic,DW-PCL,DESKJET,DYN
 *
 * Pcl3Gui2 is the HPLIP encapsulator used for modern DeskJet / Ink Tank
 * printers. It differs from the older Pcl3Gui (Mode 2 PackBits, dj890/895
 * platform only) in the following ways:
 *
 *   • 18-byte CRD block (crd_sequence_k / crd_sequence_color / crd_sequence_both)
 *     instead of the 8-byte or 10-byte Format-2 CRD used by old Pcl3Gui.
 *   • DPI is patched into bytes [10..13] of the CRD sequence at StartPage() time.
 *   • Mode 9 (Advanced Delta Row) compression for the K plane.
 *   • Mode 10 for the color plane (not used here — K-only path).
 *   • 10,000 null-byte FIFO flush before the job (flushPrinterBuffer in HPLIP).
 *   • ESC&u#D (unit-of-measure) + ESC*r#S (raster width) before grafStart.
 *   • ESC&l-2H (media pre-load) immediately before the buffer flush/send.
 *   • ESC*r1A (grafStart) — not ESC*r2A.
 *   • ESC*b#W per row using correct multi-byte Mode 9 offset-count-data encoding.
 *
 * Stream structure (K-only, single page):
 *
 *   10,000 null bytes  (FIFO flush — Encapsulator::flushPrinterBuffer)
 *   ESC E              (PCL Reset)
 *   ESC%-12345X        (UEL — Universal Exit Language)
 *   @PJL SET STRINGCODESET=UTF8\n
 *   @PJL JOB NAME="PocketPrint"\n
 *   @PJL ENTER LANGUAGE=PCL3GUI\n   ← Pcl3Gui2 still uses PCL3GUI language tag
 *   ESC&u#D            (unit of measure = DPI)
 *   ESC*t#R            (actual vertical resolution)
 *   ESC*r#S            (raster width in pixels)
 *   CRD[18]            (crd_sequence_k with DPI patched into bytes 10–13)
 *   ESC&l-2H           (media pre-load)
 *   ESC*r1A            (start raster graphics at current cursor)
 *   ESC*b9M            (compression: Mode 9 delta row)
 *   For each row:
 *     ESC*b#W <mode9-compressed-row>   (zero-length if row == seed)
 *   ESC*rC             (end raster)
 *   FF (0x0C)          (form feed / page eject)
 *   ESC E              (PCL Reset)
 *   ESC%-12345X        (UEL)
 *   @PJL EOJ\n
 *   ESC%-12345X        (trailing UEL)
 */
package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.quality.MonochromeRowRasterizer
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PageContentClassifier
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class HpPcl3Gui2Encoder : PrinterDataEncoder {

    override val protocolName: String =
        "HP PCL3GUI2 / Ink Tank Raster (Pcl3Gui2, K-only, Mode 9 Delta Row)"

    // -------------------------------------------------------------------------
    // HPLIP constant byte sequences (from PrinterCommands.h, BSD 3-Clause HP)
    // -------------------------------------------------------------------------

    /** Universal Exit Language — 9 bytes: ESC%-12345X */
    private val UEL = byteArrayOf(
        0x1b, '%'.code.toByte(), '-'.code.toByte(),
        '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(),
        '4'.code.toByte(), '5'.code.toByte(), 'X'.code.toByte()
    )

    /**
     * crd_sequence_k[18] from PrinterCommands.h.
     *
     * Wire layout:
     *   [0..5]  ESC*g12W        (command: Configure Raster Data, 12 data bytes follow)
     *   [6]     format = 0x06   (Pcl3Gui2 format identifier)
     *   [7]     SP     = 0x1F   (source plane flags, K-only)
     *   [8]     0x00
     *   [9]     nComponents = 0x01
     *   [10..11] horizontal resolution (BE uint16) — PATCHED at encode time
     *   [12..13] vertical   resolution (BE uint16) — PATCHED at encode time
     *   [14]    compression = 0x09 (Mode 9)
     *   [15]    orientation = 0x00
     *   [16]    bits/plane  = 0x01 (1-bit mono K)
     *   [17]    planes      = 0x01
     */
    private fun buildCrdSequenceK(dpi: Int): ByteArray {
        val crd = byteArrayOf(
            0x1b, 0x2a, 0x67, 0x31, 0x32, 0x57, // ESC*g12W
            0x06,                                 // format
            0x1F.toByte(),                        // SP (source plane K)
            0x00,                                 // reserved
            0x01,                                 // nComponents
            (dpi shr 8).toByte(),                 // hRes high byte  [10]
            (dpi and 0xFF).toByte(),              // hRes low byte   [11]
            (dpi shr 8).toByte(),                 // vRes high byte  [12]
            (dpi and 0xFF).toByte(),              // vRes low byte   [13]
            0x09,                                 // compression = Mode 9
            0x00,                                 // orientation
            0x01,                                 // bits per plane = 1
            0x01                                  // planes = 1
        )
        return crd
    }

    // -------------------------------------------------------------------------
    // Public entry points
    // -------------------------------------------------------------------------

    /**
     * Encodes a single-page job (test page, single-document page).
     * Emits: FIFO flush + job header + page raster + job trailer.
     */
    override fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        encodeJobStart(options.dpi, bitmap.width, output)
        encodeRasterPage(bitmap, options, output)
        encodeJobEnd(output)
        output.flush()
        DiagnosticLogger.usb("[PCL3GUI2] Single-page encode complete.")
    }

    /**
     * Encodes a multi-page job.
     *
     * Call order:
     *   encodeJobStart(dpi, pageWidthPx, output)   — once, emits FIFO flush + PJL envelope
     *   encodeRasterPage(bitmap, dpi, output)       — once per page
     *   encodeJobEnd(output)                        — once, emits PJL EOJ
     */
    fun encodeJobStart(dpi: Int, pageWidthPx: Int, output: OutputStream) {
        // ── 0. FIFO flush — 10,000 null bytes (Encapsulator::flushPrinterBuffer)
        output.write(ByteArray(10_000))
        DiagnosticLogger.usb("[PCL3GUI2] FIFO flush: 10,000 null bytes sent")

        // ── 1. PCL Reset + UEL
        output.write(0x1b); output.write('E'.code)
        output.write(UEL)

        // ── 2. PJL envelope
        output.write("@PJL SET STRINGCODESET=UTF8\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL JOB NAME=\"PocketPrint\"\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray(Charsets.US_ASCII))

        // ── 3. Unit-of-measure + resolution
        output.write("\u001B&u${dpi}D".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*t${dpi}R".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*r${pageWidthPx}S".toByteArray(Charsets.US_ASCII))

        DiagnosticLogger.usb("[PCL3GUI2] Job start: DPI=$dpi, width=${pageWidthPx}px")
    }

    /**
     * Encodes one page of raster data into the output stream.
     * Must be called between encodeJobStart() and encodeJobEnd().
     */
    fun encodeRasterPage(bitmap: Bitmap, dpi: Int, output: OutputStream) {
        encodeRasterPage(bitmap, PrintOptions(dpi = dpi), output)
    }

    fun encodeRasterPage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        val width       = bitmap.width
        val height      = bitmap.height
        val bytesPerRow = (width + 7) / 8
        val dpi = options.dpi

        // ── CRD block (18 bytes, crd_sequence_k with DPI patched)
        output.write(buildCrdSequenceK(dpi))
        DiagnosticLogger.usb("[PCL3GUI2] CRD[18] written, DPI=$dpi")

        // ── Media pre-load: ESC&l-2H
        output.write("\u001B&l-2H".toByteArray(Charsets.US_ASCII))

        // ── Start raster: ESC*r1A
        output.write("\u001B*r1A".toByteArray(Charsets.US_ASCII))

        // ── Mode 9 compression: ESC*b9M
        output.write("\u001B*b9M".toByteArray(Charsets.US_ASCII))
        DiagnosticLogger.usb("[PCL3GUI2] Mode 9 raster: ${height} rows × ${bytesPerRow} B/row")

        val pixels = IntArray(width)
        val seedRow = ByteArray(bytesPerRow)
        val pageProfile = if (options.monochromeStrategy == MonochromeStrategy.AUTO) {
            classifyBitmap(bitmap)
        } else {
            null
        }
        val rasterizer = MonochromeRowRasterizer(
            width = width,
            strategy = options.monochromeStrategy,
            threshold = options.monochromeThreshold,
            pageProfile = pageProfile
        )
        DiagnosticLogger.usb(
            "[PCL3GUI2] MONO raster width=$width height=$height stride=$bytesPerRow " +
                "dpi=$dpi strategy=${rasterizer.resolvedStrategy} threshold=${options.monochromeThreshold} " +
                "profile=${pageProfile?.let { "mid=${"%.3f".format(it.midtoneFraction)},bins=${it.occupiedLuminanceBins}" } ?: "explicit"}"
        )

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            val rawRow = rasterizer.rasterizeRow(pixels)

            val compressed = HpMode9Compressor.compress(rawRow, seedRow, bytesPerRow)
            output.write("\u001B*b${compressed.size}W".toByteArray(Charsets.US_ASCII))
            if (compressed.isNotEmpty()) {
                output.write(compressed)
            }
            rawRow.copyInto(seedRow)
        }

        // ── End raster + form feed
        output.write("\u001B*rC".toByteArray(Charsets.US_ASCII))
        output.write(0x0C)
        DiagnosticLogger.usb("[PCL3GUI2] Page raster complete.")
    }

    private fun classifyBitmap(bitmap: Bitmap): com.pocketprint.data.print.quality.PageContentProfile {
        val sampleWidth = minOf(bitmap.width, 64)
        val sampleHeight = minOf(bitmap.height, 64)
        val samples = IntArray(sampleWidth * sampleHeight)
        var index = 0
        for (sampleY in 0 until sampleHeight) {
            val y = if (sampleHeight == 1) 0 else sampleY * (bitmap.height - 1) / (sampleHeight - 1)
            for (sampleX in 0 until sampleWidth) {
                val x = if (sampleWidth == 1) 0 else sampleX * (bitmap.width - 1) / (sampleWidth - 1)
                samples[index++] = bitmap.getPixel(x, y)
            }
        }
        return PageContentClassifier.classify(samples)
    }

    /**
     * Closes the PCL job: PCL Reset + UEL + PJL EOJ + trailing UEL.
     */
    fun encodeJobEnd(output: OutputStream) {
        output.write(0x1b); output.write('E'.code)
        output.write(UEL)
        output.write("@PJL EOJ\n".toByteArray(Charsets.US_ASCII))
        output.write(UEL)
        output.flush()
        DiagnosticLogger.usb("[PCL3GUI2] Job end written.")
    }



    /** Hex-dumps first [maxBytes] of [data] to logcat for stream verification. */
    fun dumpHex(data: ByteArray, maxBytes: Int = 256) {
        val limit = minOf(maxBytes, data.size)
        val sb = StringBuilder("[PCL3GUI2] First $limit bytes:\n")
        for (i in 0 until limit) {
            sb.append("%02X ".format(data[i].toInt() and 0xFF))
            if ((i + 1) % 16 == 0) sb.append("\n")
        }
        DiagnosticLogger.usb(sb.toString())
    }
}
