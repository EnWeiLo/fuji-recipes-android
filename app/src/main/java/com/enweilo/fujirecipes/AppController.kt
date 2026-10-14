package com.enweilo.fujirecipes

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.camera.FujiOps
import com.enweilo.fujirecipes.camera.FujiUsb
import com.enweilo.fujirecipes.camera.PTP_OK
import com.enweilo.fujirecipes.camera.PtpCam
import com.enweilo.fujirecipes.camera.SlotInfo
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.RecipeStore
import com.enweilo.fujirecipes.data.emptyRecipe
import com.enweilo.fujirecipes.polaroid.PolaroidController
import com.enweilo.fujirecipes.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { Recipes, Camera, Polaroid, Settings }

sealed interface Screen {
    data class Detail(val id: String) : Screen
    data class Edit(val id: String) : Screen
}

enum class CamStatus { Disconnected, Connecting, Connected }

class ToastMessage(val text: String, val serial: Long)

/**
 * Holds everything the screens show: recipes, navigation, camera connection.
 * Lives in the Application object, so it survives screen rotation.
 */
class AppController(private val app: Application) {

    val store = RecipeStore(app)
    private val usb = FujiUsb(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The 拍立得 tab keeps its own state (photos, styles, saving). */
    val polaroid = PolaroidController(app, scope) { text -> showToast(text) }

    // ---------------- general state ----------------

    var recipes by mutableStateOf(store.load())
        private set
    var query by mutableStateOf("")
    var tab by mutableStateOf(Tab.Recipes)
    val stack = mutableStateListOf<Screen>()

    /** 0 = follow the system, 1 = light, 2 = dark */
    var themeMode by mutableIntStateOf(prefs.getInt("theme", 0))
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set
    private var toastCounter = 0L

    fun showToast(text: String) {
        toast = ToastMessage(text, ++toastCounter)
    }

    fun setTheme(mode: Int) {
        themeMode = mode
        prefs.edit().putInt("theme", mode).apply()
    }

    private fun persist() = store.save(recipes)

    // ---------------- updates ----------------

    var autoUpdate by mutableStateOf(prefs.getBoolean("autoUpdate", true))
        private set
    var latest by mutableStateOf<Updater.Release?>(null)
        private set
    var updatePrompt by mutableStateOf(false)
    /** Non-empty while downloading, e.g. "下載更新中… 40%". */
    var updateStatus by mutableStateOf("")
        private set
    var checking by mutableStateOf(false)
        private set
    var updateNote by mutableStateOf("")
        private set
    val installedBuild: Long get() = Updater.installedBuild(app)

    fun changeAutoUpdate(on: Boolean) {
        autoUpdate = on
        prefs.edit().putBoolean("autoUpdate", on).apply()
    }

    /** [manual] = the user asked; otherwise it is the quiet check at launch. */
    fun checkForUpdate(manual: Boolean) {
        if (checking || updateStatus.isNotEmpty()) return
        if (!manual && !autoUpdate) return
        checking = true
        if (manual) updateNote = "檢查中…"
        scope.launch {
            val release = withContext(Dispatchers.IO) { Updater.fetchLatest() }
            checking = false
            if (release == null) {
                if (manual) updateNote = "無法連線，請稍後再試"
                return@launch
            }
            val newer = Updater.isNewer(release, installedBuild)
            latest = if (newer) release else null
            updateNote = if (newer) "有新版本 build ${release.build}" else "已是最新版本"
            if (newer && (manual || prefs.getInt("dismissedBuild", 0) != release.build)) updatePrompt = true
        }
    }

    fun dismissUpdatePrompt() {
        updatePrompt = false
        latest?.let { prefs.edit().putInt("dismissedBuild", it.build).apply() }
    }

    fun startUpdate() {
        val release = latest ?: return
        if (updateStatus.isNotEmpty()) return
        if (!Updater.canInstall(app)) {
            showToast("請先允許「安裝未知應用程式」，再按一次更新")
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
            return
        }
        updateStatus = "下載更新中… 0%"
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                Updater.download(app, release) { pct -> scope.launch { updateStatus = "下載更新中… $pct%" } }
            }
            updateStatus = ""
            if (file == null) showToast("下載失敗，請稍後再試") else Updater.install(app, file)
        }
    }

    // ---------------- navigation ----------------

    fun openDetail(id: String) {
        stack.add(Screen.Detail(id))
    }

    fun back(): Boolean {
        val top = stack.lastOrNull()
        if (top is Screen.Edit) {
            cancelEdit()
            return true
        }
        if (top != null) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (tab != Tab.Recipes) {
            tab = Tab.Recipes
            return true
        }
        return false
    }

    // ---------------- editing ----------------

    var draft by mutableStateOf(emptyRecipe())
        private set
    private var draftOriginalPhotos: List<String> = emptyList()
    private var draftIsNew = false

    fun startNew() {
        draft = emptyRecipe()
        draftOriginalPhotos = emptyList()
        draftIsNew = true
        stack.add(Screen.Edit(draft.id))
    }

    fun startEdit(id: String) {
        val recipe = recipes.firstOrNull { it.id == id } ?: return
        draft = recipe
        draftOriginalPhotos = recipe.photos
        draftIsNew = false
        stack.add(Screen.Edit(id))
    }

    fun updateDraft(transform: (Recipe) -> Recipe) {
        draft = transform(draft)
    }

    fun addPhotos(uris: List<Uri>) {
        val room = MAX_PHOTOS - draft.photos.size
        if (room <= 0) {
            showToast("最多 $MAX_PHOTOS 張照片")
            return
        }
        scope.launch {
            val ids = withContext(Dispatchers.IO) { uris.take(room).mapNotNull { store.importPhoto(it) } }
            if (ids.size < uris.take(room).size) showToast("有照片讀取失敗")
            draft = draft.copy(photos = draft.photos + ids)
        }
    }

    fun removeDraftPhoto(id: String) {
        draft = draft.copy(photos = draft.photos - id)
    }

    fun saveDraft() {
        if (draft.name.isBlank()) {
            showToast("先取個名字")
            return
        }
        val now = System.currentTimeMillis()
        val saved = draft.copy(name = draft.name.trim(), updatedAt = now)
        recipes = if (draftIsNew) listOf(saved) + recipes
        else recipes.map { if (it.id == saved.id) saved else it }
        persist()
        // pictures that were taken out of the recipe are no longer needed
        (draftOriginalPhotos - saved.photos.toSet()).forEach { store.deletePhoto(it) }
        stack.removeAt(stack.lastIndex)
        if (draftIsNew) stack.add(Screen.Detail(saved.id))
        showToast("已存檔")
    }

    private fun cancelEdit() {
        // pictures added during this edit session are dropped again
        (draft.photos - draftOriginalPhotos.toSet()).forEach { store.deletePhoto(it) }
        stack.removeAt(stack.lastIndex)
    }

    fun deleteRecipe(id: String) {
        val recipe = recipes.firstOrNull { it.id == id } ?: return
        recipe.photos.forEach { store.deletePhoto(it) }
        recipes = recipes.filter { it.id != id }
        persist()
        stack.clear()
        showToast("已刪除")
    }

    // ---------------- import / export ----------------

    fun exportTo(uri: Uri) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openOutputStream(uri)?.use {
                        it.write(store.exportJson(recipes).toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    false
                }
            }
            showToast(if (ok) "已匯出（不含照片）" else "匯出失敗")
        }
    }

    fun importFrom(uri: Uri) {
        scope.launch {
            val incoming = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.use { store.parseImport(it.readBytes().toString(Charsets.UTF_8)) }
                } catch (e: Exception) {
                    null
                }
            }
            if (incoming == null) {
                showToast("匯入失敗，請確認 JSON")
                return@launch
            }
            var list = recipes
            val now = System.currentTimeMillis()
            for (r in incoming) {
                val index = list.indexOfFirst { it.id == r.id || it.name == r.name }
                list = if (index >= 0) {
                    // same recipe again: take the new settings, keep the photos already here
                    list.mapIndexed { i, old -> if (i == index) r.copy(id = old.id, photos = old.photos, updatedAt = now) else old }
                } else {
                    listOf(r.copy(updatedAt = now)) + list
                }
            }
            recipes = list
            persist()
            showToast("已匯入 ${incoming.size} 道食譜")
        }
    }

    // ---------------- camera ----------------

    var camStatus by mutableStateOf(CamStatus.Disconnected)
        private set
    var camModel by mutableStateOf("")
        private set
    var camBusy by mutableStateOf(false)
        private set
    var camSlot by mutableIntStateOf(1)
    var camRecipeId by mutableStateOf<String?>(null)
    var slotInfos by mutableStateOf<List<SlotInfo>>(emptyList())
        private set
    val camLog = mutableStateListOf<String>()

    private var cam: PtpCam? = null

    private fun log(text: String) {
        scope.launch { camLog.add(text) }
    }

    fun clearLog() = camLog.clear()

    fun connectCamera() {
        if (camStatus != CamStatus.Disconnected || camBusy) return
        camStatus = CamStatus.Connecting
        scope.launch {
            try {
                val device = usb.findCamera()
                if (device == null) {
                    log("找不到相機。請用 OTG 線接上，並把相機的 USB 模式設成「USB RAW CONV./BACKUP RESTORE」或 Tether。")
                    camStatus = CamStatus.Disconnected
                    showToast("找不到相機")
                    return@launch
                }
                log("裝置：" + (device.productName ?: "Fujifilm"))
                log("要求 USB 權限…")
                if (!usb.ensurePermission(device)) {
                    log("沒有取得 USB 權限")
                    camStatus = CamStatus.Disconnected
                    showToast("沒有 USB 權限")
                    return@launch
                }
                val (connected, model) = withContext(Dispatchers.IO) {
                    log("開啟 USB…")
                    val c = usb.open(device)
                    log("介面已占用，開始 PTP…")
                    try {
                        val session = c.openSession()
                        if (session.code != PTP_OK && session.code != 0x201E) {
                            log("OpenSession 回應 " + session.code.toString(16) + "，仍嘗試繼續")
                        }
                        val info = c.getDeviceInfo()
                        log("型號：" + info.model + (if (info.version.isNotEmpty()) "  FW " + info.version else ""))
                        c to info.model
                    } catch (e: Exception) {
                        c.close()
                        throw e
                    }
                }
                cam = connected
                camModel = model
                camStatus = CamStatus.Connected
                log("連線完成。")
                showToast("相機已連線")
            } catch (e: Exception) {
                log("失敗：" + (e.message ?: e.toString()))
                camStatus = CamStatus.Disconnected
                showToast("連線失敗")
            }
        }
    }

    fun disconnectCamera() {
        val c = cam ?: return
        cam = null
        camStatus = CamStatus.Disconnected
        slotInfos = emptyList()
        scope.launch {
            withContext(Dispatchers.IO) { c.close() }
            log("已中斷。")
        }
    }

    /** The cable was pulled. */
    fun onUsbDetached() {
        if (cam != null) {
            cam = null
            camStatus = CamStatus.Disconnected
            slotInfos = emptyList()
            log("相機已拔除。")
        }
    }

    /** The camera was plugged in while the app is open or got started by it. */
    fun onUsbAttached() {
        tab = Tab.Camera
        stack.clear()
    }

    private fun runCamera(label: String, block: (PtpCam) -> Unit) {
        val c = cam
        if (c == null) {
            showToast("先連接相機")
            return
        }
        if (camBusy) return
        camBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { block(c) }
            } catch (e: Exception) {
                log(label + "錯誤：" + (e.message ?: e.toString()))
                showToast(label + "失敗")
            } finally {
                camBusy = false
            }
        }
    }

    fun listSlots() = runCamera("讀取") { c ->
        val list = FujiOps(c, ::log).readSlots()
        scope.launch { slotInfos = list }
    }

    fun readSlotToRecipe() {
        val slot = camSlot
        runCamera("讀取") { c ->
            val recipe = FujiOps(c, ::log).readSlotAsRecipe(slot)
            scope.launch {
                recipes = listOf(recipe) + recipes
                persist()
                showToast("已存成「${recipe.name}」")
                tab = Tab.Recipes
                openDetail(recipe.id)
            }
        }
    }

    fun writeToCamera() {
        val recipe = recipes.firstOrNull { it.id == camRecipeId }
        if (recipe == null) {
            showToast("先選一道食譜")
            return
        }
        val slot = camSlot
        runCamera("寫入") { c ->
            FujiOps(c, ::log).writeRecipe(recipe, slot)
            scope.launch { showToast("寫入完成") }
        }
    }

    private companion object {
        const val MAX_PHOTOS = 6
    }
}
