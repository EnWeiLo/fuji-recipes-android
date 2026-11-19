package com.enweilo.fujirecipes.shoot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Applies a [Look] to a full-size photo on the GPU, piece by piece so even very large photos fit.
 * The bitmap is changed in place. Call from one background thread only.
 */
class PhotoProcessor(private val store: LutStore) {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: LookProgram? = null
    private var maxTile = 2048

    private fun setUp() {
        if (program != null) return
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: error("沒有可用的 OpenGL ES 3")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val max = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, max, 0)
        maxTile = min(2048, max[0])
        program = LookProgram(external = false)
    }

    fun process(bmp: Bitmap, look: Look) {
        setUp()
        EGL14.eglMakeCurrent(display, surface, surface, context)
        val p = program!!
        val hasLut = p.useLut(look.lutKey, store)

        val w = bmp.width
        val h = bmp.height
        val tile = maxTile
        val tex = IntArray(1)
        val fbTex = IntArray(1)
        val fb = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glGenTextures(1, fbTex, 0)
        GLES30.glGenFramebuffers(1, fb, 0)
        val pixels = ByteBuffer.allocateDirect(tile * tile * 4).order(ByteOrder.nativeOrder())
        val canvas = Canvas(bmp)
        val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        try {
            var y = 0
            while (y < h) {
                val th = min(tile, h - y)
                var x = 0
                while (x < w) {
                    val tw = min(tile, w - x)
                    val part = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
                    Canvas(part).drawBitmap(bmp, Rect(x, y, x + tw, y + th), Rect(0, 0, tw, th), null)

                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                    GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, part, 0)

                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fbTex[0])
                    GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
                    GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fbTex[0], 0)
                    GLES30.glViewport(0, 0, tw, th)

                    val tileRect = floatArrayOf(x.toFloat() / w, y.toFloat() / h, tw.toFloat() / w, th.toFloat() / h)
                    p.draw(
                        tex[0], LookProgram.IDENTITY4, LookProgram.IDENTITY3, LookProgram.FULL, tileRect,
                        h.toFloat() / w, look, hasLut, false,
                    )
                    pixels.position(0)
                    GLES30.glReadPixels(0, 0, tw, th, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
                    pixels.position(0)
                    part.copyPixelsFromBuffer(pixels)
                    canvas.drawBitmap(part, x.toFloat(), y.toFloat(), copyPaint)
                    part.recycle()
                    x += tw
                }
                y += th
            }
        } finally {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glDeleteFramebuffers(1, fb, 0)
            GLES30.glDeleteTextures(1, tex, 0)
            GLES30.glDeleteTextures(1, fbTex, 0)
        }
    }

    /** A new LUT was installed. */
    fun resetLut() {
        program?.resetLut()
    }
}
