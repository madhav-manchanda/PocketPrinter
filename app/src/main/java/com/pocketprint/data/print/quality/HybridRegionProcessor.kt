package com.pocketprint.data.print.quality

data class HybridRegionMap(
    val pageWidth: Int,
    val pageHeight: Int,
    val tileSize: Int,
    val columns: Int,
    val rows: Int,
    private val textTiles: BooleanArray
) {
    val textTileCount: Int get() = textTiles.count { it }
    val totalTileCount: Int get() = textTiles.size

    fun isTextRegion(x: Int, y: Int): Boolean {
        val column = (x / tileSize).coerceIn(0, columns - 1)
        val row = (y / tileSize).coerceIn(0, rows - 1)
        return textTiles[row * columns + column]
    }
}

/** Bounded tile statistics; no full-page pixel copy is retained. */
class HybridRegionAnalyzer(
    private val width: Int,
    private val height: Int,
    private val tileSize: Int
) {
    init {
        require(width > 0 && height > 0 && tileSize > 0)
    }

    private val columns = (width + tileSize - 1) / tileSize
    private val rows = (height + tileSize - 1) / tileSize
    private val total = IntArray(columns * rows)
    private val white = IntArray(total.size)
    private val dark = IntArray(total.size)
    private val midtone = IntArray(total.size)

    fun addRow(argb: IntArray, y: Int) {
        require(argb.size >= width && y in 0 until height)
        val tileRow = y / tileSize
        for (x in 0 until width) {
            val tile = tileRow * columns + x / tileSize
            val luminance = PerceptualLuminance.fromArgb(argb[x])
            total[tile]++
            if (luminance >= 232) white[tile]++
            if (luminance <= 96) dark[tile]++
            if (luminance in 32..223) midtone[tile]++
        }
    }

    fun finish(): HybridRegionMap {
        val textTiles = BooleanArray(total.size)
        for (tile in total.indices) {
            if (total[tile] == 0) continue
            val whiteFraction = white[tile].toDouble() / total[tile]
            val darkFraction = dark[tile].toDouble() / total[tile]
            val midtoneFraction = midtone[tile].toDouble() / total[tile]
            textTiles[tile] = whiteFraction >= 0.55 &&
                darkFraction in 0.003..0.30 &&
                midtoneFraction <= 0.35
        }
        return HybridRegionMap(width, height, tileSize, columns, rows, textTiles)
    }
}

data class HybridPixelRoute(
    val useBlackPlane: Boolean,
    val grayscale: Int
)

object HybridPixelRouter {
    fun route(argb: Int, textRegion: Boolean, textThreshold: Int): HybridPixelRoute {
        val alpha = (argb ushr 24) and 0xFF
        val red = composite((argb ushr 16) and 0xFF, alpha)
        val green = composite((argb ushr 8) and 0xFF, alpha)
        val blue = composite(argb and 0xFF, alpha)
        val grayscale = PerceptualLuminance.fromRgb(red, green, blue)
        val chroma = maxOf(red, green, blue) - minOf(red, green, blue)
        return HybridPixelRoute(
            useBlackPlane = textRegion && grayscale < textThreshold && chroma <= 18,
            grayscale = grayscale
        )
    }

    private fun composite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255
}
