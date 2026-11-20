package com.enweilo.fujirecipes.shoot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Live viewfinder: camera frames go into an OpenGL texture and are drawn through [LookProgram] into this
 * TextureView, so what you see is already the recipe. A TextureView (not a SurfaceView) so it moves, fades
 * and clips together with the rest of the screen while swiping between tabs.
 */
@SuppressLint("ViewConstructor")
class LookPreview(context: Context, private val store: LutStore) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile var look: Look = Look.NONE
        set(v) { field = v; redraw() }
    @Volatile var original = false
        set(v) { field = v; redraw() }
    /** Shape of the photo; the camera picture is cropped to it. */
    @Volatile var ratio: Ratio = Ratio.R3x2
        set(v) { field = v; updateCrop(); redraw() }
    @Volatile private var crop: FloatArray = LookProgram.FULL
    /** short / long side of the camera picture */
    @Volatile private var frame = 3f / 4f

    private fun updateCrop() {
        val target = ratio.short / ratio.long
        crop = if (target < frame) {
            val du = target / frame
            floatArrayOf((1f - du) / 2f, 0f, du, 1f)
        } else {
            val dv = frame / target
            floatArrayOf(0f, (1f - dv) / 2f, 1f, dv)
        }
    }

    @Volatile private var rotationDeg = 90
    @Volatile private var screenOnlyDeg = 0
    @Volatile private var cameraTransform = false
    @Volatile private var mirror = false
    @Volatile private var lutDirty = false

    /** Called on the main thread when the first camera picture is drawn. */
    var onFirstFrame: (() -> Unit)? = null

    private val thread = HandlerThread("viewfinder").apply { start() }
    private val gl = Handler(thread.looper)

    // GL-thread state
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: LookProgram? = null
    private var oesTex = 0
    private var camTexture: SurfaceTexture? = null
    private val texMat = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    private var pending: SurfaceRequest? = null
    private var gotFrame = false
    private var frameReady = false

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    fun setMirror(on: Boolean) {
        mirror = on
    }

    /**
     * A point on this view ([nx], [ny] from the top-left, 0..1) as a point on the whole upright camera frame
     * (before cropping to the photo shape), for tap-to-focus.
     */
    fun toFrame(nx: Float, ny: Float): Pair<Float, Float> {
        val c = crop
        val fx = c[0] + nx * c[2]
        val fyUp = c[1] + (1f - ny) * c[3]
        return fx to (1f - fyUp)
    }

    fun reloadLut() {
        lutDirty = true
        redraw()
    }

    /** Hands CameraX a surface backed by our texture. */
    val surfaceProvider = Preview.SurfaceProvider { request ->
        val mirrorNow = mirror
        request.setTransformationInfoListener(ContextCompat.getMainExecutor(context)) { info ->
            // When the camera writes its orientation into the SurfaceTexture (most phones), the texture matrix
            // already stands the picture upright; only the screen's own rotation is left, and the screen is held
            // upright while shooting. Otherwise turn it by the rotation CameraX reports.
            val screen = when (info.targetRotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            screenOnlyDeg = (360 - screen) % 360
            rotationDeg = info.rotationDegrees
            cameraTransform = info.hasCameraTransform()
            mirror = info.isMirroring || mirrorNow
            redraw()
        }
        gl.post { attach(request) }
    }

    private fun attach(request: SurfaceRequest) {
        val st = camTexture
        if (st == null) {
            pending?.willNotProvideSurface()
            pending = request
            return
        }
        gotFrame = false
        frameReady = false
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        frame = minOf(res.width, res.height).toFloat() / maxOf(res.width, res.height)
        updateCrop()
        val surface = Surface(st)
        request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { surface.release() }
    }

    private fun redraw() {
        gl.post { draw() }
    }

    // ---------------- TextureView callbacks (main thread) ----------------

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        gl.post { setUp(surface, width, height) }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        gl.post { viewW = width; viewH = height; draw() }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        gl.post { tearDown() }
        return false.also { gl.post { surface.release() } }
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    /** Stops the drawing thread for good. */
    fun release() {
        gl.post {
            tearDown()
            thread.quitSafely()
        }
    }

    // ---------------- GL thread ----------------

    private fun setUp(output: SurfaceTexture, width: Int, height: Int) {
        viewW = width
        viewH = height
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: return
        eglContext = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        window = EGL14.eglCreateWindowSurface(display, config, output, intArrayOf(EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, window, window, eglContext)

        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        camTexture = SurfaceTexture(oesTex).apply {
            setOnFrameAvailableListener({ frameReady = true; draw() }, gl)
        }
        program = try {
            LookProgram(external = true)
        } catch (e: Throwable) {
            android.util.Log.e("LookPreview", "shader", e)
            null
        }
        pending?.let { pending = null; attach(it) }
        draw()
    }

    private fun draw() {
        if (window == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, window, window, eglContext)
        val st = camTexture ?: return
        if (frameReady) {
            try {
                st.updateTexImage()
            } catch (_: Throwable) {
                return
            }
            st.getTransformMatrix(texMat)
        }
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val p = program
        if (frameReady && p != null) {
            if (lutDirty) {
                lutDirty = false
                p.resetLut()
            }
            val l = look
            val hasLut = p.useLut(l.lutKey, store)
            p.draw(
                oesTex, texMat, LookProgram.rotation(effectiveRotation(), mirror), crop, LookProgram.FULL,
                viewH.toFloat() / viewW, l, hasLut, original,
            )
        }
        EGL14.eglSwapBuffers(display, window)
        if (frameReady && !gotFrame) {
            gotFrame = true
            post { onFirstFrame?.invoke() }
        }
    }

    /**
     * How far we still need to turn the picture. If the camera already put its orientation into the texture
     * matrix (it then swaps the x and y axes for a sideways sensor), only the screen rotation is left.
     */
    private fun effectiveRotation(): Int {
        val matrixTurns = abs(texMat[0]) < 0.5f && abs(texMat[1]) > 0.5f
        return if (cameraTransform || matrixTurns) screenOnlyDeg else rotationDeg
    }

    private fun tearDown() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, window, window, eglContext)
        program?.release()
        program = null
        camTexture?.release()
        camTexture = null
        if (oesTex != 0) GLES30.glDeleteTextures(1, intArrayOf(oesTex), 0)
        oesTex = 0
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window)
        EGL14.eglDestroyContext(display, eglContext)
        window = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }
}
