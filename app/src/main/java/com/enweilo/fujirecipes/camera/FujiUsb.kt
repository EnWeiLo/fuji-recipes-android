package com.enweilo.fujirecipes.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Finds a Fujifilm camera on the USB port, asks Android for permission and opens it for PTP. */
class FujiUsb(context: Context) {

    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    fun findCamera(): UsbDevice? = usb.deviceList.values.firstOrNull { it.vendorId == FUJI_VID }

    /** Shows Android's own "allow this app to access the device" dialog unless it was already granted. */
    suspend fun ensurePermission(device: UsbDevice): Boolean {
        if (usb.hasPermission(device)) return true
        return suspendCancellableCoroutine { continuation ->
            val action = appContext.packageName + ".USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { appContext.unregisterReceiver(this) } catch (_: Exception) {}
                    if (continuation.isActive) {
                        continuation.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                appContext, 0, Intent(action).setPackage(appContext.packageName), flags,
            )
            continuation.invokeOnCancellation {
                try { appContext.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
            usb.requestPermission(device, pending)
        }
    }

    /** Opens the camera and picks the interface that has one bulk-in and one bulk-out endpoint (the PTP one). */
    fun open(device: UsbDevice): PtpCam {
        var chosen: UsbInterface? = null
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val candidate = device.getInterface(i)
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (e in 0 until candidate.endpointCount) {
                val endpoint = candidate.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint else bulkOut = endpoint
                }
            }
            if (bulkIn != null && bulkOut != null) {
                chosen = candidate
                inEndpoint = bulkIn
                outEndpoint = bulkOut
                // interface class 6 is "still image" (PTP): take it right away if present
                if (candidate.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE) break
            }
        }

        val usbInterface = chosen
        val epIn = inEndpoint
        val epOut = outEndpoint
        if (usbInterface == null || epIn == null || epOut == null) throw PtpException("找不到相機的 PTP 介面")

        val connection = usb.openDevice(device) ?: throw PtpException("無法開啟 USB 裝置")
        // force = true: take the interface even if Android's own camera handler holds it
        if (!connection.claimInterface(usbInterface, true)) {
            connection.close()
            throw PtpException("無法占用 USB 介面")
        }
        if (usbInterface.alternateSetting != 0) {
            try { connection.setInterface(usbInterface) } catch (_: Exception) {}
        }
        return PtpCam(connection, usbInterface, epIn, epOut, device.productName ?: "Fujifilm")
    }
}
