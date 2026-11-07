package com.pocketprint

import com.pocketprint.data.print.util.PrintPageScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintPageSchedulerTest {

    @Test
    fun `collated copies keep complete documents together`() {
        val schedule = PrintPageScheduler.schedule(listOf(0, 2), copies = 2, collate = true)
        assertEquals(
            listOf(0 to 1, 2 to 1, 0 to 2, 2 to 2),
            schedule.map { it.pageIndex to it.copyNumber }
        )
    }

    @Test
    fun `uncollated copies keep copies of each page together`() {
        val schedule = PrintPageScheduler.schedule(listOf(0, 2), copies = 2, collate = false)
        assertEquals(
            listOf(0 to 1, 0 to 2, 2 to 1, 2 to 2),
            schedule.map { it.pageIndex to it.copyNumber }
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `copy count is bounded`() {
        PrintPageScheduler.schedule(listOf(0), copies = 100, collate = true)
    }
}
