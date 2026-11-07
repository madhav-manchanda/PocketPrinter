package com.pocketprint.data.print.quality

/** Processing presets kept separate from printer capability verification. */
enum class PrintQualityProfile(
    val displayName: String,
    val targetDpi: Int,
    val experimentalResolution: Boolean,
    val monochromeStrategy: MonochromeStrategy,
    val monochromeThreshold: Int
) {
    DRAFT(
        displayName = "Draft - fast text",
        targetDpi = 300,
        experimentalResolution = false,
        monochromeStrategy = MonochromeStrategy.TEXT,
        monochromeThreshold = 132
    ),
    NORMAL(
        displayName = "Normal - balanced 300 DPI",
        targetDpi = 300,
        experimentalResolution = false,
        monochromeStrategy = MonochromeStrategy.AUTO,
        monochromeThreshold = 144
    ),
    HIGH(
        displayName = "High - experimental 600 DPI",
        targetDpi = 600,
        experimentalResolution = true,
        monochromeStrategy = MonochromeStrategy.AUTO,
        monochromeThreshold = 140
    ),
    PHOTO(
        displayName = "Photo - tonal detail 300 DPI",
        targetDpi = 300,
        experimentalResolution = false,
        monochromeStrategy = MonochromeStrategy.PHOTO,
        monochromeThreshold = 144
    ),
    MAXIMUM(
        displayName = "Maximum - experimental 1200 DPI",
        targetDpi = 1200,
        experimentalResolution = true,
        monochromeStrategy = MonochromeStrategy.PHOTO,
        monochromeThreshold = 140
    );

    fun isAvailable(maximumVerifiedRasterDpi: Int): Boolean =
        this != MAXIMUM && (!experimentalResolution || maximumVerifiedRasterDpi >= targetDpi)
}
