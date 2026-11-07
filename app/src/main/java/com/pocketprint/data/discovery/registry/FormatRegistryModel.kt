package com.pocketprint.data.discovery.registry

/**
 * Categories of printer formats defined in the PocketPrint comprehensive registry.
 */
enum class FormatCategory(val displayName: String) {
    PAGE_DESCRIPTION_LANGUAGE("Page Description Language (PDL)"),
    RASTER_FORMAT("Raster Printing Format"),
    INTERMEDIATE_IMAGE("Intermediate Image Format"),
    RASTER_ENCODING("Raster Encoding Variation"),
    TRANSPORT_PROTOCOL("Transport Protocol")
}

/**
 * Compatibility classification status.
 */
enum class CompatibilityStatus(val displayName: String) {
    ADVERTISED("Advertised (IEEE 1284 CMD)"),
    DOCUMENTED_SUPPORTED("Documented Supported (Vendor Spec)"),
    DRIVER_SUPPORTED("Driver Supported (HPLIP / Gutenprint)"),
    THEORETICALLY_COMPATIBLE("Theoretically Compatible"),
    UNSUPPORTED("Unsupported (Incompatible Architecture)"),
    NOT_INVESTIGATED("Not Investigated"),
    PHYSICALLY_VERIFIED("Physically Verified (Actual Printed Output)")
}

/**
 * Implementation availability in the Android application.
 */
enum class ImplementationAvailability(val displayName: String) {
    AVAILABLE_NATIVE("Implemented Natively (Direct USB Engine)"),
    CONVERTED_VIA_PIPELINE("Converted via Internal Rendering Pipeline"),
    NETWORK_ONLY("Registered Network Transport (Not Direct USB)"),
    NOT_APPLICABLE("Informational / Protocol Variation"),
    PLANNED("Architecture Planned")
}

/**
 * Individual format entry in the comprehensive registry.
 */
data class FormatRegistryEntry(
    val id: String,
    val name: String,
    val category: FormatCategory,
    val specification: String,
    val compatibilityEvidence: String,
    val isAdvertisedByDevice: Boolean,
    val isDriverSupported: Boolean,
    val requiredJobInit: String,
    val pageFraming: String,
    val jobTermination: String,
    val supportedColorModes: List<String>,
    val supportedResolutions: List<Int>,
    val supportedPaperSizes: List<String>,
    val compressionRequirements: String,
    val implementationAvailability: ImplementationAvailability,
    var status: CompatibilityStatus,
    val notes: String = ""
)
