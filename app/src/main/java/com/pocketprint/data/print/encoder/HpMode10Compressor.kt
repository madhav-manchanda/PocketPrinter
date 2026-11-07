/*
 * HpMode10Compressor.kt
 *
 * Ported directly from HPLIP hpcups Mode10 engine (prnt/hpcups/Mode10.cpp & Mode10.h)
 * "Bert Compression Format" for continuous-tone RGB pixel data.
 * Authored by Elden Wood, Hewlett-Packard Co. (BSD 3-Clause).
 *
 * Used by HP Pcl3Gui2 printers (including HP Ink Tank 310 series) for the
 * 24-bit contone color plane (Component 1 of crd_sequence_both).
 */
package com.pocketprint.data.print.encoder

import java.io.ByteArrayOutputStream

object HpMode10Compressor {

    data class CompressionResult(
        val compressed: ByteArray,
        val reconstructedRow: ByteArray
    )

    const val BYTES_PER_PIXEL = 3
    private const val WHITE_PIXEL = 0x00FFFFFF

    // Command byte masks & tags
    private const val CMD_LITERAL = 0x00
    private const val CMD_RLE = 0x80

    private const val SRC_NEW = 0x00
    private const val SRC_WEST = 0x20
    private const val SRC_NORTHEAST = 0x40
    private const val SRC_CACHED = 0x60

    private inline fun getPixel(buf: ByteArray, idx: Int): Int {
        val o = idx * 3
        val r = buf[o].toInt() and 0xFF
        val g = buf[o + 1].toInt() and 0xFF
        val b = buf[o + 2].toInt() and 0xFF
        return (r shl 16) or (g shl 8) or b
    }

    private inline fun putPixel(buf: ByteArray, idx: Int, pixel: Int) {
        val o = idx * 3
        buf[o] = ((pixel ushr 16) and 0xFF).toByte()
        buf[o + 1] = ((pixel ushr 8) and 0xFF).toByte()
        buf[o + 2] = (pixel and 0xFF).toByte()
    }

    private fun shortDelta(curr: Int, upper: Int): Int {
        val dr = ((curr ushr 16) and 0xFF) - ((upper ushr 16) and 0xFF)
        val dg = ((curr ushr 8) and 0xFF) - ((upper ushr 8) and 0xFF)
        val db = (curr and 0xFF) - (upper and 0xFF)

        return if (dr in -16..15 && dg in -16..15 && db in -32..30) {
            val rField = (dr and 0x1F) shl 10
            val gField = (dg and 0x1F) shl 5
            val bField = (db shr 1) and 0x1F
            (0x8000 or rField or gField or bField) and 0xFFFF
        } else {
            0
        }
    }

    private fun outputVli(out: ByteArrayOutputStream, number: Int) {
        var num = number
        do {
            val chunk = minOf(num, 255)
            out.write(chunk)
            if (num == 255) {
                out.write(0)
            }
            num -= chunk
        } while (num > 0)
    }

    /**
     * Compresses a 24-bit RGB [currentRow] relative to [seedRow].
     * Both arrays are of size [widthPixels] * 3.
     * Returns compressed bytes, or an empty array if [currentRow] is identical to [seedRow].
     */
    fun compress(currentRow: ByteArray, seedRow: ByteArray, widthPixels: Int): ByteArray {
        if (widthPixels < 1) return ByteArray(0)
        val rowLength = widthPixels * BYTES_PER_PIXEL
        require(currentRow.size >= rowLength && seedRow.size >= rowLength) {
            "Mode 10 rows must contain widthPixels * 3 bytes"
        }
        var identical = true
        for (index in 0 until rowLength) {
            if (currentRow[index] != seedRow[index]) {
                identical = false
                break
            }
        }
        if (identical) {
            return ByteArray(0)
        }

        // Make working copy of current row so sentinel modification doesn't corrupt caller data
        val cur = currentRow.copyOf(widthPixels * 3)
        val seed = seedRow
        val out = ByteArrayOutputStream(widthPixels * 4)

        val lastPixel = widthPixels - 1
        val realLastPixel = getPixel(cur, lastPixel)

        // Setup sentinel on the last pixel
        var newLastPixel = realLastPixel
        while (lastPixel > 0 && (getPixel(cur, lastPixel - 1) == newLastPixel || getPixel(seed, lastPixel) == newLastPixel)) {
            newLastPixel = (newLastPixel + 0x100) and WHITE_PIXEL
        }
        putPixel(cur, lastPixel, newLastPixel)

        var curPixel = 0
        var cachedColor = WHITE_PIXEL

        while (curPixel <= lastPixel) {
            var cmdByte = CMD_LITERAL
            var replacementCount = 0
            var pixelSource = SRC_NEW

            // Step 1: Count matching pixels with seed row
            val seedCopyStart = curPixel
            while (getPixel(seed, curPixel) == getPixel(cur, curPixel)) {
                curPixel++
            }
            val seedRowPixelCopyCount = curPixel - seedCopyStart

            if (curPixel == lastPixel) {
                putPixel(cur, lastPixel, realLastPixel)
                if (getPixel(seed, curPixel) == realLastPixel) {
                    // Entire remainder matches seed row
                    break
                } else {
                    // Code last pixel as literal
                    cmdByte = CMD_LITERAL
                    pixelSource = SRC_NEW
                    replacementCount = 1
                    curPixel++
                }
            } else {
                replacementCount = curPixel
                val rleRun = getPixel(cur, curPixel)

                curPixel++
                while (rleRun == getPixel(cur, curPixel)) {
                    curPixel++
                }
                curPixel--
                replacementCount = curPixel - replacementCount

                if (replacementCount > 0) {
                    curPixel++
                    replacementCount++

                    pixelSource = when {
                        cachedColor == rleRun -> SRC_CACHED
                        getPixel(seed, curPixel - replacementCount + 1) == rleRun -> SRC_NORTHEAST
                        curPixel - replacementCount > 0 && getPixel(cur, curPixel - replacementCount - 1) == rleRun -> SRC_WEST
                        else -> {
                            cachedColor = rleRun
                            SRC_NEW
                        }
                    }
                    cmdByte = CMD_RLE
                } else {
                    cmdByte = CMD_LITERAL
                }

                if (curPixel == lastPixel && replacementCount > 0) {
                    if (realLastPixel == rleRun) {
                        putPixel(cur, lastPixel, realLastPixel)
                        replacementCount++
                        curPixel++
                    }
                }

                if (replacementCount == 0) {
                    val tempPixel = getPixel(cur, curPixel)
                    cmdByte = CMD_LITERAL

                    pixelSource = when {
                        cachedColor == tempPixel -> SRC_CACHED
                        getPixel(seed, curPixel + 1) == tempPixel -> SRC_NORTHEAST
                        curPixel > 0 && getPixel(cur, curPixel - 1) == tempPixel -> SRC_WEST
                        else -> {
                            cachedColor = tempPixel
                            SRC_NEW
                        }
                    }

                    replacementCount = curPixel
                    var cachePixel: Int
                    var nextPixel = getPixel(cur, curPixel + 1)
                    do {
                        curPixel++
                        if (curPixel == lastPixel) {
                            putPixel(cur, lastPixel, realLastPixel)
                            curPixel++
                            break
                        }
                        cachePixel = nextPixel
                        nextPixel = getPixel(cur, curPixel + 1)
                    } while (cachePixel != nextPixel && cachePixel != getPixel(seed, curPixel))

                    replacementCount = curPixel - replacementCount
                }
            }

            // Write compressed command
            if (cmdByte == CMD_LITERAL) {
                val normCount = replacementCount - 1
                val cmd = (pixelSource) or (minOf(3, seedRowPixelCopyCount) shl 3) or (minOf(7, normCount))
                out.write(cmd)

                if (seedRowPixelCopyCount >= 3) {
                    outputVli(out, seedRowPixelCopyCount - 3)
                }

                val totalReplacement = replacementCount
                var upward = if (pixelSource != SRC_NEW) 2 else 1
                var remaining = if (pixelSource != SRC_NEW) replacementCount - 1 else replacementCount

                while (upward <= totalReplacement) {
                    val pixIdx = curPixel - remaining
                    val currP = getPixel(cur, pixIdx)
                    val seedP = getPixel(seed, pixIdx)
                    val delta = shortDelta(currP, seedP)

                    if (delta != 0) {
                        out.write((delta ushr 8) and 0xFF)
                        out.write(delta and 0xFF)
                    } else {
                        val uncomp = currP ushr 1
                        out.write((uncomp ushr 16) and 0xFF)
                        out.write((uncomp ushr 8) and 0xFF)
                        out.write(uncomp and 0xFF)
                    }

                    if ((upward - 8) % 255 == 0) {
                        out.write(minOf(255, totalReplacement - upward))
                    }

                    remaining--
                    upward++
                }
            } else {
                // RLE
                val normCount = replacementCount - 2
                val cmd = CMD_RLE or (pixelSource) or (minOf(3, seedRowPixelCopyCount) shl 3) or (minOf(7, normCount))
                out.write(cmd)

                if (seedRowPixelCopyCount >= 3) {
                    outputVli(out, seedRowPixelCopyCount - 3)
                }

                if (pixelSource == SRC_NEW) {
                    val pixIdx = curPixel - replacementCount
                    val currP = getPixel(cur, pixIdx)
                    val seedP = getPixel(seed, pixIdx)
                    val delta = shortDelta(currP, seedP)

                    if (delta != 0) {
                        out.write((delta ushr 8) and 0xFF)
                        out.write(delta and 0xFF)
                    } else {
                        val uncomp = currP ushr 1
                        out.write((uncomp ushr 16) and 0xFF)
                        out.write((uncomp ushr 8) and 0xFF)
                        out.write(uncomp and 0xFF)
                    }
                }

                if (replacementCount - 2 >= 7) {
                    outputVli(out, replacementCount - 9)
                }
            }
        }

        return out.toByteArray()
    }

    /** Returns the printer-reconstructed row that must seed the following scanline. */
    fun compressWithReconstructedSeed(
        currentRow: ByteArray,
        seedRow: ByteArray,
        widthPixels: Int
    ): CompressionResult {
        val compressed = compress(currentRow, seedRow, widthPixels)
        return CompressionResult(
            compressed = compressed,
            reconstructedRow = decompress(compressed, seedRow, widthPixels)
        )
    }

    /**
     * Decodes HP PCL Mode 10 [compressed] data into a copy of [seedRow].
     * Both [seedRow] and output are [widthPixels] * 3 bytes.
     */
    fun decompress(compressed: ByteArray, seedRow: ByteArray, widthPixels: Int): ByteArray {
        val result = seedRow.copyOf(widthPixels * 3)
        var curPixel = 0
        var cachedColor = WHITE_PIXEL
        var inIdx = 0

        while (curPixel < widthPixels && inIdx < compressed.size) {
            val cmd = compressed[inIdx++].toInt() and 0xFF
            val isRle = (cmd and 0x80) != 0
            val pixelSource = cmd and 0x60
            var seedCopy = (cmd ushr 3) and 0x03
            val countBits = cmd and 0x07

            if (seedCopy == 3) {
                var ext: Int
                do {
                    ext = compressed[inIdx++].toInt() and 0xFF
                    seedCopy += ext
                } while (ext == 255)
            }

            // Copy matching seed pixels
            for (i in 0 until seedCopy) {
                if (curPixel < widthPixels) {
                    putPixel(result, curPixel, getPixel(seedRow, curPixel))
                    curPixel++
                }
            }

            if (curPixel >= widthPixels) break

            if (isRle) {
                var runLen = countBits + 2
                val repeatedPixel = when (pixelSource) {
                    SRC_CACHED -> cachedColor
                    SRC_NORTHEAST -> getPixel(seedRow, curPixel + 1)
                    SRC_WEST -> getPixel(result, curPixel - 1)
                    else -> {
                        val p = readPixel(compressed, inIdx, seedRow, curPixel)
                        inIdx += p.bytesConsumed
                        cachedColor = p.pixel
                        p.pixel
                    }
                }

                if (countBits == 7) {
                    var ext: Int
                    do {
                        ext = compressed[inIdx++].toInt() and 0xFF
                        runLen += ext
                    } while (ext == 255)
                }

                for (i in 0 until runLen) {
                    if (curPixel < widthPixels) {
                        putPixel(result, curPixel++, repeatedPixel)
                    }
                }
            } else {
                // Literal
                var runLen = countBits + 1
                var upward = 1

                if (pixelSource != SRC_NEW) {
                    val firstPixel = when (pixelSource) {
                        SRC_CACHED -> cachedColor
                        SRC_NORTHEAST -> getPixel(seedRow, curPixel + 1)
                        SRC_WEST -> getPixel(result, curPixel - 1)
                        else -> WHITE_PIXEL
                    }
                    if (curPixel < widthPixels) {
                        putPixel(result, curPixel++, firstPixel)
                    }
                    upward = 2
                }

                while (upward <= runLen) {
                    val p = readPixel(compressed, inIdx, seedRow, curPixel)
                    inIdx += p.bytesConsumed
                    if (upward == 1 && pixelSource == SRC_NEW) {
                        cachedColor = p.pixel
                    }
                    if (curPixel < widthPixels) {
                        putPixel(result, curPixel++, p.pixel)
                    }

                    if (countBits == 7 && upward >= 8 && (upward - 8) % 255 == 0) {
                        if (inIdx >= compressed.size) {
                            throw IllegalArgumentException("Truncated Mode 10 literal extension")
                        }
                        runLen += compressed[inIdx++].toInt() and 0xFF
                    }
                    upward++
                }
            }
        }

        return result
    }

    private data class DecodedPixel(val pixel: Int, val bytesConsumed: Int)

    private fun readPixel(buf: ByteArray, inIdx: Int, seedRow: ByteArray, curPixel: Int): DecodedPixel {
        val b0 = buf[inIdx].toInt() and 0xFF
        return if ((b0 and 0x80) != 0) {
            val b1 = buf[inIdx + 1].toInt() and 0xFF
            val delta = (b0 shl 8) or b1
            var dr = (delta ushr 10) and 0x1F
            if (dr >= 16) dr -= 32
            var dg = (delta ushr 5) and 0x1F
            if (dg >= 16) dg -= 32
            var dbHalf = delta and 0x1F
            if (dbHalf >= 16) dbHalf -= 32
            val db = dbHalf shl 1

            val upper = if (curPixel < seedRow.size / 3) getPixel(seedRow, curPixel) else WHITE_PIXEL
            val r = (((upper ushr 16) and 0xFF) + dr).coerceIn(0, 255)
            val g = (((upper ushr 8) and 0xFF) + dg).coerceIn(0, 255)
            val b = ((upper and 0xFF) + db).coerceIn(0, 255)
            DecodedPixel((r shl 16) or (g shl 8) or b, 2)
        } else {
            val b1 = buf[inIdx + 1].toInt() and 0xFF
            val b2 = buf[inIdx + 2].toInt() and 0xFF
            val uncomp = (b0 shl 16) or (b1 shl 8) or b2
            val pixel = (uncomp shl 1) and WHITE_PIXEL
            DecodedPixel(pixel, 3)
        }
    }
}
