package com.enweilo.fujirecipes.shoot

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.enweilo.fujirecipes.data.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Ratio(val label: String, val long: Float, val short: Float) {
    R3x2("3:2", 3f, 2f), R4x3("4:3", 4f, 3f), R16x9("16:9", 16f, 9f), R1x1("1:1", 1f, 1f);

    val value: Float get() = long / short
}

enum class FlashMode(val label: String) { Off("關"), Auto("自動"), On("開"), Torch("恆亮") }

/** State and camera work for the 拍照 tab. */
class ShootController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    private val recipes: () -> List<Recipe>,
) {
    private val prefs = app.getSharedPreferences("shoot", Context.MODE_PRIVATE)
    val luts = LutStore(app)

    // ---------------- LUTs ----------------

    var installed by mutableStateOf(luts.installed())
        private set
    var lutBusy by mutableStateOf(false)
        private set
    var lutProgress by mutableFloatStateOf(0f)
        private set
    var lutText by mutableStateOf("")
        private set
    /** Size of Fujifilm's zip, -1 while unknown. */
    var lutSize by mutableLongStateOf(-1L)
        private set
    private var lutUrl: String? = null
    /** Bumped after a new install so the viewfinder reloads the textures. */
    var lutVersion by mutableIntStateOf(0)
        private set

    private val gl = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val processor = PhotoProcessor(luts)

    fun probeLutSize() {
        if (lutSize >= 0 || lutBusy) return
        scope.launch {
            val (url, size) = withContext(Dispatchers.IO) { luts.probeSize() }
            lutUrl = url
            lutSize = size
        }
    }

    fun downloadLuts() {
        if (lutBusy) return
        lutBusy = true
        lutProgress = 0f
        lutText = "連線到富士官網…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = lutUrl ?: luts.probeSize().first
                    luts.download(url) { f, t -> scope.launch { lutProgress = f; lutText = t } }
                }
            }
            finishInstall(result)
        }
    }

    fun importLuts(uris: List<Uri>) {
        if (lutBusy || uris.isEmpty()) return
        lutBusy = true
        lutProgress = 0.5f
        lutText = "讀取檔案…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { luts.importUris(uris) { f, t -> scope.launch { lutProgress = f; lutText = t } } }
            }
            finishInstall(result)
        }
    }

    private fun finishInstall(result: Result<Int>) {
        lutBusy = false
        installed = luts.installed()
        lutVersion++
        scope.launch(gl) { processor.resetLut() }
        result.onSuccess { n ->
            lutText = ""
            toast(if (n > 0) "已安裝 $n 種底片模擬" else "檔案裡沒有找到 F-Log2 的 LUT")
        }.onFailure { e ->
            lutText = "失敗：" + (e.message ?: e.javaClass.simpleName)
            toast("LUT 安裝失敗，可以改用手動匯入")
        }
    }

    fun removeLuts() {
        luts.clear()
        installed = emptySet()
        lutVersion++
        lutSize = -1
    }

    // ---------------- settings (kept between launches) ----------------

    var recipeId by mutableStateOf(prefs.getString("recipe", null))
        private set
    var ratio by mutableStateOf(Ratio.entries.getOrElse(prefs.getInt("ratio", 0)) { Ratio.R3x2 })
        private set
    var grid by mutableStateOf(prefs.getBoolean("grid", true))
        private set
    var level by mutableStateOf(prefs.getBoolean("level", false))
        private set
    var flash by mutableStateOf(FlashMode.entries.getOrElse(prefs.getInt("flash", 0)) { FlashMode.Off })
        private set
    var timer by mutableIntStateOf(prefs.getInt("timer", 0))
        private set
    var saveOriginal by mutableStateOf(prefs.getBoolean("saveOriginal", true))
        private set
    var volumeShutter by mutableStateOf(prefs.getBoolean("volumeShutter", true))
        private set
    var useFront by mutableStateOf(false)
        private set

    fun changeSaveOriginal(on: Boolean) { saveOriginal = on; prefs.edit().putBoolean("saveOriginal", on).apply() }
    fun changeVolumeShutter(on: Boolean) { volumeShutter = on; prefs.edit().putBoolean("volumeShutter", on).apply() }
    fun toggleGrid() { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() }
    fun toggleLevel() { level = !level; prefs.edit().putBoolean("level", level).apply() }
    fun nextRatio() { ratio = Ratio.entries[(ratio.ordinal + 1) % Ratio.entries.size]; prefs.edit().putInt("ratio", ratio.ordinal).apply() }
    fun nextTimer() { timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }; prefs.edit().putInt("timer", timer).apply() }
    fun nextFlash() {
        flash = FlashMode.entries[(flash.ordinal + 1) % FlashMode.entries.size]
        prefs.edit().putInt("flash", flash.ordinal).apply()
        applyFlash()
    }

    /** null = no recipe (plain phone picture). */
    fun selectRecipe(id: String?) {
        if (id == recipeId) return
        recipeId = id
        prefs.edit().putString("recipe", id).apply()
        val r = currentRecipe()
        applyWhiteBalance()
        // a recipe's suggested exposure compensation becomes the starting point
        changeEv(r?.let { Look.ev(it.ev) } ?: 0f)
    }

    fun currentRecipe(): Recipe? = recipes().firstOrNull { it.id == recipeId }

    fun currentLook(): Look = currentRecipe()?.let { Look.of(it) } ?: Look.NONE

    // ---------------- camera ----------------

    var cameraReady by mutableStateOf(false)
        private set
    var hasFront by mutableStateOf(false)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var zoomMin by mutableFloatStateOf(1f)
        private set
    var zoomMax by mutableFloatStateOf(1f)
        private set
    var ev by mutableFloatStateOf(0f)
        private set
    var evMin by mutableFloatStateOf(0f)
        private set
    var evMax by mutableFloatStateOf(0f)
        private set
    private var evStep = 1f / 3f

    var countdown by mutableIntStateOf(0)
        private set
    var capturing by mutableStateOf(false)
        private set
    /** Photos still being processed and saved. */
    var processing by mutableIntStateOf(0)
        private set
    var lastThumb by mutableStateOf<Bitmap?>(null)
        private set
    var lastUri by mutableStateOf<Uri?>(null)
        private set
    /** Increases on every shutter press, for the flash animation. */
    var shutterCount by mutableIntStateOf(0)
        private set

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var capture: ImageCapture? = null
    private var preview: Preview? = null
    private var bound: Pair<LifecycleOwner, LookPreview>? = null
    var targetRotation = Surface.ROTATION_0
        set(value) {
            field = value
            capture?.targetRotation = value
        }

    fun bind(owner: LifecycleOwner, view: LookPreview) {
        bound = owner to view
        val future = ProcessCameraProvider.getInstance(app)
        future.addListener({
            val p = try { future.get() } catch (_: Throwable) { toast("無法開啟相機"); return@addListener }
            provider = p
            hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            val back = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            if (!back && hasFront) useFront = true
            if (bound?.second === view) start(owner, view)
        }, ContextCompat.getMainExecutor(app))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun start(owner: LifecycleOwner, view: LookPreview) {
        val p = provider ?: return
        p.unbindAll()
        cameraReady = false
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val pv = Preview.Builder().setResolutionSelector(selector).build()
        val ic = ImageCapture.Builder()
            .setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetRotation(targetRotation)
            .setJpegQuality(97)
            .build()
        view.setMirror(useFront)
        pv.surfaceProvider = view.surfaceProvider
        val cam = try {
            p.bindToLifecycle(owner, if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
        } catch (e: Throwable) {
            toast("相機啟動失敗：" + (e.message ?: ""))
            return
        }
        camera = cam
        capture = ic
        preview = pv
        cameraReady = true

        cam.cameraInfo.zoomState.value?.let { zoomMin = it.minZoomRatio; zoomMax = it.maxZoomRatio; zoom = it.zoomRatio }
        cam.cameraInfo.zoomState.observe(owner) { z -> zoomMin = z.minZoomRatio; zoomMax = z.maxZoomRatio; zoom = z.zoomRatio }
        val es = cam.cameraInfo.exposureState
        evStep = es.exposureCompensationStep.toFloat().takeIf { it > 0f } ?: (1f / 3f)
        evMin = es.exposureCompensationRange.lower * evStep
        evMax = es.exposureCompensationRange.upper * evStep
        applyFlash()
        applyWhiteBalance()
        if (ev != 0f) changeEv(ev)
    }

    fun unbind() {
        bound = null
        provider?.unbindAll()
        camera = null
        capture = null
        preview = null
        cameraReady = false
    }

    fun switchCamera() {
        if (!hasFront) return
        useFront = !useFront
        zoom = 1f
        val b = bound ?: return
        start(b.first, b.second)
    }

    fun changeZoom(r: Float) {
        val v = r.coerceIn(zoomMin, max(zoomMin, zoomMax))
        zoom = v
        camera?.cameraControl?.setZoomRatio(v)
    }

    fun changeEv(v: Float) {
        val idx = (v / evStep).roundToInt()
        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        val clamped = if (range != null && !(range.lower == 0 && range.upper == 0)) idx.coerceIn(range.lower, range.upper) else idx
        ev = clamped * evStep
        if (range != null && !(range.lower == 0 && range.upper == 0)) {
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        }
    }

    /** [x], [y] in the camera picture, 0..1 from its top-left (sensor orientation). */
    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    private fun applyFlash() {
        val ic = capture ?: return
        ic.flashMode = when (flash) {
            FlashMode.Auto -> ImageCapture.FLASH_MODE_AUTO
            FlashMode.On -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        camera?.cameraControl?.enableTorch(flash == FlashMode.Torch)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyWhiteBalance() {
        val cam = camera ?: return
        val r = currentRecipe()
        val mode = if (r == null) CameraMetadata.CONTROL_AWB_MODE_AUTO else awbMode(r)
        val available = cam.cameraInfo.let {
            runCatching {
                androidx.camera.camera2.interop.Camera2CameraInfo.from(it)
                    .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            }.getOrNull()
        }
        val use = if (available == null || available.contains(mode)) mode else CameraMetadata.CONTROL_AWB_MODE_AUTO
        runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
                CaptureRequestOptions.Builder().setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, use).build(),
            )
        }
    }

    private fun awbMode(r: Recipe): Int = when (r.wb) {
        "晴天" -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
        "陰影" -> CameraMetadata.CONTROL_AWB_MODE_SHADE
        "螢光燈1", "螢光燈3" -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
        "螢光燈2" -> CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT
        "白熾燈" -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
        "色溫" -> {
            val k = r.kelvin.filter { it.isDigit() }.toIntOrNull() ?: 5500
            when {
                k < 3500 -> CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT
                k < 4600 -> CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT
                k < 5900 -> CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT
                k < 7000 -> CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                else -> CameraMetadata.CONTROL_AWB_MODE_SHADE
            }
        }
        else -> CameraMetadata.CONTROL_AWB_MODE_AUTO
    }

    // ---------------- taking a picture ----------------

    fun shutter() {
        if (capturing || countdown > 0 || capture == null) return
        if (timer > 0) {
            scope.launch {
                for (s in timer downTo 1) {
                    countdown = s
                    delay(1000)
                }
                countdown = 0
                takePicture()
            }
        } else {
            takePicture()
        }
    }

    fun cancelTimer() {
        countdown = 0
    }

    private fun takePicture() {
        val ic = capture ?: return
        val recipe = currentRecipe()
        val look = currentLook()
        val ratioNow = ratio
        val keepOriginal = saveOriginal
        capturing = true
        shutterCount++
        ic.takePicture(ContextCompat.getMainExecutor(app), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                capturing = false
                val rotation = image.imageInfo.rotationDegrees
                val bytes = try {
                    val buf = image.planes[0].buffer
                    ByteArray(buf.remaining()).also { buf.get(it) }
                } catch (_: Throwable) {
                    null
                } finally {
                    image.close()
                }
                if (bytes == null) { toast("拍照失敗"); return }
                processing++
                scope.launch {
                    val result = withContext(gl) {
                        runCatching { develop(bytes, rotation, recipe, look, ratioNow, keepOriginal) }
                    }
                    processing--
                    result.onSuccess { (uri, thumb) ->
                        lastUri = uri
                        lastThumb = thumb
                    }.onFailure { e ->
                        toast("儲存失敗：" + (e.message ?: e.javaClass.simpleName))
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                capturing = false
                toast("拍照失敗：" + (exception.message ?: ""))
            }
        })
    }

    /** Crops, applies the recipe, writes EXIF and saves. Runs on the GL thread. */
    @SuppressLint("NewApi")
    private fun develop(
        bytes: ByteArray, rotation: Int, recipe: Recipe?, look: Look, ratio: Ratio, keepOriginal: Boolean,
    ): Pair<Uri?, Bitmap?> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val srcExif = ExifInterface(ByteArrayInputStream(bytes))
        val written = srcExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val fromCamera = when (((rotation % 360) + 360) % 360) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
        // some phones write the orientation into the JPEG, some only tell CameraX
        val orientation = if (written == ExifInterface.ORIENTATION_UNDEFINED || written == ExifInterface.ORIENTATION_NORMAL) fromCamera else written

        if (keepOriginal) {
            if (orientation == written) {
                saveJpeg(bytes, "FUJI_${stamp}_原圖.jpg")
            } else {
                val f = File(app.cacheDir, "orig.jpg")
                f.writeBytes(bytes)
                ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                saveJpeg(f.readBytes(), "FUJI_${stamp}_原圖.jpg")
                f.delete()
            }
        }

        // crop in the sensor's own orientation; the ratio is long side : short side so it works both ways
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        val rect = cropRect(w, h, ratio)
        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
        var bmp = decoder.decodeRegion(rect, opts) ?: error("無法讀取照片")
        decoder.recycle()
        if (!bmp.isMutable) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
            bmp.recycle()
            bmp = copy
        }

        if (look !== Look.NONE) processor.process(bmp, look)

        val tmp = File(app.cacheDir, "shot.jpg")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val thumb = thumbnail(bmp, orientation)
        bmp.recycle()

        // EXIF: keep the camera's own data (exposure, lens…) and add which recipe was used
        val ex = ExifInterface(tmp.absolutePath)
        for (tag in COPY_TAGS) srcExif.getAttribute(tag)?.let { ex.setAttribute(tag, it) }
        ex.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        ex.setAttribute(ExifInterface.TAG_SOFTWARE, "富士食譜")
        if (recipe != null) {
            ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "${recipe.name.ifEmpty { "未命名" }} · ${recipe.filmSim}")
        }
        ex.saveAttributes()

        val uri = saveJpeg(tmp.readBytes(), "FUJI_$stamp.jpg")
        tmp.delete()
        return uri to thumb
    }

    private fun cropRect(w: Int, h: Int, ratio: Ratio): Rect {
        val long = max(w, h).toFloat()
        val short = min(w, h).toFloat()
        val frame = long / short
        var cl = long
        var cs = short
        if (ratio.value > frame) cs = long / ratio.value else cl = short * ratio.value
        val cw = (if (w >= h) cl else cs).roundToInt().coerceAtMost(w)
        val ch = (if (w >= h) cs else cl).roundToInt().coerceAtMost(h)
        val x = (w - cw) / 2
        val y = (h - ch) / 2
        return Rect(x, y, x + cw, y + ch)
    }

    private fun thumbnail(bmp: Bitmap, orientation: Int): Bitmap {
        val k = 240f / max(bmp.width, bmp.height)
        val small = Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * k).toInt()), max(1, (bmp.height * k).toInt()), true)
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return small
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
    }

    private fun saveJpeg(data: ByteArray, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/富士食譜")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("無法寫入相簿")
            resolver.openOutputStream(uri)?.use { it.write(data) } ?: error("無法寫入相簿")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: error("無法寫入")
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(data)
        return Uri.fromFile(file)
    }

    companion object {
        private val COPY_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        )
    }
}
