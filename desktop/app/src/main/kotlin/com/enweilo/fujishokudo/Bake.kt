package com.enweilo.fujishokudo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.enweilo.fujirecipes.camera.FujiBaker
import com.enweilo.fujirecipes.camera.RafInfo
import com.enweilo.fujirecipes.data.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class RawFile(val file: File, val info: RafInfo) {
    val model: String get() = info.model
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

enum class ViewMode { Original, Baked, Split }

/** 烘焙: a folder of RAF files, a recipe, and the camera developing them. */
class BakeModel(
    private val scope: CoroutineScope,
    private val camera: CameraManager,
    private val repo: RecipeRepo,
    private val settings: Settings,
    private val toast: (String) -> Unit,
) {
    var folder by mutableStateOf<File?>(null)
        private set
    var files by mutableStateOf<List<RawFile>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var recipeId by mutableStateOf<String?>(null)
        private set
    var view by mutableStateOf(ViewMode.Baked)
    var original by mutableStateOf<ImageBitmap?>(null)
        private set
    var result by mutableStateOf<ImageBitmap?>(null)
        private set
    /** -1 idle, else FujiBaker.STAGE_* */
    var stage by mutableIntStateOf(-1)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** cache keys that exist, so the strip can show which files are baked with the chosen recipe */
    var baked by mutableStateOf<Set<String>>(emptySet())
        private set
    var served by mutableIntStateOf(0)
        private set

    var batchTotal by mutableIntStateOf(0)
        private set
    var batchDone by mutableIntStateOf(0)
        private set
    var batchRunning by mutableStateOf(false)
        private set
    private var batchCancel = false

    private var job: Job? = null
    private var again = false
    private var baker: FujiBaker? = null
    private var bakerGen = -1
    private var loadedPath: String? = null
    private val thumbGate = Semaphore(3)

    val current: RawFile? get() = files.getOrNull(index)
    fun currentRecipe(): Recipe? = repo.recipes.firstOrNull { it.id == recipeId }

    init {
        settings.str("lastFolder").takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.isDirectory }?.let { openFolder(it) }
    }

    // ---------------- files ----------------

    fun openFolder(dir: File) {
        folder = dir
        settings.put("lastFolder", dir.absolutePath)
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                (dir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".raf") } ?: emptyArray())
                    .sortedBy { it.name.lowercase() }
                    .mapNotNull { f -> Images.rafInfo(f)?.let { RawFile(f, it) } }
            }
            files = list
            refreshBaked()
            select(0)
            for (rf in list) launch(Dispatchers.IO) {
                thumbGate.withPermit {
                    val bmp = Images.rafPreview(rf.file, rf.info)?.let { Images.decodeBitmap(it, 240) }
                    withContext(Dispatchers.Main) { rf.thumb = bmp }
                }
            }
            if (list.isEmpty()) toast("這個資料夾裡沒有 .RAF 檔")
        }
    }

    fun select(i: Int) {
        if (files.isEmpty()) { index = 0; original = null; result = null; return }
        index = i.coerceIn(0, files.lastIndex)
        error = null
        val rf = files[index]
        original = rf.thumb
        result = null
        scope.launch {
            val big = withContext(Dispatchers.IO) { Images.rafPreview(rf.file, rf.info)?.let { Images.decodeBitmap(it, 2400) } }
            if (current === rf && big != null) original = big
        }
        showCachedOrBake()
    }

    fun next() = select(index + 1)
    fun prev() = select(index - 1)

    fun chooseRecipe(id: String) {
        recipeId = id
        error = null
        refreshBaked()
        showCachedOrBake()
    }

    fun chooseRecipeAt(n: Int) {
        repo.recipes.getOrNull(n)?.let { chooseRecipe(it.id) }
    }

    // ---------------- cache ----------------

    private fun key(f: File, r: Recipe): String {
        val look = r.copy(id = "", name = "", notes = "", photos = emptyList(), createdAt = 0, updatedAt = 0, ev = "", iso = "")
        val s = f.absolutePath + "|" + f.length() + "|" + f.lastModified() + "|" + look.hashCode()
        return Integer.toHexString(s.hashCode()) + Integer.toHexString(s.reversed().hashCode())
    }

    private fun cacheFile(f: File, r: Recipe) = File(Paths.cache, key(f, r) + ".jpg")

    private fun refreshBaked() {
        val r = currentRecipe() ?: run { baked = emptySet(); return }
        baked = files.filter { cacheFile(it.file, r).exists() }.map { it.file.absolutePath }.toSet()
    }

    fun mismatch(): String? {
        val rf = current ?: return null
        if (camera.state != CamState.Connected) return null
        fun n(s: String) = s.uppercase().replace("FUJIFILM", "").filter { it.isLetterOrDigit() }
        return if (n(rf.model) == n(camera.model)) null else "這張是 ${rf.model} 拍的，接著的是 ${camera.model}：相機只能烘自己型號的 RAW"
    }

    fun showCachedOrBake() {
        val rf = current ?: return
        val r = currentRecipe() ?: run { result = null; return }
        val cf = cacheFile(rf.file, r)
        if (cf.exists()) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { Images.decodeFileBitmap(cf, 2400) }
                if (current === rf && recipeId == r.id) result = bmp
            }
            return
        }
        result = null
        if (camera.state != CamState.Connected || mismatch() != null || batchRunning) return
        bake()
    }

    // ---------------- baking ----------------

    fun bake() {
        if (job?.isActive == true) { again = true; return }
        val rf = current ?: return
        val r = currentRecipe() ?: return
        if (camera.state != CamState.Connected) { toast("相機還沒連上"); return }
        mismatch()?.let { error = it; return }
        error = null
        job = scope.launch {
            try {
                val out = bakeOne(rf, r, show = true)
                if (current === rf && recipeId == r.id) {
                    result = withContext(Dispatchers.IO) { Images.decodeBitmap(out, 2400) }
                    served++
                }
            } catch (e: Exception) {
                error = e.message ?: "烘焙失敗"
                camera.log("烘焙錯誤：$error")
                loadedPath = null
            } finally {
                stage = -1
                if (again) {
                    again = false
                    showCachedOrBake()
                }
            }
        }
    }

    /** Has the camera bake [rf] with [r]; keeps the JPEG in the cache. Returns its bytes. */
    private suspend fun bakeOne(rf: RawFile, r: Recipe, show: Boolean): ByteArray {
        val cf = cacheFile(rf.file, r)
        if (cf.exists()) return withContext(Dispatchers.IO) { cf.readBytes() }
        fun ui(block: () -> Unit) { if (show) scope.launch(Dispatchers.Main) { block() } }
        val jpeg = camera.use { cam ->
            if (baker == null || bakerGen != camera.generation) {
                baker = FujiBaker(cam) { camera.log(it) }
                bakerGen = camera.generation
                loadedPath = null
            }
            val b = baker!!
            if (loadedPath != rf.file.absolutePath || b.baseProfile == null) {
                ui { stage = FujiBaker.STAGE_SEND; progress = 0f }
                val bytes = rf.file.readBytes()
                loadedPath = null
                b.load(bytes) { p -> ui { progress = p } }
                loadedPath = rf.file.absolutePath
            }
            ui { stage = FujiBaker.STAGE_CAMERA; progress = 0f }
            b.bake(r, { s -> ui { stage = s; progress = 0f } }, { p -> ui { progress = p } })
        }
        withContext(Dispatchers.IO) {
            cf.writeBytes(jpeg)
            trimCache()
        }
        withContext(Dispatchers.Main) {
            if (recipeId == r.id) baked = baked + rf.file.absolutePath
        }
        return jpeg
    }

    private fun trimCache() {
        val all = Paths.cache.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in all) {
            total += f.length()
            if (total > 3L * 1024 * 1024 * 1024) f.delete()
        }
    }

    // ---------------- keeping ----------------

    fun outputDir(): File? {
        val custom = settings.str("outputDir")
        if (custom.isNotEmpty()) return File(custom)
        return folder?.let { File(it, "富士食堂") }
    }

    private fun outName(rf: RawFile, r: Recipe): String {
        val safe = r.name.replace(Regex("[\\\\/:*?\"<>|]"), "").trim().take(40).ifEmpty { "recipe" }
        return rf.file.nameWithoutExtension + "_" + safe + ".jpg"
    }

    /** Copies the current bake to the output folder. */
    fun save(after: (File?) -> Unit = {}) {
        val rf = current ?: return
        val r = currentRecipe() ?: return
        val cf = cacheFile(rf.file, r)
        if (!cf.exists()) { toast("還沒烘好"); return }
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = outputDir() ?: return@runCatching null
                    dir.mkdirs()
                    File(dir, outName(rf, r)).also { cf.copyTo(it, overwrite = true) }
                }.getOrNull()
            }
            toast(if (out != null) "已存到 ${out.parentFile.name}\\${out.name}" else "儲存失敗")
            after(out)
        }
    }

    /** Bakes every file with the chosen recipe and saves them all. */
    fun batch() {
        val r = currentRecipe() ?: return
        if (batchRunning) { batchCancel = true; return }
        if (camera.state != CamState.Connected) { toast("相機還沒連上"); return }
        val todo = files.toList()
        batchRunning = true
        batchCancel = false
        batchTotal = todo.size
        batchDone = 0
        scope.launch {
            var failed = 0
            for (rf in todo) {
                if (batchCancel) break
                if (mismatch(rf)) { failed++; batchDone++; continue }
                try {
                    bakeOne(rf, r, show = false)
                    withContext(Dispatchers.IO) {
                        val dir = outputDir() ?: return@withContext
                        dir.mkdirs()
                        cacheFile(rf.file, r).copyTo(File(dir, outName(rf, r)), overwrite = true)
                    }
                } catch (e: Exception) {
                    failed++
                    camera.log("批次：${rf.file.name} 失敗：${e.message}")
                    loadedPath = null
                }
                batchDone++
            }
            batchRunning = false
            toast(if (batchCancel) "已停止批次" else if (failed > 0) "批次完成，$failed 張失敗" else "批次完成：${batchDone} 張已存到 ${outputDir()?.name}")
            showCachedOrBake()
        }
    }

    private fun mismatch(rf: RawFile): Boolean {
        fun n(s: String) = s.uppercase().replace("FUJIFILM", "").filter { it.isLetterOrDigit() }
        return n(rf.model) != n(camera.model)
    }

    /** The settings a RAF was shot with, read through the camera, as a new recipe. */
    fun recipeFromRaf(f: File, done: (Recipe?) -> Unit) {
        val info = Images.rafInfo(f) ?: run { toast("不是富士 RAW"); done(null); return }
        val rf = RawFile(f, info)
        if (camera.state != CamState.Connected) { toast("要先接上相機才能讀"); done(null); return }
        if (mismatch(rf)) { toast("相機只能讀自己型號拍的 RAW"); done(null); return }
        scope.launch {
            val r = runCatching {
                camera.use { cam ->
                    if (baker == null || bakerGen != camera.generation) {
                        baker = FujiBaker(cam) { camera.log(it) }
                        bakerGen = camera.generation
                    }
                    val b = baker!!
                    if (loadedPath != f.absolutePath || b.baseProfile == null) {
                        loadedPath = null
                        b.load(f.readBytes()) {}
                        loadedPath = f.absolutePath
                    }
                    FujiBaker.recipeFromProfile(b.baseProfile!!, f.nameWithoutExtension)
                }
            }.onFailure { toast("讀取失敗：${it.message}") }.getOrNull()
            done(r)
        }
    }
}
