package com.pocketprint.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pocketprint.R
import com.pocketprint.data.model.UsbDeviceInfo
import com.pocketprint.data.model.UsbInterfaceInfo
import com.pocketprint.databinding.ItemUsbDeviceBinding

class DeviceAdapter(
    private val onRequestPermission: (UsbDeviceInfo) -> Unit,
    private val onProbeDevice: (UsbDeviceInfo) -> Unit
) : ListAdapter<UsbDeviceInfo, DeviceAdapter.DeviceViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemUsbDeviceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class DeviceViewHolder(
        private val binding: ItemUsbDeviceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(device: UsbDeviceInfo) {
            val context = itemView.context

            binding.tvDeviceDisplayName.text = device.displayName
            binding.tvDevicePath.text = device.deviceName

            // Chip badge
            if (device.isPrinterCandidate) {
                binding.chipDeviceType.text = "PRINTER CANDIDATE"
                binding.chipDeviceType.setChipBackgroundColorResource(R.color.accent)
                binding.chipDeviceType.setTextColor(ContextCompat.getColor(context, android.R.color.white))
            } else {
                binding.chipDeviceType.text = "USB DEVICE"
                binding.chipDeviceType.setChipBackgroundColorResource(R.color.border_card)
                binding.chipDeviceType.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            // VID / PID
            val hpTag = if (device.isHpDevice) " — HP (Hewlett-Packard)" else ""
            binding.tvVid.text = "${device.vendorIdHex} (${device.vendorId})$hpTag"
            binding.tvPid.text = "${device.productIdHex} (${device.productId})"
            binding.tvDeviceClass.text = "${device.deviceClassLabel} [Sub: ${device.deviceSubclass}, Proto: ${device.deviceProtocol}]"
            binding.tvInterfaceSummary.text = "${device.interfaceCount} interface(s) present"

            // Buttons state
            if (device.hasPermission) {
                binding.btnItemRequestPermission.text = "Permission Granted"
                binding.btnItemRequestPermission.isEnabled = false
                binding.btnItemProbe.isEnabled = true
            } else {
                binding.btnItemRequestPermission.text = "Request Permission"
                binding.btnItemRequestPermission.isEnabled = true
                binding.btnItemProbe.isEnabled = false
            }

            binding.btnItemRequestPermission.setOnClickListener {
                onRequestPermission(device)
            }

            binding.btnItemProbe.setOnClickListener {
                onProbeDevice(device)
            }

            // Interfaces rendering
            renderInterfaces(device.interfaces, binding.containerInterfaces)
        }

        private fun renderInterfaces(
            interfaces: List<UsbInterfaceInfo>,
            container: LinearLayout
        ) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)

            if (interfaces.isEmpty()) {
                val tv = TextView(container.context).apply {
                    text = "  No interfaces exposed or device not yet enumerated."
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(container.context, R.color.text_secondary))
                }
                container.addView(tv)
                return
            }

            for (iface in interfaces) {
                val ifaceView = inflater.inflate(R.layout.item_usb_interface, container, false)
                val tvHeader = ifaceView.findViewById<TextView>(R.id.tvInterfaceHeader)
                val tvEndpoints = ifaceView.findViewById<TextView>(R.id.tvEndpointDetails)

                val classBadge = if (iface.isPrinterClass) " [PRINTER CLASS]" else ""
                tvHeader.text = "Interface #${iface.id}: ${iface.interfaceClassLabel}$classBadge\n" +
                        "Subclass: ${iface.interfaceSubclassLabel} | Protocol: ${iface.interfaceProtocolLabel}"

                if (iface.endpoints.isEmpty()) {
                    tvEndpoints.text = "  (No endpoints for this interface)"
                } else {
                    val epStringBuilder = StringBuilder()
                    for (ep in iface.endpoints) {
                        epStringBuilder.append("  • EP ${ep.endpointNumber}: ${ep.typeLabel} ${ep.directionLabel} ")
                        epStringBuilder.append("(Addr: ${ep.addressHex}, MaxPacket: ${ep.maxPacketSize}B)\n")
                    }
                    tvEndpoints.text = epStringBuilder.toString().trimEnd()
                }

                container.addView(ifaceView)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<UsbDeviceInfo>() {
        override fun areItemsTheSame(oldItem: UsbDeviceInfo, newItem: UsbDeviceInfo): Boolean {
            return oldItem.deviceName == newItem.deviceName
        }

        override fun areContentsTheSame(oldItem: UsbDeviceInfo, newItem: UsbDeviceInfo): Boolean {
            return oldItem == newItem
        }
    }
}
