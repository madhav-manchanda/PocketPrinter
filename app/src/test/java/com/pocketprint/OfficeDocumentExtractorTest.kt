package com.pocketprint

import com.pocketprint.data.document.office.OfficeDocumentExtractionException
import com.pocketprint.data.document.office.OfficeDocumentExtractor
import com.pocketprint.data.document.office.UnsupportedOfficeDocumentException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeDocumentExtractorTest {

    @Test
    fun `supports RTF and OOXML names and MIME types but not legacy formats`() {
        assertTrue(OfficeDocumentExtractor.supports("note.RTF", null))
        assertTrue(OfficeDocumentExtractor.supports("document.docx", "application/octet-stream"))
        assertTrue(
            OfficeDocumentExtractor.supports(
                "upload",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        )
        assertTrue(OfficeDocumentExtractor.supports("slides.pptx", null))
        assertFalse(OfficeDocumentExtractor.supports("legacy.doc", "application/msword"))
        assertFalse(OfficeDocumentExtractor.supports("unknown.bin", null))
    }

    @Test
    fun `RTF decodes controls ANSI escapes Unicode fallback and pages`() {
        val rtf = """{\rtf1\ansi\ansicpg1252{\fonttbl{\f0 Arial;}}""" +
            """{\info{\title Sample RTF}}First\tab Second\par """ +
            """Caf\'e9 \u945? and \{brace\}\page Last page}"""
        val input = ClosingInputStream(rtf.toByteArray(Charsets.US_ASCII))

        val document = OfficeDocumentExtractor.extract(input, "fallback.rtf", "application/rtf")

        assertTrue(input.closed)
        assertEquals("Sample RTF", document.title)
        assertEquals(
            listOf("First\tSecond\nCaf\u00e9 \u03b1 and {brace}", "Last page"),
            document.pages
        )
        assertTrue(document.fidelityNotes.isNotEmpty())
    }

    @Test
    fun `DOCX preserves paragraph table order and explicit page breaks`() {
        val documentXml = """
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Before</w:t></w:r></w:p>
                <w:tbl><w:tr>
                  <w:tc><w:p><w:r><w:t>A</w:t></w:r></w:p></w:tc>
                  <w:tc><w:p><w:r><w:t>B</w:t></w:r></w:p></w:tc>
                </w:tr></w:tbl>
                <w:p><w:r><w:t>First</w:t><w:br w:type="page"/></w:r></w:p>
                <w:p><w:r><w:t>Second</w:t><w:tab/><w:t>Tail</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val coreXml = """
            <cp:coreProperties
                xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                xmlns:dc="http://purl.org/dc/elements/1.1/">
              <dc:title>Handmade DOCX</dc:title>
            </cp:coreProperties>
        """.trimIndent()
        val packageBytes = zipOf(
            "word/document.xml" to documentXml,
            "docProps/core.xml" to coreXml
        )

        val document = OfficeDocumentExtractor.extract(
            ByteArrayInputStream(packageBytes),
            "document.docx",
            null
        )

        assertEquals("Handmade DOCX", document.title)
        assertEquals(listOf("Before\nA\tB\nFirst", "Second\tTail"), document.pages)
    }

    @Test
    fun `XLSX follows workbook order and decodes shared and inline strings`() {
        val workbookXml = """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Second" sheetId="2" r:id="rId2"/>
                <sheet name="First" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()
        val relationshipsXml = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
            </Relationships>
        """.trimIndent()
        val sharedStringsXml = """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Shared</t></si>
              <si><r><t>Rich</t></r><r><t> Text</t></r></si>
            </sst>
        """.trimIndent()
        val secondSheetXml = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="2">
                <c r="C2" t="s"><v>0</v></c>
                <c r="A2" t="inlineStr"><is><t>Inline</t></is></c>
              </row></sheetData>
            </worksheet>
        """.trimIndent()
        val firstSheetXml = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData><row r="1">
                <c r="A1"><v>42</v></c><c r="B1" t="s"><v>1</v></c>
              </row></sheetData>
            </worksheet>
        """.trimIndent()
        val packageBytes = zipOf(
            "xl/worksheets/sheet1.xml" to firstSheetXml,
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/workbook.xml" to workbookXml,
            "xl/worksheets/sheet2.xml" to secondSheetXml,
            "xl/_rels/workbook.xml.rels" to relationshipsXml
        )

        val document = OfficeDocumentExtractor.extract(
            ByteArrayInputStream(packageBytes),
            "book.xlsx",
            null
        )

        assertEquals("book", document.title)
        assertEquals(listOf("Inline\t\tShared", "42\tRich Text"), document.pages)
    }

    @Test
    fun `PPTX slides are numerically ordered and text runs are concatenated`() {
        val slide1 = slideXml("<a:r><a:t>One</a:t></a:r><a:r><a:t> run</a:t></a:r>")
        val slide2 = slideXml("<a:r><a:t>Two</a:t></a:r></a:p><a:p><a:r><a:t>Next</a:t></a:r>")
        val slide10 = slideXml("<a:r><a:t>Ten</a:t></a:r>")
        val packageBytes = zipOf(
            "ppt/slides/slide10.xml" to slide10,
            "ppt/slides/slide2.xml" to slide2,
            "ppt/slides/slide1.xml" to slide1
        )

        val document = OfficeDocumentExtractor.extract(
            ByteArrayInputStream(packageBytes),
            "deck.pptx",
            null
        )

        assertEquals(listOf("One run", "Two\nNext", "Ten"), document.pages)
    }

    @Test
    fun `unsafe OOXML doctypes are rejected`() {
        val xml = """
            <!DOCTYPE w:document [<!ENTITY x "unsafe">]>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body>
            </w:document>
        """.trimIndent()

        val exception = assertThrows(OfficeDocumentExtractionException::class.java) {
            OfficeDocumentExtractor.extract(
                ByteArrayInputStream(zipOf("word/document.xml" to xml)),
                "unsafe.docx",
                null
            )
        }

        assertTrue(exception.message.orEmpty().contains("DOCTYPE"))
    }

    @Test
    fun `legacy and encrypted compound files report clear errors and close input`() {
        val legacyInput = ClosingInputStream(byteArrayOf(1, 2, 3))
        val legacy = assertThrows(UnsupportedOfficeDocumentException::class.java) {
            OfficeDocumentExtractor.extract(legacyInput, "old.doc", "application/msword")
        }
        assertTrue(legacyInput.closed)
        assertTrue(legacy.message.orEmpty().contains("Legacy"))

        val compoundHeader = byteArrayOf(
            0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(),
            0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()
        )
        val encrypted = assertThrows(UnsupportedOfficeDocumentException::class.java) {
            OfficeDocumentExtractor.extract(
                ByteArrayInputStream(compoundHeader),
                "protected.docx",
                null
            )
        }
        assertTrue(encrypted.message.orEmpty().contains("Encrypted"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, contents) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun slideXml(paragraphContents: String): String = """
        <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <p:cSld><p:spTree><p:sp><p:txBody><a:p>$paragraphContents</a:p></p:txBody></p:sp></p:spTree></p:cSld>
        </p:sld>
    """.trimIndent()

    private class ClosingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
