package com.pocketprint

import com.pocketprint.data.agent.model.RemotePrintJob
import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintJobState
import com.pocketprint.data.print.model.PrintOrientation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RemotePrintJobTest {

    @Test
    fun testJsonSerializationAndDeserialization() {
        val original = RemotePrintJob(
            jobId = "test-job-42",
            deviceId = "test-device-01",
            filename = "sample.pdf",
            fileSize = 1048576L,
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            mimeType = "application/pdf",
            pageCount = 3,
            pageRange = "1-2",
            colorMode = PrintColorMode.MONOCHROME_1BIT,
            paperSize = PaperSize.A4,
            orientation = PrintOrientation.PORTRAIT,
            copies = 2,
            status = PrintJobState.QUEUED,
            leaseToken = "pp_lease_test_token_12345",
            attempt = 2
        )

        val json = original.toJson()
        val restored = RemotePrintJob.fromJson(json)

        assertEquals(original.jobId, restored.jobId)
        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.filename, restored.filename)
        assertEquals(original.fileSize, restored.fileSize)
        assertEquals(original.sha256, restored.sha256)
        assertEquals(original.mimeType, restored.mimeType)
        assertEquals(original.pageCount, restored.pageCount)
        assertEquals(original.pageRange, restored.pageRange)
        assertEquals(original.colorMode, restored.colorMode)
        assertEquals(original.paperSize, restored.paperSize)
        assertEquals(original.copies, restored.copies)
        assertEquals(original.status, restored.status)
        assertEquals(original.leaseToken, restored.leaseToken)
        assertEquals(original.attempt, restored.attempt)
    }

    @Test
    fun testToPrintOptionsMapping() {
        val job = RemotePrintJob(
            jobId = "test-job-options",
            deviceId = "test-dev",
            filename = "invoice.pdf",
            fileSize = 2048L,
            mimeType = "application/pdf",
            pageCount = 1,
            copies = 3,
            colorMode = PrintColorMode.COLOR_RGB,
            paperSize = PaperSize.LETTER
        )

        val options = job.toPrintOptions()
        assertNotNull(options)
        assertEquals(3, options.copies)
        assertEquals(PaperSize.LETTER, options.paperSize)
        assertEquals(PrintColorMode.COLOR_RGB, options.colorMode)
    }
}
