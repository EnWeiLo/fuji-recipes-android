package com.enweilo.fujirecipes

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enweilo.fujirecipes.camera.FujiBaker
import com.enweilo.fujirecipes.data.Recipe
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 烘焙 tab without a camera: a made-up RAF, the oven, and the profile patch. */
@RunWith(AndroidJUnit4::class)
class BakeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun step(t: String) = Log.i("BakeTest", t)
    private fun sh(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    @Test
    fun patchWritesTheRightFields() {
        // a profile shaped like the camera's: u16 count, then count int32 fields at the end
        val n = 40
        val base = ByteArray(625)
        ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN).putShort(0, n.toShort())
        val r = Recipe(id = "x", filmSim = "Classic Neg.", dr = "DR400", grain = "強／大", colorChrome = "強", colorChromeBlue = "弱",
            wb = "色溫", kelvin = "6300", shiftR = "+2", shiftB = "-4", highlight = "-1.5", shadow = "+1", color = "+3",
            sharpness = "-2", nr = "-4", clarity = "-3")
        val out = FujiBaker.patch(base, r)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val off = out.size - n * 4
        fun f(i: Int) = bb.getInt(off + i * 4)
        assertEquals(0x11, f(8)); assertEquals(400, f(6)); assertEquals(5, f(9)); assertEquals(3, f(10)); assertEquals(2, f(25))
        assertEquals(0x8007, f(12)); assertEquals(6300, f(15)); assertEquals(2, f(13)); assertEquals(-4, f(14))
        assertEquals(-15, f(16)); assertEquals(10, f(17)); assertEquals(30, f(18)); assertEquals(-20, f(19))
        assertEquals(0x8000, f(20)); assertEquals(-30, f(27)); assertEquals(0, f(4))
        step("patch ok")
    }

    @Test
    fun tab() {
        val ctx = inst.targetContext
        val c = (ctx.applicationContext as FujiApp).controller
        // a made-up RAF: header, then the sample JPEG where a camera puts its preview
        val jpeg = inst.context.assets.open("sample.jpg").use { it.readBytes() }
        val head = ByteArray(100)
        "FUJIFILMCCD-RAW 0201FF393501".toByteArray().copyInto(head)
        "X-T50".toByteArray().copyInto(head, 0x1C)
        ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN).putInt(84, 100).putInt(88, jpeg.size)
        val raf = File(ctx.cacheDir, "DSCF0001.RAF").apply { writeBytes(head + jpeg) }
        inst.runOnMainSync {
            c.changeAutoUpdate(false); c.changeBgMotion(false)
            c.saveRecipeForTest(Recipe(id = "bk1", name = "暖陽街拍", filmSim = "Classic Chrome"))
            c.saveRecipeForTest(Recipe(id = "bk2", name = "老派負片", filmSim = "Classic Neg."))
        }
        ctx.startActivity(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Thread.sleep(3000)
        inst.runOnMainSync { c.dismissUpdatePrompt(); c.tab = Tab.Bake }
        Thread.sleep(2000)
        sh("screencap -p /data/local/tmp/s_bake_0_empty.png")
        inst.runOnMainSync { c.bake.addFiles(listOf(Uri.fromFile(raf))) }
        Thread.sleep(2500)
        step("items ${c.bake.items.size} model ${c.bake.current?.model} recipe ${c.bake.recipeId}")
        sh("screencap -p /data/local/tmp/s_bake_1_file.png")
        inst.runOnMainSync { c.bake.showStageForTest(FujiBaker.STAGE_SEND, 0.42f) }
        Thread.sleep(1200)
        sh("screencap -p /data/local/tmp/s_bake_2_oven.png")
        inst.runOnMainSync { c.bake.showStageForTest(FujiBaker.STAGE_BAKING, 0f) }
        Thread.sleep(900)
        sh("screencap -p /data/local/tmp/s_bake_3_oven.png")
        inst.runOnMainSync { c.bake.showStageForTest(-1, 0f) }
        step("done")
    }
}
