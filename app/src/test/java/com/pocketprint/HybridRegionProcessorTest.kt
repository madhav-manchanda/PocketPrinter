package com.pocketprint

import com.pocketprint.data.print.quality.HybridPixelRouter
import com.pocketprint.data.print.quality.HybridRegionAnalyzer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRegionProcessorTest {
    @Test
    fun `thin black table lines on white classify as text region`() {
        val analyzer = HybridRegionAnalyzer(64, 64, 64)
        repeat(64) { y ->
            analyzer.addRow(IntArray(64) { x ->
                if (x == 16 || x == 48 || y == 20 || y == 40) BLACK else WHITE
            }, y)
        }
        assertTrue(analyzer.finish().isTextRegion(10, 10))
    }

    @Test
    fun `continuous gradient remains tonal region`() {
        val analyzer = HybridRegionAnalyzer(64, 64, 64)
        repeat(64) { y ->
            analyzer.addRow(IntArray(64) { x -> gray(x * 255 / 63) }, y)
        }
        assertFalse(analyzer.finish().isTextRegion(10, 10))
    }

    @Test
    fun `neutral dark text routes to K but colored and tonal pixels do not`() {
        assertTrue(HybridPixelRouter.route(gray(30), true, 144).useBlackPlane)
        assertFalse(HybridPixelRouter.route(0xFF102060.toInt(), true, 144).useBlackPlane)
        assertFalse(HybridPixelRouter.route(gray(30), false, 144).useBlackPlane)
    }

    @Test
    fun `transparent black routes as white tonal pixel`() {
        val route = HybridPixelRouter.route(0x00000000, true, 144)
        assertFalse(route.useBlackPlane)
        assertTrue(route.grayscale == 255)
    }

    private fun gray(value: Int): Int =
        (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    companion object {
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
