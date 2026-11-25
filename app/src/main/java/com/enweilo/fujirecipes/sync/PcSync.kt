package com.enweilo.fujirecipes.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.RecipeSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

/** A computer running 富士食堂 on the same Wi-Fi. */
data class FoundPc(val name: String, val host: String, val port: Int)

/**
 * The phone's side of recipe sync with the desktop app (see [RecipeSync]): finds the computer by its UDP
 * announcements, pairs once with the code the computer shows, then syncs whenever the app comes to the front
 * and shortly after every change.
 */
class PcSync(
    private val context: Context,
    private val scope: CoroutineScope,
    private val local: () -> Pair<List<Recipe>, Map<String, Long>>,
    private val apply: (List<Recipe>, Map<String, Long>) -> Unit,
    private val toast: (String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("pcsync", Context.MODE_PRIVATE)

    var pcName by mutableStateOf(prefs.getString("name", "") ?: "")
        private set
    var paired by mutableStateOf(prefs.getString("token", "").orEmpty().isNotEmpty())
        private set
    var auto by mutableStateOf(prefs.getBoolean("auto", true))
        private set
    var lastSync by mutableStateOf(prefs.getLong("last", 0L))
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var found by mutableStateOf<List<FoundPc>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private var host: String get() = prefs.getString("host", "") ?: ""
        set(v) { prefs.edit().putString("host", v).apply() }
    private val token: String get() = prefs.getString("token", "") ?: ""
    private var pending: Job? = null

    fun changeAuto(on: Boolean) {
        auto = on
        prefs.edit().putBoolean("auto", on).apply()
    }

    /** Listens for computers for a few seconds. */
    fun search(seconds: Int = 6, onDone: (List<FoundPc>) -> Unit = {}) {
        if (searching) return
        searching = true
        found = emptyList()
        scope.launch {
            val list = withContext(Dispatchers.IO) { listen(seconds * 1000L) }
            found = list
            searching = false
            onDone(list)
        }
    }

    private fun listen(ms: Long): List<FoundPc> {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("fujishokudo")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        val out = LinkedHashMap<String, FoundPc>()
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.broadcast = true
                s.bind(InetSocketAddress(RecipeSync.UDP_PORT))
                val end = System.currentTimeMillis() + ms
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    s.soTimeout = maxOf(100, (end - System.currentTimeMillis()).toInt())
                    val pk = DatagramPacket(buf, buf.size)
                    val ok = runCatching { s.receive(pk) }.isSuccess
                    if (!ok) continue
                    val o = runCatching { JSONObject(String(pk.data, 0, pk.length, Charsets.UTF_8)) }.getOrNull() ?: continue
                    if (o.optString("app") != RecipeSync.APP) continue
                    val h = pk.address.hostAddress ?: continue
                    out[h] = FoundPc(o.optString("name", h), h, o.optInt("port", RecipeSync.HTTP_PORT))
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock?.release() }
        }
        return out.values.toList()
    }

    private fun post(url: String, body: JSONObject, auth: String? = null): Pair<Int, JSONObject?> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 15000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (auth != null) c.setRequestProperty("Authorization", "Bearer $auth")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return code to runCatching { JSONObject(text) }.getOrNull()
    }

    /** Pairs with [pc] using the six digits the computer shows. */
    fun pair(pc: FoundPc, code: String, done: (Boolean) -> Unit) {
        busy = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    post("http://${pc.host}:${pc.port}/pair", JSONObject().put("code", code.filter { it.isDigit() }).put("name", Build.MODEL ?: "手機"))
                }.getOrNull()
            }
            busy = false
            val token = res?.second?.optString("token").orEmpty()
            if (res != null && res.first == 200 && token.isNotEmpty()) {
                prefs.edit().putString("token", token).putString("host", pc.host).putString("name", pc.name).apply()
                pcName = pc.name
                paired = true
                toast("已和 ${pc.name} 配對")
                done(true)
                syncNow(quiet = false)
            } else {
                toast(res?.second?.optString("error")?.takeIf { it.isNotEmpty() } ?: "連不到電腦，確認在同一個 Wi-Fi")
                done(false)
            }
        }
    }

    fun unpair() {
        prefs.edit().remove("token").remove("host").remove("name").apply()
        paired = false
        pcName = ""
        status = ""
    }

    /** Syncs soon (after edits), so a burst of changes goes in one go. */
    fun soon() {
        if (!paired || !auto) return
        pending?.cancel()
        pending = scope.launch {
            delay(2500)
            syncNow(quiet = true)
        }
    }

    fun onForeground() {
        if (paired && auto) syncNow(quiet = true)
    }

    fun syncNow(quiet: Boolean) {
        if (!paired || busy) return
        busy = true
        status = "同步中…"
        scope.launch {
            val (mine, tombs) = local()
            val body = RecipeSync.toJson(mine, tombs)
            var res = withContext(Dispatchers.IO) { runCatching { post("http://$host:${RecipeSync.HTTP_PORT}/sync", body, token) }.getOrNull() }
            if (res == null) {
                // the computer may have a new address: look for it by name
                status = "尋找 $pcName…"
                val list = withContext(Dispatchers.IO) { listen(5000) }
                val pc = list.firstOrNull { it.name == pcName } ?: list.singleOrNull()
                if (pc != null) {
                    host = pc.host
                    res = withContext(Dispatchers.IO) { runCatching { post("http://${pc.host}:${pc.port}/sync", body, token) }.getOrNull() }
                }
            }
            busy = false
            when {
                res == null -> { status = "找不到 $pcName（電腦版有開嗎？在同一個 Wi-Fi 嗎？）"; if (!quiet) toast(status) }
                res.first == 401 -> { status = "電腦已取消配對，請重新配對"; unpair(); toast(status) }
                res.first != 200 || res.second == null -> { status = "同步失敗（${res.first}）"; if (!quiet) toast(status) }
                else -> {
                    val m = RecipeSync.fromJson(res.second!!)
                    // what changed here while we were talking wins next time; apply the merged set now
                    val merged = RecipeSync.merge(local().first, local().second, m.recipes, m.tombstones)
                    apply(merged.recipes, merged.tombstones)
                    lastSync = System.currentTimeMillis()
                    prefs.edit().putLong("last", lastSync).apply()
                    status = "已同步 · ${merged.recipes.size} 個食譜"
                    if (!quiet) toast("已和 $pcName 同步")
                }
            }
        }
    }
}
