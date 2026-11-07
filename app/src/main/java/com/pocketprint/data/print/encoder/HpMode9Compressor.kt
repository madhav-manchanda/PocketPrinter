/*
 * HpMode9Compressor.kt
 *
 * Ported directly from HPLIP hpcups Mode9 engine (prnt/hpcups/Mode9.cpp)
 * Authored by Kevin Hudson, Hewlett-Packard Co. (BSD 3-Clause).
 *
 * Mode 9 is the Advanced Delta Row compression algorithm used by HP Pcl3Gui2
 * printers (including HP Ink Tank 310 series) for the monochrome K plane.
 */
package com.pocketprint.data.print.encoder

import java.io.ByteArrayOutputStream

object HpMode9Compressor {

    private const val MAX_OFFSET0 = 14
    private const val OFFSET_START0 = 0
    private const val MAX_COUNT0 = 6
    private const val COUNT_START0 = 1

    private const val MAX_OFFSET1 = 2
    private const val OFFSET_START1 = 0
    private const val MAX_COUNT1 = 30
    private const val COUNT_START1 = 2

    /**
     * Compresses [current] row relative to [seed] row using HP PCL Mode 9.
     * Returns an empty ByteArray if [current] is identical to [seed].
     */
    fun compress(current: ByteArray, seed: ByteArray, len: Int): ByteArray {
        val out = ByteArrayOutputStream(len * 2)
        var s = 0
        var n = 0
        var size = len

        while (size > 0) {
            var offset = 0
            while (offset < size && seed[s + offset] == current[n + offset]) {
                offset++
            }

            if (offset >= size) {
                // No more differences in the remainder of the row
                break
            }

            s += offset
            n += offset
            size -= offset

            // Mode 0: Literal replacement
            // Mode 1: Repeated byte run (RLE)
            val isMode0 = (size < 2) || (current[n] != current[n + 1])

            if (isMode0) {
                var lastByte = current[n++]
                s++
                var byteCount = 1

                while (byteCount < size && seed[s] != current[n] && lastByte != current[n]) {
                    byteCount++
                    lastByte = current[n++]
                    s++
                }

                // If stopped because two consecutive identical bytes were encountered,
                // pull back the last byte so Mode 1 can compress the repeated run
                if (byteCount < size && lastByte == current[n]) {
                    n--
                    s--
                    byteCount--
                }

                size -= byteCount

                val roff = if (offset > (MAX_OFFSET0 + OFFSET_START0)) {
                    MAX_OFFSET0 + 1
                } else {
                    offset - OFFSET_START0
                }

                val replaceCount = if (byteCount > (MAX_COUNT0 + COUNT_START0)) {
                    MAX_COUNT0 + 1
                } else {
                    byteCount - COUNT_START0
                }

                // Mode 0 command byte: (type=0:1 | roff:4 | replace_count:3)
                val comchar = ((roff and 0x0F) shl 3) or (replaceCount and 0x07)
                out.write(comchar)

                // Offset extension
                if (offset > (MAX_OFFSET0 + OFFSET_START0)) {
                    var remOff = offset - (MAX_OFFSET0 + OFFSET_START0 + 1)
                    if (remOff == 0) {
                        out.write(0)
                    } else {
                        while (remOff > 0) {
                            val chunk = minOf(remOff, 255)
                            out.write(chunk)
                            if (remOff == 255) {
                                out.write(0)
                            }
                            remOff -= chunk
                        }
                    }
                }

                // Count extension
                if (byteCount > (MAX_COUNT0 + COUNT_START0)) {
                    var remCount = byteCount - (MAX_COUNT0 + COUNT_START0 + 1)
                    if (remCount == 0) {
                        out.write(0)
                    } else {
                        while (remCount > 0) {
                            val chunk = minOf(remCount, 255)
                            out.write(chunk)
                            if (remCount == 255) {
                                out.write(0)
                            }
                            remCount -= chunk
                        }
                    }
                }

                // Literal bytes
                val startPos = n - byteCount
                out.write(current, startPos, byteCount)

            } else {
                // Mode 1: Repeated byte run
                val repeatedByte = current[n]
                var byteCount = 2
                n += 2

                while (byteCount < size && current[n] == repeatedByte) {
                    byteCount++
                    n++
                }

                s += byteCount
                size -= byteCount

                val roff = if (offset > (MAX_OFFSET1 + OFFSET_START1)) {
                    MAX_OFFSET1 + 1
                } else {
                    offset - OFFSET_START1
                }

                val replaceCount = if (byteCount > (MAX_COUNT1 + COUNT_START1)) {
                    MAX_COUNT1 + 1
                } else {
                    byteCount - COUNT_START1
                }

                // Mode 1 command byte: (type=1:1 | roff:2 | replace_count:5)
                val comchar = 0x80 or ((roff and 0x03) shl 5) or (replaceCount and 0x1F)
                out.write(comchar)

                // Offset extension
                if (offset > (MAX_OFFSET1 + OFFSET_START1)) {
                    var remOff = offset - (MAX_OFFSET1 + OFFSET_START1 + 1)
                    if (remOff == 0) {
                        out.write(0)
                    } else {
                        while (remOff > 0) {
                            val chunk = minOf(remOff, 255)
                            out.write(chunk)
                            if (remOff == 255) {
                                out.write(0)
                            }
                            remOff -= chunk
                        }
                    }
                }

                // Count extension
                if (byteCount > (MAX_COUNT1 + COUNT_START1)) {
                    var remCount = byteCount - (MAX_COUNT1 + COUNT_START1 + 1)
                    if (remCount == 0) {
                        out.write(0)
                    } else {
                        while (remCount > 0) {
                            val chunk = minOf(remCount, 255)
                            out.write(chunk)
                            if (remCount == 255) {
                                out.write(0)
                            }
                            remCount -= chunk
                        }
                    }
                }

                // Single repeated byte
                out.write(repeatedByte.toInt() and 0xFF)
            }
        }

        return out.toByteArray()
    }

    /**
     * Decodes HP PCL Mode 9 [compressed] data into a copy of [seed].
     * Used for round-trip verification and diagnostic preview reconstruction.
     */
    fun decompress(compressed: ByteArray, seed: ByteArray, len: Int): ByteArray {
        val result = seed.copyOf(len)
        var inIdx = 0
        var outIdx = 0

        while (inIdx < compressed.size && outIdx < len) {
            val cmd = compressed[inIdx++].toInt() and 0xFF
            val isMode1 = (cmd and 0x80) != 0

            if (!isMode1) {
                // Mode 0: Literal replacement
                val roff = (cmd ushr 3) and 0x0F
                val countBits = cmd and 0x07

                var offset = roff
                if (roff == 15) {
                    var ext = compressed[inIdx++].toInt() and 0xFF
                    offset += ext
                    while (ext == 255) {
                        ext = compressed[inIdx++].toInt() and 0xFF
                        offset += ext
                    }
                }

                var byteCount = countBits + 1
                if (countBits == 7) {
                    var ext = compressed[inIdx++].toInt() and 0xFF
                    byteCount += ext
                    while (ext == 255) {
                        ext = compressed[inIdx++].toInt() and 0xFF
                        byteCount += ext
                    }
                }

                outIdx += offset
                for (i in 0 until byteCount) {
                    if (outIdx < len) {
                        result[outIdx++] = compressed[inIdx++]
                    }
                }
            } else {
                // Mode 1: Repeated byte run
                val roff = (cmd ushr 5) and 0x03
                val countBits = cmd and 0x1F

                var offset = roff
                if (roff == 3) {
                    var ext = compressed[inIdx++].toInt() and 0xFF
                    offset += ext
                    while (ext == 255) {
                        ext = compressed[inIdx++].toInt() and 0xFF
                        offset += ext
                    }
                }

                var byteCount = countBits + 2
                if (countBits == 31) {
                    var ext = compressed[inIdx++].toInt() and 0xFF
                    byteCount += ext
                    while (ext == 255) {
                        ext = compressed[inIdx++].toInt() and 0xFF
                        byteCount += ext
                    }
                }

                outIdx += offset
                val repeatedByte = compressed[inIdx++]
                for (i in 0 until byteCount) {
                    if (outIdx < len) {
                        result[outIdx++] = repeatedByte
                    }
                }
            }
        }

        return result
    }
}
