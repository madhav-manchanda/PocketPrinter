package com.pocketprint.data.print.util

import com.pocketprint.data.print.model.PageSet

/**
 * Parses user-supplied page range expressions into zero-based page indices.
 *
 * Supported formats:
 *   - "all" or empty string -> all pages 0 until totalPages
 *   - "1" -> page 0
 *   - "1,3,5" -> pages 0, 2, 4
 *   - "1-5" -> pages 0, 1, 2, 3, 4
 *   - "1, 3-5, 8" -> pages 0, 2, 3, 4, 7
 *
 * All inputs are validated against [totalPages]:
 *   - Pages < 1 are ignored.
 *   - Pages > totalPages are clamped/ignored.
 *   - Inverted ranges (e.g. "5-2") are reversed to ascending order.
 *   - Duplicates are removed.
 *   - Results are returned in ascending order.
 */
object PageRangeParser {

    fun parse(
        rangeString: String?,
        totalPages: Int,
        pageSet: PageSet = PageSet.ALL
    ): List<Int> {
        if (totalPages <= 0) return emptyList()
        val allPages = (0 until totalPages).toList()
        if (rangeString.isNullOrBlank() || rangeString.trim().equals("all", ignoreCase = true)) {
            return filterPageSet(allPages, pageSet)
        }

        val resultSet = sortedSetOf<Int>()
        val segments = rangeString.split(",")

        for (segment in segments) {
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-").mapNotNull { it.trim().toIntOrNull() }
                if (bounds.size >= 2) {
                    val start = bounds[0]
                    val end = bounds[1]
                    val low = minOf(start, end).coerceAtLeast(1)
                    val high = maxOf(start, end).coerceAtMost(totalPages)
                    for (p in low..high) {
                        resultSet.add(p - 1) // Convert 1-based to 0-based
                    }
                }
            } else {
                val singlePage = trimmed.toIntOrNull()
                if (singlePage != null && singlePage in 1..totalPages) {
                    resultSet.add(singlePage - 1)
                }
            }
        }

        return filterPageSet(resultSet.toList(), pageSet)
    }

    private fun filterPageSet(pages: List<Int>, pageSet: PageSet): List<Int> = when (pageSet) {
        PageSet.ALL -> pages
        PageSet.ODD -> pages.filter { (it + 1) % 2 == 1 }
        PageSet.EVEN -> pages.filter { (it + 1) % 2 == 0 }
    }
}
