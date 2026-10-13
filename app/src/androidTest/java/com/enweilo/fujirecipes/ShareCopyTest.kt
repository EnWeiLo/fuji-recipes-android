package com.enweilo.fujirecipes

import android.content.ClipboardManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The share sheet must offer "複製圖片", and pressing it must put a readable picture on the clipboard. */
@RunWith(AndroidJUnit4::class)
class ShareCopyTest {

    @Test
    fun copyImageButton() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val dev = UiDevice.getInstance(inst)
        val controller = (ctx.applicationContext as FujiApp).controller
        val pc = controller.polaroid

        val file = File(ctx.cacheDir, "share_sample.jpg")
        inst.context.assets.open("sample_exif.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = Uri.fromFile(file)

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            inst.runOnMainSync { controller.tab = Tab.Polaroid }
            inst.runOnMainSync { pc.addPhotos(listOf(uri), true) }
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && !(pc.items.size == 1 && !pc.busy && pc.source != null)) Thread.sleep(200)

            inst.runOnMainSync { pc.shareCurrent() }
            val chip = dev.wait(Until.findObject(By.text("複製圖片")), 25_000)
            Thread.sleep(800)
            shot(inst, "share_01_chooser")
            assertNotNull("the share sheet has no 複製圖片 button", chip)

            chip.click()
            Thread.sleep(1500)
            shot(inst, "share_02_after_copy")

            // back in the app: what is on the clipboard?
            var clipUri: Uri? = null
            var mime = ""
            val until = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < until && clipUri == null) {
                inst.runOnMainSync {
                    val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        clipUri = clip.getItemAt(0).uri
                        mime = clip.description.getMimeType(0)
                    }
                }
                if (clipUri == null) Thread.sleep(400)
            }
            assertNotNull("nothing image-like on the clipboard", clipUri)
            assertTrue("clipboard type is $mime", mime.startsWith("image/"))
            val bytes = ctx.contentResolver.openInputStream(clipUri!!)!!.use { it.readBytes().size }
            assertTrue("copied picture too small: $bytes", bytes > 1000)
        }
    }

    private fun shot(inst: android.app.Instrumentation, name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/rendercheck")
        }
        val resolver = inst.targetContext.contentResolver
        val out = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(out)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }
}
