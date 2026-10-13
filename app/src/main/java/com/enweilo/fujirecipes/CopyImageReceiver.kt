package com.enweilo.fujirecipes

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** The "複製圖片" button in the share sheet: puts the finished picture on the clipboard. */
class CopyImageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return
        try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "拍立得照片", uri))
            Toast.makeText(context, "已複製圖片", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "複製失敗", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
    }
}
