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
    /** Film simulations whose original LUT is kept (needed for RAW). */
    var installedFlog by mutableStateOf(luts.installedFlog())
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
    private val rawDev = RawDeveloper(luts)

    /** Looks at the LUT folder again (cheap). */
    fun refreshLuts() {
        installedFlog = luts.installedFlog()
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
        installedFlog = luts.installedFlog()
        scope.launch(gl) { rawDev.resetLut() }
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

    /** Develop from RAW when the phone allows it. */
    var rawEnabled by mutableStateOf(prefs.getBoolean("raw", true))
        private set
    fun changeRaw(on: Boolean) {
        rawEnabled = on
        prefs.edit().putBoolean("raw", on).apply()
        bound?.let { start(it.first, it.second) }
    }

    /** Show the photo after taking it: 0 off, 1 = 1.5 s, 2 = 3 s, 3 = until closed. */
    var reviewMode by mutableIntStateOf(prefs.getInt("review", 1))
        private set
    fun changeReviewMode(m: Int) { reviewMode = m; prefs.edit().putInt("review", m).apply() }

    /** The photo just taken, shown over the viewfinder. */
    data class Review(
        val id: Int,
        val quick: Bitmap? = null,
        val final: Bitmap? = null,
        val uri: Uri? = null,
        val info: String = "",
        val busy: Boolean = true,
    )
    var review by mutableStateOf<Review?>(null)
        private set
    private var reviewSeq = 0
    fun dismissReview() { review = null }

    /** Lines about RAW for 設定 → 相機資訊. */
    var rawReport by mutableStateOf("")
        private set

    fun deleteLast(uri: Uri) {
        runCatching { app.contentResolver.delete(uri, null, null) }
        if (lastUri == uri) { lastUri = null; lastThumb = null }
        review = null
        toast("已刪除")
    }

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
            if (lenses.isEmpty() && (plan?.mode ?: LensScout.Mode.CameraX) == LensScout.Mode.CameraX) discoverLenses(p)
            val known = plan ?: scout.cached()
            if (known != null) {
                usePlan(known)
                if (bound?.second === view) start(owner, view)
            } else {
                // first time on this phone: find out which lenses can be used (opens each once)
                scanning = true
                scope.launch {
                    val found = withContext(Dispatchers.IO) {
                        runCatching { scout.scout() }.getOrElse { LensScout.Plan(LensScout.Mode.CameraX, emptyList(), listOf("偵測失敗：${it.message}")) }
                    }
                    scout.save(found)
                    scanning = false
                    usePlan(found)
                    if (bound?.second === view) start(owner, view)
                }
            }
        }, ContextCompat.getMainExecutor(app))
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleWatch)
        lifecycleOwner = owner
        owner.lifecycle.addObserver(lifecycleWatch)
    }

    // ---------------- hidden combined camera (Camera2) ----------------

    private val scout = LensScout(app)
    /** How this phone's back lenses are reached (worked out once per phone). */
    private var plan: LensScout.Plan? = null
    /** true while the lenses are being found for the first time. */
    var scanning by mutableStateOf(false)
        private set
    var lensMode by mutableStateOf("")
        private set

    private fun usePlan(p: LensScout.Plan) {
        plan = p
        lensMode = when (p.mode) {
            LensScout.Mode.Combined -> "模式：未公開的組合相機（${p.entries.firstOrNull()?.cameraId}）"
            LensScout.Mode.Separate -> "模式：分開的鏡頭（" + p.entries.joinToString("、") { it.cameraId } + "）"
            LensScout.Mode.CameraX -> "模式：一般（系統公開的相機）"
        }
        val base = cameraReport.filterNot {
            it.startsWith("　") || (p.mode != LensScout.Mode.CameraX && (it.startsWith("手機沒有開放") || it.startsWith("可切換鏡頭")))
        }
        cameraReport = (base + p.notes.map { "　$it" }).distinct()
        if (p.mode != LensScout.Mode.CameraX) {
            val mainIndex = p.entries.indexOfFirst { abs(it.ratio - 1f) < 0.05f }.coerceAtLeast(0)
            val sameStops = lenses.size == p.entries.size
            lenses = p.entries.map { Lens(it.ratio, null, if (p.mode == LensScout.Mode.Combined) it.zoom else null) }
            if (!sameStops || lensIndex !in p.entries.indices || p.mode == LensScout.Mode.Combined) lensIndex = mainIndex
        }
    }

    /** Forget what was found; the next time the 拍照 tab opens, the lenses are looked for again. */
    fun rescanLenses() {
        scout.forget()
        plan = null
        lenses = emptyList()
        lensIndex = 0
        lensMode = ""
        cameraReport = emptyList()
        toast("下次打開拍照分頁會重新偵測鏡頭")
    }

    /** A lens of the plan would not open: drop it (or the whole plan) and remember that. */
    private fun dropFailed(entry: LensScout.Entry) {
        val p = plan ?: return
        val isMain = abs(entry.ratio - 1f) < 0.05f
        val next = if (p.mode == LensScout.Mode.Separate && !isMain && p.entries.size > 2) {
            p.copy(entries = p.entries - entry, notes = p.notes + "相機 ${entry.cameraId} 打不開，已移除")
        } else {
            LensScout.Plan(LensScout.Mode.CameraX, emptyList(), p.notes + "相機 ${entry.cameraId} 打不開，改用一般模式")
        }
        scout.save(next)
        lenses = emptyList()
        usePlan(next)
        lensIndex = if (next.mode == LensScout.Mode.CameraX) 0 else next.entries.indexOfFirst { abs(it.ratio - 1f) < 0.05f }.coerceAtLeast(0)
        toast(if (next.mode == LensScout.Mode.CameraX) "鏡頭打不開，改用一般模式" else "這顆鏡頭打不開，已移除")
        provider?.let { discoverLenses(it) }
    }
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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun startHidden(owner: LifecycleOwner, view: LookPreview, entry: LensScout.Entry, attempt: Int = 0) {
        val id = entry.cameraId
        val wasCameraX = camera != null
        provider?.unbindAll()
        camera = null; capture = null; preview = null
        c2b?.close()
        c2b = null
        cameraReady = false
        if (wasCameraX && attempt == 0) {
            // give CameraX a moment to release the picture surface
            mainHandler.postDelayed({ if (bound?.second === view && !useFront) startHidden(owner, view, entry, 1) }, 350)
            return
        }
        val b = try {
            Camera2Backend(app, id)
        } catch (_: Throwable) {
            dropFailed(entry)
            start(owner, view)
            return
        }
        c2b = b
        zoomMin = b.zoomRange.first
        zoomMax = b.zoomRange.second
        zoom = entry.zoom.coerceIn(zoomMin, max(zoomMin, zoomMax))
        evStep = b.evStep
        evMin = b.evRange.first * evStep
        evMax = b.evRange.second * evStep
        b.zoom = zoom
        b.evIndex = (ev / evStep).roundToInt()
        b.flash = flashCode()
        b.awbMode = currentRecipe()?.let { awbMode(it) } ?: CameraMetadata.CONTROL_AWB_MODE_AUTO
        b.wantRaw = rawEnabled
        val size = b.previewSize()
        view.directSurface(size.width, size.height, b.sensorOrientation) { surface ->
            if (c2b !== b) return@directSurface
            b.open(surface) { ok ->
                if (c2b !== b) return@open
                if (ok) {
                    usingHidden = true
                    cameraReady = true
                    val rs = b.rawSize
                    rawReport = when {
                        rs == null -> "RAW：相機 $id 沒有開放 RAW 給 App"
                        !rawEnabled -> "RAW：支援（${rs.width}×${rs.height}），目前關閉"
                        b.rawActive -> "RAW：使用中（${rs.width}×${rs.height}）"
                        else -> "RAW：支援，但無法和預覽同時使用"
                    }
                } else if (attempt < 2) {
                    // the picture surface may still be held by the camera we just left; try again shortly
                    b.close()
                    c2b = null
                    mainHandler.postDelayed({ if (bound?.second === view && !useFront) startHidden(owner, view, entry, attempt + 1) }, 400)
                } else {
                    b.close()
                    c2b = null
                    usingHidden = false
                    dropFailed(entry)
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
        val pl = plan
        if (scanning) return
        if (!useFront && pl != null && pl.mode != LensScout.Mode.CameraX) {
            val entry = pl.entries.getOrNull(lensIndex) ?: pl.entries.first()
            // a combined camera stays open across its zoom stops
            if (pl.mode == LensScout.Mode.Combined && c2b?.id == entry.cameraId && cameraReady) return
            startHidden(owner, view, entry)
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
        val id = ++reviewSeq
        if (reviewMode != 0) review = Review(id)
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
                if (bytes == null) { toast("拍照失敗"); if (review?.id == id) review = null; return }
                processing++
                scope.launch {
                    val result = withContext(gl) {
                        runCatching { develop(bytes, rotation, wantTurn, sensorTurn, recipe, look, ratioNow, keepOriginal) }
                    }
                    processing--
                    result.onSuccess { r ->
                        lastUri = r.uri
                        lastThumb = r.thumb
                        review?.let { if (it.id == id) review = it.copy(final = r.preview, uri = r.uri, info = r.info, busy = false) }
                    }.onFailure { e ->
                        toast("儲存失敗：" + (e.message ?: e.javaClass.simpleName))
                        if (review?.id == id) review = null
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
        val zoomNow = zoom
        val separate = plan?.mode == LensScout.Mode.Separate
        val mainEq = plan?.mainEq ?: 0f
        val awbUsed = b.awbMode
        capturing = true
        shutterCount++
        feedback.shutter()
        if (shutterSoundOn) feedback.shutterSound()
        val id = ++reviewSeq
        if (reviewMode != 0) review = Review(id)
        b.capture { shot ->
            capturing = false
            if (shot == null) { toast("拍照失敗"); if (review?.id == id) review = null; return@capture }
            processing++
            scope.launch {
                if (reviewMode != 0) {
                    val q = withContext(gl) { runCatching { quickPreview(shot.jpeg, look, ratioNow, turn) }.getOrNull() }
                    review?.let { if (it.id == id) review = it.copy(quick = q) }
                }
                val result = withContext(gl) {
                    runCatching {
                        developShot(shot, recipe, look, ratioNow, keepOriginal, turn, lens, zoomNow, separate, mainEq, awbUsed, b.sensorOrientation)
                    }
                }
                processing--
                result.onSuccess { r ->
                    lastUri = r.uri
                    lastThumb = r.thumb
                    review?.let { if (it.id == id) review = it.copy(final = r.preview, uri = r.uri, info = r.info, busy = false) }
                }.onFailure { e ->
                    toast("儲存失敗：" + (e.message ?: e.javaClass.simpleName))
                    if (review?.id == id) review = null
                }
            }
        }
    }

    class DevResult(val uri: Uri?, val thumb: Bitmap?, val preview: Bitmap?, val info: String)

    /** A quick, small version of the look for the review screen, ready in a moment. */
    private fun quickPreview(jpeg: ByteArray, look: Look, ratio: Ratio, turn: Int): Bitmap {
        val b0 = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, b0)
        var sample = 1
        while (max(b0.outWidth, b0.outHeight) / (sample * 2) >= 1400) sample *= 2
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("decode")
        val r = cropRect(full.width, full.height, ratio)
        var bmp = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
        if (bmp === full || !bmp.isMutable) bmp = bmp.copy(Bitmap.Config.ARGB_8888, true)
        if (look !== Look.NONE) processor.process(bmp, look)
        return turned(bmp, turn)
    }

    private fun turned(bmp: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return bmp
        val m = Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    /** A screen-sized, upright copy for the review screen. */
    private fun displayCopy(bmp: Bitmap, turn: Int): Bitmap {
        val k = min(1f, 1600f / max(bmp.width, bmp.height))
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) else bmp.copy(Bitmap.Config.ARGB_8888, false)
        return turned(small, turn)
    }

    private fun shotInfo(recipe: Recipe?, how: String, lens: String?, exif: ExifInterface?): String {
        val parts = mutableListOf<String>()
        parts += recipe?.name?.ifEmpty { "未命名" } ?: "不套用"
        parts += how
        if (lens != null) parts += lens
        exif?.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let {
            parts += if (it >= 1) "%.1fs".format(it) else "1/${(1 / it).roundToInt()}"
        }
        exif?.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { parts += "f/%.1f".format(it) }
        exif?.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { parts += "ISO $it" }
        return parts.joinToString(" · ")
    }

    private fun nominalKelvin(awb: Int): Float? = when (awb) {
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> 2850f
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> 3000f
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> 4200f
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> 5500f
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> 6500f
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> 7500f
        else -> null
    }

    /** RAW when possible (bright enough, LUT available), otherwise the camera's JPEG. Runs on the GL thread. */
    private fun developShot(
        shot: Camera2Backend.Shot, recipe: Recipe?, look: Look, ratio: Ratio, keepOriginal: Boolean, turn: Int,
        lens: String, zoomNow: Float, separate: Boolean, mainEq: Float, awbUsed: Int, sensorOrientation: Int,
    ): DevResult {
        val raw = shot.raw
        if (raw != null && rawEnabled) {
            val iso = raw.result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY) ?: 100
            val expo = raw.result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val dark = iso >= 1600 || expo >= 66_000_000L
            val lutOk = look.lutKey == null || look.lutKey in luts.installedFlog()
            // how much of the RAW frame the photo shows
            val native = if (!separate && raw.physicalId != null && mainEq > 0f) {
                (com.enweilo.fujirecipes.shoot.Camera2Backend.equivalent(raw.chars) ?: mainEq) / mainEq
            } else 1f
            val crop = zoomNow / native
            if (!dark && lutOk && crop >= 0.97f) {
                val wb = floatArrayOf(1f, 1f, 1f)
                if (recipe?.wb == "色溫") {
                    val from = nominalKelvin(awbUsed)
                    val to = recipe.kelvin.filter { it.isDigit() }.toFloatOrNull()
                    if (from != null && to != null) RawDeveloper.adapt(from, to).copyInto(wb)
                }
                val dev = runCatching {
                    rawDev.develop(raw, shot.jpeg, RawDeveloper.Job(look, ratio, crop.coerceAtLeast(1f), wb, sensorOrientation, iso))
                }.getOrNull()
                if (dev != null) return finishRaw(dev.bitmap, shot, raw, recipe, keepOriginal, turn, lens)
            }
        }
        val how = when {
            raw == null || !rawEnabled -> "一般"
            else -> "一般（光線不足或不適用 RAW）"
        }
        return develop(shot.jpeg, 0, turn, sensorOrientation, recipe, look, ratio, keepOriginal, forceTurn = turn, lens = lens, how = how)
    }

    private fun orientationOf(turn: Int) = when (turn) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun finishRaw(
        bmp: Bitmap, shot: Camera2Backend.Shot, raw: Camera2Backend.RawFrame, recipe: Recipe?, keepOriginal: Boolean,
        turn: Int, lens: String,
    ): DevResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val orientation = orientationOf(turn)
        if (keepOriginal) {
            if (!saveDng(raw, orientation, "FUJI_${stamp}_原圖.dng")) {
                val f = File(app.cacheDir, "orig.jpg")
                f.writeBytes(shot.jpeg)
                ExifInterface(f.absolutePath).apply { setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes() }
                saveJpeg(f.readBytes(), "FUJI_${stamp}_原圖.jpg")
                f.delete()
            }
        }
        val srcExif = runCatching { ExifInterface(ByteArrayInputStream(shot.jpeg)) }.getOrNull()
        val tmp = File(app.cacheDir, "shot.jpg")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        val thumb = thumbnail(bmp, orientation)
        val preview = displayCopy(bmp, turn)
        bmp.recycle()
        val ex = ExifInterface(tmp.absolutePath)
        if (srcExif != null) for (tag in COPY_TAGS) srcExif.getAttribute(tag)?.let { ex.setAttribute(tag, it) }
        ex.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        ex.setAttribute(ExifInterface.TAG_SOFTWARE, "富士食堂")
        ex.setAttribute(ExifInterface.TAG_LENS_MODEL, lens)
        ex.setAttribute(
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            (if (recipe != null) "${recipe.name.ifEmpty { "未命名" }} · ${recipe.filmSim}" else "不套用") + " · RAW",
        )
        ex.saveAttributes()
        val uri = saveJpeg(tmp.readBytes(), "FUJI_$stamp.jpg")
        tmp.delete()
        return DevResult(uri, thumb, preview, shotInfo(recipe, "RAW", lens, srcExif))
    }

    /** Saves the RAW frame as a DNG (Adobe's open RAW format). false if the phone's data does not allow it. */
    private fun saveDng(raw: Camera2Backend.RawFrame, orientation: Int, name: String): Boolean = runCatching {
        val creator = runCatching { android.hardware.camera2.DngCreator(raw.chars, raw.physicalResult ?: raw.result) }
            .getOrElse { android.hardware.camera2.DngCreator(raw.chars, raw.result) }
        creator.setOrientation(orientation)
        val out = java.io.ByteArrayOutputStream(raw.width * raw.height * 2 + 65536)
        raw.pixels.position(0)
        creator.writeByteBuffer(out, android.util.Size(raw.width, raw.height), raw.pixels, 0)
        creator.close()
        raw.pixels.position(0)
        saveFile(out.toByteArray(), name, "image/x-adobe-dng") != null
    }.getOrDefault(false)

    /** Crops, applies the recipe, writes EXIF and saves. Runs on the GL thread. */
    @SuppressLint("NewApi")
    private fun develop(
        bytes: ByteArray, rotation: Int, wantTurn: Int, sensorTurn: Int,
        recipe: Recipe?, look: Look, ratio: Ratio, keepOriginal: Boolean,
        forceTurn: Int? = null, lens: String? = null, how: String = "一般",
    ): DevResult {
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
        val preview = displayCopy(bmp, turn)
        bmp.recycle()

        // EXIF: keep the camera's own data (exposure, lens…) and add which recipe was used
        val ex = ExifInterface(tmp.absolutePath)
        for (tag in COPY_TAGS) srcExif.getAttribute(tag)?.let { ex.setAttribute(tag, it) }
        ex.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        ex.setAttribute(ExifInterface.TAG_SOFTWARE, "富士食堂")
        if (lens != null) ex.setAttribute(ExifInterface.TAG_LENS_MODEL, lens)
        if (recipe != null) {
            ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "${recipe.name.ifEmpty { "未命名" }} · ${recipe.filmSim}")
        }
        ex.saveAttributes()

        val uri = saveJpeg(tmp.readBytes(), "FUJI_$stamp.jpg")
        tmp.delete()
        return DevResult(uri, thumb, preview, shotInfo(recipe, how, lens, srcExif))
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

    private fun saveJpeg(data: ByteArray, name: String): Uri? = saveFile(data, name, "image/jpeg")

    private fun saveFile(data: ByteArray, name: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/富士食堂")
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
