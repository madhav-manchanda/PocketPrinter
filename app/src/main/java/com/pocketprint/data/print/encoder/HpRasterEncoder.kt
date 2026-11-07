package com.pocketprint.data.print.encoder

import android.graphics.Bitmap
import com.pocketprint.data.print.model.PrintOptions
import java.io.OutputStream

class HpRasterEncoder : PrinterDataEncoder {

    override val protocolName: String = "HP Raster (Ink Tank)"

    override fun encodePage(bitmap: Bitmap, options: PrintOptions, output: OutputStream) {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        // Raster mode setup for HP Ink Tank / DeskJet
        // Enter raster presentation mode (portrait)
        output.write("\u001B*r0F".toByteArray(Charsets.US_ASCII))
        // Start raster at left graphics margin
        output.write("\u001B*r1A".toByteArray(Charsets.US_ASCII))
        // Compression mode 0 = uncompressed binary bytes
        output.write("\u001B*b0M".toByteArray(Charsets.US_ASCII))

        // Pixel buffer per line
        val pixels = IntArray(width)
        val rowBytes = ByteArray(bytesPerRow)

        for (y in 0 until height) {
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

            // Pack pixels into 1-bit monochrome bytes (MSB first, 1 = Black ink dot, 0 = White)
            var byteIdx = 0
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
                    rowBytes[byteIdx++] = currentByte.toByte()
                    currentByte = 0
                    bitCount = 0
                }
            }

            // Flush remaining partial byte if width is not a multiple of 8
            if (bitCount > 0) {
                currentByte = currentByte shl (8 - bitCount)
                rowBytes[byteIdx++] = currentByte.toByte()
            }

            // Transfer raster row: ESC * b <transferLength> W <data>
            // Transfer full row (no trailing-zero trim for raw raster)
            output.write("\u001B*b${bytesPerRow}W".toByteArray(Charsets.US_ASCII))
            output.write(rowBytes, 0, bytesPerRow)
        }

        // End raster mode
        output.write("\u001B*rB".toByteArray(Charsets.US_ASCII))
        output.flush()
    }
}