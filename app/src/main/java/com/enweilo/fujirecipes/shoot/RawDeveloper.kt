package com.enweilo.fujirecipes.shoot

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Build
import android.util.Half
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Develops a phone RAW frame the way a Fujifilm camera would: sensor light → linear colour → F-Log2 →
 * Fujifilm's own film simulation LUT → the recipe's other settings. Runs on one background thread.
 *
 * Steps: black/white level, demosaic (Malvar–He–Cutler, or 2×2 binning for very big sensors), lens shading,
 * white balance and colour matrix from the camera, clipped highlights made neutral, then — per output pixel —
 * lens distortion correction, zoom crop, light noise reduction and sharpening, exposure matched to the
 * viewfinder, white balance per the recipe, F-Log2 and the LUT.
 */
class RawDeveloper(private val store: LutStore) {

    /** What to make. */
    class Job(
        val look: Look,
        val ratio: Ratio,
        /** how much of the RAW frame the photo covers: 1 = all, 2 = the middle half (2× zoom) */
        val cropFactor: Float,
        /** extra white balance (recipe colour temperature etc.), multiplies linear light */
        val wb: FloatArray,
        /** clockwise turn of the sensor relative to the upright phone (to read the JPEG the same way) */
        val sensorOrientation: Int,
        val iso: Int,
    )

    class Result(val bitmap: Bitmap, val exposureScale: Float)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pass1 = 0
    private var pass2 = 0
    private var maxTex = 4096
    private var floatTargets = false
    private var lutTex = 0
    private var lutKey: String? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)); position(0) }
    private val loc = HashMap<String, Int>()

    private fun setUp() {
        if (pass1 != 0) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: error("沒有 OpenGL ES 3")
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val m = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, m, 0)
        maxTex = m[0]
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        floatTargets = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        pass1 = program(VERTEX, PASS1)
        pass2 = program(VERTEX, PASS2)
    }

    /** True when this phone's GPU can develop RAW (checked once). */
    fun supported(): Boolean = runCatching { setUp(); floatTargets }.getOrDefault(false)

    fun resetLut() {
        lutKey = null
    }

    // ---------------- metadata ----------------

    private class Meta(
        val cfaRed: IntArray, // x, y of the red pixel in the 2×2 pattern
        val black: FloatArray, // per position (0,0) (1,0) (0,1) (1,1)
        val white: Float,
        val gains: FloatArray, // r, g, b
        val ccm: FloatArray, // row-major 3×3, camera (white balanced) → linear sRGB
        val shading: android.hardware.camera2.params.LensShadingMap?,
    )

    private fun meta(f: Camera2Backend.RawFrame): Meta? {
        fun <T> get(k: CaptureResult.Key<T>): T? = f.physicalResult?.get(k) ?: f.result.get(k)
        val ch = f.chars
        val cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: 0
        val red = when (cfa) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> intArrayOf(0, 0)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> intArrayOf(1, 0)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> intArrayOf(0, 1)
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> intArrayOf(1, 1)
            else -> return null // RGB or mono sensors are not handled
        }
        val dyn = if (Build.VERSION.SDK_INT >= 28) get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) else null
        val black = if (dyn != null && dyn.size >= 4) floatArrayOf(dyn[0], dyn[1], dyn[2], dyn[3]) else {
            val p = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
            if (p != null) floatArrayOf(
                p.getOffsetForIndex(0, 0).toFloat(), p.getOffsetForIndex(1, 0).toFloat(),
                p.getOffsetForIndex(0, 1).toFloat(), p.getOffsetForIndex(1, 1).toFloat(),
            ) else floatArrayOf(64f, 64f, 64f, 64f)
        }
        val white = (if (Build.VERSION.SDK_INT >= 28) get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)?.toFloat() else null)
            ?: ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL)?.toFloat() ?: 1023f
        val g = get(CaptureResult.COLOR_CORRECTION_GAINS) ?: return null
        val t = get(CaptureResult.COLOR_CORRECTION_TRANSFORM) ?: return null
        val ccm = FloatArray(9) { t.getElement(it % 3, it / 3).toFloat() }
        val applied = Build.VERSION.SDK_INT >= 29 && ch.get(CameraCharacteristics.SENSOR_INFO_LENS_SHADING_APPLIED) == true
        val shading = if (applied) null else (f.physicalResult?.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
            ?: f.result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP))
        return Meta(red, black, white, floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue), ccm, shading)
    }

    // ---------------- the work ----------------

    /**
     * Develops [f]. [jpeg] is the camera's own picture of the same moment, used to set the brightness so the
     * photo matches what the viewfinder showed. Returns null when this frame cannot be developed.
     */
    fun develop(f: Camera2Backend.RawFrame, jpeg: ByteArray, job: Job): Result? {
        val m = meta(f) ?: return null
        setUp()
        if (!floatTargets) return null
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val look = job.look
        val lutKeyNow = look.lutKey
        if (lutKeyNow != null && !useLut(lutKeyNow)) return null

        val W = f.width
        val H = f.height
        val ch = f.chars
        // geometry: the active area inside the RAW frame
        val pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sx = if (pa != null && pa.width > 0) W.toFloat() / pa.width else 1f
        val sy = if (pa != null && pa.height > 0) H.toFloat() / pa.height else 1f
        val act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val ax0 = (act?.left ?: 0) * sx
        val ay0 = (act?.top ?: 0) * sy
        val aw = (act?.width() ?: W) * sx
        val ah = (act?.height() ?: H) * sy
        val cx = ax0 + aw / 2f
        val cy = ay0 + ah / 2f
        // zoom crop, then the photo shape (long side : short side)
        var rw = aw / job.cropFactor.coerceAtLeast(1f)
        var rh = ah / job.cropFactor.coerceAtLeast(1f)
        val long = max(rw, rh)
        val short = min(rw, rh)
        val zoomRegion = floatArrayOf(cx - rw / 2f, cy - rh / 2f, rw, rh)
        if (job.ratio.value > long / short) {
            if (rw >= rh) rh = rw / job.ratio.value else rw = rh / job.ratio.value
        } else {
            if (rw >= rh) rw = rh * job.ratio.value else rh = rw * job.ratio.value
        }
        val bin = if (W.toLong() * H > 16_500_000L) 2 else 1
        if (W / bin > maxTex || H / bin > maxTex || W > maxTex || H > maxTex) return null

        // lens distortion (mostly the ultra-wide)
        var dist = false
        val intr = FloatArray(4)
        val kd = FloatArray(5)
        if (Build.VERSION.SDK_INT >= 28) {
            val d = ch.get(CameraCharacteristics.LENS_DISTORTION)
            val i = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            if (d != null && i != null && d.size >= 5 && i.size >= 4 && i[0] > 0f && d.any { abs(it) > 1e-4f }) {
                dist = true
                intr[0] = i[0] * sx; intr[1] = i[1] * sy
                intr[2] = (i[2] + (pre?.left ?: 0)) * sx; intr[3] = (i[3] + (pre?.top ?: 0)) * sy
                for (k in 0 until 5) kd[k] = d[k]
            }
        }
        val region = floatArrayOf(cx - rw / 2f, cy - rh / 2f, rw, rh)
        val distScale = if (dist) fitDistortion(region, intr, kd, W, H) else 1f

        // output size (at most ~16 MP)
        var outW = (rw / bin).roundToInt()
        var outH = (rh / bin).roundToInt()
        val px = outW.toLong() * outH
        if (px > 16_500_000L) {
            val k = kotlin.math.sqrt(16_500_000.0 / px).toFloat()
            outW = (outW * k).roundToInt(); outH = (outH * k).roundToInt()
        }

        val scale = exposureScale(f, m, jpeg, zoomRegion, job.sensorOrientation, W, H)

        // ---- pass 1: RAW → linear colour texture ----
        val rawTex = IntArray(1)
        val shadeTex = IntArray(1)
        val linTex = IntArray(1)
        val fb = IntArray(1)
        GLES30.glGenTextures(1, rawTex, 0)
        GLES30.glGenTextures(1, shadeTex, 0)
        GLES30.glGenTextures(1, linTex, 0)
        GLES30.glGenFramebuffers(1, fb, 0)
        try {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[0])
            nearest()
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 2)
            f.pixels.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R16UI, W, H, 0, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, f.pixels)

            // lens shading gains (R, G even, G odd, B), or flat
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadeTex[0])
            linear()
            val sm = m.shading
            if (sm != null && sm.columnCount > 0 && sm.rowCount > 0) {
                val buf = ByteBuffer.allocateDirect(sm.columnCount * sm.rowCount * 8).order(ByteOrder.nativeOrder())
                for (r in 0 until sm.rowCount) for (c in 0 until sm.columnCount) for (k in 0 until 4) {
                    buf.putShort(Half.toHalf(sm.getGainFactor(k, c, r)))
                }
                buf.position(0)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, sm.columnCount, sm.rowCount, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf)
            } else {
                val one = Half.toHalf(1f)
                val buf = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply { repeat(4) { putShort(one) }; position(0) }
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf)
            }

            val lw = W / bin
            val lh = H / bin
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linTex[0])
            linear()
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, lw, lh, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, linTex[0], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return null
            GLES30.glViewport(0, 0, lw, lh)
            GLES30.glUseProgram(pass1)
            loc.clear()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[0])
            GLES30.glUniform1i(u(pass1, "uRaw"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadeTex[0])
            GLES30.glUniform1i(u(pass1, "uShade"), 1)
            GLES30.glUniform4fv(u(pass1, "uBlack"), 1, m.black, 0)
            GLES30.glUniform1f(u(pass1, "uWhite"), m.white)
            GLES30.glUniform2i(u(pass1, "uRedPos"), m.cfaRed[0], m.cfaRed[1])
            GLES30.glUniform2i(u(pass1, "uSize"), W, H)
            GLES30.glUniform1i(u(pass1, "uBin"), bin)
            GLES30.glUniform3fv(u(pass1, "uGain"), 1, m.gains, 0)
            val c = m.ccm
            // column-major for GL
            GLES30.glUniformMatrix3fv(u(pass1, "uCcm"), 1, false, floatArrayOf(c[0], c[3], c[6], c[1], c[4], c[7], c[2], c[5], c[8]), 0)
            drawQuad()
            GLES30.glFinish()

            // ---- pass 2: per output tile ----
            GLES30.glUseProgram(pass2)
            loc.clear()
            val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val tile = min(2048, maxTex)
            val tileTex = IntArray(1)
            GLES30.glGenTextures(1, tileTex, 0)
            val pixels = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
            val part = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            val noise = ((job.iso - 100) / 1200f).coerceIn(0.12f, 1f) * (1f + 0.15f * look.nr).coerceIn(0.3f, 1.8f)
            val sharp = (0.3f + 0.07f * look.sharp).coerceIn(0f, 0.7f)
            try {
                var y = 0
                while (y < outH) {
                    val th = min(tile, outH - y)
                    var x = 0
                    while (x < outW) {
                        val tw = min(tile, outW - x)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tileTex[0])
                        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tileTex[0], 0)
                        GLES30.glViewport(0, 0, tw, th)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, linTex[0])
                        GLES30.glUniform1i(u(pass2, "uLin"), 0)
                        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
                        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, if (lutKeyNow != null) lutTex else 0)
                        GLES30.glUniform1i(u(pass2, "uLut"), 1)
                        GLES30.glUniform1f(u(pass2, "uHasLut"), if (lutKeyNow != null) 1f else 0f)
                        GLES30.glUniform4f(u(pass2, "uTile"), x.toFloat() / outW, y.toFloat() / outH, tw.toFloat() / outW, th.toFloat() / outH)
                        GLES30.glUniform4fv(u(pass2, "uRegion"), 1, region, 0)
                        GLES30.glUniform2f(u(pass2, "uRawSize"), W.toFloat(), H.toFloat())
                        GLES30.glUniform1f(u(pass2, "uBin"), bin.toFloat())
                        GLES30.glUniform1i(u(pass2, "uDist"), if (dist) 1 else 0)
                        GLES30.glUniform4fv(u(pass2, "uIntr"), 1, intr, 0)
                        GLES30.glUniform4f(u(pass2, "uKr"), kd[0], kd[1], kd[2], 0f)
                        GLES30.glUniform2f(u(pass2, "uKt"), kd[3], kd[4])
                        GLES30.glUniform1f(u(pass2, "uDistScale"), distScale)
                        GLES30.glUniform1f(u(pass2, "uScale"), scale)
                        val wb = floatArrayOf(job.wb[0] * look.gain[0], job.wb[1] * look.gain[1], job.wb[2] * look.gain[2])
                        GLES30.glUniform3fv(u(pass2, "uWb"), 1, wb, 0)
                        GLES30.glUniform1f(u(pass2, "uDr"), look.dr)
                        GLES30.glUniform1f(u(pass2, "uNoise"), noise)
                        GLES30.glUniform1f(u(pass2, "uSharp"), sharp)
                        GLES30.glUniform1f(u(pass2, "uAspect"), outH.toFloat() / outW)
                        GLES30.glUniform1f(u(pass2, "uPreMono"), if (look.preMono) 1f else 0f)
                        GLES30.glUniform3fv(u(pass2, "uMonoW"), 1, look.monoW, 0)
                        GLES30.glUniform1f(u(pass2, "uPostMono"), if (look.postMono) 1f else 0f)
                        GLES30.glUniform3fv(u(pass2, "uTint"), 1, look.tint, 0)
                        GLES30.glUniform1f(u(pass2, "uContrast"), look.contrast)
                        GLES30.glUniform1f(u(pass2, "uWarmHi"), look.warmHi)
                        GLES30.glUniform1f(u(pass2, "uSat"), look.sat)
                        GLES30.glUniform1f(u(pass2, "uHi"), look.hi)
                        GLES30.glUniform1f(u(pass2, "uSh"), look.sh)
                        GLES30.glUniform1f(u(pass2, "uChrome"), look.chrome)
                        GLES30.glUniform1f(u(pass2, "uChromeBlue"), look.chromeBlue)
                        GLES30.glUniform1f(u(pass2, "uGrain"), look.grain)
                        GLES30.glUniform1f(u(pass2, "uCells"), look.grainCells)
                        drawQuad()
                        pixels.position(0)
                        GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                        pixels.position(0)
                        if (tw == tile && th == tile) {
                            part.copyPixelsFromBuffer(pixels)
                            canvas.drawBitmap(part, x.toFloat(), y.toFloat(), null)
                        } else {
                            val p2 = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                            p2.copyPixelsFromBuffer(pixels)
                            canvas.drawBitmap(p2, x.toFloat(), y.toFloat(), null)
                            p2.recycle()
                        }
                        x += tw
                    }
                    y += th
                }
            } finally {
                GLES30.glDeleteTextures(1, tileTex, 0)
                part.recycle()
            }
            return Result(out, scale)
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fb, 0)
            GLES30.glDeleteTextures(1, rawTex, 0)
            GLES30.glDeleteTextures(1, shadeTex, 0)
            GLES30.glDeleteTextures(1, linTex, 0)
        }
    }

    /**
     * How bright the RAW light must be made so the photo matches the phone's own picture (and so the viewfinder):
     * compares the middle of the frame, through the same curve the viewfinder uses.
     */
    private fun exposureScale(
        f: Camera2Backend.RawFrame, m: Meta, jpeg: ByteArray, zoom: FloatArray, sensorOrientation: Int, W: Int, H: Int,
    ): Float {
        val curve = store.phoneCurve() ?: return 2.5f
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 480) sample *= 2
        val small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return 2.5f
        val jw = small.width
        val jh = small.height
        val jTall = jh > jw
        val rTall = zoom[3] > zoom[2]
        val pix = f.pixels.duplicate().order(ByteOrder.nativeOrder())
        fun raw(x: Int, y: Int): Float {
            val xx = x.coerceIn(0, W - 1)
            val yy = y.coerceIn(0, H - 1)
            val v = (pix.getShort((yy * W + xx) * 2).toInt() and 0xFFFF).toFloat()
            val b = m.black[(xx and 1) + 2 * (yy and 1)]
            return (v - b) / (m.white - b)
        }
        val ratios = ArrayList<Float>()
        val c = m.ccm
        for (gy in 0 until 40) for (gx in 0 until 52) {
            val u = 0.22f + 0.56f * gx / 51f
            val v = 0.22f + 0.56f * gy / 39f
            val rx = ((zoom[0] + u * zoom[2]).toInt()) and 1.inv()
            val ry = ((zoom[1] + v * zoom[3]).toInt()) and 1.inv()
            val q = floatArrayOf(raw(rx, ry), raw(rx + 1, ry), raw(rx, ry + 1), raw(rx + 1, ry + 1))
            if (q.any { it > 0.92f }) continue
            val ri = m.cfaRed[0] + 2 * m.cfaRed[1]
            val bi = 3 - ri
            val rr = q[ri]
            val bb = q[bi]
            val gg = (q.sum() - rr - bb) / 2f
            val wr = rr * m.gains[0]; val wg = gg * m.gains[1]; val wbb = bb * m.gains[2]
            val lr = c[0] * wr + c[1] * wg + c[2] * wbb
            val lg = c[3] * wr + c[4] * wg + c[5] * wbb
            val lb = c[6] * wr + c[7] * wg + c[8] * wbb
            val lumR = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            if (lumR < 1e-4f) continue
            // the same spot in the JPEG (which may be stored turned)
            val (ju, jv) = if (jTall == rTall) u to v else when (((sensorOrientation % 360) + 360) % 360) {
                270 -> v to (1f - u)
                else -> (1f - v) to u
            }
            val p = small.getPixel((ju * (jw - 1)).toInt(), (jv * (jh - 1)).toInt())
            fun dec(x: Int): Float { val s = x / 255f; return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat() }
            val yl = 0.2126f * dec((p shr 16) and 255) + 0.7152f * dec((p shr 8) and 255) + 0.0722f * dec(p and 255)
            val yd = (if (yl <= 0.0031308f) yl * 12.92f else (1.055 * yl.toDouble().pow(1 / 2.4) - 0.055).toFloat()).coerceIn(0f, 1f)
            if (yd < 0.08f || yd > 0.85f) continue
            val i = min((yd * 255f).toInt(), 254)
            val target = curve[i] + (curve[i + 1] - curve[i]) * (yd * 255f - i)
            ratios += target / lumR
        }
        small.recycle()
        if (ratios.size < 30) return 2.5f
        ratios.sort()
        return ratios[ratios.size / 2].coerceIn(0.05f, 60f)
    }

    /** How much to zoom in so the distortion correction leaves no empty corners. */
    private fun fitDistortion(region: FloatArray, intr: FloatArray, k: FloatArray, W: Int, H: Int): Float {
        fun inside(s: Float): Boolean {
            for (i in 0..8) {
                val fx = (i % 3) / 2f
                val fy = (i / 3) / 2f
                val px = region[0] + fx * region[2]
                val py = region[1] + fy * region[3]
                val xc = (px - intr[2]) / intr[0] * s
                val yc = (py - intr[3]) / intr[1] * s
                var xi = xc; var yi = yc
                repeat(8) {
                    val r2 = xi * xi + yi * yi
                    val rad = 1 + k[0] * r2 + k[1] * r2 * r2 + k[2] * r2 * r2 * r2
                    val dx = k[3] * 2 * xi * yi + k[4] * (r2 + 2 * xi * xi)
                    val dy = k[4] * 2 * xi * yi + k[3] * (r2 + 2 * yi * yi)
                    xi = (xc - dx) / rad; yi = (yc - dy) / rad
                }
                val sxp = xi * intr[0] + intr[2]
                val syp = yi * intr[1] + intr[3]
                if (sxp < 0 || syp < 0 || sxp > W - 1 || syp > H - 1) return false
            }
            return true
        }
        if (inside(1f)) return 1f
        var lo = 0.6f
        var hi = 1f
        repeat(14) {
            val mid = (lo + hi) / 2
            if (inside(mid)) lo = mid else hi = mid
        }
        return lo
    }

    // ---------------- GL helpers ----------------

    private fun useLut(key: String): Boolean {
        if (key == lutKey && lutTex != 0) return true
        val data = store.loadFlog(key) ?: return false
        if (lutTex == 0) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            lutTex = t[0]
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val n = LutStore.GRID
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, data)
        lutKey = key
        return true
    }

    private fun nearest() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun linear() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun u(prog: Int, name: String): Int = loc.getOrPut(name) { GLES30.glGetUniformLocation(prog, name) }

    private fun drawQuad() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
    }

    private fun program(vs: String, fs: String): Int {
        fun sh(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) error("raw shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glBindAttribLocation(p, 0, "aPos")
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("raw link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    companion object {
        /** Colour of a black body at [kelvin] in linear sRGB, brightness 1 (for white balance). */
        fun kelvinRgb(kelvin: Float): FloatArray {
            val t = kelvin.coerceIn(1700f, 24000f).toDouble()
            val x = if (t <= 4000) -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910
            else -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
            val y = when {
                t <= 2222 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
                else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
            }
            val X = x / y
            val Z = (1 - x - y) / y
            val r = 3.2406 * X - 1.5372 - 0.4986 * Z
            val g = -0.9689 * X + 1.8758 + 0.0415 * Z
            val b = 0.0557 * X - 0.2040 + 1.0570 * Z
            val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return floatArrayOf((r / l).toFloat().coerceAtLeast(0.05f), (g / l).toFloat().coerceAtLeast(0.05f), (b / l).toFloat().coerceAtLeast(0.05f))
        }

        /** Gains that turn white balance set for [from] K into white balance set for [to] K. */
        fun adapt(from: Float, to: Float): FloatArray {
            val a = kelvinRgb(from)
            val b = kelvinRgb(to)
            val g = floatArrayOf(a[0] / b[0], a[1] / b[1], a[2] / b[2])
            val l = 0.2126f * g[0] + 0.7152f * g[1] + 0.0722f * g[2]
            return floatArrayOf(g[0] / l, g[1] / l, g[2] / l)
        }

        private const val VERTEX = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = aPos;
    gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0);
}
"""

        /** RAW → linear sRGB (alpha unused). */
        private const val PASS1 = """#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
uniform usampler2D uRaw;
uniform sampler2D uShade;
uniform vec4 uBlack;
uniform float uWhite;
uniform ivec2 uRedPos;
uniform ivec2 uSize;
uniform int uBin;
uniform vec3 uGain;
uniform mat3 uCcm;
in vec2 vUv;
out vec4 o;

float raw(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    int idx = (p.x & 1) + 2 * (p.y & 1);
    float b = uBlack[idx];
    return (float(texelFetch(uRaw, p, 0).r) - b) / (uWhite - b);
}
int colorAt(ivec2 p) {
    ivec2 q = ivec2(p.x & 1, p.y & 1);
    if (q == uRedPos) return 0;
    if (q == ivec2(1) - uRedPos) return 2;
    return 1;
}

void main() {
    ivec2 op = ivec2(gl_FragCoord.xy);
    vec3 rgb;
    float clip;
    if (uBin == 2) {
        ivec2 b = op * 2;
        vec4 q = vec4(raw(b), raw(b + ivec2(1, 0)), raw(b + ivec2(0, 1)), raw(b + ivec2(1, 1)));
        int ri = uRedPos.x + 2 * uRedPos.y;
        int bi = 3 - ri;
        float R = q[ri];
        float B = q[bi];
        float G = (q.x + q.y + q.z + q.w - R - B) * 0.5;
        rgb = vec3(R, G, B);
        clip = max(max(q.x, q.y), max(q.z, q.w));
    } else {
        // Malvar-He-Cutler demosaic
        ivec2 p = op;
        float C = raw(p);
        float n1 = raw(p + ivec2(0, -1)), s1 = raw(p + ivec2(0, 1)), w1 = raw(p + ivec2(-1, 0)), e1 = raw(p + ivec2(1, 0));
        float n2 = raw(p + ivec2(0, -2)), s2 = raw(p + ivec2(0, 2)), w2 = raw(p + ivec2(-2, 0)), e2 = raw(p + ivec2(2, 0));
        float nw = raw(p + ivec2(-1, -1)), ne = raw(p + ivec2(1, -1)), sw = raw(p + ivec2(-1, 1)), se = raw(p + ivec2(1, 1));
        float axial2 = n2 + s2 + w2 + e2;
        float diag = nw + ne + sw + se;
        float gAt = (4.0 * C + 2.0 * (n1 + s1 + w1 + e1) - axial2) / 8.0;
        float horiz = (5.0 * C + 4.0 * (w1 + e1) - diag - (w2 + e2) + 0.5 * (n2 + s2)) / 8.0;
        float vert = (5.0 * C + 4.0 * (n1 + s1) - diag - (n2 + s2) + 0.5 * (w2 + e2)) / 8.0;
        float cross = (6.0 * C + 2.0 * diag - 1.5 * axial2) / 8.0;
        int c = colorAt(p);
        if (c == 0) {
            rgb = vec3(C, gAt, cross);
        } else if (c == 2) {
            rgb = vec3(cross, gAt, C);
        } else {
            // green pixel: is red to the left/right (same row) or above/below?
            bool redRow = colorAt(p + ivec2(1, 0)) == 0;
            rgb = redRow ? vec3(horiz, C, vert) : vec3(vert, C, horiz);
        }
        clip = max(C, max(max(n1, s1), max(w1, e1)));
    }
    vec2 uv = (vec2(op) + 0.5) / vec2(uSize / uBin);
    vec4 sh = texture(uShade, uv);
    rgb = max(rgb, 0.0) * vec3(sh.r, (sh.g + sh.b) * 0.5, sh.a) * uGain;
    // clipped highlights: make them neutral instead of pink
    float k = smoothstep(0.9, 1.0, clip);
    float top = max(rgb.r, max(rgb.g, rgb.b));
    rgb = mix(rgb, vec3(top), k);
    vec3 lin = uCcm * rgb;
    o = vec4(max(lin, 0.0), 1.0);
}
"""

        /** Linear colour → recipe, per output pixel. */
        private const val PASS2 = """#version 300 es
precision highp float;
precision highp sampler3D;
uniform sampler2D uLin;
uniform sampler3D uLut;
uniform float uHasLut;
uniform vec4 uTile;
uniform vec4 uRegion;
uniform vec2 uRawSize;
uniform float uBin;
uniform int uDist;
uniform vec4 uIntr;
uniform vec4 uKr;
uniform vec2 uKt;
uniform float uDistScale;
uniform float uScale;
uniform vec3 uWb;
uniform float uDr, uNoise, uSharp, uAspect;
uniform float uPreMono, uPostMono, uContrast, uWarmHi, uSat, uHi, uSh, uChrome, uChromeBlue, uGrain, uCells;
uniform vec3 uMonoW, uTint;
in vec2 vUv;
out vec4 o;

const vec3 W = vec3(0.2126, 0.7152, 0.0722);

float hash(vec2 p) {
    p = fract(p * vec2(0.1031, 0.1030));
    p += dot(p, p.yx + 33.33);
    return fract((p.x + p.y) * p.x);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float flog2(float x) {
    return x >= 0.000889 ? 0.245281 * log(5.555556 * x + 0.064829) / log(10.0) + 0.384316 : 8.799461 * x + 0.092864;
}

void main() {
    vec2 full = uTile.xy + vUv * uTile.zw;
    vec2 pr = uRegion.xy + full * uRegion.zw;
    if (uDist == 1) {
        float xc = (pr.x - uIntr.z) / uIntr.x * uDistScale;
        float yc = (pr.y - uIntr.w) / uIntr.y * uDistScale;
        float xi = xc, yi = yc;
        for (int i = 0; i < 6; i++) {
            float r2 = xi * xi + yi * yi;
            float rad = 1.0 + uKr.x * r2 + uKr.y * r2 * r2 + uKr.z * r2 * r2 * r2;
            float dx = uKt.x * 2.0 * xi * yi + uKt.y * (r2 + 2.0 * xi * xi);
            float dy = uKt.y * 2.0 * xi * yi + uKt.x * (r2 + 2.0 * yi * yi);
            xi = (xc - dx) / rad;
            yi = (yc - dy) / rad;
        }
        pr = vec2(xi * uIntr.x + uIntr.z, yi * uIntr.y + uIntr.w);
    }
    vec2 uv = pr / uRawSize;
    vec2 t = vec2(uBin * 1.5) / uRawSize;
    vec3 c0 = texture(uLin, uv).rgb;
    float l0 = dot(c0, W);

    // light, edge-aware noise reduction and a touch of sharpening
    vec3 sumC = c0;
    float sumW = 1.0;
    float blur = l0;
    for (int j = 0; j < 8; j++) {
        float a = float(j) * 0.785398;
        vec2 off = vec2(cos(a), sin(a)) * t;
        vec3 ci = texture(uLin, uv + off).rgb;
        float li = dot(ci, W);
        blur += li;
        float d = (li - l0) / (0.10 * l0 + 0.002);
        float w = exp(-d * d);
        sumC += ci * w;
        sumW += w;
    }
    blur /= 9.0;
    vec3 c = mix(c0, sumC / sumW, uNoise);
    float lc = max(dot(c, W), 1e-5);
    c *= clamp(1.0 + uSharp * (l0 - blur) / lc, 0.6, 1.6);

    // the light as the camera would see it
    vec3 scene = c * uScale * uWb;
    float y = dot(scene, W);
    if (uDr > 0.0 && y > 0.18) {
        float yc = 0.18 * pow(y / 0.18, 1.0 / (1.0 + 0.16 * uDr));
        scene *= yc / y;
    }
    if (uPreMono > 0.5) scene = vec3(dot(scene, uMonoW));
    vec3 fg = mat3(0.6274, 0.0691, 0.0164, 0.3293, 0.9195, 0.0880, 0.0433, 0.0114, 0.8956) * max(scene, 0.0);
    vec3 code = vec3(flog2(fg.r), flog2(fg.g), flog2(fg.b));
    vec3 col;
    if (uHasLut > 0.5) {
        col = texture(uLut, clamp(code, 0.0, 1.0) * (32.0 / 33.0) + 0.5 / 33.0).rgb;
    } else {
        // no LUT: plain sRGB with a gentle roll-off
        vec3 s = scene / (1.0 + scene * 0.25);
        col = mix(s * 12.92, 1.055 * pow(max(s, 0.0), vec3(1.0 / 2.4)) - 0.055, step(0.0031308, s));
    }

    if (uContrast != 0.0) col = clamp(mix(col, col * col * (3.0 - 2.0 * col), uContrast), 0.0, 1.0);
    float L = dot(col, W);
    float hb = L * L * (1.0 - L) * 4.0;
    float sb = L * (1.0 - L) * (1.0 - L) * 4.0;
    float L2 = clamp(L + 0.07 * uHi * hb - 0.07 * uSh * sb, 0.0, 1.0);
    if (L > 0.0001) col *= L2 / L;
    float mx = max(col.r, max(col.g, col.b));
    float mn = min(col.r, min(col.g, col.b));
    float sat = mx > 0.0001 ? (mx - mn) / mx : 0.0;
    col *= 1.0 - uChrome * 0.13 * sat * sat * smoothstep(0.15, 0.85, mx);
    float blue = clamp((col.b - max(col.r, col.g)) / max(mx, 0.001) * 2.0, 0.0, 1.0);
    col *= 1.0 - uChromeBlue * 0.16 * blue * sat;
    L = dot(col, W);
    col = mix(vec3(L), col, uSat);
    col += uWarmHi * smoothstep(0.35, 1.0, L) * vec3(0.045, 0.018, -0.045);
    if (uPostMono > 0.5) col = vec3(dot(col, W)) * uTint;
    if (uGrain > 0.0) {
        vec2 g = full * vec2(uCells, uCells * uAspect);
        float n = vnoise(g) + vnoise(g * 1.9 + 17.0) * 0.5 - 0.75;
        L = dot(col, W);
        col += n * uGrain * (0.35 + 1.3 * L * (1.0 - L));
    }
    o = vec4(clamp(col, 0.0, 1.0), 1.0);
}
"""
    }
}
