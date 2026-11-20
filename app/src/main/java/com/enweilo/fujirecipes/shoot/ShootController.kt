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
import kotlin.math.abs
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

    /** Looks at the LUT folder again (cheap). */
    fun refreshLuts() {
        val now = luts.installed()
        if (now != installed) {
            installed = now
            lutVersion++
        }
    }

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
    var shutterSoundOn by mutableStateOf(prefs.getBoolean("shutterSound", true))
        private set
    var useFront by mutableStateOf(false)
        private set

    val feedback = Feedback(app)

    fun changeShutterSound(on: Boolean) { shutterSoundOn = on; prefs.edit().putBoolean("shutterSound", on).apply() }

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

    /** How far the on-screen labels are turned so they read upright: 0, 90 or -90 degrees. */
    var uiTurn by mutableIntStateOf(0)
        private set

    /** From an OrientationEventListener (degrees the phone is turned clockwise). */
    fun onOrientation(o: Int) {
        if (o < 0) return
        // a little stickiness so it does not flip back and forth around 45°
        val current = when (targetRotation) { Surface.ROTATION_270 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_90 -> 270; else -> 0 }
        val diff = ((o - current + 540) % 360) - 180
        if (kotlin.math.abs(diff) < 55) return
        val (rot, turn) = when (o) {
            in 45..134 -> Surface.ROTATION_270 to -90
            in 135..224 -> Surface.ROTATION_180 to uiTurn
            in 225..314 -> Surface.ROTATION_90 to 90
            else -> Surface.ROTATION_0 to 0
        }
        targetRotation = rot
        uiTurn = turn
    }

    /** One way of looking: a lens the phone lets apps open, or a zoom step on the main camera. */
    class Lens(
        val ratio: Float,
        val selector: CameraSelector?,
        /** for a zoom step on the main camera (a phone that switches lenses by itself) */
        val zoomRatio: Float? = null,
    ) {
        val label: String get() = if (ratio < 0.95f) "%.1f".format(ratio).trimEnd('0').trimEnd('.') else "%.0f×".format(ratio).let {
            if (kotlin.math.abs(ratio - ratio.roundToInt()) > 0.05f) "%.1f×".format(ratio) else it
        }
    }

    /** Back lenses found on this phone, sorted wide to long. Only one = no real lens switching. */
    var lenses by mutableStateOf<List<Lens>>(emptyList())
        private set
    var lensIndex by mutableIntStateOf(0)
        private set
    /** What the phone told us about its cameras, for 設定 → 相機資訊. */
    var cameraReport by mutableStateOf<List<String>>(emptyList())
        private set
    /** Bumps when the camera is restarted (lens or front/back), for the switch animation. */
    var cameraSwitchCount by mutableIntStateOf(0)
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
            if (lenses.isEmpty()) discoverLenses(p)
            if (!hiddenChecked) {
                hiddenChecked = true
                hiddenId = runCatching { Camera2Backend.findHiddenCombined(app) }.getOrNull()
            }
            if (bound?.second === view) start(owner, view)
        }, ContextCompat.getMainExecutor(app))
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleWatch)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(lifecycleWatch)
    }

    // ---------------- hidden combined camera (Camera2) ----------------

    private var hiddenChecked = false
    private var hiddenId: String? = null
    /** Set when the hidden camera refused to work; then we stay with the normal camera. */
    private var hiddenFailed = false
    private var c2b: Camera2Backend? = null
    /** true while the back camera runs through the hidden combined camera. */
    var usingHidden by mutableStateOf(false)
        private set
    private var lifecycleOwner: LifecycleOwner? = null
    private var reopenOnStart = false
    private val lifecycleWatch = androidx.lifecycle.LifecycleEventObserver { _, e ->
        when (e) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (c2b != null) {
                c2b?.close(); c2b = null; cameraReady = false; reopenOnStart = true
            }
            androidx.lifecycle.Lifecycle.Event.ON_START -> if (reopenOnStart) {
                reopenOnStart = false
                bound?.let { start(it.first, it.second) }
            }
            else -> {}
        }
    }

    private fun mainBackEquivalent(): Float? = runCatching {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        Camera2Backend.equivalent(cm.getCameraCharacteristics(id))
    }.getOrNull()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startHidden(owner: LifecycleOwner, view: LookPreview, id: String, attempt: Int = 0) {
        val wasCameraX = camera != null
        provider?.unbindAll()
        camera = null; capture = null; preview = null
        c2b?.close()
        c2b = null
        cameraReady = false
        if (wasCameraX && attempt == 0) {
            // give CameraX a moment to release the picture surface
            mainHandler.postDelayed({ if (bound?.second === view && !useFront) startHidden(owner, view, id, 1) }, 350)
            return
        }
        val b = try {
            Camera2Backend(app, id)
        } catch (_: Throwable) {
            hiddenFailed = true
            start(owner, view)
            return
        }
        c2b = b
        zoomMin = b.zoomRange.first
        zoomMax = b.zoomRange.second
        zoom = 1f
        evStep = b.evStep
        evMin = b.evRange.first * evStep
        evMax = b.evRange.second * evStep
        val ratios = b.lensRatios(mainBackEquivalent())
        lenses = ratios.map { Lens(it, null, it) }
        lensIndex = ratios.indexOfFirst { abs(it - 1f) < 0.01f }.coerceAtLeast(0)
        b.zoom = 1f
        b.evIndex = (ev / evStep).roundToInt()
        b.flash = flashCode()
        b.awbMode = currentRecipe()?.let { awbMode(it) } ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        val size = b.previewSize()
        view.directSurface(size.width, size.height, b.sensorOrientation) { surface ->
            if (c2b !== b) return@directSurface
            b.open(surface) { ok ->
                if (c2b !== b) return@open
                if (ok) {
                    usingHidden = true
                    cameraReady = true
                    if (cameraReport.none { it.startsWith("使用未公開") }) {
                        cameraReport = cameraReport + ("使用未公開的組合相機 $id：" + lenses.joinToString("／") { it.label })
                    }
                } else if (attempt < 2) {
                    // the picture surface may still be held by the camera we just left; try again shortly
                    b.close()
                    c2b = null
                    mainHandler.postDelayed({ if (bound?.second === view && !useFront) startHidden(owner, view, id, attempt + 1) }, 400)
                } else {
                    b.close()
                    c2b = null
                    hiddenFailed = true
                    usingHidden = false
                    lenses = emptyList()
                    toast("隱藏鏡頭打不開，改用一般模式")
                    start(owner, view)
                }
            }
        }
    }

    private fun flashCode() = when (flash) { FlashMode.Auto -> 1; FlashMode.On -> 2; FlashMode.Torch -> 3; else -> 0 }

    private fun c2(info: androidx.camera.core.CameraInfo) = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)

    /** Equivalent focal length in mm (35 mm film), or null. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun equivalent(info: androidx.camera.core.CameraInfo): Float? = runCatching {
        val c = c2(info)
        val f = c.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val size = c.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        val diag = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
        if (diag <= 0f) null else f * 43.27f / diag
    }.getOrNull()

    /**
     * Looks for every back lens an app may use: cameras the phone lists on their own, and the lenses hidden
     * inside a combined ("logical") camera.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun discoverLenses(p: ProcessCameraProvider) {
        val report = mutableListOf<String>()
        val all = runCatching { p.availableCameraInfos }.getOrDefault(emptyList())
        val main = runCatching { CameraSelector.DEFAULT_BACK_CAMERA.filter(all).firstOrNull() }.getOrNull()
        val mainEq = main?.let { equivalent(it) }
        class Found(val eq: Float, val selector: CameraSelector, val physical: Boolean, val intrinsic: Float)
        val found = mutableListOf<Found>()
        for (info in all) {
            val id = runCatching { c2(info).cameraId }.getOrDefault("?")
            val facing = when (info.lensFacing) {
                CameraSelector.LENS_FACING_BACK -> "後"
                CameraSelector.LENS_FACING_FRONT -> "前"
                else -> "外接"
            }
            val eq = equivalent(info)
            val physicals = runCatching { info.physicalCameraInfos }.getOrDefault(emptySet())
            report += "相機 $id（$facing）：約 ${eq?.roundToInt() ?: "?"} mm" +
                (if (info == main) "，預設主鏡頭" else "") +
                (if (physicals.size > 1) "，內含 ${physicals.size} 顆實體鏡頭" else "")
            if (info.lensFacing != CameraSelector.LENS_FACING_BACK) continue
            val byId = CameraSelector.Builder().addCameraFilter { list -> list.filter { runCatching { c2(it).cameraId }.getOrNull() == id } }
            if (eq != null) found += Found(eq, byId.build(), false, info.intrinsicZoomRatio)
            if (physicals.size > 1) {
                for (ph in physicals) {
                    val pid = runCatching { c2(ph).cameraId }.getOrNull() ?: continue
                    val peq = equivalent(ph)
                    report += "　└ 實體鏡頭 $pid：約 ${peq?.roundToInt() ?: "?"} mm"
                    if (peq == null) continue
                    val sel = CameraSelector.Builder()
                        .addCameraFilter { list -> list.filter { runCatching { c2(it).cameraId }.getOrNull() == id } }
                        .setPhysicalCameraId(pid)
                        .build()
                    found += Found(peq, sel, true, androidx.camera.core.CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN)
                }
            }
        }
        val base = mainEq ?: found.firstOrNull()?.eq ?: 0f
        val groups = mutableListOf<Found>()
        for (f in found.sortedWith(compareBy<Found> { it.physical }.thenBy { it.eq })) {
            // the same focal length found twice (own camera + inside the combined one): keep the first
            if (groups.none { kotlin.math.abs(it.eq - f.eq) / it.eq < 0.06f }) groups += f
        }
        lenses = if (base <= 0f || groups.size < 2) emptyList() else groups.sortedBy { it.eq }.map { g ->
            val r = if (g.intrinsic > 0f && !g.physical) g.intrinsic else g.eq / base
            Lens(r, g.selector)
        }
        lensIndex = lenses.indexOfFirst { kotlin.math.abs(it.ratio - 1f) < 0.06f }.coerceAtLeast(0)
        report += if (lenses.size >= 2) "可切換鏡頭：" + lenses.joinToString("、") { it.label }
        else "手機沒有開放其他後鏡頭給 App 使用，放大是數位裁切"
        cameraReport = report
    }

    // ---------------- hidden lenses ----------------

    /** Lines from the last hidden-lens probe, for 設定 → 相機資訊. */
    var probeReport by mutableStateOf<List<String>>(emptyList())
        private set
    var probing by mutableStateOf(false)
        private set

    /**
     * Some phones list only cameras 0 and 1 but still let apps open others by number. Tries 0–15 straight through
     * Camera2 (outside CameraX), reports what each one is and whether it really opens. Call while the camera is
     * not in use (from the settings page).
     */
    @SuppressLint("MissingPermission")
    fun probeHiddenCameras() {
        if (probing) return
        if (ContextCompat.checkSelfPermission(app, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("請先在拍照分頁允許相機權限")
            return
        }
        probing = true
        probeReport = listOf("尋找中…")
        scope.launch {
            val lines = withContext(Dispatchers.IO) { runCatching { probe() }.getOrElse { listOf("失敗：${it.message}") } }
            probeReport = lines
            probing = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun probe(): List<String> {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val listed = runCatching { cm.cameraIdList.toSet() }.getOrDefault(emptySet())
        val out = mutableListOf("系統公開的相機：" + listed.sorted().joinToString("、").ifEmpty { "（無）" })
        val thread = android.os.HandlerThread("probe").apply { start() }
        val handler = android.os.Handler(thread.looper)
        var hiddenOk = 0
        try {
            for (n in 0..15) {
                val id = n.toString()
                val ch = try {
                    cm.getCameraCharacteristics(id)
                } catch (_: Throwable) {
                    continue
                }
                val facing = when (ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)) {
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK -> "後"
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT -> "前"
                    else -> "外接"
                }
                val f = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                val size = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (f != null && size != null) {
                    val d = kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
                    if (d > 0f) (f * 43.27f / d).roundToInt() else null
                } else null
                val caps = ch.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
                val normal = android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps
                val logical = Build.VERSION.SDK_INT >= 28 &&
                    android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
                val physical = if (Build.VERSION.SDK_INT >= 28) runCatching { ch.physicalCameraIds }.getOrDefault(emptySet()) else emptySet()
                val hidden = id !in listed
                val opens = if (hidden) tryOpen(cm, id, handler) else null
                if (hidden && opens == true && facing == "後") hiddenOk++
                out += buildString {
                    append("相機 $id（$facing）約 ${eq ?: "?"} mm")
                    append(if (hidden) "，未公開" else "，公開")
                    if (!normal) append("，非一般拍照用")
                    if (logical) append("，組合相機：${physical.sorted().joinToString("、")}")
                    when (opens) {
                        true -> append(" → 打得開 ✓")
                        false -> append(" → 打不開 ✗")
                        null -> {}
                    }
                }
            }
            // physical lenses inside the public cameras, as Camera2 sees them
            for (id in listed) {
                val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
                if (Build.VERSION.SDK_INT < 28) break
                for (pid in runCatching { ch.physicalCameraIds }.getOrDefault(emptySet())) {
                    val pc = runCatching { cm.getCameraCharacteristics(pid) }.getOrNull() ?: continue
                    val f = pc.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                    val size = pc.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val eq = if (f != null && size != null) (f * 43.27f / kotlin.math.hypot(size.width.toDouble(), size.height.toDouble()).toFloat()).roundToInt() else null
                    out += "　相機 $id 裡的實體鏡頭 $pid：約 ${eq ?: "?"} mm"
                }
            }
        } finally {
            thread.quitSafely()
        }
        out += if (hiddenOk > 0) "找到 $hiddenOk 顆未公開但打得開的後鏡頭" else "沒有找到可用的隱藏後鏡頭"
        return out
    }

    /** Opens camera [id] for a moment. true = opened, false = refused. */
    @SuppressLint("MissingPermission")
    private fun tryOpen(cm: android.hardware.camera2.CameraManager, id: String, handler: android.os.Handler): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        var ok = false
        try {
            cm.openCamera(id, object : android.hardware.camera2.CameraDevice.StateCallback() {
                override fun onOpened(camera: android.hardware.camera2.CameraDevice) { ok = true; camera.close(); done.countDown() }
                override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) { camera.close(); done.countDown() }
                override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) { camera.close(); done.countDown() }
            }, handler)
        } catch (_: Throwable) {
            return false
        }
        done.await(4, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    /** Switch to lens [i] of [lenses]. */
    fun selectLens(i: Int) {
        val l = lenses.getOrNull(i) ?: return
        if (i == lensIndex && !useFront) return
        lensIndex = i
        useFront = false
        if (l.zoomRatio != null) {
            changeZoom(l.zoomRatio)
            return
        }
        cameraSwitchCount++
        val b = bound ?: return
        start(b.first, b.second)
    }

    /** Zoom as the photographer sees it: relative to the main lens. */
    val shownZoom: Float get() {
        val l = lenses.getOrNull(lensIndex)
        return if (useFront || l == null || l.zoomRatio != null) zoom else l.ratio * zoom
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun start(owner: LifecycleOwner, view: LookPreview) {
        val p = provider ?: return
        val hid = hiddenId
        if (!useFront && hid != null && !hiddenFailed) {
            startHidden(owner, view, hid)
            return
        }
        if (c2b != null) {
            // let the directly driven camera let go before CameraX takes over
            c2b?.close()
            c2b = null
            usingHidden = false
            cameraReady = false
            mainHandler.postDelayed({ if (bound?.second === view) start(owner, view) }, 350)
            return
        }
        usingHidden = false
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
        val lens = lenses.getOrNull(lensIndex)
        val wanted = when {
            useFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            lens?.selector != null -> lens.selector
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }
        val cam = try {
            p.bindToLifecycle(owner, wanted, pv, ic)
        } catch (e: Throwable) {
            if (wanted !== CameraSelector.DEFAULT_BACK_CAMERA && !useFront) {
                // this lens is closed to apps; go back to the main one
                toast("這顆鏡頭手機不開放給 App 使用")
                lensIndex = lenses.indexOfFirst { kotlin.math.abs(it.ratio - 1f) < 0.06f }.coerceAtLeast(0)
                try {
                    p.unbindAll()
                    p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pv, ic)
                } catch (e2: Throwable) {
                    toast("相機啟動失敗：" + (e2.message ?: ""))
                    return
                }
            } else {
                toast("相機啟動失敗：" + (e.message ?: ""))
                return
            }
        }
        camera = cam
        capture = ic
        preview = pv
        cameraReady = true

        cam.cameraInfo.zoomState.value?.let { zoomMin = it.minZoomRatio; zoomMax = it.maxZoomRatio; zoom = it.zoomRatio }
        // a combined camera that can zoom below 1× switches to its wide lens by itself
        if (!useFront && lenses.isEmpty() && zoomMin < 0.95f) {
            lenses = listOf(Lens(zoomMin, null, zoomMin), Lens(1f, null, 1f))
            lensIndex = 1
            cameraReport = cameraReport + "主相機可縮小到 ${"%.1f".format(zoomMin)}×（手機自動切換超廣角）"
        }
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
        c2b?.close()
        c2b = null
        reopenOnStart = false
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleWatch)
        lifecycleOwner = null
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
        cameraSwitchCount++
        val b = bound ?: return
        start(b.first, b.second)
    }

    fun changeZoom(r: Float) {
        val v = r.coerceIn(zoomMin, max(zoomMin, zoomMax))
        zoom = v
        c2b?.let { it.zoom = v; it.repeat(); return }
        camera?.cameraControl?.setZoomRatio(v)
    }

    fun changeEv(v: Float) {
        val idx = (v / evStep).roundToInt()
        c2b?.let { b ->
            val i = idx.coerceIn(b.evRange.first, b.evRange.second)
            ev = i * evStep
            b.evIndex = i
            b.repeat()
            return
        }
        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        val clamped = if (range != null && !(range.lower == 0 && range.upper == 0)) idx.coerceIn(range.lower, range.upper) else idx
        ev = clamped * evStep
        if (range != null && !(range.lower == 0 && range.upper == 0)) {
            camera?.cameraControl?.setExposureCompensationIndex(clamped)
        }
    }

    /** [x], [y] on the whole upright frame as shown on [display], 0..1 from its top-left. */
    fun focusAt(display: android.view.Display?, x: Float, y: Float, onResult: (Boolean) -> Unit = {}) {
        c2b?.let { b ->
            // upright frame (screen held upright) → sensor picture
            val (sx, sy) = when (b.sensorOrientation) {
                90 -> y to 1f - x
                180 -> 1f - x to 1f - y
                270 -> 1f - y to x
                else -> x to y
            }
            b.focus(sx, sy, onResult)
            return
        }
        val cam = camera ?: return
        val factory = if (display != null) {
            androidx.camera.core.DisplayOrientedMeteringPointFactory(display, cam.cameraInfo, 1f, 1f)
        } else {
            SurfaceOrientedMeteringPointFactory(1f, 1f)
        }
        val point = factory.createPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            val ok = runCatching { f.get().isFocusSuccessful }.getOrDefault(false)
            onResult(ok)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun applyFlash() {
        c2b?.let { it.flash = flashCode(); it.repeat(); return }
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
        c2b?.let { b ->
            b.awbMode = currentRecipe()?.let { awbMode(it) } ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
            b.repeat()
            return
        }
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
        if (capturing || countdown > 0 || (capture == null && c2b == null) || !cameraReady) return
        if (timer > 0) {
            timerJob = scope.launch {
                for (s in timer downTo 1) {
                    countdown = s
                    if (shutterSoundOn) feedback.beep()
                    feedback.tick()
                    delay(1000)
                }
                countdown = 0
                takePicture()
            }
        } else {
            takePicture()
        }
    }

    private var timerJob: kotlinx.coroutines.Job? = null

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        countdown = 0
        feedback.click()
    }

    private fun takePicture() {
        c2b?.let { takeHidden(it); return }
        val ic = capture ?: return
        val recipe = currentRecipe()
        val look = currentLook()
        val ratioNow = ratio
        val keepOriginal = saveOriginal
        // which way the photo must turn: worked out from the phone's position now, not from what the camera writes
        val info = camera?.cameraInfo
        val wantTurn = info?.getSensorRotationDegrees(targetRotation) ?: 0
        val sensorTurn = info?.getSensorRotationDegrees(Surface.ROTATION_0) ?: 0
        capturing = true
        shutterCount++
        feedback.shutter()
        if (shutterSoundOn) feedback.shutterSound()
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
                        runCatching { develop(bytes, rotation, wantTurn, sensorTurn, recipe, look, ratioNow, keepOriginal) }
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

    /** Which lens the zoom ratio lands on, for EXIF. */
    private fun lensName(): String {
        val r = zoom
        val tele = lenses.lastOrNull()?.ratio ?: 99f
        return when {
            r < 0.95f -> "超廣角"
            tele > 1.3f && r >= tele - 0.02f -> "長焦"
            else -> "主鏡頭"
        } + " %.1fx".format(r)
    }

    private fun takeHidden(b: Camera2Backend) {
        val recipe = currentRecipe()
        val look = currentLook()
        val ratioNow = ratio
        val keepOriginal = saveOriginal
        val screen = when (targetRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        val turn = ((b.sensorOrientation - screen) % 360 + 360) % 360
        val lens = lensName()
        capturing = true
        shutterCount++
        feedback.shutter()
        if (shutterSoundOn) feedback.shutterSound()
        b.capture { bytes ->
            capturing = false
            if (bytes == null) { toast("拍照失敗"); return@capture }
            processing++
            scope.launch {
                val result = withContext(gl) {
                    runCatching { develop(bytes, 0, turn, b.sensorOrientation, recipe, look, ratioNow, keepOriginal, forceTurn = turn, lens = lens) }
                }
                processing--
                result.onSuccess { (uri, thumb) ->
                    lastUri = uri
                    lastThumb = thumb
                }.onFailure { e -> toast("儲存失敗：" + (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** Crops, applies the recipe, writes EXIF and saves. Runs on the GL thread. */
    @SuppressLint("NewApi")
    private fun develop(
        bytes: ByteArray, rotation: Int, wantTurn: Int, sensorTurn: Int,
        recipe: Recipe?, look: Look, ratio: Ratio, keepOriginal: Boolean,
        forceTurn: Int? = null, lens: String? = null,
    ): Pair<Uri?, Bitmap?> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val srcExif = ExifInterface(ByteArrayInputStream(bytes))
        val written = srcExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val dims = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dims)
        val tall = dims.outHeight > dims.outWidth
        val sideways = sensorTurn % 180 != 0
        val turn = if (forceTurn != null) forceTurn else when {
            // A sideways sensor gives a wide picture. A tall one was already stood up for an upright phone
            // (some phones always do that, whichever way the phone is held), so only the rest is left.
            sideways && tall -> ((wantTurn - sensorTurn) % 360 + 360) % 360
            // the phone turned a wide picture itself and says nothing more is needed
            (written == ExifInterface.ORIENTATION_NORMAL || written == ExifInterface.ORIENTATION_UNDEFINED) && rotation == 0 -> 0
            // pixels exactly as the sensor delivers them
            else -> wantTurn
        }
        val orientation = when (turn) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }

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
        if (lens != null) ex.setAttribute(ExifInterface.TAG_LENS_MODEL, lens)
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
