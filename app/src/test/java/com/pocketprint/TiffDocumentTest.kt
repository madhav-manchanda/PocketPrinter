package com.pocketprint

import com.pocketprint.data.document.tiff.TiffDocument
import com.pocketprint.data.document.tiff.TiffFormatException
import com.pocketprint.data.document.tiff.UnsupportedTiffFeatureException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.zip.Deflater

class TiffDocumentTest {

    @Test
    fun `little endian uncompressed grayscale decodes`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 3,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 1,
            payload = byteArrayOf(0, 0x7F, 0xFF.toByte())
        )

        val document = TiffDocument(input)
        val page = document.decodePage(0)

        assertEquals(1, document.pageCount)
        assertEquals(3, page.width)
        assertEquals(1, page.height)
        assertArrayEquals(
            intArrayOf(0xFF000000.toInt(), 0xFF7F7F7F.toInt(), 0xFFFFFFFF.toInt()),
            page.argb
        )
    }

    @Test
    fun `big endian uncompressed RGB decodes`() {
        val input = singleStripTiff(
            littleEndian = false,
            width = 2,
            height = 1,
            bitsPerSample = intArrayOf(8, 8, 8),
            samplesPerPixel = 3,
            photometric = 2,
            compression = 1,
            payload = byteArrayOf(
                0xFF.toByte(), 0, 0,
                0, 0x80.toByte(), 0xFF.toByte()
            )
        )

        val page = TiffDocument(input).decodePage(0)

        assertArrayEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF0080FF.toInt()),
            page.argb
        )
    }

    @Test
    fun `linked IFDs expose multiple pages`() {
        val entryCount = 9
        val ifdSize = ifdSize(entryCount)
        val firstIfd = 8
        val secondIfd = firstIfd + ifdSize
        val firstPixel = secondIfd + ifdSize
        val secondPixel = firstPixel + 1
        val writer = TiffWriter(secondPixel + 1, littleEndian = true)
        writer.header(firstIfd)
        writeGrayIfd(
            writer = writer,
            offset = firstIfd,
            stripOffset = firstPixel,
            nextIfdOffset = secondIfd
        )
        writeGrayIfd(
            writer = writer,
            offset = secondIfd,
            stripOffset = secondPixel,
            nextIfdOffset = 0
        )
        writer.bytes[firstPixel] = 0
        writer.bytes[secondPixel] = 0xFF.toByte()

        val document = TiffDocument(writer.bytes)

        assertEquals(2, document.pageCount)
        assertArrayEquals(intArrayOf(0xFF000000.toInt()), document.decodePage(0).argb)
        assertArrayEquals(intArrayOf(0xFFFFFFFF.toInt()), document.decodePage(1).argb)
    }

    @Test
    fun `PackBits literal and repeated runs decode`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 6,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 32773,
            payload = byteArrayOf(
                0xFE.toByte(), 7,
                2, 8, 9, 10
            )
        )

        val page = TiffDocument(input).decodePage(0)

        assertArrayEquals(
            intArrayOf(gray(7), gray(7), gray(7), gray(8), gray(9), gray(10)),
            page.argb
        )
    }

    @Test
    fun `multiple strips decode in row order`() {
        val entryCount = 9
        val ifdOffset = 8
        val offsetsArray = ifdOffset + ifdSize(entryCount)
        val countsArray = offsetsArray + 8
        val firstStrip = countsArray + 8
        val secondStrip = firstStrip + 4
        val writer = TiffWriter(secondStrip + 2, littleEndian = true)
        writer.header(ifdOffset)
        writer.unsignedShort(ifdOffset, entryCount)

        var entry = ifdOffset + 2
        writer.longEntry(entry, 256, 2)
        entry += 12
        writer.longEntry(entry, 257, 3)
        entry += 12
        writer.shortEntry(entry, 258, 8)
        entry += 12
        writer.shortEntry(entry, 259, 1)
        entry += 12
        writer.shortEntry(entry, 262, 1)
        entry += 12
        writer.longValuesEntry(entry, 273, intArrayOf(firstStrip, secondStrip), offsetsArray)
        entry += 12
        writer.shortEntry(entry, 277, 1)
        entry += 12
        writer.longEntry(entry, 278, 2)
        entry += 12
        writer.longValuesEntry(entry, 279, intArrayOf(4, 2), countsArray)
        entry += 12
        writer.unsignedInt(entry, 0)
        byteArrayOf(0, 64, 0x80.toByte(), 0xC0.toByte()).copyInto(writer.bytes, firstStrip)
        byteArrayOf(0xFF.toByte(), 32).copyInto(writer.bytes, secondStrip)

        val page = TiffDocument(writer.bytes).decodePage(0)

        assertArrayEquals(
            intArrayOf(gray(0), gray(64), gray(128), gray(192), gray(255), gray(32)),
            page.argb
        )
    }

    @Test
    fun `bilevel WhiteIsZero decodes most significant bit first`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 8,
            height = 1,
            bitsPerSample = intArrayOf(1),
            samplesPerPixel = 1,
            photometric = 0,
            compression = 1,
            payload = byteArrayOf(0b01010101)
        )

        val page = TiffDocument(input).decodePage(0)

        assertArrayEquals(
            intArrayOf(
                0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt(),
                0xFFFFFFFF.toInt(), 0xFF000000.toInt()
            ),
            page.argb
        )
    }

    @Test
    fun `RGBA preserves unassociated alpha`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 1,
            height = 1,
            bitsPerSample = intArrayOf(8, 8, 8, 8),
            samplesPerPixel = 4,
            photometric = 2,
            compression = 1,
            payload = byteArrayOf(0x60, 0x40, 0x20, 0x80.toByte()),
            extraSample = 2
        )

        assertArrayEquals(
            intArrayOf(0x80604020.toInt()),
            TiffDocument(input).decodePage(0).argb
        )
    }

    @Test(expected = com.pocketprint.data.document.tiff.UnsupportedTiffFeatureException::class)
    fun `fourth TIFF sample without alpha declaration is rejected`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 1,
            height = 1,
            bitsPerSample = intArrayOf(8, 8, 8, 8),
            samplesPerPixel = 4,
            photometric = 2,
            compression = 1,
            payload = byteArrayOf(1, 2, 3, 4),
            extraSample = null
        )
        TiffDocument(input).decodePage(0)
    }

    @Test
    fun `TIFF LZW stream decodes`() {
        val compressed = packNineBitCodes(256, 10, 20, 30, 257)
        val input = singleStripTiff(
            littleEndian = true,
            width = 3,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 5,
            payload = compressed
        )

        assertArrayEquals(
            intArrayOf(gray(10), gray(20), gray(30)),
            TiffDocument(input).decodePage(0).argb
        )
    }

    @Test
    fun `Deflate and horizontal predictor decode`() {
        val compressed = deflate(byteArrayOf(10, 5, 5))
        val input = singleStripTiff(
            littleEndian = true,
            width = 3,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 8,
            payload = compressed,
            predictor = 2
        )

        assertArrayEquals(
            intArrayOf(gray(10), gray(15), gray(20)),
            TiffDocument(input).decodePage(0).argb
        )
    }

    @Test
    fun `IFD outside input is rejected during parsing`() {
        val malformed = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0,
            100, 0, 0, 0
        )

        val error = assertThrows(TiffFormatException::class.java) {
            TiffDocument(malformed)
        }

        assertTrue(error.message.orEmpty().contains("outside TIFF data"))
    }

    @Test
    fun `strip outside input is rejected before decoding`() {
        val malformed = singleStripTiff(
            littleEndian = true,
            width = 1,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 1,
            payload = byteArrayOf(0)
        )
        // StripOffsets is the sixth entry in this handcrafted IFD.
        TiffWriter.writeUnsignedInt(malformed, 8 + 2 + 5 * 12 + 8, 0x7FFF_FFFFL, true)

        val error = assertThrows(TiffFormatException::class.java) {
            TiffDocument(malformed).decodePage(0)
        }

        assertTrue(error.message.orEmpty().contains("TIFF strip 0"))
        assertTrue(error.message.orEmpty().contains("outside TIFF data"))
    }

    @Test
    fun `BigTIFF is rejected explicitly`() {
        val bigTiffHeader = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 43, 0,
            8, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
        )

        val error = assertThrows(UnsupportedTiffFeatureException::class.java) {
            TiffDocument(bigTiffHeader)
        }

        assertTrue(error.message.orEmpty().contains("BigTIFF"))
    }

    @Test
    fun `unsupported compression reports its code`() {
        val input = singleStripTiff(
            littleEndian = true,
            width = 1,
            height = 1,
            bitsPerSample = intArrayOf(8),
            samplesPerPixel = 1,
            photometric = 1,
            compression = 7,
            payload = byteArrayOf(0)
        )

        val error = assertThrows(UnsupportedTiffFeatureException::class.java) {
            TiffDocument(input).decodePage(0)
        }

        assertTrue(error.message.orEmpty().contains("compression 7"))
    }

    private fun singleStripTiff(
        littleEndian: Boolean,
        width: Int,
        height: Int,
        bitsPerSample: IntArray,
        samplesPerPixel: Int,
        photometric: Int,
        compression: Int,
        payload: ByteArray,
        predictor: Int = 1,
        extraSample: Int? = null
    ): ByteArray {
        val entryCount = 10 + (if (predictor == 1) 0 else 1) + (if (extraSample == null) 0 else 1)
        val ifdOffset = 8
        val valuesOffset = ifdOffset + ifdSize(entryCount)
        val externalBitsSize = if (bitsPerSample.size * 2 > 4) bitsPerSample.size * 2 else 0
        val stripOffset = valuesOffset + externalBitsSize
        val writer = TiffWriter(stripOffset + payload.size, littleEndian)
        writer.header(ifdOffset)
        writer.unsignedShort(ifdOffset, entryCount)

        var entry = ifdOffset + 2
        writer.longEntry(entry, 256, width)
        entry += 12
        writer.longEntry(entry, 257, height)
        entry += 12
        writer.shortValuesEntry(entry, 258, bitsPerSample, valuesOffset)
        entry += 12
        writer.shortEntry(entry, 259, compression)
        entry += 12
        writer.shortEntry(entry, 262, photometric)
        entry += 12
        writer.longEntry(entry, 273, stripOffset)
        entry += 12
        writer.shortEntry(entry, 277, samplesPerPixel)
        entry += 12
        writer.longEntry(entry, 278, height)
        entry += 12
        writer.longEntry(entry, 279, payload.size)
        entry += 12
        writer.shortEntry(entry, 284, 1)
        entry += 12
        if (predictor != 1) {
            writer.shortEntry(entry, 317, predictor)
            entry += 12
        }
        if (extraSample != null) {
            writer.shortEntry(entry, 338, extraSample)
            entry += 12
        }
        writer.unsignedInt(entry, 0)
        payload.copyInto(writer.bytes, stripOffset)
        return writer.bytes
    }

    private fun writeGrayIfd(
        writer: TiffWriter,
        offset: Int,
        stripOffset: Int,
        nextIfdOffset: Int
    ) {
        writer.unsignedShort(offset, 9)
        var entry = offset + 2
        writer.longEntry(entry, 256, 1)
        entry += 12
        writer.longEntry(entry, 257, 1)
        entry += 12
        writer.shortEntry(entry, 258, 8)
        entry += 12
        writer.shortEntry(entry, 259, 1)
        entry += 12
        writer.shortEntry(entry, 262, 1)
        entry += 12
        writer.longEntry(entry, 273, stripOffset)
        entry += 12
        writer.shortEntry(entry, 277, 1)
        entry += 12
        writer.longEntry(entry, 278, 1)
        entry += 12
        writer.longEntry(entry, 279, 1)
        entry += 12
        writer.unsignedInt(entry, nextIfdOffset.toLong())
    }

    private fun ifdSize(entryCount: Int): Int = 2 + entryCount * 12 + 4

    private fun gray(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    private fun packNineBitCodes(vararg codes: Int): ByteArray {
        val output = ByteArray((codes.size * 9 + 7) / 8)
        var bitPosition = 0
        for (code in codes) {
            for (bit in 8 downTo 0) {
                if ((code and (1 shl bit)) != 0) {
                    val byteIndex = bitPosition / 8
                    val bitInByte = 7 - bitPosition % 8
                    output[byteIndex] = (
                        (output[byteIndex].toInt() and 0xFF) or (1 shl bitInByte)
                        ).toByte()
                }
                bitPosition++
            }
        }
        return output
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArray(64)
            output.copyOf(deflater.deflate(output))
        } finally {
            deflater.end()
        }
    }

    private class TiffWriter(
        size: Int,
        private val littleEndian: Boolean
    ) {
        val bytes = ByteArray(size)

        fun header(firstIfdOffset: Int) {
            bytes[0] = if (littleEndian) 'I'.code.toByte() else 'M'.code.toByte()
            bytes[1] = bytes[0]
            unsignedShort(2, 42)
            unsignedInt(4, firstIfdOffset.toLong())
        }

        fun shortEntry(offset: Int, tag: Int, value: Int) {
            unsignedShort(offset, tag)
            unsignedShort(offset + 2, 3)
            unsignedInt(offset + 4, 1)
            unsignedShort(offset + 8, value)
        }

        fun shortValuesEntry(offset: Int, tag: Int, values: IntArray, valuesOffset: Int) {
            unsignedShort(offset, tag)
            unsignedShort(offset + 2, 3)
            unsignedInt(offset + 4, values.size.toLong())
            if (values.size * 2 <= 4) {
                values.forEachIndexed { index, value ->
                    unsignedShort(offset + 8 + index * 2, value)
                }
            } else {
                unsignedInt(offset + 8, valuesOffset.toLong())
                values.forEachIndexed { index, value ->
                    unsignedShort(valuesOffset + index * 2, value)
                }
            }
        }

        fun longEntry(offset: Int, tag: Int, value: Int) {
            unsignedShort(offset, tag)
            unsignedShort(offset + 2, 4)
            unsignedInt(offset + 4, 1)
            unsignedInt(offset + 8, value.toLong())
        }

        fun longValuesEntry(offset: Int, tag: Int, values: IntArray, valuesOffset: Int) {
            unsignedShort(offset, tag)
            unsignedShort(offset + 2, 4)
            unsignedInt(offset + 4, values.size.toLong())
            unsignedInt(offset + 8, valuesOffset.toLong())
            values.forEachIndexed { index, value ->
                unsignedInt(valuesOffset + index * 4, value.toLong())
            }
        }

        fun unsignedShort(offset: Int, value: Int) {
            if (littleEndian) {
                bytes[offset] = value.toByte()
                bytes[offset + 1] = (value ushr 8).toByte()
            } else {
                bytes[offset] = (value ushr 8).toByte()
                bytes[offset + 1] = value.toByte()
            }
        }

        fun unsignedInt(offset: Int, value: Long) {
            writeUnsignedInt(bytes, offset, value, littleEndian)
        }

        companion object {
            fun writeUnsignedInt(
                destination: ByteArray,
                offset: Int,
                value: Long,
                littleEndian: Boolean
            ) {
                for (index in 0 until 4) {
                    val shift = if (littleEndian) index * 8 else (3 - index) * 8
                    destination[offset + index] = (value ushr shift).toByte()
                }
            }
        }
    }
}
