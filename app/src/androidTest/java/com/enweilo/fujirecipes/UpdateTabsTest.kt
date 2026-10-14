package com.enweilo.fujirecipes

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.enweilo.fujirecipes.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpdateTabsTest {

    @Test
    fun releaseCanBeFoundAndDownloaded() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rel = Updater.fetchLatest()
        assertNotNull("could not read the latest release", rel)
        assertTrue("build ${rel!!.build}", rel.build >= 16)
        assertTrue(rel.apkUrl.startsWith("https://"))
        assertTrue(Updater.isNewer(rel, 1))
        assertFalse(Updater.isNewer(rel, rel.build.toLong()))
        val file = Updater.download(ctx, rel) { }
        assertNotNull("download or validation failed", file)
        assertTrue("apk too small: ${file!!.length()}", file.length() > 1_000_000)
    }

    @Test
    fun tabsAndPrompt() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dev = UiDevice.getInstance(inst)
        val c = (inst.targetContext.applicationContext as FujiApp).controller
        Updater.installedBuildOverride = 1
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                Thread.sleep(1500)
                shot(inst, "tabs_01_recipes")
                // order of the bar, left to right
                val xs = listOf("食譜", "相機", "拍立得", "設定").map { dev.findObject(By.text(it))?.visibleBounds?.centerX() ?: -1 }
                assertTrue("tab order wrong: $xs", xs.all { it >= 0 } && xs == xs.sorted())

                dev.findObject(By.text("相機"))?.click()
                Thread.sleep(150)
                shot(inst, "tabs_02_mid_transition")
                Thread.sleep(700)
                assertEquals(Tab.Camera, c.tab)
                dev.findObject(By.text("拍立得"))?.click()
                Thread.sleep(800)
                assertEquals(Tab.Polaroid, c.tab)
                shot(inst, "tabs_03_polaroid")
                dev.findObject(By.text("設定"))?.click()
                Thread.sleep(800)
                assertEquals(Tab.Settings, c.tab)

                inst.runOnMainSync { c.checkForUpdate(true) }
                val end = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < end && !c.updatePrompt) Thread.sleep(200)
                assertTrue("no update prompt: ${c.updateNote}", c.updatePrompt)
                Thread.sleep(600)
                shot(inst, "tabs_04_update_prompt")
                inst.runOnMainSync { c.dismissUpdatePrompt() }
                Thread.sleep(500)
                shot(inst, "tabs_05_settings_update")
            }
        } finally {
            Updater.installedBuildOverride = null
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
