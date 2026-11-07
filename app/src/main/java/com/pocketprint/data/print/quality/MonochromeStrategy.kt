package com.pocketprint.data.print.quality

enum class MonochromeStrategy(val displayName: String) {
    AUTO("Automatic content detection"),
    TEXT("Crisp text and tables"),
    MIXED("Mixed document (ordered halftone)"),
    PHOTO("Photograph (error diffusion)")
}
