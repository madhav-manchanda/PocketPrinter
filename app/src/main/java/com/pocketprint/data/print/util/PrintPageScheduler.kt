package com.pocketprint.data.print.util

data class ScheduledPrintPage(
    val pageIndex: Int,
    val selectedPageNumber: Int,
    val copyNumber: Int
)

/** Creates the exact software-copy order sent to the printer. */
object PrintPageScheduler {

    fun schedule(pageIndices: List<Int>, copies: Int, collate: Boolean): List<ScheduledPrintPage> {
        require(copies in 1..99) { "Copies must be between 1 and 99" }
        if (pageIndices.isEmpty()) return emptyList()

        return buildList(pageIndices.size * copies) {
            if (collate) {
                for (copy in 1..copies) {
                    pageIndices.forEachIndexed { selectedIndex, pageIndex ->
                        add(ScheduledPrintPage(pageIndex, selectedIndex + 1, copy))
                    }
                }
            } else {
                pageIndices.forEachIndexed { selectedIndex, pageIndex ->
                    for (copy in 1..copies) {
                        add(ScheduledPrintPage(pageIndex, selectedIndex + 1, copy))
                    }
                }
            }
        }
    }
}
