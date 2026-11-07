package com.pocketprint.data.print.quality

/** Selects neutral pixels for the binary K plane without collapsing colored shadows. */
object BlackInkSelector {
    fun shouldUseBlackPlane(
        red: Int,
        green: Int,
        blue: Int,
        maximumChannel: Int,
        neutralTolerance: Int
    ): Boolean {
        require(red in 0..255 && green in 0..255 && blue in 0..255)
        require(maximumChannel in 0..255 && neutralTolerance in 0..255)
        val highest = maxOf(red, green, blue)
        val lowest = minOf(red, green, blue)
        return highest <= maximumChannel && highest - lowest <= neutralTolerance
    }
}
