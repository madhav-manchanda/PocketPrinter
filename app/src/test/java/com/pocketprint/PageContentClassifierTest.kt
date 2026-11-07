package com.pocketprint

import com.pocketprint.data.print.quality.MonochromeRowRasterizer
import com.pocketprint.data.print.quality.MonochromeStrategy
import com.pocketprint.data.print.quality.PageContentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageContentClassifierTest {
    @Test
    fun `classifier caps the sampled pixel count`() {
        val pixels = IntArray(PageContentClassifier.MAX_SAMPLES * 3) { index ->
            opaqueGray(index and 0xFF)
        }

        val profile = PageContentClassifier.classify(pixels)

        assertEquals(PageContentClassifier.MAX_SAMPLES, profile.sampleCount)
    }

    @Test
    fun `classifier distinguishes text mixed and photo tone distributions`() {
        val text = IntArray(256) { if (it and 1 == 0) BLACK else WHITE }
        val mixed = IntArray(300) {
            when (it % 3) {
                0 -> BLACK
                1 -> WHITE
                else -> opaqueGray(128)
            }
        }
        val photo = IntArray(256) { opaqueGray(it) }

        assertEquals(MonochromeStrategy.TEXT, PageContentClassifier.classify(text).recommendedStrategy)
        assertEquals(MonochromeStrategy.MIXED, PageContentClassifier.classify(mixed).recommendedStrategy)
        assertEquals(MonochromeStrategy.PHOTO, PageContentClassifier.classify(photo).recommendedStrategy)
    }

    @Test
    fun `auto resolves once from its page profile`() {
        val profile = PageContentClassifier.classify(IntArray(256) { opaqueGray(it) })
        val rasterizer = MonochromeRowRasterizer.auto(width = 8, pageProfile = profile)

        assertEquals(MonochromeStrategy.AUTO, rasterizer.requestedStrategy)
        assertEquals(MonochromeStrategy.PHOTO, rasterizer.resolvedStrategy)
        rasterizer.rasterizeRow(IntArray(8) { WHITE })
        rasterizer.rasterizeRow(IntArray(8) { BLACK })
        assertEquals(MonochromeStrategy.PHOTO, rasterizer.resolvedStrategy)
    }

    @Test
    fun `auto without a sampled page profile is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MonochromeRowRasterizer(width = 8, strategy = MonochromeStrategy.AUTO)
        }
    }

    private fun opaqueGray(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    companion object {
        private const val BLACK = -0x1000000
        private const val WHITE = -0x1
    }
}
