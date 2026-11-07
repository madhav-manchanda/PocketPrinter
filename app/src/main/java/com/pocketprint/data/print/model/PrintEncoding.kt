package com.pocketprint.data.print.model

/**
 * Concrete printer-side encoding pipeline selected for a single page.
 *
 * Maps a user-facing colour mode onto the printer-specific encoding stage while
 * keeping the colour/mono processing decision separate from protocol encoding.
 */
enum class PrintEncoding(val displayName: String) {
    MONOCHROME_1BIT("Black & White (1-bit Ink Jet)"),
    GRAYSCALE_8BIT("Grayscale via PCL3GUI2 Color"),
    COLOR_RGB("Full Color (RGB)")
}
