package com.pocketprint.data.print.encoder

import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintEncoding

/**
 * Resolves the concrete printer-side encoding pipeline for a user-facing colour mode.
 *
 * The colour/mono processing decision is kept here so callers (print job manager,
 * test page jobs) do not hardcode encoder selection inline.
 */
object PrintEncodingResolver {

    fun resolve(colorMode: PrintColorMode, candidateProtocol: DiscoveryProtocol?): PrintEncoding = when (colorMode) {
        PrintColorMode.MONOCHROME_1BIT -> PrintEncoding.MONOCHROME_1BIT
        PrintColorMode.GRAYSCALE_8BIT -> PrintEncoding.GRAYSCALE_8BIT
        PrintColorMode.COLOR_RGB -> {
            if (candidateProtocol == DiscoveryProtocol.PCL3GUI2_COLOR_MODE10 ||
                candidateProtocol == DiscoveryProtocol.PCL3GUI2_K_MODE9
            ) {
                PrintEncoding.COLOR_RGB
            } else {
                PrintEncoding.MONOCHROME_1BIT
            }
        }
    }
}
