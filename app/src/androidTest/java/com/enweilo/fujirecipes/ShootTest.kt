package com.enweilo.fujirecipes

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.shoot.Look
import com.enweilo.fujirecipes.shoot.LutStore
import com.enweilo.fujirecipes.shoot.PhotoProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Downloads Fujifilm's LUTs, develops the sample picture with several recipes, then tries the 拍照 tab. */
@RunWith(AndroidJUnit4::class)
class ShootTest {

    @Test
    fun lutsLooksAndTab() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val ctx = inst.targetContext
        val store = LutStore(ctx)
        if (store.installed().size < 10) {
            val (url, size) = store.probeSize()
            log(inst, "lut url $url size $size")
            val n = store.download(url) { _, _ -> }
            log(inst, "installed $n")
        }
        assertTrue("luts: ${store.installed()}", store.installed().size >= 10)

        val src = inst.context.assets.open("sample_exif.jpg").use { BitmapFactory.decodeStream(it) }
        val scale = 600f / maxOf(src.width, src.height)
        val small = Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        // colour patches under the picture
        val base = Bitmap.createBitmap(small.width, small.height + 80, Bitmap.Config.ARGB_8888)
        Canvas(base).apply {
            drawBitmap(small, 0f, 0f, null)
            val cols = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, 0xFFFFCC33.toInt(), 0xFFE6A080.toInt(), 0xFF3399E6.toInt(), Color.GRAY, Color.WHITE)
            val w = base.width / cols.size.toFloat()
            cols.forEachIndexed { i, c -> drawRect(i * w, small.height.toFloat(), (i + 1) * w, base.height.toFloat(), Paint().apply { color = c }) }
        }

        val sims = listOf(
            "PROVIA / Standard", "Velvia / Vivid", "ASTIA / Soft", "Classic Chrome", "REALA ACE", "PRO Neg. Std",
            "Classic Neg.", "ETERNA / Cinema", "ETERNA Bleach Bypass", "ACROS", "Nostalgic Neg.", "Sepia",
        )
        val proc = PhotoProcessor(store)
        val tiles = mutableListOf(base.copy(Bitmap.Config.ARGB_8888, true))
        for (sim in sims) {
            val b = base.copy(Bitmap.Config.ARGB_8888, true)
            proc.process(b, Look.of(Recipe(id = "t", filmSim = sim, grain = "關", dr = "DR100")))
            tiles += b
        }
        // a busier recipe: Classic Chrome with tone, chrome, grain and a warm shift
        val busy = base.copy(Bitmap.Config.ARGB_8888, true)
        proc.process(
            busy,
            Look.of(
                Recipe(
                    id = "t", filmSim = "Classic Chrome", dr = "DR400", grain = "強／小", colorChrome = "強",
                    colorChromeBlue = "弱", shiftR = "+3", shiftB = "-5", highlight = "-1", shadow = "+1", color = "+2",
                ),
            ),
        )
        tiles += busy
        save(ctx, "shoot_looks", grid(tiles, 4))

        // the tab itself
        // The emulator's virtual camera tends to take the whole emulator down, so the camera is only used when asked.
        val useCamera = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("camera") == "1"
        if (useCamera) inst.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.CAMERA)
        val dev = UiDevice.getInstance(inst)
        val c = (ctx.applicationContext as FujiApp).controller
        inst.runOnMainSync {
            if (c.recipes.none { it.name == "測試經典正片" }) {
                c.saveRecipeForTest(Recipe(id = "shoot-test", name = "測試經典正片", filmSim = "Classic Chrome", grain = "弱／小"))
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            dev.findObject(By.text("拍照"))?.click()
            Thread.sleep(4000)
            shot(inst, "shoot_01_tab")
            inst.runOnMainSync { c.shoot.selectRecipe("shoot-test") }
            Thread.sleep(2000)
            shot(inst, "shoot_02_recipe")
            if (useCamera) inst.runOnMainSync { c.shoot.shutter() }
            val end = System.currentTimeMillis() + if (useCamera) 20_000 else 0
            while (System.currentTimeMillis() < end && c.shoot.lastUri == null) Thread.sleep(300)
            Thread.sleep(800)
            shot(inst, "shoot_03_after_shot")
            log(inst, "lastUri ${c.shoot.lastUri}")
            dev.findObject(By.text("設定"))?.click()
            Thread.sleep(1200)
            dev.swipe(dev.displayWidth / 2, dev.displayHeight * 3 / 4, dev.displayWidth / 2, dev.displayHeight / 4, 20)
            Thread.sleep(800)
            shot(inst, "shoot_04_settings")
        }
    }

    private fun grid(tiles: List<Bitmap>, cols: Int): Bitmap {
        val w = tiles[0].width
        val h = tiles[0].height
        val rows = (tiles.size + cols - 1) / cols
        val out = Bitmap.createBitmap(w * cols, h * rows, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        tiles.forEachIndexed { i, b -> cv.drawBitmap(b, (i % cols) * w.toFloat(), (i / cols) * h.toFloat(), null) }
        return out
    }

    private fun save(ctx: android.content.Context, name: String, bmp: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/rendercheck")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun shot(inst: android.app.Instrumentation, name: String) {
        val bmp = inst.uiAutomation.takeScreenshot() ?: return
        save(inst.targetContext, name, bmp)
    }

    private fun log(inst: android.app.Instrumentation, text: String) {
        android.util.Log.i("ShootTest", text)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "shoot-log-${System.currentTimeMillis()}.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/rendercheck")
        }
        runCatching {
            val r = inst.targetContext.contentResolver
            val uri = r.insert(MediaStore.Files.getContentUri("external"), values) ?: return
            r.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        }
    }
}
