/*
 * HpPcl3Gui2ColorEncoder.kt
 *
 * Implements the full dual-plane color printing pipeline for HP Ink Tank 310 series
 * and modern Pcl3Gui2 printers, derived directly from HPLIP hpcups source
 * (Pcl3Gui2.cpp, Mode9.cpp, Mode10.cpp, PrinterCommands.h — BSD 3-Clause).
 *
 * Stream architecture (Dual Plane eCrd_both):
 *   Plane 0 (Black K):   1-bit monochrome pigment black, compressed with Mode 9 Delta Row.
 *                        Emitted with ESC * b <len> V
 *   Plane 1 (Color RGB): 24-bit contone color, compressed with Mode 10 Bert Compression.
 *                        Emitted with ESC * b <len> W
 */
package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.util.GrayscaleConverter
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PageContentClassifier
import com.pocketprint.data.print.quality.BlackInkSelector
import com.pocketprint.data.print.quality.HybridPixelRouter
import com.pocketprint.data.print.quality.HybridRegionAnalyzer
import java.io.OutputStream
import kotlin.math.abs

class HpPcl3Gui2ColorEncoder : PrinterDataEncoder {

    override val protocolName: String =
        "HP PCL3GUI2 / Ink Tank Full-Color (Dual Plane: K Mode 9 + RGB Mode 10)"

    private val UEL = byteArrayOf(
        0x1b, '%'.code.toByte(), '-'.code.toByte(),
        '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(),
        '4'.code.toByte(), '5'.code.toByte(), 'X'.code.toByte()
    )

    /**
     * Builds 26-byte crd_sequence_both from HPLIP PrinterCommands.h with runtime DPI patched.
     */
    private fun buildCrdSequenceBoth(dpi: Int): ByteArray {
        val crd = byteArrayOf(
            0x1b, 0x2a, 0x67, 0x32, 0x30, 0x57, // ESC*g20W (20 parameter bytes follow)
            0x06,                                 // format = 6 (Pcl3Gui2)
            0x1F.toByte(),                        // SP = 0x1F (Source Planes: K + RGB)
            0x00,                                 // reserved
            0x02,                                 // nComponents = 2 (K and RGB)
            // Component 0: Black K (Mode 9, 1-bit)
            (dpi shr 8).toByte(),                 // hRes high [10]
            (dpi and 0xFF).toByte(),              // hRes low  [11]
            (dpi shr 8).toByte(),                 // vRes high [12]
            (dpi and 0xFF).toByte(),              // vRes low  [13]
            0x09,                                 // compression = Mode 9
            0x00,                                 // orientation
            0x01,                                 // bits per plane = 1
            0x01,                                 // planes = 1
            // Component 1: Color RGB (Mode 10, 24/32-bit contone)
            (dpi shr 8).toByte(),                 // hRes high [18]
            (dpi and 0xFF).toByte(),              // hRes low  [19]
            (dpi shr 8).toByte(),                 // vRes high [20]
            (dpi and 0xFF).toByte(),              // vRes low  [21]
            0x0A,                                 // compression = Mode 10
            0x01,                                 // orientation
            0x20,                                 // bits per plane = 32 (0x20 container)
            0x01                                  // planes = 1
        )
        return crd
    }

    override fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        encodeJobStart(options.dpi, bitmap.width, output)
        encodeRasterPage(bitmap, options.dpi, output)
        encodeJobEnd(output)
        output.flush()
        DiagnosticLogger.usb("[PCL3GUI2-COLOR] Single-page color encode complete.")
    }

    fun encodeJobStart(dpi: Int, pageWidthPx: Int, output: OutputStream) {
        // 1. FIFO flush — 10,000 null bytes (Encapsulator::flushPrinterBuffer)
        output.write(ByteArray(10_000))
        DiagnosticLogger.usb("[PCL3GUI2-COLOR] FIFO flush: 10,000 null bytes sent")

        // 2. PCL Reset + UEL
        output.write(0x1b); output.write('E'.code)
        output.write(UEL)

        // 3. PJL envelope
        output.write("@PJL SET STRINGCODESET=UTF8\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL JOB NAME=\"PocketPrint\"\n".toByteArray(Charsets.US_ASCII))
        output.write("@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray(Charsets.US_ASCII))

        // 4. Geometry & Resolution
        output.write("\u001B&u${dpi}D".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*t${dpi}R".toByteArray(Charsets.US_ASCII))
        output.write("\u001B*r${pageWidthPx}S".toByteArray(Charsets.US_ASCII))

        DiagnosticLogger.usb("[PCL3GUI2-COLOR] Job start: DPI=$dpi, width=${pageWidthPx}px")
    }

    fun encodeRasterPage(bitmap: Bitmap, dpi: Int, output: OutputStream) {
        encodeRasterPage(bitmap, dpi, output, ProcessingMode.COLOR, 144)
    }

    /**
     * Converts each source row to neutral BT.709 luminance before sending it
     * through the hardware-verified dual-plane color protocol.
     */
    fun encodeGrayscaleRasterPage(bitmap: Bitmap, dpi: Int, output: OutputStream) {
        encodeRasterPage(bitmap, dpi, output, ProcessingMode.GRAYSCALE, 144)
    }

    fun encodeHybridRasterPage(
        bitmap: Bitmap,
        dpi: Int,
        textThreshold: Int,
        output: OutputStream
    ) {
        encodeRasterPage(bitmap, dpi, output, ProcessingMode.HYBRID, textThreshold)
    }

    private fun encodeRasterPage(
        bitmap: Bitmap,
        dpi: Int,
        output: OutputStream,
        processingMode: ProcessingMode,
        textThreshold: Int
    ) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRowK = (width + 7) / 8
        val bytesPerRowColor = width * 3

        // CRD block (26 bytes: Dual-Plane K Mode 9 + RGB Mode 10)
        output.write(buildCrdSequenceBoth(dpi))

        // Media pre-load
        output.write("\u001B&l-2H".toByteArray(Charsets.US_ASCII))

        // Start raster graphics
        output.write("\u001B*r1A".toByteArray(Charsets.US_ASCII))

        val pixels = IntArray(width)
        val rawRowK = ByteArray(bytesPerRowK)
        val seedRowK = ByteArray(bytesPerRowK) { 0 }

        val rawRowColor = ByteArray(bytesPerRowColor)
        val seedRowColor = ByteArray(bytesPerRowColor) { 0xFF.toByte() } // Color seed starts at 0xFF (white)
        val profile = classifyBitmap(bitmap)
        val hybridMap = if (processingMode == ProcessingMode.HYBRID) {
            val analyzer = HybridRegionAnalyzer(width, height, maxOf(32, dpi / 5))
            val analysisRow = IntArray(width)
            for (analysisY in 0 until height) {
                bitmap.getPixels(analysisRow, 0, width, 0, analysisY, width, 1)
                analyzer.addRow(analysisRow, analysisY)
            }
            analyzer.finish()
        } else null
        val blackExtractionThreshold = when {
            processingMode == ProcessingMode.GRAYSCALE -> 0
            profile.recommendedStrategy == MonochromeStrategy.TEXT -> 96
            profile.recommendedStrategy == MonochromeStrategy.MIXED -> 24
            else -> 8
        }
        val neutralTolerance = if (profile.recommendedStrategy == MonochromeStrategy.TEXT) 12 else 4
        DiagnosticLogger.usb(
                "[PCL3GUI2-COLOR] raster width=$width height=$height rgbStride=$bytesPerRowColor " +
                "kStride=$bytesPerRowK dpi=$dpi mode=$processingMode " +
                "content=${profile.recommendedStrategy} kThreshold=$blackExtractionThreshold " +
                "hybridTextTiles=${hybridMap?.textTileCount ?: 0}/${hybridMap?.totalTileCount ?: 0}"
        )

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            var kByteIdx = 0
            var kCurrentByte = 0
            var kBitCount = 0

            for (x in 0 until width) {
                val p = pixels[x]
                var r: Int
                var g: Int
                var b: Int
                var hybridUseBlack = false
                if (processingMode == ProcessingMode.HYBRID) {
                    val route = HybridPixelRouter.route(
                        p,
                        hybridMap?.isTextRegion(x, y) == true,
                        textThreshold
                    )
                    r = route.grayscale
                    g = route.grayscale
                    b = route.grayscale
                    hybridUseBlack = route.useBlackPlane
                } else if (processingMode == ProcessingMode.GRAYSCALE) {
                    val gray = GrayscaleConverter.toOpaqueGrayscale(p) and 0xFF
                    r = gray
                    g = gray
                    b = gray
                } else {
                    val a = (p ushr 24) and 0xFF
                    r = (p ushr 16) and 0xFF
                    g = (p ushr 8) and 0xFF
                    b = p and 0xFF

                    // Blend against white background if translucent.
                    if (a < 255) {
                        r = ((r * a + 255 * (255 - a) + 127) / 255).coerceIn(0, 255)
                        g = ((g * a + 255 * (255 - a) + 127) / 255).coerceIn(0, 255)
                        b = ((b * a + 255 * (255 - a) + 127) / 255).coerceIn(0, 255)
                    }
                }

                // Black extraction logic (HPCupsFilter::extractBlackPixels):
                // If a pixel is pure black (or very close neutral black, e.g. text/line art),
                // extract it to the K pigment plane and render RGB as white.
                // Otherwise, render full RGB on the color plane and keep K = 0.
                val isNeutralDark = if (processingMode == ProcessingMode.HYBRID) {
                    hybridUseBlack
                } else {
                    BlackInkSelector.shouldUseBlackPlane(
                        r,
                        g,
                        b,
                        blackExtractionThreshold,
                        neutralTolerance
                    )
                }

                if (isNeutralDark) {
                    // Extract to Black K plane
                    kCurrentByte = (kCurrentByte shl 1) or 1
                    // Clear RGB to pure white
                    val colOff = x * 3
                    rawRowColor[colOff] = 0xFF.toByte()
                    rawRowColor[colOff + 1] = 0xFF.toByte()
                    rawRowColor[colOff + 2] = 0xFF.toByte()
                } else {
                    // Not pure black: leave K as 0
                    kCurrentByte = kCurrentByte shl 1
                    val colOff = x * 3
                    rawRowColor[colOff] = r.toByte()
                    rawRowColor[colOff + 1] = g.toByte()
                    rawRowColor[colOff + 2] = b.toByte()
                }

                kBitCount++
                if (kBitCount == 8) {
                    rawRowK[kByteIdx++] = kCurrentByte.toByte()
                    kCurrentByte = 0
                    kBitCount = 0
                }
            }

            if (kBitCount > 0 && kByteIdx < bytesPerRowK) {
                rawRowK[kByteIdx] = (kCurrentByte shl (8 - kBitCount)).toByte()
            }

            // 1. Compress & send Black K plane (ESC * b <len> V)
            val compK = HpMode9Compressor.compress(rawRowK, seedRowK, bytesPerRowK)
            output.write("\u001B*b${compK.size}V".toByteArray(Charsets.US_ASCII))
            if (compK.isNotEmpty()) {
                output.write(compK)
            }
            rawRowK.copyInto(seedRowK)

            // 2. Compress & send Color RGB plane (ESC * b <len> W — advances scanline)
            val colorResult = HpMode10Compressor.compressWithReconstructedSeed(
                rawRowColor,
                seedRowColor,
                width
            )
            val compColor = colorResult.compressed
            output.write("\u001B*b${compColor.size}W".toByteArray(Charsets.US_ASCII))
            if (compColor.isNotEmpty()) {
                output.write(compColor)
            }
            colorResult.reconstructedRow.copyInto(seedRowColor)
        }

        // End raster & eject page
        output.write("\u001B*rC".toByteArray(Charsets.US_ASCII))
        output.write(0x0C)
        DiagnosticLogger.usb("[PCL3GUI2-COLOR] Page raster complete: $height rows ($processingMode).")
    }

    private enum class ProcessingMode {
        COLOR,
        GRAYSCALE,
        HYBRID
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

    fun encodeJobEnd(output: OutputStream) {
        output.write(0x1b); output.write('E'.code)
        output.write(UEL)
        output.write("@PJL EOJ\n".toByteArray(Charsets.US_ASCII))
        output.write(UEL)
        output.flush()
        DiagnosticLogger.usb("[PCL3GUI2-COLOR] Job end written.")
    }
}
