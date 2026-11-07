package com.pocketprint

import com.pocketprint.data.print.quality.BlackInkSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackInkSelectorTest {
    @Test
    fun `photo shadows are not collapsed into binary black`() {
        assertFalse(BlackInkSelector.shouldUseBlackPlane(39, 39, 39, 8, 4))
        assertTrue(BlackInkSelector.shouldUseBlackPlane(4, 4, 4, 8, 4))
    }

    @Test
    fun `text mode extracts dark neutral text`() {
        assertTrue(BlackInkSelector.shouldUseBlackPlane(92, 94, 91, 96, 12))
        assertFalse(BlackInkSelector.shouldUseBlackPlane(110, 110, 110, 96, 12))
    }

    @Test
    fun `dark colored pixels stay on rgb plane`() {
        assertFalse(BlackInkSelector.shouldUseBlackPlane(8, 20, 8, 24, 4))
    }

    @Test
    fun `grayscale mode extracts only exact black`() {
        assertTrue(BlackInkSelector.shouldUseBlackPlane(0, 0, 0, 0, 4))
        assertFalse(BlackInkSelector.shouldUseBlackPlane(1, 1, 1, 0, 4))
    }
}
