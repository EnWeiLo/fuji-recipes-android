package com.enweilo.fujirecipes

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The zoom ring on the 拍照 tab: screenshots while dragging. */
@RunWith(AndroidJUnit4::class)
class RingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun sh(cmd: String) {
        inst.uiAutomation.executeShellCommand(cmd).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    @Test
    fun ring() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("promo") == "1")
        val ctx = inst.targetContext
        val c = (ctx.applicationContext as FujiApp).controller
        sh("pm grant ${ctx.packageName} android.permission.CAMERA")
        val jpeg = inst.context.assets.open("promo_coffee_portrait.jpg").use { it.readBytes() }
        inst.runOnMainSync { c.changeAutoUpdate(false); c.changeBgMotion(false) }
        ctx.startActivity(
            android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        Thread.sleep(3000)
        inst.runOnMainSync { c.dismissUpdatePrompt(); c.shoot.startDemo(jpeg); c.tab = Tab.Shoot }
        Thread.sleep(3000)
        fun shot(n: String) = sh("screencap -p /data/local/tmp/s_ring_$n.png")
        shot("0closed")
        val d = ctx.resources.displayMetrics.density
        val w = ctx.resources.displayMetrics.widthPixels.toFloat()
        val h = ctx.resources.displayMetrics.heightPixels.toFloat()
        // the real screen height includes the navigation bar
        val real = android.graphics.Point().also { (ctx.getSystemService(android.view.WindowManager::class.java)).defaultDisplay.getRealSize(it) }
        val y = real.y - (48 + 94 + 96 + 29 + 65) * d
        var x = w / 2
        val t0 = SystemClock.uptimeMillis()
        fun ev(a: Int) {
            val e = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), a, x, y, 0)
            inst.sendPointerSync(e); e.recycle()
        }
        ev(MotionEvent.ACTION_DOWN)
        Thread.sleep(60)
        fun move(dx: Float, steps: Int) { repeat(steps) { x += dx / steps; ev(MotionEvent.ACTION_MOVE); Thread.sleep(16) } }
        move(-260f, 20)
        Thread.sleep(500); shot("1drag")
        move(-260f, 20)
        Thread.sleep(500); shot("2drag")
        move(700f, 30)
        Thread.sleep(500); shot("3wide")
        ev(MotionEvent.ACTION_UP)
        Thread.sleep(400); shot("4up")
        Thread.sleep(2600); shot("5folded")
        inst.runOnMainSync { c.shoot.stopDemo(); c.changeBgMotion(true) }
    }
}
