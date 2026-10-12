package com.enweilo.fujirecipes

import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enweilo.fujirecipes.polaroid.Photos
import java.io.File
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Saving to the gallery, the ZIP, and the share file must really work. */
@RunWith(AndroidJUnit4::class)
class ExportTest {

    @Test
    fun saveZipAndShare() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val pc = (ctx.applicationContext as FujiApp).controller.polaroid

        val file = File(ctx.cacheDir, "export_sample.jpg")
        inst.context.assets.open("sample_exif.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = Uri.fromFile(file)

        inst.runOnMainSync { pc.addPhotos(listOf(uri, uri, uri), true) }
        waitFor { pc.items.size == 3 && !pc.busy && pc.source != null }
        assertEquals(3, pc.items.size)

        inst.runOnMainSync { pc.saveCurrent() }
        Thread.sleep(300)
        waitFor { !pc.busy }
        val name = "polaroid_1"
        var found = false
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE),
            MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?", arrayOf("$name%"), null,
        )?.use { c -> while (c.moveToNext()) if (c.getLong(1) > 10_000) found = true }
        assertTrue("saved picture not found in the gallery", found)

        inst.runOnMainSync { pc.saveAllZip() }
        Thread.sleep(300)
        waitFor { !pc.busy }
        var entries = 0
        var zipUri: Uri? = null
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", arrayOf("polaroids_batch%"), null,
        )?.use { c ->
            if (c.moveToFirst()) zipUri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
        }
        assertNotNull("zip not found in Downloads", zipUri)
        ctx.contentResolver.openInputStream(zipUri!!)!!.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name.startsWith("polaroid_") && e.name.endsWith(".png")) entries++
                }
            }
        }
        assertEquals("zip entries", 3, entries)

        val bmp = Photos.decode(ctx, uri, 800)!!
        val shared = Photos.shareFile(ctx, bmp, "polaroid_1.png")
        assertNotNull(shared)
        val shareUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", shared!!)
        assertTrue(ctx.contentResolver.openInputStream(shareUri)!!.use { it.readBytes().size } > 1000)
    }

    private fun waitFor(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(200)
        }
    }
}
