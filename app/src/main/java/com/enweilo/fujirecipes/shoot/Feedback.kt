package com.enweilo.fujirecipes.shoot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Small vibrations and sounds for the 拍照 tab, so every control answers the finger. */
class Feedback(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private fun predefined(id: Int, fallbackMs: Long, amp: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(id))
            else v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp))
        }
    }

    /** Lightest: passing a step on a dial (recipe strip, exposure, zoom). */
    fun tick() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_TICK else 0, 8, 60)

    /** A button changing state. */
    fun click() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_CLICK else 0, 14, 120)

    /** Something landed: focus locked, level reached, recipe chosen. */
    fun confirm() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_DOUBLE_CLICK else 0, 20, 140)

    /** The shutter. */
    fun shutter() = predefined(if (Build.VERSION.SDK_INT >= 29) VibrationEffect.EFFECT_HEAVY_CLICK else 0, 28, 255)

    // ---------------- sound ----------------

    private val rate = 44100
    private val shutterPcm: ShortArray by lazy { makeShutter() }
    private val beepPcm: ShortArray by lazy { makeBeep() }

    fun shutterSound() = play(shutterPcm)
    fun beep() = play(beepPcm)

    private fun play(pcm: ShortArray) {
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) { t.release() }
                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        }
    }

    /** A mechanical focal-plane shutter: a sharp first curtain, a softer second curtain, a little body ring. */
    private fun makeShutter(): ShortArray {
        val n = (rate * 0.16).toInt()
        val out = FloatArray(n)
        val rnd = Random(7)
        fun clack(at: Double, gain: Float, decay: Double, tone: Double) {
            val start = (at * rate).toInt()
            var lp = 0f
            for (i in 0 until n - start) {
                val t = i.toDouble() / rate
                val env = exp(-t / decay).toFloat()
                val noise = rnd.nextFloat() * 2f - 1f
                lp += (noise - lp) * 0.35f
                val ring = sin(2 * PI * tone * t).toFloat() * 0.35f
                out[start + i] += gain * env * (lp + ring)
            }
        }
        clack(0.0, 0.9f, 0.006, 2300.0)
        clack(0.055, 0.6f, 0.009, 1500.0)
        clack(0.058, 0.25f, 0.03, 420.0)
        return toPcm(out, 0.8f)
    }

    private fun makeBeep(): ShortArray {
        val n = (rate * 0.07).toInt()
        val out = FloatArray(n) { i ->
            val t = i.toDouble() / rate
            val env = (minOf(1.0, t / 0.004) * minOf(1.0, (0.07 - t) / 0.01)).toFloat()
            env * sin(2 * PI * 2000 * t).toFloat()
        }
        return toPcm(out, 0.35f)
    }

    private fun toPcm(f: FloatArray, gain: Float): ShortArray {
        var peak = 0.0001f
        f.forEach { peak = maxOf(peak, kotlin.math.abs(it)) }
        return ShortArray(f.size) { (f[it] / peak * gain * Short.MAX_VALUE).toInt().toShort() }
    }
}
