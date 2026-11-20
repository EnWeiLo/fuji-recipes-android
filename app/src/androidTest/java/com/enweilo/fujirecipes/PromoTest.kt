package com.enweilo.fujirecipes

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.enweilo.fujirecipes.data.Recipe
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.enweilo.fujirecipes.polaroid.Frame
import com.enweilo.fujirecipes.shoot.LutStore
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records screen clips of the app for the promo video (run with -e promo 1).
 * Clips land in Pictures/rendercheck as rec_*.mp4.
 */
@RunWith(AndroidJUnit4::class)
class PromoTest {

    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(900)

    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun step(text: String) = android.util.Log.i("PromoTest", text)
    private val dev = UiDevice.getInstance(inst)

    private fun sh(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).use { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
        }
    }

    /** Taps the tab bar (5 tabs) without searching the screen (searching waits for the app to be idle). */
    private fun tab(i: Int) {
        val d = ctx().resources.displayMetrics.density
        val w = dev.displayWidth
        val nav = (48 * d).toInt()
        val y = dev.displayHeight - nav - (14 * d + 33 * d).toInt()
        val x0 = 24 * d
        val bw = w - 2 * x0
        dev.click((x0 + bw * (i + 0.5f) / 5).toInt(), y)
    }

    private fun ctx() = inst.targetContext

    /** Starts screenrecord for [seconds] in the background. */
    private fun record(name: String, seconds: Int) {
        val pfd = inst.uiAutomation.executeShellCommand(
            "screenrecord --size 720x1600 --bit-rate 16000000 --time-limit $seconds /data/local/tmp/rec_$name.mp4",
        )
        Thread {
            val text = runCatching { String(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()) }.getOrElse { it.toString() }
            step("screenrecord $name said: ${text.take(300)}")
        }.apply { isDaemon = true }.start()
        step("recording $name")
        Thread.sleep(700)
        val ls = inst.uiAutomation.executeShellCommand("ls -la /data/local/tmp/")
        step("tmp: " + String(android.os.ParcelFileDescriptor.AutoCloseInputStream(ls).readBytes()).lines().filter { "rec_" in it }.joinToString(" | "))
    }

    @Test
    fun recordClips() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("promo") == "1")
        val ctx = inst.targetContext
        val c = (ctx.applicationContext as FujiApp).controller

        // a tidy phone: animations on, clean status bar
        sh("settings put global animator_duration_scale 1")
        sh("settings put global transition_animation_scale 1")
        sh("settings put global window_animation_scale 1")
        sh("settings put global sysui_demo_allowed 1")
        sh("am broadcast -a com.android.systemui.demo -e command enter")
        sh("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941")
        sh("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false")
        sh("am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4")
        sh("am broadcast -a com.android.systemui.demo -e command notifications -e visible false")

        // the app keeps animating, so never wait for "idle" (it would stall every swipe by seconds)
        androidx.test.uiautomator.Configurator.getInstance().apply {
            waitForIdleTimeout = 0
            waitForSelectorTimeout = 0
            actionAcknowledgmentTimeout = 0
        }
        step("start")
        val store = LutStore(ctx)
        if (store.installedFlog().size < 10) {
            val (url, _) = store.probeSize()
            store.download(url) { _, _ -> }
        }
        step("luts ready")
        inst.runOnMainSync {
            c.changeAutoUpdate(false)
            c.dismissUpdatePrompt()
            c.changeBgMotion(false)
            c.shoot.refreshLuts()
            c.shoot.changeReviewMode(1)
        }
        val portrait = inst.context.assets.open("promo_coffee_portrait.jpg").use { it.readBytes() }
        val landscape = inst.context.assets.open("promo_coffee.jpg").use { it.readBytes() }

        // the coffee photo in the gallery, for the polaroid tab
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "promo_coffee.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/promo")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(landscape) }

        // recipes to show off (the list, the strip in the shoot tab)
        val seeds = listOf(
            Recipe(id = "p1", name = "暖陽街拍", filmSim = "Classic Chrome", dr = "DR200", grain = "弱／小", colorChrome = "強", colorChromeBlue = "弱", wb = "日光", shiftR = "2", shiftB = "-4", highlight = "-1", shadow = "1", color = "2", ev = "+1/3"),
            Recipe(id = "p2", name = "老派負片", filmSim = "Classic Neg.", dr = "DR400", grain = "強／小", colorChrome = "強", wb = "自動", shiftR = "1", shiftB = "-3", highlight = "1", shadow = "2", color = "3", ev = "0"),
            Recipe(id = "p3", name = "清透日常", filmSim = "REALA ACE", dr = "DR200", grain = "關", colorChrome = "弱", colorChromeBlue = "弱", wb = "自動", highlight = "-1", shadow = "-1", color = "1", ev = "+2/3"),
            Recipe(id = "p4", name = "濃郁風景", filmSim = "Velvia", dr = "DR100", grain = "關", colorChrome = "強", colorChromeBlue = "強", wb = "日光", highlight = "0", shadow = "1", color = "1", ev = "-1/3"),
            Recipe(id = "p5", name = "電影感", filmSim = "ETERNA", dr = "DR400", grain = "弱／大", wb = "自動", shiftR = "-1", shiftB = "-2", highlight = "-2", shadow = "-1", color = "-2", ev = "0"),
            Recipe(id = "p6", name = "黑白紀實", filmSim = "ACROS", dr = "DR200", grain = "強／大", highlight = "1", shadow = "2", sharpness = "1", ev = "0"),
        )
        inst.runOnMainSync { seeds.reversed().forEach { c.saveRecipeForTest(it) } }
        sh("pm grant ${ctx.packageName} android.permission.CAMERA")

        ctx.startActivity(
            android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        fun shot(name: String) { sh("screencap -p /data/local/tmp/s_$name.png"); step("shot $name") }
        fun main(block: () -> Unit) = inst.runOnMainSync(block)
        Thread.sleep(3500)
        main { c.dismissUpdatePrompt() }

        step("1. recipes")
        Thread.sleep(800)
        shot("recipes")
        main { c.openDetail("p1") }
        Thread.sleep(1800)
        shot("detail")
        val w = dev.displayWidth
        val h = dev.displayHeight
        dev.swipe(w / 2, (h * 0.75).toInt(), w / 2, (h * 0.35).toInt(), 40)
        Thread.sleep(1800)
        shot("detail2")
        main { c.back() }
        Thread.sleep(1500)

        step("2. connect")
        main { c.tab = Tab.Camera }
        Thread.sleep(2500)
        shot("connect")

        step("3. shoot")
        main { c.shoot.startDemo(portrait); c.shoot.selectRecipe(null); c.shoot.changeReviewMode(3) }
        main { c.tab = Tab.Shoot }
        Thread.sleep(3500)
        shot("shoot_0")
        seeds.forEachIndexed { i, r ->
            main { c.shoot.selectRecipe(r.id) }
            Thread.sleep(2200)
            shot("shoot_${i + 1}")
        }
        main { c.shoot.selectRecipe("p1") }
        Thread.sleep(2200)
        shot("shoot_pre")
        main { c.shoot.shutter() }
        Thread.sleep(6000)
        shot("review")
        main { c.shoot.dismissReview() }
        Thread.sleep(1500)
        shot("shoot_after")

        step("4. polaroid")
        main { c.polaroid.addPhotos(listOf(uri), true) }
        main { c.tab = Tab.Polaroid }
        Thread.sleep(3500)
        Frame.values().forEachIndexed { i, f ->
            main { c.polaroid.setFrame(f) }
            Thread.sleep(1800)
            shot("pol_$i")
        }

        step("5. settings")
        main { c.tab = Tab.Settings }
        Thread.sleep(2000)
        shot("settings")
        main { c.tab = Tab.Recipes }
        Thread.sleep(1500)
        shot("home")
        sh("am broadcast -a com.android.systemui.demo -e command exit")
        sh("settings put global animator_duration_scale 0")
        sh("settings put global transition_animation_scale 0")
        sh("settings put global window_animation_scale 0")
        androidx.test.uiautomator.Configurator.getInstance().apply {
            waitForIdleTimeout = 10_000
            waitForSelectorTimeout = 10_000
            actionAcknowledgmentTimeout = 3_000
        }
        inst.runOnMainSync { c.shoot.stopDemo(); c.changeBgMotion(true) }
        step("done")
    }
}
