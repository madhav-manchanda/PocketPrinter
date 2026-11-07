package com.pocketprint.data.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pocketprint.data.logging.DiagnosticLogger
import java.util.concurrent.atomic.AtomicBoolean

interface UsbPermissionListener {
    fun onPermissionGranted(device: UsbDevice)
    fun onPermissionDenied(device: UsbDevice?)
}

class UsbPermissionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val listener: UsbPermissionListener
) {
    private val isRequestPending = AtomicBoolean(false)
    private var isReceiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbConstants.ACTION_USB_PERMISSION) return
            isRequestPending.set(false)

            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            if (device != null) {
                if (granted) {
                    DiagnosticLogger.usb("Permission GRANTED for device: ${device.deviceName}")
                    listener.onPermissionGranted(device)
                } else {
                    DiagnosticLogger.usbWarn("Permission DENIED by user for device: ${device.deviceName}")
                    listener.onPermissionDenied(device)
                }
            } else {
                DiagnosticLogger.usbWarn("Permission response received but target device was null (possibly disconnected)")
                listener.onPermissionDenied(null)
            }
        }
    }

    fun register() {
        if (isReceiverRegistered) return
        val filter = IntentFilter(UsbConstants.ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isReceiverRegistered = true
    }

    fun unregister() {
        if (!isReceiverRegistered) return
        try {
            context.unregisterReceiver(permissionReceiver)
        } catch (e: Exception) {
            // Ignore unregister exception during process cleanup
        }
        isReceiverRegistered = false
        isRequestPending.set(false)
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun requestPermission(device: UsbDevice) {
        if (hasPermission(device)) {
            DiagnosticLogger.usb("Permission already granted for ${device.deviceName}")
            listener.onPermissionGranted(device)
            return
        }

        if (isRequestPending.getAndSet(true)) {
            DiagnosticLogger.usbWarn("USB Permission request already pending; ignoring duplicate request")
            return
        }

        DiagnosticLogger.usb("Prompting user for USB permission on ${device.deviceName} (VID: 0x${String.format("%04X", device.vendorId)})")

        val intent = Intent(UsbConstants.ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            flags
        )

        usbManager.requestPermission(device, pendingIntent)
    }
}
