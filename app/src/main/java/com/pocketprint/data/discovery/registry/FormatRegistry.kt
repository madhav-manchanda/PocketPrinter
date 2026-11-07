package com.pocketprint.data.discovery.registry

/**
 * Comprehensive format registry containing all relevant printer languages,
 * raster formats, intermediate images, raster encodings, and transports.
 * Dynamically updates compatibility based on device IEEE 1284 capabilities and physical test results.
 */
object FormatRegistry {

    private val registryEntries = mutableListOf<FormatRegistryEntry>()

    init {
        resetToDefault()
    }

    fun resetToDefault() {
        registryEntries.clear()
        registryEntries.addAll(buildDefaultRegistry())
    }

    fun getAllEntries(): List<FormatRegistryEntry> = registryEntries.toList()

    fun getEntriesByCategory(category: FormatCategory): List<FormatRegistryEntry> =
        registryEntries.filter { it.category == category }

    fun getEntryById(id: String): FormatRegistryEntry? =
        registryEntries.find { it.id.equals(id, ignoreCase = true) }

    fun updateStatus(id: String, newStatus: CompatibilityStatus, notes: String? = null) {
        val entry = getEntryById(id) ?: return
        entry.status = newStatus
    }

    /**
     * Inspects the printer's IEEE 1284 Device ID and updates registry compatibility evidence.
     * Target: HP Ink Tank 310 series
     * Advertised CMD string: "PCL3GUI,PJL,Automatic,DW-PCL,DESKJET,DYN"
     */
    fun evaluatePrinterDevice(ieee1284Id: String) {
        val upper = ieee1284Id.uppercase()

        // Extract CMD: tag
        val cmdMatch = Regex("CMD:([^;]+)").find(upper)
        val advertisedCmds = cmdMatch?.groupValues?.get(1)?.split(",")?.map { it.trim() } ?: emptyList()

        registryEntries.forEach { entry ->
            when (entry.id) {
                "PCL3GUI" -> {
                    if (advertisedCmds.any { it.contains("PCL3GUI") }) {
                        entry.status = CompatibilityStatus.ADVERTISED
                    }
                }
                "PJL" -> {
                    if (advertisedCmds.any { it.contains("PJL") }) {
                        entry.status = CompatibilityStatus.ADVERTISED
                    }
                }
                "HP_DESKJET" -> {
                    if (advertisedCmds.any { it.contains("DESKJET") || it.contains("DW-PCL") }) {
                        entry.status = CompatibilityStatus.ADVERTISED
                    }
                }
                "PCL3" -> {
                    entry.status = CompatibilityStatus.DRIVER_SUPPORTED
                }
                "PCL5", "PCL6_XL", "POSTSCRIPT", "ESCP", "ESCP2" -> {
                    entry.status = CompatibilityStatus.UNSUPPORTED
                }
                "USB_PRINTER_CLASS" -> {
                    entry.status = CompatibilityStatus.DOCUMENTED_SUPPORTED
                }
            }
        }
    }

    private fun buildDefaultRegistry(): List<FormatRegistryEntry> = listOf(
        // ==========================================
        // A. PRINTER LANGUAGES / PDLs
        // ==========================================
        FormatRegistryEntry(
            id = "PCL3GUI",
            name = "HP PCL3GUI (PCL 3 Graphical User Interface)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "HP PCL3GUI Protocol / DeskJet Inkjet Command Stream",
            compatibilityEvidence = "Advertised in IEEE 1284 CMD: tag; primary driver language for HP Ink Tank 310",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B%-12345X@PJL ENTER LANGUAGE=PCL3GUI\\r\\n\\x1BE\\x1B&l0O",
            pageFraming = "ESC * r # A (Start) ... ESC * r C (End of Page)",
            jobTermination = "\\x1B%-12345X + USB Zero-Length Packet (ZLP)",
            supportedColorModes = listOf("1-bit Monochrome", "8-bit Grayscale", "24-bit RGB planar/chunky"),
            supportedResolutions = listOf(300, 600, 1200),
            supportedPaperSizes = listOf("A4", "Letter", "Legal", "4x6 Photo"),
            compressionRequirements = "Mode 0 (Raw), Mode 2 (PackBits), Mode 9 (Delta Row + 8-byte CRD)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Primary printing protocol for HP DeskJet and Ink Tank models."
        ),
        FormatRegistryEntry(
            id = "PCL3",
            name = "Standard PCL 3 Simple Raster",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "HP PCL 3 Technical Reference Manual (Hewlett-Packard)",
            compatibilityEvidence = "Supported as base command set by HPLIP hpcups driver",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "\\x1BE (Reset) \\x1B*t300R (DPI) \\x1B*r1A (Start Raster)",
            pageFraming = "ESC * r 1 A (Start) ... ESC * r B / ESC * r C (End)",
            jobTermination = "\\x1B*rB\\x1B&l0H\\x1BE + ZLP Flush",
            supportedColorModes = listOf("1-bit Monochrome"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Uncompressed Mode 0 or TIFF Mode 2",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DRIVER_SUPPORTED,
            notes = "Simplified classic command structure without complex GUI wrappers."
        ),
        FormatRegistryEntry(
            id = "PCL5",
            name = "HP PCL 5 (Enhanced / LaserJet)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "PCL 5 Technical Reference (HP LaserJet series)",
            compatibilityEvidence = "Not present in IEEE 1284 CMD. Consumer Ink Tank lacks onboard PCL5 vector processor.",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "\\x1BE@PJL ENTER LANGUAGE=PCL\\r\\n",
            pageFraming = "ESC & l 0 E ... ESC * r 1 A",
            jobTermination = "\\x1BE",
            supportedColorModes = listOf("1-bit Monochrome", "Color PCL 5c"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Mode 1, Mode 2, Mode 3 RLE",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.UNSUPPORTED,
            notes = "Incompatible: HP Ink Tank 310 is a host-rendered inkjet with no PCL5 interpreter."
        ),
        FormatRegistryEntry(
            id = "PCL6_XL",
            name = "HP PCL 6 (PCL XL Object-oriented)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "PCL XL Feature Reference Protocol Class 2.0/3.0",
            compatibilityEvidence = "Not advertised in IEEE 1284 CMD. Requires enterprise hardware parser.",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "\\x1B%-12345X@PJL ENTER LANGUAGE=PCLXL\\r\\n) HP-PCL XL;2;0;Comment",
            pageFraming = "BeginPage ... EndPage stream tokens",
            jobTermination = "CloseJob\\x1B%-12345X",
            supportedColorModes = listOf("Grayscale", "RGB"),
            supportedResolutions = listOf(600, 1200),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "RLE, JPEG, Flate",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.UNSUPPORTED,
            notes = "Incompatible: Unsupported on DeskJet / Ink Tank hardware."
        ),
        FormatRegistryEntry(
            id = "HP_DESKJET",
            name = "HP DeskJet Compatibility Mode (DW-PCL / DYN)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "HP DeskJet Device Architecture & Dynamic Raster Specification",
            compatibilityEvidence = "Advertised in IEEE 1284 CMD: 'DW-PCL, DESKJET, DYN'",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B%-12345X@PJL JOB\\r\\n@PJL ENTER LANGUAGE=PCL3GUI\\r\\n",
            pageFraming = "ESC * r 1 A ... ESC * r C",
            jobTermination = "ESC E + ZLP Flush",
            supportedColorModes = listOf("Monochrome", "CMYK/RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter", "4x6"),
            compressionRequirements = "Mode 2, Mode 9, Mode 10",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Direct native match for HP Ink Tank 310 series printhead controller."
        ),
        FormatRegistryEntry(
            id = "PJL",
            name = "Printer Job Language (HP PJL)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "HP PJL Technical Reference Manual",
            compatibilityEvidence = "Advertised in IEEE 1284 CMD: 'PJL'",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B%-12345X@PJL\\r\\n@PJL SET STRINGCODESET=UTF8\\r\\n",
            pageFraming = "@PJL ENTER LANGUAGE=PCL3GUI",
            jobTermination = "@PJL EOJ\\r\\n\\x1B%-12345X",
            supportedColorModes = listOf("N/A (Job Envelope)"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "N/A",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Standard framing and job-control wrapper for modern HP devices."
        ),
        FormatRegistryEntry(
            id = "POSTSCRIPT",
            name = "Adobe PostScript Level 2 / Level 3",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "PostScript Language Reference Manual (Adobe)",
            compatibilityEvidence = "Not present in IEEE 1284 CMD. Inkjet GDI hardware.",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "%!PS-Adobe-3.0",
            pageFraming = "%%Page: 1 1 ... showpage",
            jobTermination = "%%EOF",
            supportedColorModes = listOf("Monochrome", "RGB", "CMYK"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "ASCII85, FlateDecode",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.UNSUPPORTED,
            notes = "Incompatible: Requires PostScript RIP interpreter."
        ),
        FormatRegistryEntry(
            id = "ESCP",
            name = "Epson Standard Code for Printers (ESC/P)",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "Epson ESC/P Reference Manual",
            compatibilityEvidence = "Proprietary Epson protocol. Incompatible with HP hardware.",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "\\x1B@",
            pageFraming = "\\x1B(G ... \\x1B.",
            jobTermination = "\\x0C\\x1B@",
            supportedColorModes = listOf("1-bit Monochrome"),
            supportedResolutions = listOf(180, 360),
            supportedPaperSizes = listOf("Continuous", "A4"),
            compressionRequirements = "None",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.UNSUPPORTED,
            notes = "Incompatible: Epson dot-matrix / inkjet command architecture."
        ),
        FormatRegistryEntry(
            id = "ESCP2",
            name = "Epson ESC/P2 Enhanced Dot / Raster Language",
            category = FormatCategory.PAGE_DESCRIPTION_LANGUAGE,
            specification = "Epson ESC/P2 Reference Manual",
            compatibilityEvidence = "Proprietary Epson protocol. Incompatible with HP hardware.",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "\\x1B\\x01@EJL 1284.4\\n@EJL [Enter ESC/P2]\\n",
            pageFraming = "\\x1Bi ... \\x1B.",
            jobTermination = "\\x0C",
            supportedColorModes = listOf("Monochrome", "Variable Droplet Color"),
            supportedResolutions = listOf(360, 720, 1440),
            supportedPaperSizes = listOf("A4", "Photo"),
            compressionRequirements = "TIFF RLE",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.UNSUPPORTED,
            notes = "Incompatible: Epson-specific printhead pulse controller."
        ),

        // ==========================================
        // B. RASTER PRINTING FORMATS
        // ==========================================
        FormatRegistryEntry(
            id = "PWG_RASTER",
            name = "PWG Raster (PWG 5102.4)",
            category = FormatCategory.RASTER_FORMAT,
            specification = "PWG 5102.4-2012 Standard (Printer Working Group)",
            compatibilityEvidence = "Standard for IPP Everywhere; requires host-side conversion for USB Ink Tank",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "RaSt (PWG Magic Number 0x52 0x61 0x53 0x74)",
            pageFraming = "PWG Page Header (1796 bytes) ... Raster Rows",
            jobTermination = "EOF stream marker",
            supportedColorModes = listOf("Black_1", "Sgray_8", "Srgb_8"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "PWG PackBits (Row-based RLE)",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.THEORETICALLY_COMPATIBLE,
            notes = "Universal modern raster standard, converted to PCL3GUI for direct USB delivery."
        ),
        FormatRegistryEntry(
            id = "APPLE_RASTER_URF",
            name = "Apple Raster (image/urf / AirPrint)",
            category = FormatCategory.RASTER_FORMAT,
            specification = "Apple Raster Specification (AirPrint 1.0+)",
            compatibilityEvidence = "AirPrint protocol standard; requires host-side conversion for USB Ink Tank",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "UNIRAST\\x00 (0x55 0x4E 0x49 0x52 0x41 0x53 0x54 0x00)",
            pageFraming = "URF Page Header (28 bytes) ... PackBits Packets",
            jobTermination = "End-of-page packet count",
            supportedColorModes = listOf("W8", "SRGB24"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Apple PackBits",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.THEORETICALLY_COMPATIBLE,
            notes = "Decoded by PocketPrint rendering pipeline and re-encoded to native PCL3GUI."
        ),
        FormatRegistryEntry(
            id = "HP_PCL3GUI_RASTER",
            name = "HP PCL3GUI Native Raster Stream",
            category = FormatCategory.RASTER_FORMAT,
            specification = "HP PCL3GUI Engineering Reference",
            compatibilityEvidence = "Native hardware raster dialect of HP Ink Tank 310 series",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B*t300R\\x1B*r1A",
            pageFraming = "Row by row: ESC * b # W [Raster Row]",
            jobTermination = "\\x1B*rC",
            supportedColorModes = listOf("1-bit Monochrome", "8-bit Grayscale", "RGB Planar"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter", "4x6"),
            compressionRequirements = "Mode 0, Mode 2, Mode 9 (Delta Row + 8-byte CRD)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Verified native target format for PocketPrint USB pipeline."
        ),
        FormatRegistryEntry(
            id = "RAW_RASTER",
            name = "Raw Uncompressed Raster",
            category = FormatCategory.RASTER_FORMAT,
            specification = "Generic Binary Bitplane Grid",
            compatibilityEvidence = "Transported inside PCL3/PCL3GUI Mode 0 envelopes",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "Protocol-dependent framing",
            pageFraming = "Sequential byte scanlines",
            jobTermination = "ZLP flush",
            supportedColorModes = listOf("1-bit Monochrome", "24-bit RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Uncompressed (Mode 0)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Baseline uncompressed rows; maximum compatibility, zero compression overhead."
        ),
        FormatRegistryEntry(
            id = "PCLM_RASTER",
            name = "PCLm / PCLmS (PDF-subset Raster Stream)",
            category = FormatCategory.RASTER_FORMAT,
            specification = "Wi-Fi Direct & Mopria PCLm Technical Specification v1.0",
            compatibilityEvidence = "Used by HP Android Print Service Plugin (libioshpmp.so)",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "%PDF-1.7\\n%PCLm-1.0\\n",
            pageFraming = "Page object with Flate/RLE compressed image strips",
            jobTermination = "trailer ... %%EOF + ZLP Flush",
            supportedColorModes = listOf("Grayscale 8-bit", "RGB 24-bit"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Flate / JPEG / RLE strips",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DRIVER_SUPPORTED,
            notes = "Standard mobile printing raster wrapper utilized by HP Android Print Plugin."
        ),

        // ==========================================
        // C. INTERMEDIATE IMAGE FORMATS
        // ==========================================
        FormatRegistryEntry(
            id = "PDF_INTERMEDIATE",
            name = "Portable Document Format (PDF)",
            category = FormatCategory.INTERMEDIATE_IMAGE,
            specification = "ISO 32000-1:2008 (PDF 1.7)",
            compatibilityEvidence = "Rendered via Android PdfRenderer into native bitmap and rasterized",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "Rendered client-side via android.graphics.pdf.PdfRenderer",
            pageFraming = "Page-by-page rendering pipeline",
            jobTermination = "Clean stream close",
            supportedColorModes = listOf("RGBA_8888", "RGB_565", "Grayscale"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("Any vector dimensions scaled to target"),
            compressionRequirements = "Internal Flate/DCT filters handled transparently",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Primary input format for user documents in PocketPrint."
        ),
        FormatRegistryEntry(
            id = "PNG_INTERMEDIATE",
            name = "Portable Network Graphics (PNG)",
            category = FormatCategory.INTERMEDIATE_IMAGE,
            specification = "W3C PNG Specification (ISO/IEC 15948)",
            compatibilityEvidence = "Decoded by Android BitmapFactory and rendered to print raster",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "BitmapFactory.decodeStream()",
            pageFraming = "Rendered to canvas",
            jobTermination = "N/A",
            supportedColorModes = listOf("RGBA_8888", "RGB_888"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("Scale to page"),
            compressionRequirements = "Deflate",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Supported input format via image import pipeline."
        ),
        FormatRegistryEntry(
            id = "BMP_INTERMEDIATE",
            name = "Windows Bitmap (BMP)",
            category = FormatCategory.INTERMEDIATE_IMAGE,
            specification = "Microsoft Windows Device-Independent Bitmap (DIB)",
            compatibilityEvidence = "Decoded by Android BitmapFactory and converted to PCL3GUI",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "BitmapFactory.decodeStream()",
            pageFraming = "Direct scanline mapping to raster",
            jobTermination = "N/A",
            supportedColorModes = listOf("1-bit", "8-bit", "24-bit RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("Scale to page"),
            compressionRequirements = "Uncompressed / BI_RLE8",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Clean 1:1 scanline alignment with printer raster engines."
        ),
        FormatRegistryEntry(
            id = "TIFF_INTERMEDIATE",
            name = "Tagged Image File Format (TIFF)",
            category = FormatCategory.INTERMEDIATE_IMAGE,
            specification = "TIFF Revision 6.0 (Adobe)",
            compatibilityEvidence = "PackBits compression scheme shared natively with PCL Mode 2",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "TIFF Header (II or MM)",
            pageFraming = "Strip/Tile IFD mapping",
            jobTermination = "N/A",
            supportedColorModes = listOf("1-bit CCITT", "8-bit Gray", "24-bit RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("Scale to page"),
            compressionRequirements = "PackBits, LZW, Deflate",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "PackBits RLE algorithm matches PCL3GUI Mode 2 encoding 1:1."
        ),
        FormatRegistryEntry(
            id = "JPEG_INTERMEDIATE",
            name = "Joint Photographic Experts Group (JPEG / JFIF)",
            category = FormatCategory.INTERMEDIATE_IMAGE,
            specification = "ISO/IEC 10918-1",
            compatibilityEvidence = "Decoded by Android BitmapFactory for photo reproduction",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "0xFFD8 SOI",
            pageFraming = "DCT macroblocks",
            jobTermination = "0xFFD9 EOI",
            supportedColorModes = listOf("Grayscale", "YCbCr / RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("Scale to page"),
            compressionRequirements = "DCT Lossy",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "High-efficiency intermediate photo storage format."
        ),

        // ==========================================
        // D. RASTER ENCODING VARIATIONS
        // ==========================================
        FormatRegistryEntry(
            id = "ENC_MONO_1BIT",
            name = "1-bit Monochrome Encoding",
            category = FormatCategory.RASTER_ENCODING,
            specification = "1 bit per pixel (0 = White, 1 = Black)",
            compatibilityEvidence = "Native HP PCL ink nozzle firing bitmask",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "ESC * v 1 S (Bits per index) ESC * v 1 N (1 plane)",
            pageFraming = "Scanline byte count = (width + 7) / 8",
            jobTermination = "N/A",
            supportedColorModes = listOf("Monochrome 1-bit"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Mode 0 (Raw), Mode 2 (PackBits), Mode 9 (Delta Row)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Fastest throughput, minimal USB bus saturation, maximum reliability."
        ),
        FormatRegistryEntry(
            id = "ENC_GRAY_2BIT",
            name = "2-bit Grayscale Encoding",
            category = FormatCategory.RASTER_ENCODING,
            specification = "2 bits per pixel (4 shade levels)",
            compatibilityEvidence = "Supported on specific DeskJet photoret models; rare on Ink Tank",
            isAdvertisedByDevice = false,
            isDriverSupported = false,
            requiredJobInit = "ESC * v 2 S",
            pageFraming = "Scanline byte count = (width + 3) / 4",
            jobTermination = "N/A",
            supportedColorModes = listOf("Grayscale 4 levels"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4"),
            compressionRequirements = "Mode 0, Mode 2",
            implementationAvailability = ImplementationAvailability.NOT_APPLICABLE,
            status = CompatibilityStatus.THEORETICALLY_COMPATIBLE,
            notes = "Subsumed by 8-bit dithered monochrome halftoning on Ink Tank 310."
        ),
        FormatRegistryEntry(
            id = "ENC_GRAY_8BIT",
            name = "8-bit Grayscale Encoding",
            category = FormatCategory.RASTER_ENCODING,
            specification = "8 bits per pixel (256 shades 0x00 to 0xFF)",
            compatibilityEvidence = "Supported in PCL3GUI via color configuration record",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "ESC * v 8 S ESC * v 1 N",
            pageFraming = "Scanline byte count = width",
            jobTermination = "N/A",
            supportedColorModes = listOf("Grayscale 8-bit"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "Mode 0, Mode 2, Mode 9",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Enables hardware dithering and continuous-tone grayscale output."
        ),
        FormatRegistryEntry(
            id = "ENC_RGB_24BIT",
            name = "24-bit RGB Color Encoding",
            category = FormatCategory.RASTER_ENCODING,
            specification = "24 bits per pixel (8-bit Red, 8-bit Green, 8-bit Blue)",
            compatibilityEvidence = "Supported in PCL3GUI color mode with 3 planar passes",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "ESC * r 3 U (3 planes) ESC * v 8 S",
            pageFraming = "3 consecutive planar scanlines (R, G, B) per raster row",
            jobTermination = "N/A",
            supportedColorModes = listOf("RGB 24-bit"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter", "4x6"),
            compressionRequirements = "Mode 0, Mode 2, Mode 9",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Full color photo rendering mode."
        ),
        FormatRegistryEntry(
            id = "ENC_RGBA_32BIT",
            name = "32-bit RGBA Intermediate Representation",
            category = FormatCategory.RASTER_ENCODING,
            specification = "32 bits per pixel (Red, Green, Blue, Alpha)",
            compatibilityEvidence = "Native Android Bitmap ARGB_8888 memory buffer format",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "Internal Android memory layout",
            pageFraming = "Rendered in software canvas",
            jobTermination = "N/A",
            supportedColorModes = listOf("ARGB_8888"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "N/A",
            implementationAvailability = ImplementationAvailability.CONVERTED_VIA_PIPELINE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Intermediate raster buffer used prior to 1-bit or 24-bit encoding."
        ),
        FormatRegistryEntry(
            id = "ENC_COMP_UNCOMPRESSED",
            name = "Uncompressed Raster (PCL Mode 0)",
            category = FormatCategory.RASTER_ENCODING,
            specification = "ESC * b 0 M (Set compression mode 0)",
            compatibilityEvidence = "Universal baseline across all HP PCL and PCL3GUI firmware",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B*b0M",
            pageFraming = "ESC * b # W [Raw Bytes]",
            jobTermination = "N/A",
            supportedColorModes = listOf("1-bit Mono", "8-bit Gray", "24-bit RGB"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "None (Literal byte stream)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.ADVERTISED,
            notes = "Simplest format; no decompression state machine in firmware to fail."
        ),
        FormatRegistryEntry(
            id = "ENC_COMP_PACKBITS",
            name = "Run-Length Encoding (PackBits / TIFF / PCL Mode 2)",
            category = FormatCategory.RASTER_ENCODING,
            specification = "ESC * b 2 M (Set compression mode 2 / PackBits)",
            compatibilityEvidence = "Documented standard for HP DeskJet; header byte: 0..127 literal, -1..-127 repeat",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B*b2M",
            pageFraming = "ESC * b # W [PackBits stream]",
            jobTermination = "N/A",
            supportedColorModes = listOf("1-bit Mono", "8-bit Gray"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "TIFF Revision 6.0 PackBits standard",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "Extremely effective for text and line art; ~60-80% size reduction."
        ),
        FormatRegistryEntry(
            id = "ENC_COMP_DELTA_ROW",
            name = "Delta Row Encoding (PCL Mode 9 + CRD)",
            category = FormatCategory.RASTER_ENCODING,
            specification = "ESC * b 9 M + Configure Raster Data (CRD: ESC * g 8 W ...)",
            compatibilityEvidence = "Documented HP PCL3GUI protocol requirement for high-resolution graphics",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "\\x1B*g8W\\x02\\x00\\x01\\x00\\x00\\x00\\x00\\x00\\x1B*b9M",
            pageFraming = "Command bytes + replacement delta offsets",
            jobTermination = "N/A",
            supportedColorModes = listOf("1-bit Mono", "RGB Planar"),
            supportedResolutions = listOf(300, 600),
            supportedPaperSizes = listOf("A4", "Letter"),
            compressionRequirements = "HP Delta-Row 9-byte CRD protocol",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DRIVER_SUPPORTED,
            notes = "Standard compression for HP DeskJet drivers in Linux/Windows."
        ),

        // ==========================================
        // E. TRANSPORT PROTOCOLS
        // ==========================================
        FormatRegistryEntry(
            id = "USB_PRINTER_CLASS",
            name = "USB Printer Class 0x07 (Subclass 1, Protocol 2)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "USB Device Class Definition for Printing Devices v1.1",
            compatibilityEvidence = "Interface #1 on HP Ink Tank 310 (VID 0x03F0, PID 0x1053)",
            isAdvertisedByDevice = true,
            isDriverSupported = true,
            requiredJobInit = "UsbManager.claimInterface(1, true)",
            pageFraming = "Bulk OUT endpoint 0x08 packets (max packet size 512 bytes)",
            jobTermination = "Zero-Length Packet (ZLP) flush + Soft reset (0x21, 0x02)",
            supportedColorModes = listOf("All host-rendered streams"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "N/A (Physical Transport)",
            implementationAvailability = ImplementationAvailability.AVAILABLE_NATIVE,
            status = CompatibilityStatus.DOCUMENTED_SUPPORTED,
            notes = "The active direct physical transport targeted in this project."
        ),
        FormatRegistryEntry(
            id = "IPP",
            name = "Internet Printing Protocol (IPP / IPP 2.0)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "RFC 8010 / RFC 8011 (PWG Standard)",
            compatibilityEvidence = "Network transport over TCP port 631; not direct USB",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "POST /ipp/print HTTP/1.1 with IPP binary header",
            pageFraming = "Chunked HTTP body containing PWG Raster or PDF",
            jobTermination = "IPP end-of-attributes tag (0x03)",
            supportedColorModes = listOf("All IPP MIME types"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "gzip / chunked",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "Network protocol; registered separately from direct USB experiments."
        ),
        FormatRegistryEntry(
            id = "IPPS",
            name = "Secure Internet Printing Protocol (IPPS / IPP over TLS)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "RFC 7472 (TLS transport for IPP)",
            compatibilityEvidence = "Encrypted network transport over TCP port 443/631",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "TLS v1.2/v1.3 Handshake + IPP POST",
            pageFraming = "TLS Application Data records",
            jobTermination = "TLS Close Notify",
            supportedColorModes = listOf("All IPP MIME types"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "TLS Record Layer",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "Encrypted network transport; classified separately."
        ),
        FormatRegistryEntry(
            id = "IPP_EVERYWHERE",
            name = "IPP Everywhere (PWG 5100.14)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "PWG Standard 5100.14-2013",
            compatibilityEvidence = "Driverless network standard using PWG Raster or PDF",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "IPP Everywhere attribute negotiation",
            pageFraming = "PWG Raster stream over IPP",
            jobTermination = "IPP complete job request",
            supportedColorModes = listOf("Standardized PWG color spaces"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "PWG PackBits",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "Universal driverless network printing standard."
        ),
        FormatRegistryEntry(
            id = "RAW_9100",
            name = "AppSocket / HP JetDirect (RAW 9100)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "HP JetDirect TCP Raw Port 9100 protocol",
            compatibilityEvidence = "Standard raw TCP socket transport on HP network print servers",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "TCP connect to port 9100",
            pageFraming = "Direct binary payload streaming",
            jobTermination = "TCP FIN / Socket Close",
            supportedColorModes = listOf("All direct PDLs (PCL3GUI, PCL, PostScript)"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "N/A",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "Raw network pipe; transports identical PCL3GUI byte streams as USB Bulk OUT."
        ),
        FormatRegistryEntry(
            id = "LPR_LPD",
            name = "Line Printer Remote / Daemon (LPR/LPD RFC 1179)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "RFC 1179 (BSD print protocol)",
            compatibilityEvidence = "Legacy Unix print spooler transport over TCP port 515",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "\\x02<printer-queue>\\n control file handshake",
            pageFraming = "Control file (cfA...) + Data file (dfA...)",
            jobTermination = "Zero byte stream ack",
            supportedColorModes = listOf("Raw binary or PostScript"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "None",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "Legacy network spool protocol."
        ),
        FormatRegistryEntry(
            id = "AIRPRINT",
            name = "Apple AirPrint (mDNS / URF / IPP)",
            category = FormatCategory.TRANSPORT_PROTOCOL,
            specification = "Apple AirPrint Architecture Specification",
            compatibilityEvidence = "Apple zero-configuration wireless protocol",
            isAdvertisedByDevice = false,
            isDriverSupported = true,
            requiredJobInit = "Bonjour mDNS TXT record query + IPP print-job",
            pageFraming = "image/urf or PDF stream over IPP",
            jobTermination = "IPP EOJ",
            supportedColorModes = listOf("Grayscale", "sRGB"),
            supportedResolutions = emptyList(),
            supportedPaperSizes = emptyList(),
            compressionRequirements = "Apple PackBits",
            implementationAvailability = ImplementationAvailability.NETWORK_ONLY,
            status = CompatibilityStatus.NOT_INVESTIGATED,
            notes = "AirPrint protocol stack; classified under network transports."
        )
    )
}
