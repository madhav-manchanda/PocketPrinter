package com.pocketprint.data.queue

import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PageSet
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PrintQualityProfile
import java.math.BigDecimal

/** Versioned, Android-independent JSON serialization for persisted print options. */
object PrintOptionsJson {
    private const val CURRENT_VERSION = 2
    private const val MAX_JSON_LENGTH = 64 * 1024

    fun encode(options: PrintOptions): String {
        require(options.dpi > 0) { "Print DPI must be positive" }
        require(options.copies > 0) { "Copy count must be positive" }
        return buildString(320) {
            append('{')
            append("\"version\":").append(CURRENT_VERSION)
            append(",\"paperSize\":").append(quote(options.paperSize.name))
            append(",\"dpi\":").append(options.dpi)
            append(",\"printingMode\":").append(quote(options.printingMode.name))
            append(",\"hybridProcessingEnabled\":").append(options.hybridProcessingEnabled)
            append(",\"colorMode\":").append(quote(options.colorMode.name))
            append(",\"orientation\":").append(quote(options.orientation.name))
            append(",\"scaling\":").append(quote(options.scaling.name))
            append(",\"margins\":{")
            append("\"leftMm\":").append(options.margins.leftMm)
            append(",\"topMm\":").append(options.margins.topMm)
            append(",\"rightMm\":").append(options.margins.rightMm)
            append(",\"bottomMm\":").append(options.margins.bottomMm)
            append('}')
            append(",\"pageRange\":").append(quote(options.pageRange))
            append(",\"pageSet\":").append(quote(options.pageSet.name))
            append(",\"qualityProfile\":").append(quote(options.qualityProfile.name))
            append(",\"monochromeStrategy\":").append(quote(options.monochromeStrategy.name))
            append(",\"monochromeThreshold\":").append(options.monochromeThreshold)
            append(",\"copies\":").append(options.copies)
            append(",\"collate\":").append(options.collate)
            append('}')
        }
    }

    fun decode(json: String): PrintOptions {
        require(json.length <= MAX_JSON_LENGTH) { "PrintOptions JSON is too large" }
        val values = JsonParser(json).parseDocument()
        val defaults = PrintOptions()
        val marginsObject = values["margins"] as? Map<*, *>
        val margins = PrintMargins(
            leftMm = marginsObject.floatOrDefault("leftMm", defaults.margins.leftMm),
            topMm = marginsObject.floatOrDefault("topMm", defaults.margins.topMm),
            rightMm = marginsObject.floatOrDefault("rightMm", defaults.margins.rightMm),
            bottomMm = marginsObject.floatOrDefault("bottomMm", defaults.margins.bottomMm)
        )
        val colorMode = enumOrDefault(values["colorMode"], defaults.colorMode)
        val printingMode = if (values.containsKey("printingMode")) {
            enumOrDefault(values["printingMode"], PrintingMode.AUTO)
        } else {
            when (colorMode) {
                PrintColorMode.MONOCHROME_1BIT -> PrintingMode.BLACK_AND_WHITE
                PrintColorMode.GRAYSCALE_8BIT -> PrintingMode.GRAYSCALE
                PrintColorMode.COLOR_RGB -> PrintingMode.COLOR
            }
        }

        return PrintOptions(
            paperSize = enumOrDefault(values["paperSize"], defaults.paperSize),
            dpi = values["dpi"].positiveIntOrNull() ?: defaults.dpi,
            printingMode = printingMode,
            hybridProcessingEnabled = values["hybridProcessingEnabled"] as? Boolean
                ?: defaults.hybridProcessingEnabled,
            colorMode = colorMode,
            orientation = enumOrDefault(values["orientation"], defaults.orientation),
            scaling = enumOrDefault(values["scaling"], defaults.scaling),
            margins = margins,
            pageRange = values["pageRange"] as? String ?: defaults.pageRange,
            pageSet = enumOrDefault(values["pageSet"], PageSet.ALL),
            qualityProfile = enumOrDefault(
                values["qualityProfile"],
                PrintQualityProfile.NORMAL
            ),
            monochromeStrategy = enumOrDefault(
                values["monochromeStrategy"],
                MonochromeStrategy.AUTO
            ),
            monochromeThreshold = values["monochromeThreshold"].boundedIntOrNull(0, 255)
                ?: defaults.monochromeThreshold,
            copies = values["copies"].positiveIntOrNull() ?: defaults.copies,
            collate = values["collate"] as? Boolean ?: defaults.collate
        )
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: Any?, default: T): T {
        val name = value as? String ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    private fun Any?.positiveIntOrNull(): Int? {
        val number = this as? BigDecimal ?: return null
        val value = runCatching { number.intValueExact() }.getOrNull() ?: return null
        return value.takeIf { it > 0 }
    }

    private fun Any?.boundedIntOrNull(minimum: Int, maximum: Int): Int? {
        val number = this as? BigDecimal ?: return null
        val value = runCatching { number.intValueExact() }.getOrNull() ?: return null
        return value.takeIf { it in minimum..maximum }
    }

    private fun Map<*, *>?.floatOrDefault(key: String, default: Float): Float {
        val number = this?.get(key) as? BigDecimal ?: return default
        val value = number.toFloat()
        return value.takeIf { it.isFinite() && it >= 0f } ?: default
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private class JsonParser(private val source: String) {
        private var offset = 0

        fun parseDocument(): Map<String, Any?> {
            skipWhitespace()
            val result = parseObject(depth = 0)
            skipWhitespace()
            if (offset != source.length) fail("Unexpected trailing content")
            return result
        }

        private fun parseValue(depth: Int): Any? {
            if (depth > 32) fail("JSON nesting is too deep")
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> fail("Expected a JSON value")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            if (consume('}')) return emptyMap()

            val values = linkedMapOf<String, Any?>()
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected an object key")
                val key = parseString()
                if (values.containsKey(key)) fail("Duplicate object key '$key'")
                skipWhitespace()
                expect(':')
                values[key] = parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) return values
                expect(',')
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            if (consume(']')) return emptyList()

            val values = mutableListOf<Any?>()
            while (true) {
                values += parseValue(depth + 1)
                skipWhitespace()
                if (consume(']')) return values
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val value = StringBuilder()
            while (offset < source.length) {
                val character = source[offset++]
                when {
                    character == '"' -> return value.toString()
                    character == '\\' -> value.append(parseEscape())
                    character.code < 0x20 -> fail("Unescaped control character in string")
                    else -> value.append(character)
                }
            }
            fail("Unterminated string")
        }

        private fun parseEscape(): Char {
            if (offset >= source.length) fail("Unterminated escape sequence")
            return when (val escaped = source[offset++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> fail("Invalid escape sequence")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (offset + 4 > source.length) fail("Incomplete unicode escape")
            var value = 0
            repeat(4) {
                val digit = source[offset++].digitToIntOrNull(16)
                    ?: fail("Invalid unicode escape")
                value = value * 16 + digit
            }
            return value.toChar()
        }

        private fun parseNumber(): BigDecimal {
            val start = offset
            consume('-')
            when {
                consume('0') -> Unit
                peek() in '1'..'9' -> while (peek() in '0'..'9') offset++
                else -> fail("Invalid number")
            }
            if (consume('.')) {
                if (peek() !in '0'..'9') fail("Invalid decimal number")
                while (peek() in '0'..'9') offset++
            }
            if (peek() == 'e' || peek() == 'E') {
                offset++
                if (peek() == '+' || peek() == '-') offset++
                if (peek() !in '0'..'9') fail("Invalid number exponent")
                while (peek() in '0'..'9') offset++
            }
            return runCatching { BigDecimal(source.substring(start, offset)) }
                .getOrElse { fail("Invalid number") }
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            if (!source.startsWith(literal, offset)) fail("Invalid JSON literal")
            offset += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (peek() == ' ' || peek() == '\n' || peek() == '\r' || peek() == '\t') {
                offset++
            }
        }

        private fun expect(character: Char) {
            if (!consume(character)) fail("Expected '$character'")
        }

        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            offset++
            return true
        }

        private fun peek(): Char = source.getOrNull(offset) ?: '\u0000'

        private fun fail(message: String): Nothing {
            throw IllegalArgumentException("Invalid PrintOptions JSON at offset $offset: $message")
        }
    }
}
