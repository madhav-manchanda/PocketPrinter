package com.pocketprint.data.document.tiff

import java.util.zip.DataFormatException
import java.util.zip.Inflater

data class DecodedTiffPage(
    val width: Int,
    val height: Int,
    val argb: IntArray
)

class TiffFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

class UnsupportedTiffFeatureException(message: String) : UnsupportedOperationException(message)

/**
 * A bounded, dependency-free decoder for classic strip-based TIFF images.
 *
 * The input is copied so metadata validated by the constructor cannot be changed by the caller.
 */
class TiffDocument(input: ByteArray) {

    private val bytes: ByteArray
    private val reader: TiffReader
    private val pageOffsets: IntArray

    val pageCount: Int
        get() = pageOffsets.size

    init {
        if (input.size > MAX_INPUT_BYTES) {
            throw TiffFormatException(
                "TIFF input exceeds the $MAX_INPUT_BYTES byte safety limit"
            )
        }
        if (input.size < 4) {
            throw TiffFormatException("TIFF data is too short for a header")
        }

        bytes = input.copyOf()
        val littleEndian = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> true
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> false
            else -> throw TiffFormatException("Invalid TIFF byte-order marker")
        }
        reader = TiffReader(bytes, littleEndian)

        when (val magic = reader.unsignedShort(2)) {
            CLASSIC_TIFF_MAGIC -> Unit
            BIG_TIFF_MAGIC -> throw UnsupportedTiffFeatureException("BigTIFF is not supported")
            else -> throw TiffFormatException("Invalid TIFF magic value $magic")
        }
        reader.requireRange(0, CLASSIC_HEADER_SIZE.toLong(), "TIFF header")
        pageOffsets = collectPageOffsets(reader.unsignedInt(4))
    }

    fun decodePage(index: Int): DecodedTiffPage {
        if (index !in pageOffsets.indices) {
            throw IndexOutOfBoundsException(
                "TIFF page index $index is outside 0 until $pageCount"
            )
        }

        val page = parsePage(pageOffsets[index])
        val pixels = IntArray(page.width * page.height)
        var firstRow = 0

        for (stripIndex in page.stripOffsets.indices) {
            val rowsInStrip = minOf(
                page.rowsPerStrip,
                (page.height - firstRow).toLong()
            ).toInt()
            val decodedSize = page.rowBytes.toLong() * rowsInStrip.toLong()
            if (decodedSize > MAX_DECODED_STRIP_BYTES) {
                throw TiffFormatException(
                    "Decoded TIFF strip $stripIndex exceeds the " +
                        "$MAX_DECODED_STRIP_BYTES byte safety limit"
                )
            }

            val decoded = decodeStrip(
                page = page,
                stripIndex = stripIndex,
                expectedSize = decodedSize.toInt()
            )
            if (page.predictor == PREDICTOR_HORIZONTAL) {
                reverseHorizontalPredictor(
                    decoded,
                    page.rowBytes,
                    rowsInStrip,
                    page.samplesPerPixel
                )
            }
            writePixels(page, decoded, firstRow, rowsInStrip, pixels)
            firstRow += rowsInStrip
        }

        if (firstRow != page.height) {
            throw TiffFormatException(
                "TIFF strips contain $firstRow rows, expected ${page.height}"
            )
        }
        return DecodedTiffPage(page.width, page.height, pixels)
    }

    private fun collectPageOffsets(firstOffset: Long): IntArray {
        val offsets = ArrayList<Int>()
        val seen = HashSet<Long>()
        var offset = firstOffset

        while (offset != 0L) {
            if (offsets.size >= MAX_PAGE_COUNT) {
                throw TiffFormatException(
                    "TIFF contains more than the $MAX_PAGE_COUNT page safety limit"
                )
            }
            if (!seen.add(offset)) {
                throw TiffFormatException("TIFF IFD chain contains a cycle at offset $offset")
            }

            reader.requireRange(offset, 2, "TIFF IFD")
            val ifdOffset = offset.toInt()
            val entryCount = reader.unsignedShort(ifdOffset)
            val nextOffsetPosition = offset + 2L + entryCount.toLong() * IFD_ENTRY_SIZE
            reader.requireRange(
                offset,
                2L + entryCount.toLong() * IFD_ENTRY_SIZE + 4L,
                "TIFF IFD"
            )
            offsets.add(ifdOffset)
            offset = reader.unsignedInt(nextOffsetPosition.toInt())
        }

        return offsets.toIntArray()
    }

    private fun parsePage(ifdOffset: Int): PageInfo {
        val entries = readEntries(ifdOffset)
        if (
            entries.containsKey(TAG_TILE_WIDTH) ||
            entries.containsKey(TAG_TILE_LENGTH) ||
            entries.containsKey(TAG_TILE_OFFSETS) ||
            entries.containsKey(TAG_TILE_BYTE_COUNTS)
        ) {
            throw UnsupportedTiffFeatureException("Tiled TIFF images are not supported")
        }

        val width = dimension(
            scalar(entries, TAG_IMAGE_WIDTH, "ImageWidth", allowLong = true),
            "ImageWidth"
        )
        val height = dimension(
            scalar(entries, TAG_IMAGE_LENGTH, "ImageLength", allowLong = true),
            "ImageLength"
        )
        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_PAGE_PIXELS) {
            throw TiffFormatException(
                "TIFF page has $pixelCount pixels, exceeding the $MAX_PAGE_PIXELS pixel safety limit"
            )
        }

        val compression = scalar(
            entries,
            TAG_COMPRESSION,
            "Compression",
            default = COMPRESSION_NONE.toLong()
        ).toInt()
        if (
            compression != COMPRESSION_NONE &&
            compression != COMPRESSION_LZW &&
            compression != COMPRESSION_DEFLATE &&
            compression != COMPRESSION_ADOBE_DEFLATE &&
            compression != COMPRESSION_PACK_BITS
        ) {
            throw UnsupportedTiffFeatureException(
                "Unsupported TIFF compression $compression"
            )
        }

        val orientation = scalar(
            entries,
            TAG_ORIENTATION,
            "Orientation",
            default = ORIENTATION_TOP_LEFT.toLong()
        )
        if (orientation != ORIENTATION_TOP_LEFT.toLong()) {
            throw UnsupportedTiffFeatureException(
                "Unsupported TIFF orientation $orientation; only orientation 1 is supported"
            )
        }

        val planarConfiguration = scalar(
            entries,
            TAG_PLANAR_CONFIGURATION,
            "PlanarConfiguration",
            default = PLANAR_CHUNKY.toLong()
        )
        if (planarConfiguration != PLANAR_CHUNKY.toLong()) {
            throw UnsupportedTiffFeatureException(
                "Planar TIFF images are not supported; PlanarConfiguration must be 1"
            )
        }

        val fillOrder = scalar(
            entries,
            TAG_FILL_ORDER,
            "FillOrder",
            default = FILL_ORDER_MOST_SIGNIFICANT_FIRST.toLong()
        )
        if (fillOrder != FILL_ORDER_MOST_SIGNIFICANT_FIRST.toLong()) {
            throw UnsupportedTiffFeatureException(
                "Unsupported TIFF FillOrder $fillOrder; only FillOrder 1 is supported"
            )
        }

        val samplesPerPixelLong = scalar(
            entries,
            TAG_SAMPLES_PER_PIXEL,
            "SamplesPerPixel",
            default = 1
        )
        if (samplesPerPixelLong !in 1L..4L) {
            throw UnsupportedTiffFeatureException(
                "Unsupported TIFF SamplesPerPixel $samplesPerPixelLong"
            )
        }
        val samplesPerPixel = samplesPerPixelLong.toInt()
        val bitsPerSample = entries[TAG_BITS_PER_SAMPLE]?.let {
            unsignedValues(
                it,
                "BitsPerSample",
                expectedCount = samplesPerPixel,
                allowLong = false
            )
        } ?: LongArray(samplesPerPixel) { 1 }

        entries[TAG_SAMPLE_FORMAT]?.let {
            val formats = unsignedValues(
                it,
                "SampleFormat",
                expectedCount = samplesPerPixel,
                allowLong = false
            )
            if (formats.any { format -> format != SAMPLE_FORMAT_UNSIGNED.toLong() }) {
                throw UnsupportedTiffFeatureException(
                    "Only unsigned integer TIFF samples are supported"
                )
            }
        }

        val photometric = scalar(
            entries,
            TAG_PHOTOMETRIC_INTERPRETATION,
            "PhotometricInterpretation"
        ).toInt()
        val pixelFormat = when {
            samplesPerPixel == 1 &&
                bitsPerSample.contentEquals(longArrayOf(1)) &&
                (photometric == PHOTOMETRIC_WHITE_IS_ZERO ||
                    photometric == PHOTOMETRIC_BLACK_IS_ZERO) -> PixelFormat.BILEVEL

            samplesPerPixel == 1 &&
                bitsPerSample.contentEquals(longArrayOf(8)) &&
                (photometric == PHOTOMETRIC_WHITE_IS_ZERO ||
                    photometric == PHOTOMETRIC_BLACK_IS_ZERO) -> PixelFormat.GRAYSCALE

            samplesPerPixel == 3 &&
                bitsPerSample.all { it == 8L } &&
                photometric == PHOTOMETRIC_RGB -> PixelFormat.RGB

            samplesPerPixel == 4 &&
                bitsPerSample.all { it == 8L } &&
                photometric == PHOTOMETRIC_RGB -> PixelFormat.RGBA

            else -> throw UnsupportedTiffFeatureException(
                "Unsupported TIFF pixel layout: photometric=$photometric, " +
                    "samples=$samplesPerPixel, bits=${bitsPerSample.joinToString()}"
            )
        }

        var associatedAlpha = false
        val extraSamplesEntry = entries[TAG_EXTRA_SAMPLES]
        if (pixelFormat == PixelFormat.RGBA && extraSamplesEntry != null) {
            val extraSample = unsignedValues(
                extraSamplesEntry,
                "ExtraSamples",
                expectedCount = 1,
                allowLong = false
            )[0]
            if (extraSample !in EXTRA_SAMPLE_ASSOCIATED_ALPHA.toLong()..EXTRA_SAMPLE_UNASSOCIATED_ALPHA.toLong()) {
                throw UnsupportedTiffFeatureException(
                    "TIFF fourth sample must explicitly be associated or unassociated alpha; value $extraSample is unsafe"
                )
            }
            associatedAlpha = extraSample == EXTRA_SAMPLE_ASSOCIATED_ALPHA.toLong()
        } else if (pixelFormat == PixelFormat.RGBA) {
            throw UnsupportedTiffFeatureException(
                "Four-sample RGB TIFF requires an explicit alpha ExtraSamples tag"
            )
        } else if (extraSamplesEntry != null) {
            throw UnsupportedTiffFeatureException(
                "ExtraSamples is only supported for 8-bit RGBA TIFF images"
            )
        }

        val predictor = scalar(
            entries,
            TAG_PREDICTOR,
            "Predictor",
            default = PREDICTOR_NONE.toLong()
        ).toInt()
        if (predictor != PREDICTOR_NONE && predictor != PREDICTOR_HORIZONTAL) {
            throw UnsupportedTiffFeatureException("Unsupported TIFF predictor $predictor")
        }
        if (predictor == PREDICTOR_HORIZONTAL && pixelFormat == PixelFormat.BILEVEL) {
            throw UnsupportedTiffFeatureException(
                "Horizontal prediction is only supported for 8-bit TIFF samples"
            )
        }

        val bitsPerPixel = bitsPerSample.sum()
        val rowBits = width.toLong() * bitsPerPixel
        val rowBytesLong = (rowBits + 7L) / 8L
        if (rowBytesLong <= 0L || rowBytesLong > MAX_DECODED_STRIP_BYTES) {
            throw TiffFormatException("TIFF row size $rowBytesLong is outside safety limits")
        }
        val rowBytes = rowBytesLong.toInt()

        val rowsPerStrip = scalar(
            entries,
            TAG_ROWS_PER_STRIP,
            "RowsPerStrip",
            default = UINT32_MAX,
            allowLong = true
        )
        if (rowsPerStrip <= 0L) {
            throw TiffFormatException("RowsPerStrip must be greater than zero")
        }
        val stripCountLong = (height.toLong() - 1L) / rowsPerStrip + 1L
        if (stripCountLong > MAX_STRIP_COUNT) {
            throw TiffFormatException(
                "TIFF page has $stripCountLong strips, exceeding the $MAX_STRIP_COUNT strip safety limit"
            )
        }
        val stripCount = stripCountLong.toInt()
        val stripOffsets = unsignedValues(
            requiredEntry(entries, TAG_STRIP_OFFSETS, "StripOffsets"),
            "StripOffsets",
            expectedCount = stripCount,
            allowLong = true
        )
        val stripByteCounts = unsignedValues(
            requiredEntry(entries, TAG_STRIP_BYTE_COUNTS, "StripByteCounts"),
            "StripByteCounts",
            expectedCount = stripCount,
            allowLong = true
        )

        for (stripIndex in 0 until stripCount) {
            reader.requireRange(
                stripOffsets[stripIndex],
                stripByteCounts[stripIndex],
                "TIFF strip $stripIndex"
            )
        }

        return PageInfo(
            width = width,
            height = height,
            rowBytes = rowBytes,
            rowsPerStrip = rowsPerStrip,
            samplesPerPixel = samplesPerPixel,
            compression = compression,
            predictor = predictor,
            photometric = photometric,
            pixelFormat = pixelFormat,
            associatedAlpha = associatedAlpha,
            stripOffsets = stripOffsets,
            stripByteCounts = stripByteCounts
        )
    }

    private fun readEntries(ifdOffset: Int): Map<Int, TiffEntry> {
        val entryCount = reader.unsignedShort(ifdOffset)
        val entries = HashMap<Int, TiffEntry>()
        var entryOffset = ifdOffset + 2

        repeat(entryCount) {
            val tag = reader.unsignedShort(entryOffset)
            if (isRelevantTag(tag)) {
                val entry = TiffEntry(
                    type = reader.unsignedShort(entryOffset + 2),
                    count = reader.unsignedInt(entryOffset + 4),
                    valueFieldOffset = entryOffset + 8
                )
                if (entries.put(tag, entry) != null) {
                    throw TiffFormatException("Duplicate TIFF tag $tag")
                }
            }
            entryOffset += IFD_ENTRY_SIZE.toInt()
        }
        return entries
    }

    private fun scalar(
        entries: Map<Int, TiffEntry>,
        tag: Int,
        name: String,
        default: Long? = null,
        allowLong: Boolean = false
    ): Long {
        val entry = entries[tag]
        if (entry == null) {
            return default ?: throw TiffFormatException("Required TIFF tag $name is missing")
        }
        return unsignedValues(entry, name, expectedCount = 1, allowLong = allowLong)[0]
    }

    private fun requiredEntry(
        entries: Map<Int, TiffEntry>,
        tag: Int,
        name: String
    ): TiffEntry = entries[tag]
        ?: throw TiffFormatException("Required TIFF tag $name is missing")

    private fun unsignedValues(
        entry: TiffEntry,
        name: String,
        expectedCount: Int,
        allowLong: Boolean
    ): LongArray {
        if (entry.count != expectedCount.toLong()) {
            throw TiffFormatException(
                "$name has ${entry.count} values, expected $expectedCount"
            )
        }
        if (entry.type != TYPE_SHORT && !(allowLong && entry.type == TYPE_LONG)) {
            val expectedType = if (allowLong) "SHORT or LONG" else "SHORT"
            throw TiffFormatException("$name must use TIFF type $expectedType")
        }

        val elementSize = if (entry.type == TYPE_SHORT) 2L else 4L
        val byteCount = entry.count * elementSize
        val valueOffset = if (byteCount <= 4L) {
            entry.valueFieldOffset.toLong()
        } else {
            reader.unsignedInt(entry.valueFieldOffset)
        }
        reader.requireRange(valueOffset, byteCount, "$name values")

        return LongArray(expectedCount) { index ->
            val offset = valueOffset.toInt() + (index * elementSize).toInt()
            if (entry.type == TYPE_SHORT) {
                reader.unsignedShort(offset).toLong()
            } else {
                reader.unsignedInt(offset)
            }
        }
    }

    private fun dimension(value: Long, name: String): Int {
        if (value <= 0L || value > MAX_DIMENSION) {
            throw TiffFormatException(
                "$name $value is outside the supported range 1..$MAX_DIMENSION"
            )
        }
        return value.toInt()
    }

    private fun decodeStrip(page: PageInfo, stripIndex: Int, expectedSize: Int): ByteArray {
        val offset = page.stripOffsets[stripIndex].toInt()
        val byteCount = page.stripByteCounts[stripIndex].toInt()
        return when (page.compression) {
            COMPRESSION_NONE -> {
                if (byteCount != expectedSize) {
                    throw TiffFormatException(
                        "Uncompressed TIFF strip $stripIndex has $byteCount bytes, " +
                            "expected $expectedSize"
                    )
                }
                bytes.copyOfRange(offset, offset + byteCount)
            }

            COMPRESSION_PACK_BITS -> decodePackBits(offset, byteCount, expectedSize, stripIndex)
            COMPRESSION_LZW -> decodeLzw(offset, byteCount, expectedSize, stripIndex)
            COMPRESSION_DEFLATE,
            COMPRESSION_ADOBE_DEFLATE -> decodeDeflate(
                offset,
                byteCount,
                expectedSize,
                stripIndex
            )

            else -> throw UnsupportedTiffFeatureException(
                "Unsupported TIFF compression ${page.compression}"
            )
        }
    }

    private fun decodePackBits(
        offset: Int,
        byteCount: Int,
        expectedSize: Int,
        stripIndex: Int
    ): ByteArray {
        val output = ByteArray(expectedSize)
        val end = offset + byteCount
        var source = offset
        var destination = 0

        while (source < end) {
            when (val control = bytes[source++].toInt()) {
                in 0..127 -> {
                    val literalCount = control + 1
                    if (source + literalCount > end) {
                        throw TiffFormatException(
                            "PackBits strip $stripIndex ends inside a literal run"
                        )
                    }
                    if (destination + literalCount > output.size) {
                        throw TiffFormatException(
                            "PackBits strip $stripIndex expands beyond $expectedSize bytes"
                        )
                    }
                    bytes.copyInto(
                        output,
                        destinationOffset = destination,
                        startIndex = source,
                        endIndex = source + literalCount
                    )
                    source += literalCount
                    destination += literalCount
                }

                in -127..-1 -> {
                    val repeatCount = 1 - control
                    if (source >= end) {
                        throw TiffFormatException(
                            "PackBits strip $stripIndex ends before a repeated byte"
                        )
                    }
                    if (destination + repeatCount > output.size) {
                        throw TiffFormatException(
                            "PackBits strip $stripIndex expands beyond $expectedSize bytes"
                        )
                    }
                    val value = bytes[source++]
                    output.fill(value, destination, destination + repeatCount)
                    destination += repeatCount
                }

                -128 -> Unit
            }
        }

        if (destination != expectedSize) {
            throw TiffFormatException(
                "PackBits strip $stripIndex expands to $destination bytes, expected $expectedSize"
            )
        }
        return output
    }

    private fun decodeLzw(
        offset: Int,
        byteCount: Int,
        expectedSize: Int,
        stripIndex: Int
    ): ByteArray {
        val bitReader = MsbBitReader(bytes, offset, byteCount)
        val output = ByteArray(expectedSize)
        val prefixes = IntArray(LZW_TABLE_SIZE)
        val suffixes = ByteArray(LZW_TABLE_SIZE)
        val stack = ByteArray(LZW_TABLE_SIZE)
        for (value in 0..255) {
            prefixes[value] = -1
            suffixes[value] = value.toByte()
        }

        var codeSize = 9
        var nextCode = LZW_FIRST_AVAILABLE_CODE
        var oldCode = -1
        var outputPosition = 0
        var sawEndCode = false

        fun emit(value: Int) {
            if (outputPosition >= output.size) {
                throw TiffFormatException(
                    "LZW strip $stripIndex expands beyond $expectedSize bytes"
                )
            }
            output[outputPosition++] = value.toByte()
        }

        fun emitCode(codeValue: Int): Int {
            var code = codeValue
            var stackSize = 0
            var links = 0
            while (code >= 256) {
                if (code >= nextCode || code >= LZW_TABLE_SIZE || links++ >= LZW_TABLE_SIZE) {
                    throw TiffFormatException(
                        "LZW strip $stripIndex contains an invalid dictionary code $codeValue"
                    )
                }
                stack[stackSize++] = suffixes[code]
                code = prefixes[code]
            }
            if (code !in 0..255) {
                throw TiffFormatException(
                    "LZW strip $stripIndex contains an invalid dictionary chain"
                )
            }
            val firstByte = code
            stack[stackSize++] = code.toByte()
            for (index in stackSize - 1 downTo 0) {
                emit(stack[index].toInt() and 0xFF)
            }
            return firstByte
        }

        val initialCode = bitReader.read(codeSize)
            ?: throw TiffFormatException("LZW strip $stripIndex is empty")
        if (initialCode != LZW_CLEAR_CODE) {
            throw TiffFormatException(
                "LZW strip $stripIndex does not begin with a clear code"
            )
        }

        while (true) {
            val code = bitReader.read(codeSize) ?: break
            when (code) {
                LZW_CLEAR_CODE -> {
                    codeSize = 9
                    nextCode = LZW_FIRST_AVAILABLE_CODE
                    oldCode = -1
                }

                LZW_END_OF_INFORMATION -> {
                    sawEndCode = true
                    break
                }

                else -> {
                    if (oldCode == -1) {
                        if (code !in 0..255) {
                            throw TiffFormatException(
                                "LZW strip $stripIndex has invalid first data code $code"
                            )
                        }
                        emit(code)
                        oldCode = code
                        continue
                    }

                    val firstByte = when {
                        code < nextCode -> emitCode(code)
                        code == nextCode -> {
                            val first = emitCode(oldCode)
                            emit(first)
                            first
                        }

                        else -> throw TiffFormatException(
                            "LZW strip $stripIndex references undefined code $code"
                        )
                    }

                    if (nextCode < LZW_TABLE_SIZE) {
                        prefixes[nextCode] = oldCode
                        suffixes[nextCode] = firstByte.toByte()
                        nextCode++
                        if (
                            codeSize < LZW_MAX_CODE_SIZE &&
                            nextCode == (1 shl codeSize) - 1
                        ) {
                            codeSize++
                        }
                    }
                    oldCode = code
                }
            }
        }

        if (!sawEndCode) {
            throw TiffFormatException("LZW strip $stripIndex is missing an end code")
        }
        if (outputPosition != expectedSize) {
            throw TiffFormatException(
                "LZW strip $stripIndex expands to $outputPosition bytes, expected $expectedSize"
            )
        }
        return output
    }

    private fun decodeDeflate(
        offset: Int,
        byteCount: Int,
        expectedSize: Int,
        stripIndex: Int
    ): ByteArray {
        val output = ByteArray(expectedSize)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, offset, byteCount)
            var outputPosition = 0
            while (outputPosition < output.size && !inflater.finished()) {
                val inflated = inflater.inflate(
                    output,
                    outputPosition,
                    output.size - outputPosition
                )
                if (inflated > 0) {
                    outputPosition += inflated
                } else if (inflater.needsDictionary()) {
                    throw TiffFormatException(
                        "Deflate strip $stripIndex requires an unsupported preset dictionary"
                    )
                } else if (inflater.needsInput()) {
                    break
                } else {
                    throw TiffFormatException("Deflate strip $stripIndex made no progress")
                }
            }

            if (outputPosition != expectedSize) {
                throw TiffFormatException(
                    "Deflate strip $stripIndex expands to $outputPosition bytes, " +
                        "expected $expectedSize"
                )
            }

            val extraByte = ByteArray(1)
            while (!inflater.finished()) {
                val inflated = inflater.inflate(extraByte)
                if (inflated > 0) {
                    throw TiffFormatException(
                        "Deflate strip $stripIndex expands beyond $expectedSize bytes"
                    )
                }
                if (inflater.needsDictionary()) {
                    throw TiffFormatException(
                        "Deflate strip $stripIndex requires an unsupported preset dictionary"
                    )
                }
                if (inflater.needsInput()) {
                    throw TiffFormatException("Deflate strip $stripIndex is truncated")
                }
                if (!inflater.finished()) {
                    throw TiffFormatException("Deflate strip $stripIndex made no progress")
                }
            }
        } catch (exception: DataFormatException) {
            throw TiffFormatException(
                "Invalid Deflate data in TIFF strip $stripIndex",
                exception
            )
        } finally {
            inflater.end()
        }
        return output
    }

    private fun reverseHorizontalPredictor(
        decoded: ByteArray,
        rowBytes: Int,
        rows: Int,
        samplesPerPixel: Int
    ) {
        repeat(rows) { row ->
            val rowStart = row * rowBytes
            val rowEnd = rowStart + rowBytes
            for (index in rowStart + samplesPerPixel until rowEnd) {
                decoded[index] = (
                    (decoded[index].toInt() and 0xFF) +
                        (decoded[index - samplesPerPixel].toInt() and 0xFF)
                    ).toByte()
            }
        }
    }

    private fun writePixels(
        page: PageInfo,
        decoded: ByteArray,
        firstRow: Int,
        rows: Int,
        destination: IntArray
    ) {
        repeat(rows) { stripRow ->
            val sourceRow = stripRow * page.rowBytes
            val destinationRow = (firstRow + stripRow) * page.width
            when (page.pixelFormat) {
                PixelFormat.BILEVEL -> {
                    for (x in 0 until page.width) {
                        val sample = (
                            decoded[sourceRow + x / 8].toInt() ushr (7 - x % 8)
                            ) and 1
                        val gray = if (page.photometric == PHOTOMETRIC_WHITE_IS_ZERO) {
                            if (sample == 0) 255 else 0
                        } else {
                            if (sample == 0) 0 else 255
                        }
                        destination[destinationRow + x] = opaqueGray(gray)
                    }
                }

                PixelFormat.GRAYSCALE -> {
                    for (x in 0 until page.width) {
                        val sample = decoded[sourceRow + x].toInt() and 0xFF
                        val gray = if (page.photometric == PHOTOMETRIC_WHITE_IS_ZERO) {
                            255 - sample
                        } else {
                            sample
                        }
                        destination[destinationRow + x] = opaqueGray(gray)
                    }
                }

                PixelFormat.RGB -> {
                    for (x in 0 until page.width) {
                        val source = sourceRow + x * 3
                        destination[destinationRow + x] = argb(
                            alpha = 255,
                            red = decoded[source].toInt() and 0xFF,
                            green = decoded[source + 1].toInt() and 0xFF,
                            blue = decoded[source + 2].toInt() and 0xFF
                        )
                    }
                }

                PixelFormat.RGBA -> {
                    for (x in 0 until page.width) {
                        val source = sourceRow + x * 4
                        val alpha = decoded[source + 3].toInt() and 0xFF
                        var red = decoded[source].toInt() and 0xFF
                        var green = decoded[source + 1].toInt() and 0xFF
                        var blue = decoded[source + 2].toInt() and 0xFF
                        if (page.associatedAlpha) {
                            red = unassociate(red, alpha)
                            green = unassociate(green, alpha)
                            blue = unassociate(blue, alpha)
                        }
                        destination[destinationRow + x] = argb(alpha, red, green, blue)
                    }
                }
            }
        }
    }

    private fun opaqueGray(gray: Int): Int =
        argb(alpha = 255, red = gray, green = gray, blue = gray)

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    private fun unassociate(channel: Int, alpha: Int): Int = when (alpha) {
        0 -> 0
        255 -> channel
        else -> ((channel * 255 + alpha / 2) / alpha).coerceAtMost(255)
    }

    private fun isRelevantTag(tag: Int): Boolean = when (tag) {
        TAG_IMAGE_WIDTH,
        TAG_IMAGE_LENGTH,
        TAG_BITS_PER_SAMPLE,
        TAG_COMPRESSION,
        TAG_PHOTOMETRIC_INTERPRETATION,
        TAG_FILL_ORDER,
        TAG_STRIP_OFFSETS,
        TAG_ORIENTATION,
        TAG_SAMPLES_PER_PIXEL,
        TAG_ROWS_PER_STRIP,
        TAG_STRIP_BYTE_COUNTS,
        TAG_PLANAR_CONFIGURATION,
        TAG_PREDICTOR,
        TAG_TILE_WIDTH,
        TAG_TILE_LENGTH,
        TAG_TILE_OFFSETS,
        TAG_TILE_BYTE_COUNTS,
        TAG_EXTRA_SAMPLES,
        TAG_SAMPLE_FORMAT -> true

        else -> false
    }

    private data class TiffEntry(
        val type: Int,
        val count: Long,
        val valueFieldOffset: Int
    )

    private data class PageInfo(
        val width: Int,
        val height: Int,
        val rowBytes: Int,
        val rowsPerStrip: Long,
        val samplesPerPixel: Int,
        val compression: Int,
        val predictor: Int,
        val photometric: Int,
        val pixelFormat: PixelFormat,
        val associatedAlpha: Boolean,
        val stripOffsets: LongArray,
        val stripByteCounts: LongArray
    )

    private enum class PixelFormat {
        BILEVEL,
        GRAYSCALE,
        RGB,
        RGBA
    }

    private class TiffReader(
        private val data: ByteArray,
        private val littleEndian: Boolean
    ) {
        fun unsignedShort(offset: Int): Int {
            requireRange(offset.toLong(), 2, "TIFF SHORT")
            val first = data[offset].toInt() and 0xFF
            val second = data[offset + 1].toInt() and 0xFF
            return if (littleEndian) {
                first or (second shl 8)
            } else {
                (first shl 8) or second
            }
        }

        fun unsignedInt(offset: Int): Long {
            requireRange(offset.toLong(), 4, "TIFF LONG")
            val first = data[offset].toLong() and 0xFFL
            val second = data[offset + 1].toLong() and 0xFFL
            val third = data[offset + 2].toLong() and 0xFFL
            val fourth = data[offset + 3].toLong() and 0xFFL
            return if (littleEndian) {
                first or (second shl 8) or (third shl 16) or (fourth shl 24)
            } else {
                (first shl 24) or (second shl 16) or (third shl 8) or fourth
            }
        }

        fun requireRange(offset: Long, length: Long, description: String) {
            if (offset < 0L || length < 0L || offset > data.size.toLong() - length) {
                throw TiffFormatException(
                    "$description points outside TIFF data " +
                        "(offset=$offset, length=$length, size=${data.size})"
                )
            }
        }
    }

    private class MsbBitReader(
        private val data: ByteArray,
        offset: Int,
        byteCount: Int
    ) {
        private var position = offset
        private val end = offset + byteCount
        private var bitBuffer = 0L
        private var bitCount = 0

        fun read(count: Int): Int? {
            while (bitCount < count) {
                if (position >= end) return null
                bitBuffer = (bitBuffer shl 8) or (data[position++].toLong() and 0xFFL)
                bitCount += 8
            }
            val remaining = bitCount - count
            val result = ((bitBuffer ushr remaining) and ((1L shl count) - 1L)).toInt()
            bitCount = remaining
            bitBuffer = if (remaining == 0) {
                0L
            } else {
                bitBuffer and ((1L shl remaining) - 1L)
            }
            return result
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 64 * 1024 * 1024
        const val MAX_PAGE_COUNT = 4_096
        const val MAX_DIMENSION = 100_000L
        const val MAX_PAGE_PIXELS = 10_000_000L
        const val MAX_STRIP_COUNT = 100_000L
        const val MAX_DECODED_STRIP_BYTES = 100_000_000L

        const val CLASSIC_TIFF_MAGIC = 42
        const val BIG_TIFF_MAGIC = 43
        const val CLASSIC_HEADER_SIZE = 8
        const val IFD_ENTRY_SIZE = 12L
        const val UINT32_MAX = 0xFFFF_FFFFL

        const val TYPE_SHORT = 3
        const val TYPE_LONG = 4

        const val TAG_IMAGE_WIDTH = 256
        const val TAG_IMAGE_LENGTH = 257
        const val TAG_BITS_PER_SAMPLE = 258
        const val TAG_COMPRESSION = 259
        const val TAG_PHOTOMETRIC_INTERPRETATION = 262
        const val TAG_FILL_ORDER = 266
        const val TAG_STRIP_OFFSETS = 273
        const val TAG_ORIENTATION = 274
        const val TAG_SAMPLES_PER_PIXEL = 277
        const val TAG_ROWS_PER_STRIP = 278
        const val TAG_STRIP_BYTE_COUNTS = 279
        const val TAG_PLANAR_CONFIGURATION = 284
        const val TAG_PREDICTOR = 317
        const val TAG_TILE_WIDTH = 322
        const val TAG_TILE_LENGTH = 323
        const val TAG_TILE_OFFSETS = 324
        const val TAG_TILE_BYTE_COUNTS = 325
        const val TAG_EXTRA_SAMPLES = 338
        const val TAG_SAMPLE_FORMAT = 339

        const val COMPRESSION_NONE = 1
        const val COMPRESSION_LZW = 5
        const val COMPRESSION_DEFLATE = 8
        const val COMPRESSION_ADOBE_DEFLATE = 32946
        const val COMPRESSION_PACK_BITS = 32773

        const val PHOTOMETRIC_WHITE_IS_ZERO = 0
        const val PHOTOMETRIC_BLACK_IS_ZERO = 1
        const val PHOTOMETRIC_RGB = 2

        const val FILL_ORDER_MOST_SIGNIFICANT_FIRST = 1
        const val ORIENTATION_TOP_LEFT = 1
        const val PLANAR_CHUNKY = 1
        const val PREDICTOR_NONE = 1
        const val PREDICTOR_HORIZONTAL = 2
        const val SAMPLE_FORMAT_UNSIGNED = 1
        const val EXTRA_SAMPLE_UNSPECIFIED = 0
        const val EXTRA_SAMPLE_ASSOCIATED_ALPHA = 1
        const val EXTRA_SAMPLE_UNASSOCIATED_ALPHA = 2

        const val LZW_CLEAR_CODE = 256
        const val LZW_END_OF_INFORMATION = 257
        const val LZW_FIRST_AVAILABLE_CODE = 258
        const val LZW_TABLE_SIZE = 4_096
        const val LZW_MAX_CODE_SIZE = 12
    }
}
