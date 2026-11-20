package com.enweilo.fujirecipes

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import com.enweilo.fujirecipes.ui.App

class MainActivity : ComponentActivity() {

    private val controller get() = (application as FujiApp).controller

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            controller.onUsbDetached()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (savedInstanceState == null && intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            controller.onUsbAttached()
        }
        setContent {
            val c = controller
            BackHandler(enabled = c.stack.isNotEmpty() || c.tab != Tab.Recipes) { c.back() }
            App(c)
        }
    }

    override fun onStart() {
        super.onStart()
        controller.onForeground()
    }

    /** Volume keys take the picture on the 拍照 tab. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val c = controller
        val volume = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN
        if (volume && c.tab == Tab.Shoot && c.stack.isEmpty() && c.shoot.volumeShutter) {
            if (event.repeatCount == 0) c.shoot.shutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) controller.onUsbAttached()
    }

    override fun onDestroy() {
        unregisterReceiver(detachReceiver)
        super.onDestroy()
    }
}
