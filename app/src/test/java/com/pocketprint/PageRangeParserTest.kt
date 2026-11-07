package com.pocketprint

import com.pocketprint.data.print.util.PageRangeParser
import com.pocketprint.data.print.model.PageSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {

    @Test
    fun `odd and even filters use original one based page numbers`() {
        assertEquals(listOf(0, 2, 4), PageRangeParser.parse("1-6", 6, PageSet.ODD))
        assertEquals(listOf(1, 3, 5), PageRangeParser.parse("1-6", 6, PageSet.EVEN))
        assertEquals(listOf(2, 4), PageRangeParser.parse("2-5", 6, PageSet.ODD))
    }

    @Test
    fun `empty range selects all pages`() {
        assertEquals((0..4).toList(), PageRangeParser.parse("", 5))
    }

    @Test
    fun `blank range selects all pages`() {
        assertEquals((0..4).toList(), PageRangeParser.parse("   ", 5))
    }

    @Test
    fun `all keyword selects all pages`() {
        assertEquals((0..4).toList(), PageRangeParser.parse("all", 5))
        assertEquals((0..4).toList(), PageRangeParser.parse("ALL", 5))
    }

    @Test
    fun `single page selection is converted to zero based index`() {
        assertEquals(listOf(2), PageRangeParser.parse("3", 5))
    }

    @Test
    fun `comma separated pages preserve ascending order`() {
        assertEquals(listOf(0, 2, 4), PageRangeParser.parse("1,3,5", 5))
    }

    @Test
    fun `single range covers requested pages`() {
        assertEquals((0..4).toList(), PageRangeParser.parse("1-5", 5))
    }

    @Test
    fun `mixed ranges and singles are supported`() {
        assertEquals(listOf(0, 2, 3, 4, 7), PageRangeParser.parse("1, 3-5, 8", 10))
    }

    @Test
    fun `out of range pages are ignored`() {
        assertEquals(listOf(0), PageRangeParser.parse("1, 99", 3))
    }

    @Test
    fun `inverted ranges are normalized to ascending order`() {
        assertEquals(listOf(1, 2, 3, 4), PageRangeParser.parse("5-2", 10))
    }

    @Test
    fun `duplicate pages are removed`() {
        assertEquals(listOf(1, 2), PageRangeParser.parse("2,2,3", 10))
    }

    @Test
    fun `invalid range produces empty selection instead of full document`() {
        assertTrue(PageRangeParser.parse("abc", 5).isEmpty())
        assertTrue(PageRangeParser.parse("abc-def", 5).isEmpty())
        assertTrue(PageRangeParser.parse("1-", 5).isEmpty())
    }

    @Test
    fun `fully out of range selection produces empty selection`() {
        assertTrue(PageRangeParser.parse("99", 3).isEmpty())
        assertTrue(PageRangeParser.parse("10-20", 3).isEmpty())
    }
}
