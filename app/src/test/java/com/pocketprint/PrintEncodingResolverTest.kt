package com.pocketprint

import com.pocketprint.data.discovery.model.DiscoveryProtocol
import com.pocketprint.data.print.encoder.PrintEncodingResolver
import com.pocketprint.data.print.model.PrintColorMode
import com.pocketprint.data.print.model.PrintEncoding
import org.junit.Assert.assertEquals
import org.junit.Test

class PrintEncodingResolverTest {

    @Test
    fun `monochrome colour mode resolves to monochrome encoding`() {
        assertEquals(
            PrintEncoding.MONOCHROME_1BIT,
            PrintEncodingResolver.resolve(PrintColorMode.MONOCHROME_1BIT, DiscoveryProtocol.PCL3GUI2_K_MODE9)
        )
    }

    @Test
    fun `grayscale colour mode resolves to grayscale encoding`() {
        assertEquals(
            PrintEncoding.GRAYSCALE_8BIT,
            PrintEncodingResolver.resolve(PrintColorMode.GRAYSCALE_8BIT, DiscoveryProtocol.PCL3GUI2_K_MODE9)
        )
    }

    @Test
    fun `colour mode resolves to color encoding for colour capable candidate`() {
        assertEquals(
            PrintEncoding.COLOR_RGB,
            PrintEncodingResolver.resolve(PrintColorMode.COLOR_RGB, DiscoveryProtocol.PCL3GUI2_COLOR_MODE10)
        )
    }

    @Test
    fun `verified pcl3gui2 family can select dual plane color from the k profile`() {
        val encoding = PrintEncodingResolver.resolve(PrintColorMode.COLOR_RGB, DiscoveryProtocol.PCL3GUI2_K_MODE9)
        assertEquals(PrintEncoding.COLOR_RGB, encoding)
    }

    @Test
    fun `colour mode with unknown protocol resolves to monochrome`() {
        assertEquals(
            PrintEncoding.MONOCHROME_1BIT,
            PrintEncodingResolver.resolve(PrintColorMode.COLOR_RGB, null)
        )
    }
}
