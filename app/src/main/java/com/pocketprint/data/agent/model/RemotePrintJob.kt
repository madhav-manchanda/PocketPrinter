package com.pocketprint.data.agent.model

import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.model.PrintOrientation
import com.pocketprint.data.print.model.PrintScaling
import com.pocketprint.data.print.model.PrintingMode
import com.pocketprint.data.print.quality.PrintQualityProfile
import org.json.JSONObject
import java.util.Locale

/**
 * Metadata and tracking state for a remote print job received from the VPS.
 */
data class RemotePrintJob(
    val jobId: String,
    val deviceId: String,
    val filename: String,
    val fileSize: Long,
    val sha256: String = "",
    val mimeType: String,
    val pageCount: Int,
    val pageRange: String = "",
    val colorMode: PrintColorMode = PrintColorMode.MONOCHROME_1BIT,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PrintOrientation = PrintOrientation.PORTRAIT,
    val copies: Int = 1,
    val scaling: PrintScaling = PrintScaling.FIT_TO_PAGE,
    val dpi: Int = 300,
    val quality: PrintQualityProfile = PrintQualityProfile.NORMAL,
    val margins: PrintMargins = PrintMargins(),
    val collate: Boolean = true,
    val pagesPerSheet: Int = 1,
    var status: PrintJobState = PrintJobState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val downloadUrl: String = "",
    val leaseToken: String = "",
    val attempt: Int = 1,
    var localFilePath: String? = null,
    var failureReason: String? = null,
    var bytesTransferred: Long = 0,
    var progressPercent: Int = 0
) {
    fun toPrintOptions(): PrintOptions {
        return PrintOptions(
            paperSize = paperSize,
            dpi = dpi,
            printingMode = when (colorMode) {
                PrintColorMode.MONOCHROME_1BIT -> PrintingMode.BLACK_AND_WHITE
                PrintColorMode.GRAYSCALE_8BIT -> PrintingMode.GRAYSCALE
                PrintColorMode.COLOR_RGB -> PrintingMode.COLOR
            },
            colorMode = colorMode,
            orientation = orientation,
            scaling = scaling,
            margins = margins,
            pageRange = pageRange,
            qualityProfile = quality,
            copies = copies,
            collate = collate
        )
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("jobId", jobId)
            put("deviceId", deviceId)
            put("filename", filename)
            put("fileSize", fileSize)
            put("sha256", sha256)
            put("mimeType", mimeType)
            put("pageCount", pageCount)
            put("pageRange", pageRange)
            put("colorMode", colorMode.name)
            put("paperSize", paperSize.name)
            put("orientation", orientation.name)
            put("copies", copies)
            put("scaling", scaling.name)
            put("dpi", dpi)
            put("quality", quality.name)
            put("marginTopMm", margins.topMm.toDouble())
            put("marginBottomMm", margins.bottomMm.toDouble())
            put("marginLeftMm", margins.leftMm.toDouble())
            put("marginRightMm", margins.rightMm.toDouble())
            put("collate", collate)
            put("pagesPerSheet", pagesPerSheet)
            put("status", status.name)
            put("createdAt", createdAt)
            put("downloadUrl", downloadUrl)
            put("leaseToken", leaseToken)
            put("attempt", attempt)
            put("localFilePath", localFilePath ?: "")
            put("failureReason", failureReason ?: "")
            put("bytesTransferred", bytesTransferred)
            put("progressPercent", progressPercent)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RemotePrintJob {
            return RemotePrintJob(
                jobId = json.getString("jobId"),
                deviceId = json.optString("deviceId", ""),
                filename = json.optString("filename", "document"),
                fileSize = json.optLong("fileSize", 0),
                sha256 = json.optString("sha256", ""),
                mimeType = json.optString("mimeType", "application/pdf"),
                pageCount = json.optInt("pageCount", 1),
                pageRange = json.optString("pageRange", ""),
                colorMode = try {
                    PrintColorMode.valueOf(json.optString("colorMode", PrintColorMode.MONOCHROME_1BIT.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PrintColorMode.MONOCHROME_1BIT
                },
                paperSize = try {
                    PaperSize.valueOf(json.optString("paperSize", PaperSize.A4.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PaperSize.A4
                },
                orientation = try {
                    PrintOrientation.valueOf(json.optString("orientation", PrintOrientation.PORTRAIT.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PrintOrientation.PORTRAIT
                },
                copies = json.optInt("copies", 1),
                scaling = try {
                    PrintScaling.valueOf(json.optString("scaling", PrintScaling.FIT_TO_PAGE.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PrintScaling.FIT_TO_PAGE
                },
                dpi = json.optInt("dpi", 300),
                quality = try {
                    PrintQualityProfile.valueOf(json.optString("quality", PrintQualityProfile.NORMAL.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PrintQualityProfile.NORMAL
                },
                margins = parseMargins(json),
                collate = json.optBoolean("collate", true),
                pagesPerSheet = json.optInt("pagesPerSheet", 1),
                status = try {
                    PrintJobState.valueOf(json.optString("status", PrintJobState.QUEUED.name).uppercase(Locale.US))
                } catch (e: Exception) {
                    PrintJobState.QUEUED
                },
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                downloadUrl = json.optString("downloadUrl", ""),
                leaseToken = json.optString("leaseToken", ""),
                attempt = json.optInt("attempt", json.optInt("currentAttempt", 1)),
                localFilePath = json.optString("localFilePath").takeIf { it.isNotEmpty() },
                failureReason = json.optString("failureReason").takeIf { it.isNotEmpty() },
                bytesTransferred = json.optLong("bytesTransferred", 0),
                progressPercent = json.optInt("progressPercent", 0)
            )
        }

        private fun parseMargins(json: JSONObject): PrintMargins {
            if (json.has("marginTopMm") || json.has("marginLeftMm")) {
                return PrintMargins(
                    topMm = json.optDouble("marginTopMm", 4.0).toFloat(),
                    bottomMm = json.optDouble("marginBottomMm", 4.0).toFloat(),
                    leftMm = json.optDouble("marginLeftMm", 4.0).toFloat(),
                    rightMm = json.optDouble("marginRightMm", 4.0).toFloat()
                )
            }
            val raw = json.optString("margins", "")
            if (raw.isNotBlank()) {
                try {
                    val m = JSONObject(raw)
                    val unit = m.optString("unit", "mm").lowercase(Locale.US)
                    val factor = if (unit == "in" || unit == "inch" || unit == "inches") 25.4f else 1.0f
                    return PrintMargins(
                        topMm = (m.optDouble("top", 4.0) * factor).toFloat(),
                        bottomMm = (m.optDouble("bottom", 4.0) * factor).toFloat(),
                        leftMm = (m.optDouble("left", 4.0) * factor).toFloat(),
                        rightMm = (m.optDouble("right", 4.0) * factor).toFloat()
                    )
                } catch (_: Exception) {}
            }
            return PrintMargins()
        }
    }
}
