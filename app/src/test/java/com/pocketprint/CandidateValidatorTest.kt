package com.pocketprint

import com.pocketprint.data.discovery.engine.CandidateValidator
import com.pocketprint.data.discovery.model.DiscoveryCandidate
import com.pocketprint.data.discovery.model.DiscoveryColorMode
import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.discovery.model.FramingStyle
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateValidatorTest {

    @Test
    fun `dual plane color validates without a standalone mode 10 command`() {
        val candidate = DiscoveryCandidate(
            candidateId = "color",
            name = "color",
            protocol = DiscoveryProtocol.PCL3GUI2_COLOR_MODE10,
            colorMode = DiscoveryColorMode.COLOR_24BIT_RGB,
            resolutionDpi = 300,
            framingStyle = FramingStyle.PJL_ENTER_PCL3GUI,
            sendZlpFlush = true,
            performSoftReset = true
        )
        val payload = buildList<Byte> {
            repeat(10_000) { add(0) }
            addAll("\u001b%-12345X@PJL ENTER LANGUAGE=PCL3GUI\n".toByteArray().toList())
            addAll(byteArrayOf(0x1B, 0x2A, 0x67, 0x32, 0x30, 0x57).toList())
            repeat(128) { add(0) }
            addAll("\u001b%-12345X".toByteArray().toList())
        }.toByteArray()

        assertTrue(CandidateValidator.validate(candidate, payload).isValid)
    }
}
