package com.enweilo.fujirecipes.polaroid

import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Parcelable
import android.service.chooser.ChooserAction
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.enweilo.fujirecipes.CopyImageReceiver
import com.enweilo.fujirecipes.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoItem(val id: Long, val uri: Uri, val thumb: Bitmap?, initial: PState) {
    var state by mutableStateOf(initial)
}

/** [full] is used for saving; [preview] is a smaller copy that keeps the editor smooth. */
class Source(val full: Bitmap, val preview: Bitmap)

/** The state of the 拍立得 tab: the photos, which one is open, and saving / sharing. */
class PolaroidController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
    init {
        Fonts.init(app)
        Logos.init(app)
    }

    val items = mutableStateListOf<PhotoItem>()
    var index by mutableIntStateOf(0)
        private set
    var source by mutableStateOf<Source?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Shown while saving a whole batch, e.g. "打包中 2 / 5". */
    var progress by mutableStateOf("")
        private set

    /** Counts up each time the shutter fires; the screen flashes on every change. */
    var flash by mutableIntStateOf(0)
        private set

    val current: PhotoItem? get() = items.getOrNull(index)

    private var nextId = 1L
    private val cache = LruCache<Long, Source>(3)
    private var loadJob: Job? = null
    private val sound: MediaActionSound? by lazy {
        try {
            MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- photos ----------------

    private fun makeItem(uri: Uri): PhotoItem? {
        val thumb = Photos.decode(app, uri, 260) ?: return null
        val state = stateFromMeta(Photos.readMeta(app, uri))
        return PhotoItem(nextId++, uri, thumb, state)
    }

    /** [replaceAll] = the first pick: start a new set. Otherwise the photos are added to the set. */
    fun addPhotos(uris: List<Uri>, replaceAll: Boolean) {
        if (busy || uris.isEmpty()) return
        scope.launch {
            busy = true
            try {
                if (replaceAll) {
                    items.clear()
                    cache.evictAll()
                    index = 0
                    source = null
                }
                val room = MAX_PHOTOS - items.size
                if (room <= 0) {
                    toast("最多 $MAX_PHOTOS 張，請先刪除部分照片")
                    return@launch
                }
                if (uris.size > room) toast("最多 $MAX_PHOTOS 張，僅加入前 $room 張")
                val firstNew = items.size
                var failed = 0
                for (u in uris.take(room)) {
                    val item = withContext(Dispatchers.IO) { makeItem(u) }
                    if (item == null) failed++ else items.add(item)
                }
                if (failed > 0) toast("有 $failed 張無法讀取，請改用 JPEG 或 PNG")
                if (items.size > firstNew) select(firstNew)
            } finally {
                busy = false
            }
        }
    }

    /** Swap the open picture for another one; its card style stays. */
    fun replaceCurrent(uri: Uri) {
        val target = current ?: return
        if (busy) return
        scope.launch {
            busy = true
            try {
                val item = withContext(Dispatchers.IO) { makeItem(uri) }
                if (item == null) {
                    toast("無法讀取這張照片，請改用 JPEG 或 PNG")
                    return@launch
                }
                val old = target.state
                var next = item.state.copy(
                    frame = old.frame, ratio = old.ratio, font = old.font, noteFont = old.noteFont,
                    noteSize = old.noteSize, noteText = old.noteText, color = old.color, sizeLeft = old.sizeLeft,
                    showCam = old.showCam, showExp = old.showExp, dateOn = old.dateOn, dateSize = old.dateSize,
                    dateRight = old.dateRight, bannerDateTop = old.bannerDateTop, datePos = old.datePos, shotOn = old.shotOn,
                )
                if (!next.hasExposure) next = next.copy(showExp = false)
                item.state = next
                val i = items.indexOf(target)
                if (i < 0) return@launch
                items[i] = item
                cache.remove(target.id)
                index = i
                loadSource()
            } finally {
                busy = false
            }
        }
    }

    fun select(i: Int) {
        if (i !in items.indices) return
        index = i
        loadSource()
    }

    fun delete(i: Int) {
        if (busy || i !in items.indices) return
        val removed = items.removeAt(i)
        cache.remove(removed.id)
        if (i < index) index--
        if (items.isEmpty()) index = 0 else if (index >= items.size) index = items.size - 1
        loadSource()
    }

    private fun loadSource() {
        val item = current
        loadJob?.cancel()
        if (item == null) {
            source = null
            return
        }
        cache.get(item.id)?.let {
            source = it
            return
        }
        source = null
        loadJob = scope.launch {
            val s = withContext(Dispatchers.IO) { loadFull(item) }
            if (s == null) {
                toast("無法讀取這張照片")
                return@launch
            }
            cache.put(item.id, s)
            if (current?.id == item.id) source = s
        }
    }

    private fun loadFull(item: PhotoItem): Source? {
        val full = Photos.decode(app, item.uri, Photos.MAX_SIDE) ?: return null
        return Source(full, Photos.scaleDown(full, Photos.PREVIEW_SIDE))
    }

    // ---------------- editing ----------------

    fun update(block: (PState) -> PState) {
        val item = current ?: return
        item.state = block(item.state)
    }

    fun resetCrop() = update { it.copy(cropZ = 1f, cropX = 0f, cropY = 0f) }

    fun resetTextPosition() = update { it.copy(datePos = 18f) }

    fun setFrame(frame: Frame) = update { it.copy(frame = frame, color = frame.textColor) }

    fun setRatio(ratio: Ratio) = update { it.copy(ratio = ratio, cropZ = 1f, cropX = 0f, cropY = 0f) }

    /** Copy the text settings of another photo onto the open one. */
    fun syncFrom(sourceIndex: Int) {
        val from = items.getOrNull(sourceIndex)?.state ?: return
        update {
            it.copy(
                showCam = from.showCam,
                showExp = if (it.hasExposure) from.showExp else false,
                noteText = from.noteText,
                dateOn = from.dateOn,
                datePos = from.datePos,
                dateRight = from.dateRight,
                bannerDateTop = from.bannerDateTop,
                dateSize = from.dateSize,
                color = from.color,
                font = from.font,
                noteFont = from.noteFont,
                noteSize = from.noteSize,
                sizeLeft = from.sizeLeft,
            )
        }
        toast("已套用第 ${sourceIndex + 1} 張的設定")
    }

    // ---------------- saving / sharing ----------------

    private fun shutter() {
        flash++
        try {
            sound?.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
    }

    private fun fullSource(item: PhotoItem): Source? = cache.get(item.id) ?: loadFull(item)

    private fun release(item: PhotoItem, s: Source) {
        if (cache.get(item.id) === s) return
        if (s.preview !== s.full) s.preview.recycle()
        s.full.recycle()
    }

    fun saveCurrent() {
        val item = current
        val number = index + 1
        if (item == null) {
            toast("先上傳照片")
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            try {
                shutter()
                val where = withContext(Dispatchers.IO) {
                    val s = fullSource(item) ?: return@withContext null
                    val bmp = Card.render(item.state, s.full)
                    try {
                        Photos.saveImage(app, bmp, "polaroid_$number.png")
                    } finally {
                        bmp.recycle()
                        release(item, s)
                    }
                }
                toast(if (where != null) "已儲存到 $where" else "儲存失敗")
            } finally {
                busy = false
            }
        }
    }

    fun shareCurrent() {
        val item = current
        val number = index + 1
        if (item == null) {
            toast("先上傳照片")
            return
        }
        if (busy) return
        scope.launch {
            busy = true
            try {
                shutter()
                val file = withContext(Dispatchers.IO) {
                    val s = fullSource(item) ?: return@withContext null
                    val bmp = Card.render(item.state, s.full)
                    try {
                        Photos.shareFile(app, bmp, "polaroid_$number.png")
                    } finally {
                        bmp.recycle()
                        release(item, s)
                    }
                }
                if (file == null) {
                    toast("無法產生圖片，請重試")
                    return@launch
                }
                val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "分享我的拍立得照片！")
                    clipData = ClipData.newRawUri("", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "拍立得照片").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android 14+: a "複製圖片" button in the share sheet, like the web version has
                if (Build.VERSION.SDK_INT >= 34) {
                    val copy = Intent(app, CopyImageReceiver::class.java).putExtra(CopyImageReceiver.EXTRA_URI, uri.toString())
                    val pending = PendingIntent.getBroadcast(
                        app, 0, copy, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    val action = ChooserAction.Builder(Icon.createWithResource(app, R.drawable.ic_copy), "複製圖片", pending).build()
                    chooser.putExtra(Intent.EXTRA_CHOOSER_CUSTOM_ACTIONS, arrayOf<Parcelable>(action))
                }
                app.startActivity(chooser)
            } catch (e: Exception) {
                toast("分享失敗")
            } finally {
                busy = false
            }
        }
    }

    fun saveAllZip() {
        if (busy) return
        if (items.isEmpty()) {
            toast("先上傳照片")
            return
        }
        val list = items.toList()
        scope.launch {
            busy = true
            try {
                shutter()
                val result = withContext(Dispatchers.IO) {
                    val target = Photos.startZip(app, "polaroids_batch.zip") ?: return@withContext null
                    try {
                        for ((i, item) in list.withIndex()) {
                            scope.launch { progress = "打包中 ${i + 1} / ${list.size}" }
                            val s = fullSource(item) ?: throw IllegalStateException("無法讀取第 ${i + 1} 張")
                            val bmp = Card.render(item.state, s.full)
                            try {
                                Photos.addToZip(target, "polaroid_${i + 1}.png", bmp)
                            } finally {
                                bmp.recycle()
                                release(item, s)
                            }
                        }
                        Photos.finishZip(app, target)
                        target.description
                    } catch (e: Throwable) {
                        Photos.abortZip(app, target)
                        "ERR:" + (e.message ?: "")
                    }
                }
                when {
                    result == null -> toast("無法建立 ZIP")
                    result.startsWith("ERR:") -> toast("批次匯出失敗：" + result.removePrefix("ERR:"))
                    else -> toast("已儲存到 $result")
                }
            } finally {
                progress = ""
                busy = false
            }
        }
    }

    companion object {
        const val MAX_PHOTOS = 20
    }
}
