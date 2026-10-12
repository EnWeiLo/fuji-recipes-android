package com.enweilo.fujirecipes

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
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the 拍立得 tab on the emulator and takes screenshots of each part of the screen. */
@RunWith(AndroidJUnit4::class)
class UiShotTest {

    @Test
    fun screens() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val dev = UiDevice.getInstance(inst)
        val controller = (ctx.applicationContext as FujiApp).controller

        val file = File(ctx.cacheDir, "sample_exif.jpg")
        inst.context.assets.open("sample_exif.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = Uri.fromFile(file)

        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            inst.runOnMainSync { controller.tab = Tab.Polaroid }
            Thread.sleep(1000)
            shot(inst, "ui_00_empty")

            inst.runOnMainSync { controller.polaroid.addPhotos(listOf(uri, uri, uri), true) }
            Thread.sleep(4000)
            shot(inst, "ui_01_photo")

            click(dev, "版型")
            shot(inst, "ui_02_style")
            click(dev, "橫幅")
            shot(inst, "ui_03_banner")
            click(dev, "方框")

            click(dev, "文字")
            shot(inst, "ui_04_text")
            dev.swipe(540, 1700, 540, 700, 25)
            Thread.sleep(600)
            shot(inst, "ui_05_text_scrolled")
            dev.swipe(540, 1700, 540, 700, 25)
            Thread.sleep(600)
            shot(inst, "ui_06_text_end")

            click(dev, "日期")
            shot(inst, "ui_07_date")

            click(dev, "照片")
            shot(inst, "ui_08_photos")

            click(dev, "文字")
            Thread.sleep(500)
            dev.swipe(540, 700, 540, 1700, 25)
            dev.swipe(540, 700, 540, 1700, 25)
            Thread.sleep(500)
            click(dev, "相機／鏡頭字型")
            Thread.sleep(800)
            shot(inst, "ui_09_font_sheet")
            dev.pressBack()
            Thread.sleep(500)

            inst.runOnMainSync { controller.setTheme(2) }
            Thread.sleep(1000)
            click(dev, "照片")
            shot(inst, "ui_10_dark")
            inst.runOnMainSync { controller.setTheme(0) }
        }
    }

    private fun click(dev: UiDevice, text: String) {
        try {
            dev.findObject(By.text(text))?.click()
        } catch (_: Throwable) {
        }
        Thread.sleep(900)
    }

    private fun shot(inst: android.app.Instrumentation, name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/rendercheck")
        }
        val resolver = inst.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }
}
