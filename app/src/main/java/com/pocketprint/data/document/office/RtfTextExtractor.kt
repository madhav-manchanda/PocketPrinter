package com.pocketprint.data.document.office

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.util.ArrayDeque

internal object RtfTextExtractor {

    fun extract(input: InputStream, fallbackTitle: String): ExtractedOfficeDocument {
        val bytes = input.readBounded(OfficeExtractionLimits.MAX_RTF_BYTES, "RTF document")
        val parsed = RtfParser(bytes).parse()
        return ExtractedOfficeDocument(
            title = parsed.title.ifBlank { fallbackTitle },
            pages = parsed.pages,
            fidelityNotes = listOf(
                "Text-only RTF extraction; formatting, images, and complex fields are omitted.",
                "Explicit RTF page controls are returned as page boundaries."
            )
        )
    }
}

private data class ParsedRtf(
    val title: String,
    val pages: List<String>
)

private class RtfParser(private val bytes: ByteArray) {
    private enum class Destination {
        BODY,
        TITLE,
        METADATA,
        SKIP
    }

    private data class State(
        var destination: Destination = Destination.BODY,
        var unicodeFallbackLength: Int = 1,
        var fallbackBytesRemaining: Int = 0,
        var charset: Charset = WINDOWS_1252
    )

    private val states = ArrayDeque<State>()
    private val pages = RtfPages()
    private val title = StringBuilder()
    private val ansiBytes = ByteArrayOutputStream()
    private var state = State()
    private var groupDepth = 0

    fun parse(): ParsedRtf {
        var index = findRtfStart()
        var rootClosed = false

        while (index < bytes.size) {
            val value = bytes[index].toInt() and 0xff
            if (rootClosed) {
                if (!value.isRtfWhitespace() && value != 0) {
                    throw OfficeDocumentExtractionException("Unexpected data after the RTF root group.")
                }
                index++
                continue
            }

            when (value) {
                '{'.code -> {
                    flushAnsiBytes()
                    if (groupDepth >= OfficeExtractionLimits.MAX_XML_DEPTH) {
                        throw OfficeDocumentExtractionException("RTF group nesting limit exceeded.")
                    }
                    states.addLast(state)
                    state = state.copy()
                    groupDepth++
                    index++
                }

                '}'.code -> {
                    flushAnsiBytes()
                    if (groupDepth == 0 || states.isEmpty()) {
                        throw OfficeDocumentExtractionException("Unbalanced closing group in RTF document.")
                    }
                    state = states.removeLast()
                    groupDepth--
                    index++
                    if (groupDepth == 0) rootClosed = true
                }

                '\\'.code -> index = readControl(index)
                '\r'.code, '\n'.code -> index++
                else -> {
                    appendAnsiByte(value)
                    index++
                }
            }
        }

        flushAnsiBytes()
        if (!rootClosed || groupDepth != 0) {
            throw OfficeDocumentExtractionException("Unterminated group in RTF document.")
        }

        val normalizedTitle = title.toString().trim().replace(WHITESPACE, " ")
        return ParsedRtf(normalizedTitle, pages.finish())
    }

    private fun findRtfStart(): Int {
        var index = if (
            bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) {
            3
        } else {
            0
        }
        while (index < bytes.size && (bytes[index].toInt() and 0xff).isRtfWhitespace()) index++

        val signature = "{\\rtf".toByteArray(Charsets.US_ASCII)
        if (index + signature.size > bytes.size ||
            !signature.indices.all { bytes[index + it] == signature[it] }
        ) {
            throw OfficeDocumentExtractionException("Input is not an RTF document.")
        }
        return index
    }

    private fun readControl(slashIndex: Int): Int {
        if (slashIndex + 1 >= bytes.size) {
            throw OfficeDocumentExtractionException("Truncated RTF control sequence.")
        }

        val marker = bytes[slashIndex + 1].toInt() and 0xff
        if (marker == '\''.code) {
            if (slashIndex + 3 >= bytes.size) {
                throw OfficeDocumentExtractionException("Truncated RTF hexadecimal escape.")
            }
            val high = (bytes[slashIndex + 2].toInt() and 0xff).hexValue()
            val low = (bytes[slashIndex + 3].toInt() and 0xff).hexValue()
            if (high < 0 || low < 0) {
                throw OfficeDocumentExtractionException("Invalid RTF hexadecimal escape.")
            }
            appendAnsiByte((high shl 4) or low)
            return slashIndex + 4
        }

        if (marker == '\\'.code || marker == '{'.code || marker == '}'.code) {
            appendAnsiByte(marker)
            return slashIndex + 2
        }

        if (!marker.isAsciiLetter()) {
            flushAnsiBytes()
            applyControlSymbol(marker)
            return slashIndex + 2
        }

        flushAnsiBytes()
        var cursor = slashIndex + 1
        while (cursor < bytes.size && (bytes[cursor].toInt() and 0xff).isAsciiLetter()) cursor++
        val word = String(bytes, slashIndex + 1, cursor - slashIndex - 1, Charsets.US_ASCII)
            .lowercase()

        val numberStart = cursor
        if (cursor + 1 < bytes.size &&
            bytes[cursor].toInt().toChar() == '-' &&
            (bytes[cursor + 1].toInt() and 0xff).isAsciiDigit()
        ) {
            cursor++
        }
        while (cursor < bytes.size && (bytes[cursor].toInt() and 0xff).isAsciiDigit()) cursor++

        val numberText = if (cursor > numberStart) {
            String(bytes, numberStart, cursor - numberStart, Charsets.US_ASCII)
        } else {
            null
        }
        val parameter = numberText?.toIntOrNull()
        if (numberText != null && parameter == null) {
            throw OfficeDocumentExtractionException("RTF control parameter is outside the supported range.")
        }
        if (cursor < bytes.size && bytes[cursor].toInt().toChar() == ' ') cursor++

        if (word == "bin") {
            val byteCount = parameter
                ?: throw OfficeDocumentExtractionException("RTF binary control is missing its length.")
            if (byteCount < 0 || byteCount > bytes.size - cursor) {
                throw OfficeDocumentExtractionException("Invalid RTF binary data length.")
            }
            return cursor + byteCount
        }

        applyControlWord(word, parameter)
        return cursor
    }

    private fun applyControlSymbol(symbol: Int) {
        when (symbol) {
            '*'.code -> state.destination = Destination.SKIP
            '~'.code -> appendFallbackCharacter("\u00a0")
            '_'.code -> appendFallbackCharacter("\u2011")
            '-'.code -> appendFallbackCharacter("\u00ad")
        }
    }

    private fun applyControlWord(word: String, parameter: Int?) {
        when (word) {
            "fonttbl", "colortbl", "stylesheet", "listtable", "listoverridetable",
            "rsidtbl", "xmlnstbl", "pict", "object", "objdata", "shp", "shpinst",
            "nonshppict", "datastore", "themedata", "colorschememapping", "latentstyles",
            "docvar", "private", "annotation", "footnote", "endnote", "header",
            "headerl", "headerr", "headerf", "footer", "footerl", "footerr", "footerf",
            "fldinst", "filetbl", "revtbl", "generator" -> {
                state.destination = Destination.SKIP
                return
            }

            "info" -> {
                if (state.destination != Destination.SKIP) state.destination = Destination.METADATA
                return
            }

            "title" -> {
                if (state.destination != Destination.SKIP) state.destination = Destination.TITLE
                return
            }

            "author", "operator", "company", "subject", "keywords", "comment", "doccomm",
            "hlinkbase", "category", "manager", "creatim", "revtim", "printim", "buptim" -> {
                if (state.destination != Destination.SKIP) state.destination = Destination.METADATA
                return
            }
        }

        when (word) {
            "ansi" -> state.charset = WINDOWS_1252
            "mac" -> state.charset = charsetOrDefault("x-MacRoman")
            "pc" -> state.charset = charsetOrDefault("IBM437")
            "pca" -> state.charset = charsetOrDefault("IBM850")
            "ansicpg" -> parameter?.let { state.charset = charsetForCodePage(it) }
            "uc" -> {
                val length = parameter ?: 1
                if (length !in 0..1_024) {
                    throw OfficeDocumentExtractionException("Unsupported RTF Unicode fallback length: $length")
                }
                state.unicodeFallbackLength = length
            }

            "u" -> {
                val value = parameter
                    ?: throw OfficeDocumentExtractionException("RTF Unicode control is missing its value.")
                appendText((value and 0xffff).toChar().toString())
                state.fallbackBytesRemaining = state.unicodeFallbackLength
            }

            "par", "line", "softline", "row" -> appendText("\n")
            "tab", "cell" -> appendText("\t")
            "page" -> {
                when (state.destination) {
                    Destination.BODY -> pages.breakPage()
                    Destination.TITLE -> appendText("\n")
                    else -> Unit
                }
            }

            "sect" -> appendText("\n")
            "emdash" -> appendText("\u2014")
            "endash" -> appendText("\u2013")
            "bullet" -> appendText("\u2022")
            "lquote" -> appendText("\u2018")
            "rquote" -> appendText("\u2019")
            "ldblquote" -> appendText("\u201c")
            "rdblquote" -> appendText("\u201d")
            "enspace" -> appendText("\u2002")
            "emspace" -> appendText("\u2003")
            "qmspace" -> appendText("\u2005")
            "ltrmark" -> appendText("\u200e")
            "rtlmark" -> appendText("\u200f")
        }
    }

    private fun appendAnsiByte(value: Int) {
        if (state.fallbackBytesRemaining > 0) {
            state.fallbackBytesRemaining--
            return
        }
        if (state.destination == Destination.BODY || state.destination == Destination.TITLE) {
            ansiBytes.write(value)
        }
    }

    private fun appendFallbackCharacter(value: String) {
        if (state.fallbackBytesRemaining > 0) {
            state.fallbackBytesRemaining--
        } else {
            appendText(value)
        }
    }

    private fun flushAnsiBytes() {
        if (ansiBytes.size() == 0) return
        val decoded = String(ansiBytes.toByteArray(), state.charset)
        ansiBytes.reset()
        appendText(decoded)
    }

    private fun appendText(value: String) {
        when (state.destination) {
            Destination.BODY -> pages.append(value)
            Destination.TITLE -> {
                if (title.length + value.length > OfficeExtractionLimits.MAX_TITLE_CHARS) {
                    throw OfficeDocumentExtractionException("RTF title exceeds the supported size.")
                }
                title.append(value)
            }

            Destination.METADATA, Destination.SKIP -> Unit
        }
    }

    private fun charsetForCodePage(codePage: Int): Charset = when (codePage) {
        437 -> charsetOrDefault("IBM437")
        850 -> charsetOrDefault("IBM850")
        10_000 -> charsetOrDefault("x-MacRoman")
        65_001 -> Charsets.UTF_8
        else -> charsetOrDefault("windows-$codePage")
    }

    private fun charsetOrDefault(name: String): Charset =
        runCatching { Charset.forName(name) }.getOrDefault(WINDOWS_1252)

    companion object {
        private val WINDOWS_1252: Charset = Charset.forName("windows-1252")
        private val WHITESPACE = Regex("\\s+")
    }
}

private class RtfPages {
    private val completed = mutableListOf<String>()
    private var current = StringBuilder()
    private var outputCharacters = 0

    fun append(value: String) {
        if (outputCharacters + value.length > OfficeExtractionLimits.MAX_OUTPUT_CHARS) {
            throw OfficeDocumentExtractionException("Extracted RTF text exceeds the supported size.")
        }
        current.append(value)
        outputCharacters += value.length
    }

    fun breakPage() {
        completed += current.toString().trimEnd('\r', '\n')
        current = StringBuilder()
    }

    fun finish(): List<String> {
        completed += current.toString().trimEnd('\r', '\n')
        return completed.toList()
    }
}

private fun InputStream.readBounded(maxBytes: Int, description: String): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count == 0) continue
        if (total > maxBytes - count) {
            throw OfficeDocumentExtractionException("$description exceeds the $maxBytes-byte limit.")
        }
        output.write(buffer, 0, count)
        total += count
    }
    return output.toByteArray()
}

private fun Int.isAsciiLetter(): Boolean = this in 'a'.code..'z'.code || this in 'A'.code..'Z'.code

private fun Int.isAsciiDigit(): Boolean = this in '0'.code..'9'.code

private fun Int.isRtfWhitespace(): Boolean = this == ' '.code || this == '\t'.code ||
    this == '\r'.code || this == '\n'.code

private fun Int.hexValue(): Int = when (this) {
    in '0'.code..'9'.code -> this - '0'.code
    in 'a'.code..'f'.code -> this - 'a'.code + 10
    in 'A'.code..'F'.code -> this - 'A'.code + 10
    else -> -1
}
