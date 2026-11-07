package com.pocketprint.data.print.quality

import android.content.Context
import android.graphics.Bitmap
import com.pocketprint.data.logging.DiagnosticLogger
import java.io.File
import java.io.FileOutputStream

/** Stores only the latest user-requested preview raster in app-private cache. */
object RasterDiagnosticStore {
    fun saveLatest(context: Context, bitmap: Bitmap): File {
        val directory = File(context.cacheDir, "quality_diagnostics").apply { mkdirs() }
        val destination = File(directory, "latest-final-raster.png")
        FileOutputStream(destination).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode final raster diagnostic"
            }
        }
        DiagnosticLogger.usb(
            "[QUALITY] Saved final raster diagnostic ${bitmap.width}x${bitmap.height} " +
                "to ${destination.absolutePath} (${destination.length()} bytes)"
        )
        return destination
    }
}
