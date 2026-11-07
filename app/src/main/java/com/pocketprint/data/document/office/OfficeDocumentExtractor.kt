package com.pocketprint.data.document.office

import java.io.IOException
import java.io.InputStream
import java.util.Locale

data class ExtractedOfficeDocument(
    val title: String,
    val pages: List<String>,
    val fidelityNotes: List<String>
)

open class OfficeDocumentExtractionException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

class UnsupportedOfficeDocumentException(
    message: String,
    cause: Throwable? = null
) : OfficeDocumentExtractionException(message, cause)

object OfficeDocumentExtractor {

    fun supports(fileName: String, mimeType: String?): Boolean =
        identifyFormat(fileName, mimeType) is FormatIdentification.Supported

    fun extract(
        input: InputStream,
        fileName: String,
        mimeType: String?
    ): ExtractedOfficeDocument = input.use { source ->
        val fallbackTitle = fileName.toDocumentTitle()
        when (val identification = identifyFormat(fileName, mimeType)) {
            is FormatIdentification.Supported -> when (identification.format) {
                OfficeFormat.RTF -> RtfTextExtractor.extract(source, fallbackTitle)
                OfficeFormat.DOCX,
                OfficeFormat.XLSX,
                OfficeFormat.PPTX -> OoxmlTextExtractor.extract(
                    source,
                    identification.format,
                    fallbackTitle
                )
            }

            is FormatIdentification.Legacy -> throw UnsupportedOfficeDocumentException(
                "Legacy binary Office format '${identification.label}' is not supported; " +
                    "convert it to DOCX, XLSX, PPTX, or RTF first."
            )

            FormatIdentification.Unknown -> throw UnsupportedOfficeDocumentException(
                "Unsupported office document format for '$fileName'" +
                    mimeType?.let { " (MIME type '$it')" }.orEmpty() + "."
            )
        }
    }

    private fun identifyFormat(fileName: String, mimeType: String?): FormatIdentification {
        val extension = fileName
            .replace('\\', '/')
            .substringAfterLast('/')
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

        when (extension) {
            "rtf" -> return FormatIdentification.Supported(OfficeFormat.RTF)
            "docx" -> return FormatIdentification.Supported(OfficeFormat.DOCX)
            "xlsx" -> return FormatIdentification.Supported(OfficeFormat.XLSX)
            "pptx" -> return FormatIdentification.Supported(OfficeFormat.PPTX)
            "doc", "dot", "xls", "xlt", "ppt", "pps" ->
                return FormatIdentification.Legacy(".$extension")
        }

        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)

        return when (normalizedMime) {
            "application/rtf", "text/rtf" ->
                FormatIdentification.Supported(OfficeFormat.RTF)

            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                FormatIdentification.Supported(OfficeFormat.DOCX)

            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                FormatIdentification.Supported(OfficeFormat.XLSX)

            "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
                FormatIdentification.Supported(OfficeFormat.PPTX)

            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint" -> FormatIdentification.Legacy(normalizedMime)

            else -> FormatIdentification.Unknown
        }
    }
}

internal enum class OfficeFormat {
    RTF,
    DOCX,
    XLSX,
    PPTX
}

private sealed interface FormatIdentification {
    data class Supported(val format: OfficeFormat) : FormatIdentification
    data class Legacy(val label: String) : FormatIdentification
    data object Unknown : FormatIdentification
}

private fun String.toDocumentTitle(): String {
    val baseName = replace('\\', '/').substringAfterLast('/')
    val withoutExtension = baseName.substringBeforeLast('.', missingDelimiterValue = baseName).trim()
    return withoutExtension.ifEmpty { "Untitled document" }
}

internal object OfficeExtractionLimits {
    const val MAX_RTF_BYTES = 16 * 1024 * 1024
    const val MAX_ZIP_ENTRIES = 4_096
    const val MAX_ZIP_ENTRY_BYTES = 16 * 1024 * 1024
    const val MAX_ZIP_EXPANDED_BYTES = 64 * 1024 * 1024
    const val MAX_OUTPUT_CHARS = 16 * 1024 * 1024
    const val MAX_XML_DEPTH = 256
    const val MAX_TITLE_CHARS = 4_096
}
