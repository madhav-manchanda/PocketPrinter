package com.pocketprint.data.discovery.engine

import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryColorMode
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.model.FramingStyle

/**
 * Generates prioritized, technically validated candidate configurations for physical printer testing.
 */
object CandidateGenerator {

    fun generateExperimentalDpiCandidates(): List<DiscoveryCandidate> = listOf(
        DiscoveryCandidate(
            candidateId = "HP_PCL3GUI2_K_M9_600_EXPERIMENTAL",
            name = "Experimental PCL3GUI2 K Mode 9 at 600 DPI",
            protocol = DiscoveryProtocol.PCL3GUI2_K_MODE9,
            colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
            resolutionDpi = 600,
            framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
            sendZlpFlush = true,
            performSoftReset = true,
            priority = 100
        ),
        DiscoveryCandidate(
            candidateId = "HP_PCL3GUI2_K_M9_1200_EXPERIMENTAL",
            name = "Experimental PCL3GUI2 K Mode 9 at 1200 DPI",
            protocol = DiscoveryProtocol.PCL3GUI2_K_MODE9,
            colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
            resolutionDpi = 1200,
            framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
            sendZlpFlush = true,
            performSoftReset = true,
            priority = 101
        )
    )

    fun generateCandidates(): List<DiscoveryCandidate> {
        val list = mutableListOf<DiscoveryCandidate>()

        // 0. PCL3GUI2 K-only Mode 9 — HPLIP Pcl3Gui2 path for HP Ink Tank 310
        //    crd_sequence_k[18], 10,000-byte FIFO flush, ESC*r1A, correct Mode 9 delta row.
        //    This is the evidence-supported path derived from HPLIP hpcups source (BSD 3-Clause HP).
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI2_K_M9_HPLIP",
                name = "Test 0: PCL3GUI2 K-only Mode 9 (HPLIP Pcl3Gui2 — Ink Tank 310 path)",
                protocol = DiscoveryProtocol.PCL3GUI2_K_MODE9,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 0
            )
        )

        // 1. PCL3GUI2 Full Color (Dual-Plane: K Mode 9 + RGB Mode 10 — HPLIP Ink Tank path)
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI2_COLOR_HPLIP",
                name = "Test 1: PCL3GUI2 Dual-Plane Color (K Mode 9 + RGB Mode 10 — HPLIP Ink Tank path)",
                protocol = DiscoveryProtocol.PCL3GUI2_COLOR_MODE10,
                colorMode = DiscoveryColorMode.COLOR_24BIT_RGB,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 1
            )
        )

        // 2. PCL3GUI Mode 0 Uncompressed Raster + PJL + ZLP (simplest, zero delta-row decoding risk)
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M0_PJL_ZLP",
                name = "Test 1: PCL3GUI Mode 0 (Uncompressed) + PJL + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE0,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 1
            )
        )

        // 2. PCL3GUI Mode 2 PackBits (TIFF) + PJL + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M2_PJL_ZLP",
                name = "Test 2: PCL3GUI Mode 2 (PackBits TIFF) + PJL + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE2,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 2
            )
        )

        // 3. PCL3GUI Mode 9 Delta Row + 8-byte CRD + PJL + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M9_PJL_ZLP",
                name = "Test 3: PCL3GUI Mode 9 (Delta Row) + PJL + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE9,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 3
            )
        )

        // 4. Standard PCL3 Simple Raster (ESC * r 1 A) + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3_SIMPLE_ZLP",
                name = "Test 4: Standard PCL3 Simple Raster + ZLP",
                protocol = DiscoveryProtocol.PCL_SIMPLE_RASTER,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.NONE,
                sendZlpFlush = true,
                performSoftReset = false,
                priority = 4
            )
        )

        // 5. Raw PCLm Stream (Validated PDF raster strips) + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCLM_RAW_ZLP",
                name = "Test 5: Direct PCLm (Validated PDF) + ZLP",
                protocol = DiscoveryProtocol.PCLM_RAW,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.NONE,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 5
            )
        )

        // 6. PCLm Stream with PJL Envelope + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCLM_PJL_ZLP",
                name = "Test 6: PCLm Stream + PJL Wrapper + ZLP",
                protocol = DiscoveryProtocol.PCLM_WRAPPED,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 6
            )
        )

        // 7. PCL3GUI Mode 0 Uncompressed with Raw UEL (no PJL) + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M0_RAW_ZLP",
                name = "Test 7: PCL3GUI Mode 0 (Raw UEL, no PJL) + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE0,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.RAW_UEL,
                sendZlpFlush = true,
                performSoftReset = false,
                priority = 7
            )
        )

        // 8. PCL3GUI Mode 9 Delta Row with Raw UEL (no PJL) + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M9_RAW_ZLP",
                name = "Test 8: PCL3GUI Mode 9 (Raw UEL, no PJL) + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE9,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 300,
                framingStyle = FramingStyle.RAW_UEL,
                sendZlpFlush = true,
                performSoftReset = false,
                priority = 8
            )
        )

        // 9. PCL3GUI Mode 2 PackBits @ 600 DPI (High Resolution Test) + PJL + ZLP
        list.add(
            DiscoveryCandidate(
                candidateId = "HP_PCL3GUI_M2_600DPI_ZLP",
                name = "Test 9: PCL3GUI Mode 2 (PackBits) @ 600 DPI + PJL + ZLP",
                protocol = DiscoveryProtocol.PCL3GUI_MODE2,
                colorMode = DiscoveryColorMode.MONOCHROME_1BIT,
                resolutionDpi = 600,
                framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
                sendZlpFlush = true,
                performSoftReset = true,
                priority = 9
            )
        )

        return list.sortedBy { it.priority }
    }
}
