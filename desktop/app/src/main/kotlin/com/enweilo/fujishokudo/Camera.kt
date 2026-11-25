package com.enweilo.fujishokudo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.camera.PtpCam
import com.enweilo.fujirecipes.camera.PtpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

enum class CamState { NoLibrary, Searching, NeedsDriver, Busy, Connected }

/** Finds the camera, keeps the connection, and runs every camera job one at a time on its own thread. */
class CameraManager(private val scope: CoroutineScope) {
    var state by mutableStateOf(CamState.Searching)
        private set
    var model by mutableStateOf("")
        private set
    var firmware by mutableStateOf("")
        private set
    var detail by mutableStateOf("")
        private set
    /** bumps on every new connection: what the camera held before is gone */
    var generation by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var logLines by mutableStateOf<List<String>>(emptyList())
        private set

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "camera").apply { isDaemon = true } }
    private val dispatcher = thread.asCoroutineDispatcher()
    private var cam: PtpCam? = null
    private var lastUse = 0L

    fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss").format(Date())
        scope.launch(Dispatchers.Main) { logLines = (logLines + "$t  $line").takeLast(200) }
    }

    fun start() {
        scope.launch {
            while (true) {
                if (cam == null) withContext(dispatcher) { poll() }
                else if (!busy && System.currentTimeMillis() - lastUse > 4000) withContext(dispatcher) { ping() }
                delay(2000)
            }
        }
    }

    private suspend fun set(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    private suspend fun poll() {
        when (val f = Usb.find()) {
            UsbFind.NoLibrary -> set { state = CamState.NoLibrary; detail = "找不到 libusb-1.0.dll" }
            UsbFind.NoCamera -> set { if (state != CamState.Searching) log("相機不在了"); state = CamState.Searching; detail = "" }
            is UsbFind.NeedsDriver -> set {
                if (state != CamState.NeedsDriver) log("找到富士裝置（04CB ${"%04X".format(f.pid)}），但驅動不是 WinUSB：${f.error}")
                state = CamState.NeedsDriver
                detail = "%04X".format(f.pid)
            }
            is UsbFind.Busy -> set { state = CamState.Busy; detail = f.error }
            is UsbFind.Found -> {
                val c = f.cam
                try {
                    c.openSession()
                    val info = c.getDeviceInfo()
                    cam = c
                    lastUse = System.currentTimeMillis()
                    set {
                        model = info.model
                        firmware = info.version
                        detail = ""
                        state = CamState.Connected
                        generation++
                    }
                    log("已連接 ${info.manufacturer} ${info.model}（韌體 ${info.version}）")
                } catch (e: Exception) {
                    runCatching { c.close() }
                    set { state = CamState.Busy; detail = e.message ?: "連線失敗" }
                }
            }
        }
    }

    private suspend fun ping() {
        val c = cam ?: return
        try {
            c.cmd(0x1001)
            lastUse = System.currentTimeMillis()
        } catch (_: Exception) {
            drop()
        }
    }

    private suspend fun drop() {
        runCatching { cam?.close() }
        cam = null
        set { state = CamState.Searching; model = ""; firmware = "" }
        log("相機已中斷")
    }

    /** Runs [block] with the camera, on the camera thread. Throws when there is no camera. */
    suspend fun <T> use(block: (PtpCam) -> T): T = withContext(dispatcher) {
        val c = cam ?: throw PtpException("相機未連接")
        set { busy = true }
        try {
            block(c)
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (msg.contains("USB") || msg.contains("中斷") || msg.contains("沒有回應")) drop()
            throw e
        } finally {
            lastUse = System.currentTimeMillis()
            set { busy = false }
        }
    }
}
