package com.enweilo.fujirecipes.bake

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.camera.FujiBaker
import com.enweilo.fujirecipes.camera.PtpCam
import com.enweilo.fujirecipes.camera.RafInfo
import com.enweilo.fujirecipes.data.Recipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A RAF the user picked. */
class RafItem(val uri: Uri, val name: String, val size: Long, val model: String, val thumb: Bitmap?)

/**
 * 烘焙: RAW files from the user's Fujifilm camera, developed by the camera itself with a recipe.
 * Nothing here is simulated: every picture shown is either the camera's own JPEG inside the RAF (拍攝時)
 * or one the camera baked.
 */
class BakeController(
    private val app: Context,
    private val scope: CoroutineScope,
    /** the open camera connection, if any */
    private val camera: () -> PtpCam?,
    private val cameraModel: () -> String,
    private val recipes: () -> List<Recipe>,
    private val toast: (String) -> Unit,
    private val log: (String) -> Unit,
    /** runs [block] on the camera without clashing with the 連線 tab */
    private val useCamera: suspend (suspend () -> Unit) -> Unit,
) {
    var items by mutableStateOf<List<RafItem>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
        private set
    var recipeId by mutableStateOf<String?>(null)
        private set

    /** -1 idle, otherwise a FujiBaker.STAGE_* */
    var stage by mutableIntStateOf(-1)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** what the camera wrote into the RAF when shooting, for the current file */
    var original by mutableStateOf<Bitmap?>(null)
        private set
    /** the camera's bake of the current file with the current recipe; null when there is none yet */
    var result by mutableStateOf<Bitmap?>(null)
        private set
    /** bumps when a fresh bake comes out (for the "ding") */
    var served by mutableIntStateOf(0)
        private set

    private val cacheDir = File(app.cacheDir, "bake").apply { mkdirs() }
    private var job: Job? = null
    private var again = false
    /** which file the camera currently holds, on which connection */
    private var loadedCam: PtpCam? = null
    private var loadedUri: Uri? = null
    private var baker: FujiBaker? = null

    val current: RafItem? get() = items.getOrNull(index)
    fun currentRecipe(): Recipe? = recipes().firstOrNull { it.id == recipeId }

    /** Model names as RAF headers and the camera spell them, compared loosely. */
    private fun sameModel(a: String, b: String): Boolean {
        fun n(s: String) = s.uppercase().replace("FUJIFILM", "").filter { it.isLetterOrDigit() }
        return n(a).isNotEmpty() && n(a) == n(b)
    }

    fun modelMismatch(): String? {
        val it = current ?: return null
        val cm = cameraModel()
        if (camera() == null || cm.isEmpty() || it.model.isEmpty()) return null
        return if (sameModel(it.model, cm)) null else "這張是 ${it.model} 拍的，接著的是 $cm：相機只能烘自己型號的 RAW"
    }

    // ---------------- files ----------------

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val found = withContext(Dispatchers.IO) { uris.mapNotNull { readItem(it) } }
            val skipped = uris.size - found.size
            if (skipped > 0) toast("$skipped 個檔案不是富士 RAW（.RAF）")
            if (found.isEmpty()) return@launch
            val known = items.map { it.uri }.toSet()
            items = items + found.filter { it.uri !in known }
            select(items.indexOfFirst { it.uri == found.first().uri }.coerceAtLeast(0))
        }
    }

    fun remove(i: Int) {
        val list = items.toMutableList()
        if (i !in list.indices) return
        list.removeAt(i)
        items = list
        select(index.coerceAtMost(items.lastIndex).coerceAtLeast(0))
    }

    private fun readItem(uri: Uri): RafItem? = runCatching {
        val cr = app.contentResolver
        var name = uri.lastPathSegment ?: "RAW"
        var size = 0L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                size = c.getLong(1)
            }
        }
        val head = cr.openInputStream(uri)?.use { s -> ByteArray(100).also { readFully(s, it) } } ?: return null
        val info = RafInfo.parse(head) ?: return null
        RafItem(uri, name, size, info.model, embedded(uri, info, 240))
    }.getOrNull()

    private fun readFully(s: java.io.InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = s.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
    }

    /** The JPEG the camera put inside the RAF, scaled to about [maxSide]. */
    private fun embedded(uri: Uri, info: RafInfo, maxSide: Int): Bitmap? = runCatching {
        if (info.jpegLength <= 0) return null
        val bytes = app.contentResolver.openInputStream(uri)?.use { s ->
            var skip = info.jpegOffset.toLong()
            while (skip > 0) {
                val k = s.skip(skip)
                if (k <= 0) break
                skip -= k
            }
            ByteArray(info.jpegLength).also { readFully(s, it) }
        } ?: return null
        decode(bytes, maxSide)
    }.getOrNull()

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return rotateByExif(bytes, bmp)
    }

    private fun rotateByExif(bytes: ByteArray, bmp: Bitmap): Bitmap {
        val deg = runCatching {
            androidx.exifinterface.media.ExifInterface(bytes.inputStream()).rotationDegrees
        }.getOrDefault(0)
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    // ---------------- choosing ----------------

    fun select(i: Int) {
        if (items.isEmpty()) { index = 0; original = null; result = null; return }
        index = i.coerceIn(0, items.lastIndex)
        error = null
        val item = items[index]
        result = null
        original = item.thumb
        scope.launch {
            val big = withContext(Dispatchers.IO) {
                val head = app.contentResolver.openInputStream(item.uri)?.use { s -> ByteArray(100).also { readFully(s, it) } }
                head?.let { RafInfo.parse(it) }?.let { embedded(item.uri, it, 2048) }
            }
            if (current?.uri == item.uri && big != null) original = big
        }
        showCachedOrBake()
    }

    fun chooseRecipe(id: String) {
        recipeId = id
        error = null
        showCachedOrBake()
    }

    private fun key(item: RafItem, r: Recipe) =
        (item.uri.toString() + "|" + r.copy(name = "", notes = "").hashCode()).hashCode().toUInt().toString(16)

    private fun cacheFile(item: RafItem, r: Recipe) = File(cacheDir, key(item, r) + ".jpg")

    /** Shows the bake if we already have it, otherwise asks the camera (when one is connected). */
    fun showCachedOrBake() {
        val item = current ?: return
        val r = currentRecipe()
        if (r == null) { result = null; return }
        val f = cacheFile(item, r)
        if (f.exists()) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decode(f.readBytes(), 2048) }
                if (current?.uri == item.uri && recipeId == r.id) result = bmp
            }
            return
        }
        result = null
        if (camera() == null) return
        if (modelMismatch() != null) return
        bake()
    }

    // ---------------- baking ----------------

    fun bake() {
        if (job?.isActive == true) { again = true; return }
        val item = current ?: return
        val r = currentRecipe() ?: return
        val cam = camera()
        if (cam == null) { toast("先在「連線」接上相機"); return }
        modelMismatch()?.let { error = it; return }
        error = null
        job = scope.launch {
            try {
                useCamera {
                    withContext(Dispatchers.IO) { run(cam, item, r) }
                }
            } catch (e: Exception) {
                log("烘焙錯誤：" + (e.message ?: e.toString()))
                error = e.message ?: "烘焙失敗"
                // the camera may have dropped the file; load it again next time
                loadedUri = null
            } finally {
                stage = -1
                if (again) {
                    again = false
                    val now = current
                    val rr = currentRecipe()
                    if (now != null && rr != null && !cacheFile(now, rr).exists()) bake()
                    else showCachedOrBake()
                }
            }
        }
    }

    private suspend fun run(cam: PtpCam, item: RafItem, r: Recipe) {
        fun ui(block: () -> Unit) = scope.launch { block() }
        val b = baker?.takeIf { loadedCam === cam } ?: FujiBaker(cam, log).also { baker = it }
        if (loadedCam !== cam || loadedUri != item.uri || b.baseProfile == null) {
            ui { stage = FujiBaker.STAGE_SEND; progress = 0f }
            val bytes = app.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("讀不到檔案")
            loadedUri = null
            b.load(bytes) { p -> ui { progress = p } }
            loadedCam = cam
            loadedUri = item.uri
        }
        ui { stage = FujiBaker.STAGE_CAMERA; progress = 0f }
        val jpeg = b.bake(r, { s -> ui { stage = s; progress = 0f } }, { p -> ui { progress = p } })
        val f = cacheFile(item, r)
        f.writeBytes(jpeg)
        trimCache()
        val bmp = decode(jpeg, 2048)
        withContext(Dispatchers.Main) {
            if (current?.uri == item.uri && recipeId == r.id) {
                result = bmp
                served++
            }
        }
    }

    private fun trimCache() {
        val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > 400L * 1024 * 1024) f.delete()
        }
    }

    // ---------------- keeping ----------------

    /** Copies the current bake into the gallery (DCIM/富士食堂). Returns its uri. */
    fun save(onSaved: (Uri?) -> Unit = {}) {
        val item = current ?: return
        val r = currentRecipe() ?: return
        val f = cacheFile(item, r)
        if (!f.exists()) { toast("還沒有烘好的照片"); return }
        scope.launch {
            val uri = withContext(Dispatchers.IO) { saveToGallery(f, item, r) }
            toast(if (uri != null) "已存到相簿 / 富士食堂" else "儲存失敗")
            onSaved(uri)
        }
    }

    private fun saveToGallery(f: File, item: RafItem, r: Recipe): Uri? = runCatching {
        val base = item.name.substringBeforeLast('.')
        val safe = r.name.replace(Regex("[\\\\/:*?\"<>|]"), "").take(30).ifEmpty { "recipe" }
        val name = "${base}_$safe.jpg"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/富士食堂")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val cr = app.contentResolver
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "富士食堂").apply { mkdirs() }
            val out = File(dir, name)
            f.copyTo(out, overwrite = true)
            Uri.fromFile(out)
        }
    }.getOrNull()

    /** For the render check: shows the oven at [s] without a camera. */
    @androidx.annotation.VisibleForTesting
    fun showStageForTest(s: Int, p: Float) { stage = s; progress = p }

    /** The camera went away: what it held is gone. */
    fun onCameraGone() {
        loadedCam = null
        loadedUri = null
        baker = null
    }
}
