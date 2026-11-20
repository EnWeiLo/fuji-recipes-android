package com.enweilo.fujirecipes.shoot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Surface
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drives one camera directly through Camera2. Used for the phone's hidden combined back camera
 * (ultra-wide + main + tele behind one zoom ratio), which CameraX cannot open because the phone leaves it
 * out of the public camera list.
 */
class Camera2Backend(private val context: Context, val id: String) {

    companion object {
        /** Equivalent focal length (35 mm film) of a camera, or null. */
        fun equivalent(ch: CameraCharacteristics): Float? {
            val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
            val s = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            val d = hypot(s.width.toDouble(), s.height.toDouble()).toFloat()
            return if (d > 0f) f * 43.27f / d else null
        }

    }

    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val ch: CameraCharacteristics = cm.getCameraCharacteristics(id)
    private val thread = HandlerThread("camera2-$id").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    val sensorOrientation: Int = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val active: Rect = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    val zoomRange: Pair<Float, Float> = if (Build.VERSION.SDK_INT >= 30) {
        ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { it.lower to it.upper } ?: (1f to 1f)
    } else 1f to (ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
    val evRange: Pair<Int, Int> = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { it.lower to it.upper } ?: (0 to 0)
    val evStep: Float = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()?.takeIf { it > 0f } ?: (1f / 3f)
    private val hasFlash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    private val awbModes = ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
    private val afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()

    /** Biggest RAW picture this camera gives apps, or null when it does not share RAW. */
    val rawSize: Size? = run {
        val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
        if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return@run null
        ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.RAW_SENSOR)
            ?.maxByOrNull { it.width.toLong() * it.height }
    }
    /** Ask for RAW too (set before [open]). */
    @Volatile var wantRaw = true
    /** true when the running session really delivers RAW. */
    @Volatile var rawActive = false
        private set
    private var rawReader: ImageReader? = null
    private val shadingModes = ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)?.toSet() ?: emptySet()

    /** One picture: the camera's JPEG and, when available, the RAW frame with what is needed to develop it. */
    class Shot(val jpeg: ByteArray, val raw: RawFrame?)

    class RawFrame(
        val width: Int,
        val height: Int,
        /** RAW16 pixels, packed (row stride = width × 2), native byte order */
        val pixels: java.nio.ByteBuffer,
        val result: TotalCaptureResult,
        /** the lens that took it (inside a combined camera), or the camera itself */
        val chars: CameraCharacteristics,
        val physicalResult: CaptureResult?,
        val physicalId: String?,
    )

    // ---------------- settings the controller changes ----------------

    @Volatile var zoom = 1f
    @Volatile var evIndex = 0
    @Volatile var awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
    /** ImageCapture.FLASH_MODE_* style: 0 off, 1 auto, 2 on, 3 torch */
    @Volatile var flash = 0
    /** Clockwise degrees the JPEG must turn for the way the phone is held (filled at capture time). */

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var afRegions: Array<MeteringRectangle>? = null
    private var focusUntil = 0L
    private var closed = false

    var onError: ((String) -> Unit)? = null

    fun previewSize(): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1440, 1080)
        val sizes = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1440, 1080)
        return sizes.filter { abs(it.width * 3 - it.height * 4) < 8 && it.width <= 1920 }.maxByOrNull { it.width * it.height }
            ?: sizes.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
    }

    private fun jpegSize(): Size {
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(4000, 3000)
        val sizes = map.getOutputSizes(ImageFormat.JPEG) ?: return Size(4000, 3000)
        // the biggest 4:3 picture up to about 16 MP (bigger pictures are binned anyway and cost memory)
        return sizes.filter { abs(it.width * 3 - it.height * 4) < 16 && it.width.toLong() * it.height <= 16_500_000L }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
    }

    /** Opens the camera and starts the preview into [surface]. [onReady] runs on the main thread. */
    @SuppressLint("MissingPermission")
    fun open(surface: Surface, onReady: (Boolean) -> Unit) {
        previewSurface = surface
        val js = jpegSize()
        reader = ImageReader.newInstance(js.width, js.height, ImageFormat.JPEG, 2)
        val rs = rawSize
        rawReader = if (wantRaw && rs != null) runCatching { ImageReader.newInstance(rs.width, rs.height, ImageFormat.RAW_SENSOR, 2) }.getOrNull() else null
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (closed) { d.close(); return }
                    device = d
                    startSession(onReady)
                }
                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    device = null
                }
                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    device = null
                    main.post { onReady(false) }
                }
            }, handler)
        } catch (e: Throwable) {
            main.post { onReady(false) }
        }
    }

    @Suppress("DEPRECATION")
    private fun startSession(onReady: (Boolean) -> Unit) {
        val d = device ?: return
        val ps = previewSurface ?: return
        val r = reader ?: return
        val rr = rawReader
        try {
            val outs = if (rr != null) listOf(ps, r.surface, rr.surface) else listOf(ps, r.surface)
            d.createCaptureSession(outs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed) { s.close(); return }
                    session = s
                    rawActive = rr != null
                    repeat()
                    main.post { onReady(true) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (rr != null) {
                        // this camera cannot stream RAW next to the others; carry on without it
                        runCatching { rr.close() }
                        rawReader = null
                        startSession(onReady)
                        return
                    }
                    main.post { onReady(false) }
                }
            }, handler)
        } catch (e: Throwable) {
            main.post { onReady(false) }
        }
    }

    private fun CaptureRequest.Builder.common(still: Boolean) {
        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        if (Build.VERSION.SDK_INT >= 30) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.coerceIn(zoomRange.first, zoomRange.second))
        } else {
            val k = 1f / zoom.coerceAtLeast(1f)
            val w = (active.width() * k).toInt()
            val h = (active.height() * k).toInt()
            set(CaptureRequest.SCALER_CROP_REGION, Rect((active.width() - w) / 2, (active.height() - h) / 2, (active.width() + w) / 2, (active.height() + h) / 2))
        }
        set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex.coerceIn(evRange.first, evRange.second))
        set(CaptureRequest.CONTROL_AWB_MODE, if (awbMode in awbModes) awbMode else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        val focusing = afRegions != null && System.currentTimeMillis() < focusUntil
        if (focusing && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_REGIONS, afRegions)
            set(CaptureRequest.CONTROL_AE_REGIONS, afRegions)
        } else if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        when {
            flash == 3 && hasFlash -> {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH)
            }
            flash == 1 && hasFlash -> set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH)
            flash == 2 && hasFlash -> set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            else -> {
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            }
        }
        if (still) {
            set(CaptureRequest.JPEG_QUALITY, 97.toByte())
            set(CaptureRequest.JPEG_ORIENTATION, 0) // we turn the picture ourselves (EXIF)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY)
        }
    }

    /** Re-applies the current settings to the live preview. */
    fun repeat() {
        handler.post {
            val s = session ?: return@post
            val d = device ?: return@post
            val ps = previewSurface ?: return@post
            runCatching {
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(ps)
                b.common(false)
                s.setRepeatingRequest(b.build(), afWatcher, handler)
            }
        }
    }

    private var afCallback: ((Boolean) -> Unit)? = null
    private val afWatcher = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
            val cb = afCallback ?: return
            when (result.get(CaptureResult.CONTROL_AF_STATE)) {
                CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> { afCallback = null; main.post { cb(true) } }
                CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> { afCallback = null; main.post { cb(false) } }
            }
        }
    }

    /** Focus and meter at ([sx], [sy]) on the sensor picture (0..1 from its top-left). */
    fun focus(sx: Float, sy: Float, onResult: (Boolean) -> Unit) {
        handler.post {
            val s = session ?: return@post
            val d = device ?: return@post
            val ps = previewSurface ?: return@post
            val half = (min(active.width(), active.height()) * 0.08f).toInt()
            val cx = (sx.coerceIn(0f, 1f) * active.width()).toInt()
            val cy = (sy.coerceIn(0f, 1f) * active.height()).toInt()
            val r = Rect(max(0, cx - half), max(0, cy - half), min(active.width() - 1, cx + half), min(active.height() - 1, cy + half))
            afRegions = arrayOf(MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            focusUntil = System.currentTimeMillis() + 4000
            afCallback = onResult
            runCatching {
                s.stopRepeating()
                val cancel = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(ps); common(false)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                }
                s.capture(cancel.build(), null, handler)
                val start = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(ps); common(false)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                }
                s.capture(start.build(), afWatcher, handler)
            }
            repeat()
            // go back to continuous focus afterwards
            handler.postDelayed({ if (System.currentTimeMillis() >= focusUntil) { afRegions = null; repeat() } }, 4100)
        }
    }

    /** Takes a picture. [onShot] gets it on the main thread (null on failure). */
    fun capture(onShot: (Shot?) -> Unit) {
        handler.post {
            val s = session
            val d = device
            val r = reader
            val ps = previewSurface
            if (s == null || d == null || r == null || ps == null) { main.post { onShot(null) }; return@post }
            val rr = if (rawActive) rawReader else null

            var jpeg: ByteArray? = null
            var rawBuf: java.nio.ByteBuffer? = null
            var rawW = 0
            var rawH = 0
            var total: TotalCaptureResult? = null
            var delivered = false
            fun finish(failed: Boolean = false) {
                if (delivered) return
                if (failed) { delivered = true; main.post { onShot(null) }; return }
                val j = jpeg ?: return
                if (rr != null && (rawBuf == null || total == null)) return
                delivered = true
                val res = total
                val frame = if (rr != null && res != null && rawBuf != null) {
                    val pid = if (Build.VERSION.SDK_INT >= 29) res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                    val pc = pid?.let { runCatching { cm.getCameraCharacteristics(it) }.getOrNull() }
                    val pr = if (pid != null && Build.VERSION.SDK_INT >= 31) runCatching { res.physicalCameraTotalResults[pid] }.getOrNull()
                    else if (pid != null) @Suppress("DEPRECATION") runCatching { res.physicalCameraResults[pid] }.getOrNull() else null
                    RawFrame(rawW, rawH, rawBuf!!, res, pc ?: ch, pr, pid)
                } else null
                main.post { onShot(Shot(j, frame)) }
            }
            // stop waiting after a while; deliver what came (JPEG alone is fine)
            handler.postDelayed({ if (!delivered && jpeg != null) { rawBuf = null; delivered = true; val j = jpeg!!; main.post { onShot(Shot(j, null)) } } else if (!delivered) finish(true) }, 6000)

            r.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireNextImage() }.getOrNull()
                jpeg = img?.let {
                    val buf = it.planes[0].buffer
                    ByteArray(buf.remaining()).also { b -> buf.get(b) }
                }
                img?.close()
                rd.setOnImageAvailableListener(null, null)
                if (jpeg == null) finish(true) else finish()
            }, handler)
            rr?.setOnImageAvailableListener({ rd ->
                val img = runCatching { rd.acquireNextImage() }.getOrNull()
                if (img != null) {
                    runCatching {
                        val plane = img.planes[0]
                        val w = img.width
                        val h = img.height
                        val src = plane.buffer
                        val out = java.nio.ByteBuffer.allocateDirect(w * h * 2).order(java.nio.ByteOrder.nativeOrder())
                        val row = ByteArray(w * 2)
                        for (y in 0 until h) {
                            src.position(y * plane.rowStride)
                            src.get(row, 0, w * 2)
                            out.put(row)
                        }
                        out.position(0)
                        rawBuf = out; rawW = w; rawH = h
                    }
                    img.close()
                }
                rd.setOnImageAvailableListener(null, null)
                finish()
            }, handler)

            val resultWatch = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(ss: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                    total = result
                    finish()
                }
                override fun onCaptureFailed(ss: CameraCaptureSession, req: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    finish(true)
                }
            }

            fun shoot() {
                runCatching {
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    b.addTarget(r.surface)
                    if (rr != null) b.addTarget(rr.surface)
                    b.addTarget(ps)
                    b.common(true)
                    if (rr != null && CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON in shadingModes) {
                        b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                    }
                    s.capture(b.build(), resultWatch, handler)
                }.onFailure { finish(true) }
            }

            if ((flash == 1 || flash == 2) && hasFlash) {
                // let the camera measure the flash first
                var done = false
                val watcher = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(ss: CameraCaptureSession, req: CaptureRequest, result: TotalCaptureResult) {
                        if (done) return
                        val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                            ae == CaptureResult.CONTROL_AE_STATE_LOCKED
                        ) {
                            done = true
                            shoot()
                        }
                    }
                }
                runCatching {
                    val pre = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(ps); common(false)
                        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                    }
                    s.capture(pre.build(), watcher, handler)
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(ps); common(false) }
                    s.setRepeatingRequest(b.build(), watcher, handler)
                }
                handler.postDelayed({ if (!done) { done = true; shoot() }; repeat() }, 1200)
            } else {
                shoot()
            }
        }
    }

    fun close() {
        closed = true
        handler.post {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { rawReader?.close() }
            session = null
            device = null
            reader = null
            thread.quitSafely()
        }
    }
}
