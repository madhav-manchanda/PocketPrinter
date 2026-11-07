package com.pocketprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketprint.data.print.PdfRendererService
import com.pocketprint.data.print.encoder.HpPcl3Gui2ColorEncoder
import com.pocketprint.data.print.encoder.HpPcl3Gui2Encoder
import com.pocketprint.data.print.model.PaperSize
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintMargins
import com.pocketprint.data.print.model.PrintOptions
import com.pocketprint.data.print.quality.PrintQualityPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream

class PrintRenderingInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer = PdfRendererService(context)

    @Test
    fun pdfPageRendersAtConfiguredPixelGeometryWithWhiteBackground() {
        val file = File(context.cacheDir, "quality-vector-test.pdf")
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(300, 400, 1).create())
        page.canvas.drawColor(Color.WHITE)
        page.canvas.drawLine(20f, 20f, 280f, 20f, Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
        })
        document.finishPage(page)
        FileOutputStream(file).use(document::writeTo)
        document.close()

        val options = PrintOptions(
            paperSize = PaperSize.A6,
            dpi = 72,
            margins = PrintMargins.NONE
        )
        val bitmap = renderer.renderPdfPageToBitmap(Uri.fromFile(file), 0, options)
        try {
            assertEquals(options.widthPixels, bitmap.width)
            assertEquals(options.heightPixels, bitmap.height)
            assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            assertTrue(countDarkPixels(bitmap) > 50)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun transparentImageCompositesToWhiteAndMonoPreviewPreservesThinLine() {
        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.TRANSPARENT)
        canvas.drawLine(32f, 0f, 32f, 63f, Paint().apply {
            color = Color.BLACK
            strokeWidth = 1f
        })
        val file = File(context.cacheDir, "quality-alpha-test.png")
        FileOutputStream(file).use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        source.recycle()

        val options = PrintOptions(
            paperSize = PaperSize.A6,
            dpi = 72,
            margins = PrintMargins.NONE,
            colorMode = PrintColorMode.MONOCHROME_1BIT
        )
        val rendered = renderer.renderImageToBitmap(Uri.fromFile(file), options)
        try {
            PrintQualityPreview.applyInPlace(rendered, options)
            assertEquals(Color.WHITE, rendered.getPixel(0, 0))
            assertTrue(countDarkPixels(rendered) > 20)
        } finally {
            rendered.recycle()
        }
    }

    @Test
    fun verifiedMonoStreamKeepsCrdMode9AndJobTermination() {
        val bitmap = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            setPixel(0, 0, Color.BLACK)
            setPixel(7, 1, Color.BLACK)
        }
        val output = ByteArrayOutputStream()
        HpPcl3Gui2Encoder().encodePage(bitmap, PrintOptions(dpi = 300), output)
        bitmap.recycle()
        val bytes = output.toByteArray()

        val crd = indexOf(bytes, byteArrayOf(0x1B, 0x2A, 0x67, 0x31, 0x32, 0x57))
        assertTrue(crd >= 0)
        assertEquals(0x01, bytes[crd + 10].toInt() and 0xFF)
        assertEquals(0x2C, bytes[crd + 11].toInt() and 0xFF)
        assertTrue(indexOf(bytes, "\u001B*b9M".toByteArray()) >= 0)
        assertTrue(indexOf(bytes, "\u001B*rC\u000C".toByteArray()) >= 0)
        assertTrue(indexOf(bytes, "@PJL EOJ\n".toByteArray()) >= 0)
    }

    @Test
    fun verifiedColorStreamKeepsDualPlaneCrdAndRowOrdering() {
        val bitmap = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888)
        val pixels = intArrayOf(
            Color.BLACK, Color.RED, Color.GREEN, Color.BLUE,
            Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.WHITE
        )
        bitmap.setPixels(pixels, 0, 8, 0, 0, 8, 1)
        bitmap.setPixels(pixels.reversedArray(), 0, 8, 0, 1, 8, 1)
        val output = ByteArrayOutputStream()
        HpPcl3Gui2ColorEncoder().encodePage(bitmap, PrintOptions(dpi = 300), output)
        bitmap.recycle()
        val bytes = output.toByteArray()

        val crd = indexOf(bytes, byteArrayOf(0x1B, 0x2A, 0x67, 0x32, 0x30, 0x57))
        assertTrue(crd >= 0)
        assertEquals(0x09, bytes[crd + 14].toInt() and 0xFF)
        assertEquals(0x0A, bytes[crd + 22].toInt() and 0xFF)
        assertTrue(hasRasterRowTerminator(bytes, 'V'.code.toByte()))
        assertTrue(hasRasterRowTerminator(bytes, 'W'.code.toByte()))
        assertTrue(indexOf(bytes, "\u001B*rC\u000C".toByteArray()) >= 0)
        assertTrue(indexOf(bytes, "@PJL EOJ\n".toByteArray()) >= 0)
    }

    private fun countDarkPixels(bitmap: Bitmap): Int {
        val row = IntArray(bitmap.width)
        var count = 0
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            count += row.count { Color.red(it) < 64 && Color.green(it) < 64 && Color.blue(it) < 64 }
        }
        return count
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        if (pattern.isEmpty() || data.size < pattern.size) return -1
        for (start in 0..data.size - pattern.size) {
            if (pattern.indices.all { data[start + it] == pattern[it] }) return start
        }
        return -1
    }

    private fun hasRasterRowTerminator(data: ByteArray, terminator: Byte): Boolean {
        for (index in 0 until data.size - 4) {
            if (data[index] != 0x1B.toByte() || data[index + 1] != '*'.code.toByte() ||
                data[index + 2] != 'b'.code.toByte()
            ) continue
            var cursor = index + 3
            while (cursor < data.size && data[cursor].toInt().toChar().isDigit()) cursor++
            if (cursor < data.size && data[cursor] == terminator) return true
        }
        return false
    }
}
