package com.enweilo.fujirecipes

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.shoot.LutStore
import com.enweilo.fujirecipes.shoot.ToneCalib
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The viewfinder kept in step with RAW: screenshots of the preview and the RAW photo of the same moment. */
@RunWith(AndroidJUnit4::class)
class CalibTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private fun step(t: String) = Log.i("CalibTest", t)

    private fun sh(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    private fun writeOut(name: String, bytes: ByteArray) {
        val (out, input) = inst.uiAutomation.executeShellCommandRw("tee /data/local/tmp/$name").let { it[0] to it[1] }
        val drain = Thread { android.os.ParcelFileDescriptor.AutoCloseInputStream(out).readBytes() }.apply { start() }
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write(bytes) }
        drain.join(5000)
    }

    @Test
    fun calib() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("promo") == "1")
        val ctx = inst.targetContext
        val c = (ctx.applicationContext as FujiApp).controller
        sh("pm grant ${ctx.packageName} android.permission.CAMERA")
        val store = LutStore(ctx)
        if (store.installedFlog().size < 10) { val (url, _) = store.probeSize(); store.download(url) { _, _ -> } }
        step("luts ${store.installedFlog().size}")
        inst.runOnMainSync {
            c.changeAutoUpdate(false); c.changeBgMotion(false)
            c.saveRecipeForTest(Recipe(id = "cal", name = "校正測試", filmSim = "Classic Chrome", dr = "DR400", grain = "關"))
            c.shoot.refreshLuts()
            c.shoot.changeRaw(true)
            c.shoot.changeReviewMode(3)
            c.shoot.selectRecipe("cal")
        }
        ctx.startActivity(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Thread.sleep(3000)
        inst.runOnMainSync { c.dismissUpdatePrompt(); c.tab = Tab.Shoot }
        Thread.sleep(4000)
        step("before: ${c.shoot.lensMode} | ${c.shoot.rawReport} | ${c.shoot.calibReport}")
        sh("screencap -p /data/local/tmp/s_cal_0_start.png")
        val end = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < end && ToneCalib.count < 3) Thread.sleep(500)
        step("calib: ${c.shoot.calibReport} count ${ToneCalib.count}")
        ToneCalib.curve?.let { cv -> step("curve " + (0 until 256 step 16).joinToString(" ") { "%.3f".format(cv[it]) }) }
        store.phoneCurve()?.let { cv -> step("static " + (0 until 256 step 16).joinToString(" ") { "%.3f".format(cv[it]) }) }
        Thread.sleep(800)
        sh("screencap -p /data/local/tmp/s_cal_1_preview.png")
        val before = c.shoot.lastUri
        inst.runOnMainSync { c.shoot.shutter() }
        val stop = System.currentTimeMillis() + 40_000
        while (System.currentTimeMillis() < stop && (c.shoot.lastUri == null || c.shoot.lastUri == before || c.shoot.processing > 0)) Thread.sleep(300)
        Thread.sleep(1500)
        step("review: ${c.shoot.review?.info}")
        sh("screencap -p /data/local/tmp/s_cal_2_review.png")
        c.shoot.lastUri?.let { u -> ctx.contentResolver.openInputStream(u)?.use { writeOut("s_cal_3_photo.jpg", it.readBytes()) } }
        inst.runOnMainSync { c.shoot.dismissReview() }
        Thread.sleep(500)
        step("done")
    }
}
