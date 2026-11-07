package com.pocketprint.data.model

data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val interfaceClassHex: String,
    val interfaceClassLabel: String,
    val interfaceSubclass: Int,
    val interfaceSubclassLabel: String,
    val interfaceProtocol: Int,
    val interfaceProtocolLabel: String,
    val name: String?,
    val endpointCount: Int,
    val endpoints: List<UsbEndpointInfo>,
    val isPrinterClass: Boolean
) {
    val bulkOutEndpoint: UsbEndpointInfo? = endpoints.firstOrNull { 
        it.typeLabel == "BULK" && it.directionLabel == "OUT" 
    }
    
    val bulkInEndpoint: UsbEndpointInfo? = endpoints.firstOrNull { 
        it.typeLabel == "BULK" && it.directionLabel == "IN" 
    }
    
    val hasBulkPrintEndpoints: Boolean = bulkOutEndpoint != null
}
