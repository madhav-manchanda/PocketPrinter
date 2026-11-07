package com.pocketprint.data.print

import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.print.encoder.HpPclmEncoder
import com.pocketprint.data.print.encoder.HpRasterEncoder
import com.pocketprint.data.print.encoder.PrinterDataEncoder
import com.pocketprint.data.usb.UsbConstants

class PrinterProtocolManager {

    /**
     * PCLm encoder — HP Print Service Plugin (libioshpmp.so) confirmed to use PCLm
     * (PCLmStartJob / PCLmEncapsulate / PCLmEndJob) for USB printing on the HP Ink Tank
     * 310 series and other modern HP inkjet printers.
     *
     * Despite the HP Ink Tank 310 advertising "PCL3GUI" in its IEEE 1284 string, firmware
     * on this device processes PCLm — a minimal PDF 1.7 containing Flate/JPEG strip images
     * — when printing via USB direct. PCL3GUI escape-sequence data is silently processed
     * but produces no physical output on this firmware revision.
     */
    private val hpPclmEncoder = HpPclmEncoder()

    /**
     * Retained as dead-code fallback; not routed for any currently-tested device.
     * Kept to avoid breaking existing references in IDE / other modules.
     */
    private val hpRasterEncoder = HpRasterEncoder()

    /**
     * Returns the appropriate encoder for [device].
     *
     * All HP devices (VID 0x03F0 or IEEE 1284 HP keywords) → HpPclmEncoder.
     * Everything else → HpRasterEncoder (bare raster, no PJL).
     */
    fun resolveEncoder(device: UsbDeviceInfo): PrinterDataEncoder {
        val ieee1284 = device.probeResult?.ieee1284DeviceId?.uppercase() ?: ""

        // HP keywords in IEEE 1284 string → PCLm
        val isHpIeee = ieee1284.contains("PCL3GUI")
            || ieee1284.contains("DW-PCL")
            || ieee1284.contains("DESKJET")
            || ieee1284.contains("HP")
            || ieee1284.contains("HEWLETT")

        if (isHpIeee) return hpPclmEncoder

        // HP VID without IEEE 1284 probe → still PCLm
        if (device.isHpDevice || device.vendorId == UsbConstants.VID_HEWLETT_PACKARD) {
            return hpPclmEncoder
        }

        // Non-HP fallback
        return hpRasterEncoder
    }
}
