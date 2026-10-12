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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) controller.onUsbAttached()
    }

    override fun onDestroy() {
        unregisterReceiver(detachReceiver)
        super.onDestroy()
    }
}
