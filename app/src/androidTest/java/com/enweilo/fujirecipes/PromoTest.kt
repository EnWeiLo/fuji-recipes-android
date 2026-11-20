package com.enweilo.fujirecipes

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
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

        ctx.startActivity(
            android.content.Intent(ctx, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        run {
            Thread.sleep(3500)
            inst.runOnMainSync { c.dismissUpdatePrompt() }
            val w = dev.displayWidth
            val h = dev.displayHeight

            step("1. recipes")
            // 1. recipes: scroll the list, open one
            record("recipes", 9)
            dev.swipe(w / 2, (h * 0.78).toInt(), w / 2, (h * 0.42).toInt(), 60)
            Thread.sleep(1300)
            dev.swipe(w / 2, (h * 0.42).toInt(), w / 2, (h * 0.70).toInt(), 60)
            Thread.sleep(1200)
            // open the first recipe card
            dev.click(w / 2, (h * 0.30).toInt())
            Thread.sleep(5200)
            dev.pressBack()
            Thread.sleep(1200)

            step("2. connect")
            // 2. connect
            tab(1)
            Thread.sleep(1500)
            record("connect", 5)
            Thread.sleep(5200)

            step("3. shoot")
            // 3. shoot: the coffee photo through different recipes, then the shutter
            inst.runOnMainSync {
                c.shoot.startDemo(portrait)
                c.shoot.selectRecipe(c.recipes.firstOrNull()?.id)
            }
            tab(2)
            Thread.sleep(2500)
            val dd = ctx.resources.displayMetrics.density
            val strip = h - ((48 + 94 + 96 + 29) * dd).toInt()
            record("shoot", 14)
            Thread.sleep(800)
            repeat(4) {
                dev.swipe((w * 0.68).toInt(), strip, (w * 0.40).toInt(), strip, 28)
                Thread.sleep(1900)
            }
            inst.runOnMainSync { c.shoot.shutter() }
            Thread.sleep(4500)

            step("4. polaroid")
            // 4. polaroid
            inst.runOnMainSync { c.polaroid.addPhotos(listOf(uri), true) }
            tab(3)
            Thread.sleep(2500)
            record("polaroid", 8)
            Thread.sleep(1500)
            for (f in listOf(Frame.Black, Frame.Tint, Frame.White)) {
                inst.runOnMainSync { c.polaroid.setFrame(f) }
                Thread.sleep(1700)
            }
            Thread.sleep(2000)

            step("5. home")
            // 5. home of the app, for the ending
            tab(0)
            Thread.sleep(1500)
            record("home", 4)
            Thread.sleep(4800)
        }
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
