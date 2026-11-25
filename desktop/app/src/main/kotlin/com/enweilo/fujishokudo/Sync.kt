package com.enweilo.fujishokudo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.data.RecipeSync
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.Executors

class Paired(val token: String, val name: String, val lastSync: Long, val count: Int)

/**
 * The computer's side of phone sync: an HTTP server on the local network and a UDP "here I am" every few
 * seconds. A phone pairs once with the six-digit code shown in 連線與同步, then syncs with its token.
 */
class SyncServer(private val scope: CoroutineScope, private val repo: RecipeRepo, private val settings: Settings) {
    var code by mutableStateOf("")
        private set
    var devices by mutableStateOf<List<Paired>>(emptyList())
        private set
    var running by mutableStateOf(false)
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var enabled by mutableStateOf(settings.bool("syncEnabled", true))
        private set

    val pcName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("電腦")
    private val rnd = SecureRandom()
    private var server: HttpServer? = null

    init {
        code = settings.str("pairCode").ifEmpty { newCode() }
        loadDevices()
    }

    private fun newCode(): String {
        val c = "%06d".format(rnd.nextInt(1_000_000))
        settings.put("pairCode", c)
        return c
    }

    fun regenerateCode() { code = newCode() }

    private fun loadDevices() {
        val o = settings.obj("paired")
        devices = o.keySet().map { t ->
            val d = o.getJSONObject(t)
            Paired(t, d.optString("name", "手機"), d.optLong("lastSync"), d.optInt("count"))
        }.sortedByDescending { it.lastSync }
    }

    private fun touch(token: String, name: String?, count: Int?) {
        val o = settings.obj("paired")
        val d = o.optJSONObject(token) ?: JSONObject()
        if (name != null) d.put("name", name)
        d.put("lastSync", System.currentTimeMillis())
        if (count != null) d.put("count", count)
        o.put(token, d)
        settings.save()
        loadDevices()
    }

    fun forget(token: String) {
        settings.obj("paired").remove(token)
        settings.save()
        loadDevices()
    }

    fun switchEnabled(on: Boolean) {
        enabled = on
        settings.put("syncEnabled", on)
        if (on) start() else stop()
    }

    fun start() {
        if (!enabled || server != null) return
        try {
            val s = HttpServer.create(InetSocketAddress(RecipeSync.HTTP_PORT), 0)
            s.executor = Executors.newFixedThreadPool(2)
            s.createContext("/ping") { ex -> reply(ex, 200, JSONObject().put("app", RecipeSync.APP).put("name", pcName)) }
            s.createContext("/pair") { ex -> handlePair(ex) }
            s.createContext("/sync") { ex -> handleSync(ex) }
            s.start()
            server = s
            running = true
            problem = null
            announce()
        } catch (e: Exception) {
            problem = "同步服務沒有啟動（${e.message}）"
            running = false
        }
    }

    fun stop() {
        server?.stop(0)
        server = null
        running = false
    }

    private fun announce() {
        scope.launch(Dispatchers.IO) {
            val sock = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return@launch
            val msg = JSONObject().put("app", RecipeSync.APP).put("name", pcName).put("port", RecipeSync.HTTP_PORT).toString().toByteArray()
            while (server != null) {
                for (addr in broadcastAddresses()) {
                    runCatching { sock.send(DatagramPacket(msg, msg.size, addr, RecipeSync.UDP_PORT)) }
                }
                delay(3000)
            }
            sock.close()
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { out += it }
            }
        }
        if (out.isEmpty()) out += InetAddress.getByName("255.255.255.255")
        return out
    }

    private fun body(ex: HttpExchange): JSONObject = JSONObject(String(ex.requestBody.readBytes(), Charsets.UTF_8))

    private fun reply(ex: HttpExchange, status: Int, o: JSONObject) {
        val b = o.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(status, b.size.toLong())
        ex.responseBody.use { it.write(b) }
    }

    private fun handlePair(ex: HttpExchange) {
        runCatching {
            val o = body(ex)
            if (o.optString("code") != code) return reply(ex, 403, JSONObject().put("error", "配對碼不對"))
            val bytes = ByteArray(24).also { rnd.nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            runBlocking(Dispatchers.Main) { touch(token, o.optString("name", "手機"), null) }
            reply(ex, 200, JSONObject().put("token", token).put("name", pcName))
        }.onFailure { runCatching { reply(ex, 400, JSONObject().put("error", it.message)) } }
    }

    private fun handleSync(ex: HttpExchange) {
        runCatching {
            val token = ex.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim().orEmpty()
            if (token.isEmpty() || !settings.obj("paired").has(token)) return reply(ex, 401, JSONObject().put("error", "還沒配對"))
            val incoming = RecipeSync.fromJson(body(ex))
            val merged = runBlocking(Dispatchers.Main) {
                val m = repo.mergeFrom(incoming.recipes, incoming.tombstones)
                touch(token, null, m.recipes.size)
                m
            }
            reply(ex, 200, RecipeSync.toJson(merged.recipes, merged.tombstones).put("name", pcName))
        }.onFailure { runCatching { reply(ex, 400, JSONObject().put("error", it.message)) } }
    }

    /** This computer's addresses on the local network, for typing into the phone by hand. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && it.isSiteLocalAddress }
            .map { it.hostAddress }
    }.getOrDefault(emptyList())
}
