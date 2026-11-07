package com.pocketprint.data.document.office

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

internal object OoxmlTextExtractor {
    private val OLE_COMPOUND_SIGNATURE = byteArrayOf(
        0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(),
        0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()
    )

    fun extract(
        input: InputStream,
        format: OfficeFormat,
        fallbackTitle: String
    ): ExtractedOfficeDocument {
        val entries = readPackage(input)
        val title = entries[CORE_PROPERTIES_PATH]
            ?.let { readCoreTitle(it) }
            .orEmpty()
            .ifBlank { fallbackTitle }
        val budget = CharacterBudget()

        return when (format) {
            OfficeFormat.DOCX -> ExtractedOfficeDocument(
                title = title,
                pages = extractDocx(entries, budget),
                fidelityNotes = listOf(
                    "Text-only DOCX extraction; styling, images, headers, footers, and comments are omitted.",
                    "Only explicit page breaks are preserved; layout-driven pagination is unavailable."
                )
            )

            OfficeFormat.XLSX -> ExtractedOfficeDocument(
                title = title,
                pages = extractXlsx(entries, budget),
                fidelityNotes = listOf(
                    "Each worksheet is returned as one tab-separated page in workbook order.",
                    "Formatting, charts, merged-cell layout, and formula calculation are omitted; cached formula values are used."
                )
            )

            OfficeFormat.PPTX -> ExtractedOfficeDocument(
                title = title,
                pages = extractPptx(entries, budget),
                fidelityNotes = listOf(
                    "Each slide is returned as one page in numeric slide-file order.",
                    "Visual layout, images, speaker notes, transitions, and animations are omitted."
                )
            )

            OfficeFormat.RTF -> error("RTF is not an OOXML format")
        }
    }

    private fun readPackage(input: InputStream): Map<String, ByteArray> {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input)
        buffered.mark(16)
        val header = ByteArray(8)
        var headerSize = 0
        while (headerSize < header.size) {
            val count = buffered.read(header, headerSize, header.size - headerSize)
            if (count < 0) break
            if (count == 0) continue
            headerSize += count
        }
        buffered.reset()

        if (headerSize >= OLE_COMPOUND_SIGNATURE.size &&
            OLE_COMPOUND_SIGNATURE.indices.all { header[it] == OLE_COMPOUND_SIGNATURE[it] }
        ) {
            throw UnsupportedOfficeDocumentException(
                "Encrypted OOXML and legacy compound Office files are not supported."
            )
        }
        if (headerSize < 4 || !header.hasZipSignature()) {
            throw OfficeDocumentExtractionException("Input is not a valid OOXML ZIP package.")
        }

        val entries = linkedMapOf<String, ByteArray>()
        val seenNames = hashSetOf<String>()
        var entryCount = 0
        var totalExpandedBytes = 0L

        try {
            ZipInputStream(buffered).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > OfficeExtractionLimits.MAX_ZIP_ENTRIES) {
                        throw OfficeDocumentExtractionException(
                            "OOXML package exceeds the ${OfficeExtractionLimits.MAX_ZIP_ENTRIES}-entry limit."
                        )
                    }

                    val name = validateEntryName(entry.name)
                    if (!seenNames.add(name)) {
                        throw OfficeDocumentExtractionException("OOXML package contains duplicate entry '$name'.")
                    }
                    if (name.substringAfterLast('/').lowercase(Locale.ROOT) in ENCRYPTED_PART_NAMES) {
                        throw UnsupportedOfficeDocumentException(
                            "Encrypted OOXML packages are not supported."
                        )
                    }
                    if (entry.size > OfficeExtractionLimits.MAX_ZIP_ENTRY_BYTES) {
                        throw OfficeDocumentExtractionException(
                            "OOXML entry '$name' exceeds the expanded-byte limit."
                        )
                    }

                    val store = !entry.isDirectory && shouldStoreEntry(name)
                    val output = if (store) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8 * 1024)
                    var entryBytes = 0
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        entryBytes += count
                        totalExpandedBytes += count.toLong()
                        if (entryBytes > OfficeExtractionLimits.MAX_ZIP_ENTRY_BYTES) {
                            throw OfficeDocumentExtractionException(
                                "OOXML entry '$name' exceeds the expanded-byte limit."
                            )
                        }
                        if (totalExpandedBytes > OfficeExtractionLimits.MAX_ZIP_EXPANDED_BYTES) {
                            throw OfficeDocumentExtractionException(
                                "OOXML package exceeds the expanded-byte limit."
                            )
                        }
                        output?.write(buffer, 0, count)
                    }
                    zip.closeEntry()
                    if (output != null) entries[name] = output.toByteArray()
                }
            }
        } catch (exception: UnsupportedOfficeDocumentException) {
            throw exception
        } catch (exception: OfficeDocumentExtractionException) {
            throw exception
        } catch (exception: ZipException) {
            if (exception.message.orEmpty().contains("encrypt", ignoreCase = true)) {
                throw UnsupportedOfficeDocumentException(
                    "Password-protected OOXML ZIP entries are not supported.",
                    exception
                )
            }
            throw OfficeDocumentExtractionException("Invalid OOXML ZIP package.", exception)
        } catch (exception: IOException) {
            throw OfficeDocumentExtractionException("Unable to read OOXML ZIP package.", exception)
        }

        return entries
    }

    private fun extractDocx(
        entries: Map<String, ByteArray>,
        budget: CharacterBudget
    ): List<String> {
        val document = entries[WORD_DOCUMENT_PATH]
            ?: throw OfficeDocumentExtractionException(
                "Invalid DOCX package: missing '$WORD_DOCUMENT_PATH'."
            )
        val handler = DocxHandler(budget)
        parseXml(WORD_DOCUMENT_PATH, document, handler)
        return handler.pages()
    }

    private fun extractXlsx(
        entries: Map<String, ByteArray>,
        budget: CharacterBudget
    ): List<String> {
        val workbookBytes = entries[WORKBOOK_PATH]
            ?: throw OfficeDocumentExtractionException(
                "Invalid XLSX package: missing '$WORKBOOK_PATH'."
            )
        val workbookHandler = WorkbookHandler()
        parseXml(WORKBOOK_PATH, workbookBytes, workbookHandler)

        val relationships = entries[WORKBOOK_RELATIONSHIPS_PATH]?.let { bytes ->
            val handler = RelationshipsHandler(WORKBOOK_PATH)
            parseXml(WORKBOOK_RELATIONSHIPS_PATH, bytes, handler)
            handler.targets
        }.orEmpty()

        val sharedStrings = entries[SHARED_STRINGS_PATH]?.let { bytes ->
            val handler = SharedStringsHandler()
            parseXml(SHARED_STRINGS_PATH, bytes, handler)
            handler.strings
        }.orEmpty()

        return workbookHandler.sheets.map { sheet ->
            val relationshipTarget = sheet.relationshipId?.let(relationships::get)
            val fallbackTarget = sheet.sheetId?.let { "xl/worksheets/sheet$it.xml" }
            val partName = relationshipTarget ?: fallbackTarget
                ?: throw OfficeDocumentExtractionException(
                    "Worksheet '${sheet.name}' has no package relationship."
                )
            val worksheetBytes = entries[partName]
                ?: throw OfficeDocumentExtractionException(
                    "Invalid XLSX package: missing worksheet '$partName'."
                )
            val handler = WorksheetHandler(sharedStrings)
            parseXml(partName, worksheetBytes, handler)
            handler.render(budget)
        }
    }

    private fun extractPptx(
        entries: Map<String, ByteArray>,
        budget: CharacterBudget
    ): List<String> {
        val slides = entries.mapNotNull { (name, bytes) ->
            val match = SLIDE_PATH.matchEntire(name) ?: return@mapNotNull null
            val number = match.groupValues[1].toLongOrNull()
                ?: throw OfficeDocumentExtractionException("Invalid PPTX slide number in '$name'.")
            SlidePart(number, name, bytes)
        }.sortedWith(compareBy<SlidePart> { it.number }.thenBy { it.path })

        return slides.map { slide ->
            val handler = PptxSlideHandler(budget)
            parseXml(slide.path, slide.bytes, handler)
            handler.text()
        }
    }

    private fun readCoreTitle(bytes: ByteArray): String {
        val handler = CoreTitleHandler()
        parseXml(CORE_PROPERTIES_PATH, bytes, handler)
        return handler.title.toString().trim().replace(WHITESPACE, " ")
    }

    private fun ByteArray.hasZipSignature(): Boolean {
        if (size < 4 || this[0] != 'P'.code.toByte() || this[1] != 'K'.code.toByte()) return false
        val third = this[2].toInt() and 0xff
        val fourth = this[3].toInt() and 0xff
        return (third == 3 && fourth == 4) ||
            (third == 5 && fourth == 6) ||
            (third == 7 && fourth == 8)
    }

    private fun validateEntryName(name: String): String {
        if (name.isEmpty() || name.length > 512 || name.startsWith('/') || name.contains('\\')) {
            throw OfficeDocumentExtractionException("OOXML package contains an unsafe ZIP entry name.")
        }
        val path = name.removeSuffix("/")
        if (path.isEmpty() || path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw OfficeDocumentExtractionException("OOXML package contains unsafe ZIP entry '$name'.")
        }
        return name
    }

    private fun shouldStoreEntry(name: String): Boolean =
        name.endsWith(".xml") || name.endsWith(".rels") || name == "[Content_Types].xml"

    private data class SlidePart(
        val number: Long,
        val path: String,
        val bytes: ByteArray
    )

    private val ENCRYPTED_PART_NAMES = setOf("encryptioninfo", "encryptedpackage")
    private val SLIDE_PATH = Regex("ppt/slides/slide([0-9]+)\\.xml")
    private val WHITESPACE = Regex("\\s+")
    private const val CORE_PROPERTIES_PATH = "docProps/core.xml"
    private const val WORD_DOCUMENT_PATH = "word/document.xml"
    private const val WORKBOOK_PATH = "xl/workbook.xml"
    private const val WORKBOOK_RELATIONSHIPS_PATH = "xl/_rels/workbook.xml.rels"
    private const val SHARED_STRINGS_PATH = "xl/sharedStrings.xml"
}

private class CharacterBudget {
    private var used = 0

    fun claim(characters: Int) {
        if (characters < 0 || characters > OfficeExtractionLimits.MAX_OUTPUT_CHARS - used) {
            throw OfficeDocumentExtractionException("Extracted Office text exceeds the supported size.")
        }
        used += characters
    }
}

private class TextPages(private val budget: CharacterBudget) {
    private val completed = mutableListOf<String>()
    private var current = StringBuilder()

    fun append(value: String) {
        budget.claim(value.length)
        current.append(value)
    }

    fun append(value: Char) {
        budget.claim(1)
        current.append(value)
    }

    fun append(value: CharArray, start: Int, length: Int) {
        budget.claim(length)
        current.append(value, start, length)
    }

    fun trimEnd(vararg characters: Char) {
        while (current.isNotEmpty() && current[current.lastIndex] in characters) {
            current.setLength(current.length - 1)
        }
    }

    fun breakPage() {
        trimEnd('\r', '\n')
        completed += current.toString()
        current = StringBuilder()
    }

    fun finish(): List<String> {
        trimEnd('\r', '\n')
        completed += current.toString()
        return completed.toList()
    }
}

private class BoundedText(private val budget: CharacterBudget) {
    private val value = StringBuilder()

    fun append(text: String) {
        budget.claim(text.length)
        value.append(text)
    }

    fun append(character: Char) {
        budget.claim(1)
        value.append(character)
    }

    fun append(characters: CharArray, start: Int, length: Int) {
        budget.claim(length)
        value.append(characters, start, length)
    }

    fun text(): String = value.toString().trimEnd('\r', '\n')

    fun rawText(): String = value.toString()
}

private abstract class BoundedXmlHandler : DefaultHandler() {
    private var depth = 0

    final override fun startElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        depth++
        if (depth > OfficeExtractionLimits.MAX_XML_DEPTH) {
            throw SAXException("XML nesting limit exceeded.")
        }
        onStartElement(uri, elementName(localName, qName), qName, attributes)
    }

    final override fun endElement(uri: String, localName: String, qName: String) {
        onEndElement(uri, elementName(localName, qName), qName)
        depth--
    }

    final override fun characters(ch: CharArray, start: Int, length: Int) {
        onCharacters(ch, start, length)
    }

    protected open fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) = Unit

    protected open fun onEndElement(uri: String, localName: String, qName: String) = Unit

    protected open fun onCharacters(ch: CharArray, start: Int, length: Int) = Unit
}

private class CoreTitleHandler : BoundedXmlHandler() {
    val title = StringBuilder()
    private var capture = false

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (localName == "title" && (uri.isEmpty() || uri == DC_NAMESPACE)) capture = true
    }

    override fun onEndElement(uri: String, localName: String, qName: String) {
        if (localName == "title" && (uri.isEmpty() || uri == DC_NAMESPACE)) capture = false
    }

    override fun onCharacters(ch: CharArray, start: Int, length: Int) {
        if (!capture) return
        if (title.length + length > OfficeExtractionLimits.MAX_TITLE_CHARS) {
            throw SAXException("Document title exceeds the supported size.")
        }
        title.append(ch, start, length)
    }
}

private class DocxHandler(budget: CharacterBudget) : BoundedXmlHandler() {
    private val output = TextPages(budget)
    private var captureText = false
    private var pageBreakWasLastContent = false

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (!matchesNamespace(uri, WORDPROCESSING_NAMESPACE)) return
        when (localName) {
            "t" -> captureText = true
            "tab", "ptab" -> appendContent('\t')
            "cr" -> appendContent('\n')
            "br" -> {
                if (attributes.attribute("type") == "page") breakPage()
                else appendContent('\n')
            }

            "lastRenderedPageBreak" -> breakPage()
            "pageBreakBefore" -> if (attributes.attribute("val").isEnabledValue()) breakPage()
            "noBreakHyphen" -> appendContent('-')
            "softHyphen" -> appendContent('\u00ad')
        }
    }

    override fun onEndElement(uri: String, localName: String, qName: String) {
        if (!matchesNamespace(uri, WORDPROCESSING_NAMESPACE)) return
        when (localName) {
            "t" -> captureText = false
            "p" -> {
                if (!pageBreakWasLastContent) output.append('\n')
                pageBreakWasLastContent = false
            }
            "tc" -> {
                output.trimEnd('\r', '\n')
                output.append('\t')
            }

            "tr" -> {
                output.trimEnd('\r', '\n', '\t')
                output.append('\n')
            }
        }
    }

    override fun onCharacters(ch: CharArray, start: Int, length: Int) {
        if (captureText && length > 0) {
            output.append(ch, start, length)
            pageBreakWasLastContent = false
        }
    }

    fun pages(): List<String> = output.finish()

    private fun appendContent(value: Char) {
        output.append(value)
        pageBreakWasLastContent = false
    }

    private fun breakPage() {
        output.breakPage()
        pageBreakWasLastContent = true
    }
}

private data class WorkbookSheet(
    val name: String,
    val relationshipId: String?,
    val sheetId: String?
)

private class WorkbookHandler : BoundedXmlHandler() {
    val sheets = mutableListOf<WorkbookSheet>()

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (localName != "sheet" || !matchesNamespace(uri, SPREADSHEET_NAMESPACE)) return
        if (sheets.size >= OfficeExtractionLimits.MAX_ZIP_ENTRIES) {
            throw SAXException("Worksheet count exceeds the supported limit.")
        }
        sheets += WorkbookSheet(
            name = attributes.attribute("name").orEmpty(),
            relationshipId = attributes.getValue(OFFICE_RELATIONSHIPS_NAMESPACE, "id")
                ?: attributes.getValue(STRICT_OFFICE_RELATIONSHIPS_NAMESPACE, "id")
                ?: attributes.attribute("id"),
            sheetId = attributes.attribute("sheetId")
        )
    }
}

private class RelationshipsHandler(private val sourcePart: String) : BoundedXmlHandler() {
    val targets = mutableMapOf<String, String>()

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (localName != "Relationship") return
        val id = attributes.attribute("Id") ?: return
        if (attributes.attribute("TargetMode").equals("External", ignoreCase = true)) return
        val type = attributes.attribute("Type")
        if (type != null && !type.endsWith("/worksheet")) return
        val target = attributes.attribute("Target") ?: return
        val resolved = resolvePackageTarget(sourcePart, target)
            ?: throw SAXException("Unsafe relationship target '$target'.")
        targets[id] = resolved
    }
}

private class SharedStringsHandler : BoundedXmlHandler() {
    val strings = mutableListOf<String>()
    private var current: StringBuilder? = null
    private var captureText = false
    private var phoneticDepth = 0

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (!matchesNamespace(uri, SPREADSHEET_NAMESPACE)) return
        when (localName) {
            "si" -> current = StringBuilder()
            "rPh" -> if (current != null) phoneticDepth++
            "t" -> if (current != null && phoneticDepth == 0) captureText = true
        }
    }

    override fun onEndElement(uri: String, localName: String, qName: String) {
        if (!matchesNamespace(uri, SPREADSHEET_NAMESPACE)) return
        when (localName) {
            "t" -> captureText = false
            "rPh" -> if (phoneticDepth > 0) phoneticDepth--
            "si" -> {
                if (strings.size >= MAX_SHARED_STRINGS) {
                    throw SAXException("Shared-string count exceeds the supported limit.")
                }
                strings += current?.toString().orEmpty()
                current = null
                captureText = false
                phoneticDepth = 0
            }
        }
    }

    override fun onCharacters(ch: CharArray, start: Int, length: Int) {
        if (captureText) current?.append(ch, start, length)
    }

    companion object {
        private const val MAX_SHARED_STRINGS = 500_000
    }
}

private data class WorksheetCell(val column: Int, val value: String)

private data class WorksheetRow(
    val row: Int,
    val sequence: Int,
    val cells: List<WorksheetCell>
)

private class WorksheetHandler(
    private val sharedStrings: List<String>
) : BoundedXmlHandler() {
    private val rows = mutableListOf<WorksheetRow>()
    private var currentCells: MutableList<WorksheetCell>? = null
    private var currentRow = 0
    private var nextImplicitRow = 1
    private var rowSequence = 0
    private var nextImplicitColumn = 0
    private var currentColumn = 0
    private var currentCellType: String? = null
    private var value = StringBuilder()
    private var formula = StringBuilder()
    private var inlineString = StringBuilder()
    private var captureValue = false
    private var captureFormula = false
    private var captureInlineText = false
    private var insideInlineString = false
    private var phoneticDepth = 0

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (!matchesNamespace(uri, SPREADSHEET_NAMESPACE)) return
        when (localName) {
            "row" -> startRow(attributes)
            "c" -> startCell(attributes)
            "v" -> if (currentCells != null) captureValue = true
            "f" -> if (currentCells != null) captureFormula = true
            "is" -> if (currentCells != null) insideInlineString = true
            "rPh" -> if (insideInlineString) phoneticDepth++
            "t" -> if (insideInlineString && phoneticDepth == 0) captureInlineText = true
        }
    }

    override fun onEndElement(uri: String, localName: String, qName: String) {
        if (!matchesNamespace(uri, SPREADSHEET_NAMESPACE)) return
        when (localName) {
            "v" -> captureValue = false
            "f" -> captureFormula = false
            "t" -> captureInlineText = false
            "rPh" -> if (phoneticDepth > 0) phoneticDepth--
            "is" -> insideInlineString = false
            "c" -> finishCell()
            "row" -> finishRow()
        }
    }

    override fun onCharacters(ch: CharArray, start: Int, length: Int) {
        when {
            captureValue -> value.append(ch, start, length)
            captureFormula -> formula.append(ch, start, length)
            captureInlineText -> inlineString.append(ch, start, length)
        }
    }

    fun render(budget: CharacterBudget): String {
        val output = BoundedText(budget)
        val orderedRows = rows.sortedWith(compareBy<WorksheetRow> { it.row }.thenBy { it.sequence })
        orderedRows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) output.append('\n')
            val orderedCells = row.cells.sortedBy { it.column }
            val values = orderedCells.associateBy { it.column }
            val lastColumn = orderedCells.maxOfOrNull { it.column } ?: -1
            for (column in 0..lastColumn) {
                if (column > 0) output.append('\t')
                values[column]?.let { output.append(it.value) }
            }
        }
        return output.rawText()
    }

    private fun startRow(attributes: Attributes) {
        if (currentCells != null) throw SAXException("Nested worksheet rows are invalid.")
        val declaredRow = attributes.attribute("r")?.toIntOrNull()
        if (declaredRow != null && declaredRow !in 1..MAX_WORKSHEET_ROWS) {
            throw SAXException("Worksheet row index is outside the supported range.")
        }
        currentRow = declaredRow ?: nextImplicitRow
        nextImplicitRow = maxOf(nextImplicitRow, currentRow + 1)
        currentCells = mutableListOf()
        nextImplicitColumn = 0
    }

    private fun startCell(attributes: Attributes) {
        if (currentCells == null) throw SAXException("Worksheet cell is outside a row.")
        val reference = attributes.attribute("r")
        currentColumn = reference?.let(::columnFromReference) ?: nextImplicitColumn
        if (currentColumn !in 0 until MAX_WORKSHEET_COLUMNS) {
            throw SAXException("Worksheet column index is outside the supported range.")
        }
        nextImplicitColumn = maxOf(nextImplicitColumn, currentColumn + 1)
        currentCellType = attributes.attribute("t")
        value = StringBuilder()
        formula = StringBuilder()
        inlineString = StringBuilder()
        captureValue = false
        captureFormula = false
        captureInlineText = false
        insideInlineString = false
        phoneticDepth = 0
    }

    private fun finishCell() {
        val cells = currentCells ?: throw SAXException("Worksheet cell is outside a row.")
        if (cells.any { it.column == currentColumn }) {
            throw SAXException("Worksheet row contains duplicate cell columns.")
        }
        val rawValue = value.toString()
        val cellValue = when (currentCellType) {
            "s" -> {
                val index = rawValue.trim().toIntOrNull()
                    ?: throw SAXException("Invalid shared-string index '$rawValue'.")
                sharedStrings.getOrNull(index)
                    ?: throw SAXException("Shared-string index $index is out of range.")
            }

            "inlineStr" -> inlineString.toString()
            "b" -> when (rawValue.trim()) {
                "1" -> "TRUE"
                "0" -> "FALSE"
                else -> rawValue
            }

            else -> rawValue.ifEmpty {
                formula.toString().takeIf { it.isNotEmpty() }?.let { "=$it" }.orEmpty()
            }
        }
        cells += WorksheetCell(currentColumn, cellValue)
    }

    private fun finishRow() {
        val cells = currentCells ?: throw SAXException("Worksheet row end has no start.")
        rows += WorksheetRow(currentRow, rowSequence++, cells.toList())
        currentCells = null
    }

    private fun columnFromReference(reference: String): Int {
        var value = 0
        var letters = 0
        for (character in reference) {
            if (!character.isLetter()) break
            val upper = character.uppercaseChar()
            if (upper !in 'A'..'Z') throw SAXException("Invalid cell reference '$reference'.")
            value = value * 26 + (upper - 'A' + 1)
            letters++
            if (value > MAX_WORKSHEET_COLUMNS) break
        }
        if (letters == 0) throw SAXException("Invalid cell reference '$reference'.")
        return value - 1
    }

    companion object {
        private const val MAX_WORKSHEET_ROWS = 1_048_576
        private const val MAX_WORKSHEET_COLUMNS = 16_384
    }
}

private class PptxSlideHandler(budget: CharacterBudget) : BoundedXmlHandler() {
    private val output = BoundedText(budget)
    private var captureText = false

    override fun onStartElement(
        uri: String,
        localName: String,
        qName: String,
        attributes: Attributes
    ) {
        if (!matchesNamespace(uri, DRAWING_NAMESPACE)) return
        when (localName) {
            "t" -> captureText = true
            "br" -> output.append('\n')
            "tab" -> output.append('\t')
        }
    }

    override fun onEndElement(uri: String, localName: String, qName: String) {
        if (!matchesNamespace(uri, DRAWING_NAMESPACE)) return
        when (localName) {
            "t" -> captureText = false
            "p" -> output.append('\n')
        }
    }

    override fun onCharacters(ch: CharArray, start: Int, length: Int) {
        if (captureText) output.append(ch, start, length)
    }

    fun text(): String = output.text()
}

private fun parseXml(partName: String, bytes: ByteArray, handler: BoundedXmlHandler) {
    rejectDoctype(partName, bytes)
    try {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        runCatching { factory.isXIncludeAware = false }
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

        val reader = factory.newSAXParser().xmlReader
        runCatching { reader.setProperty(ACCESS_EXTERNAL_DTD_PROPERTY, "") }
        runCatching { reader.setProperty(ACCESS_EXTERNAL_SCHEMA_PROPERTY, "") }
        reader.entityResolver = EntityResolver { _, _ ->
            throw SAXException("External XML entities are disabled.")
        }
        reader.contentHandler = handler
        reader.errorHandler = handler
        ByteArrayInputStream(bytes).use { stream ->
            reader.parse(InputSource(stream))
        }
    } catch (exception: OfficeDocumentExtractionException) {
        throw exception
    } catch (exception: SAXException) {
        throw OfficeDocumentExtractionException(
            "Invalid or unsafe XML in OOXML part '$partName': ${exception.message.orEmpty()}",
            exception
        )
    } catch (exception: ParserConfigurationException) {
        throw OfficeDocumentExtractionException("Secure XML parser is unavailable.", exception)
    } catch (exception: IOException) {
        throw OfficeDocumentExtractionException("Unable to parse OOXML part '$partName'.", exception)
    }
}

private fun setFeatureIfSupported(factory: SAXParserFactory, name: String, value: Boolean) {
    runCatching { factory.setFeature(name, value) }
}

private fun rejectDoctype(partName: String, bytes: ByteArray) {
    val marker = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
    var matched = 0
    for (rawByte in bytes) {
        val value = rawByte.toInt() and 0xff
        if (value == 0) continue
        matched = when {
            value == (marker[matched].toInt() and 0xff) -> matched + 1
            value == (marker[0].toInt() and 0xff) -> 1
            else -> 0
        }
        if (matched == marker.size) {
            throw OfficeDocumentExtractionException(
                "Unsafe XML in OOXML part '$partName': DOCTYPE declarations are not allowed."
            )
        }
    }
}

private fun resolvePackageTarget(sourcePart: String, target: String): String? {
    if (target.isBlank() || target.contains('\\') || target.contains('?') || target.contains('#')) {
        return null
    }
    if (target.substringBefore('/').contains(':')) return null

    val segments = mutableListOf<String>()
    if (!target.startsWith('/')) {
        sourcePart.substringBeforeLast('/', missingDelimiterValue = "")
            .split('/')
            .filterTo(segments) { it.isNotEmpty() }
    }
    for (segment in target.removePrefix("/").split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.takeIf { it.isNotEmpty() }?.joinToString("/")
}

private fun Attributes.attribute(localName: String): String? {
    for (index in 0 until length) {
        val candidate = getLocalName(index).ifEmpty { getQName(index).substringAfter(':') }
        if (candidate == localName) return getValue(index)
    }
    return null
}

private fun elementName(localName: String, qName: String): String =
    localName.ifEmpty { qName.substringAfter(':') }

private fun String?.isEnabledValue(): Boolean =
    this == null || (this != "0" && !equals("false", ignoreCase = true) && !equals("off", ignoreCase = true))

private fun matchesNamespace(actual: String, expected: String): Boolean =
    actual.isEmpty() || actual == expected || when (expected) {
        WORDPROCESSING_NAMESPACE -> actual == STRICT_WORDPROCESSING_NAMESPACE
        SPREADSHEET_NAMESPACE -> actual == STRICT_SPREADSHEET_NAMESPACE
        DRAWING_NAMESPACE -> actual == STRICT_DRAWING_NAMESPACE
        else -> false
    }

private const val WORDPROCESSING_NAMESPACE =
    "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
private const val STRICT_WORDPROCESSING_NAMESPACE =
    "http://purl.oclc.org/ooxml/wordprocessingml/main"
private const val SPREADSHEET_NAMESPACE =
    "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
private const val STRICT_SPREADSHEET_NAMESPACE =
    "http://purl.oclc.org/ooxml/spreadsheetml/main"
private const val DRAWING_NAMESPACE =
    "http://schemas.openxmlformats.org/drawingml/2006/main"
private const val STRICT_DRAWING_NAMESPACE =
    "http://purl.oclc.org/ooxml/drawingml/main"
private const val OFFICE_RELATIONSHIPS_NAMESPACE =
    "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
private const val STRICT_OFFICE_RELATIONSHIPS_NAMESPACE =
    "http://purl.oclc.org/ooxml/officeDocument/relationships"
private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"
private const val ACCESS_EXTERNAL_DTD_PROPERTY =
    "http://javax.xml.XMLConstants/property/accessExternalDTD"
private const val ACCESS_EXTERNAL_SCHEMA_PROPERTY =
    "http://javax.xml.XMLConstants/property/accessExternalSchema"
