package com.enweilo.fujirecipes.shoot

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Works out, once per phone model, the best way to reach its back lenses:
 *
 * 1. [Mode.CameraX] — the phone shares its lenses openly (a combined camera that zooms below 1×, or separate
 *    listed cameras). CameraX handles it.
 * 2. [Mode.Combined] — a combined camera the phone does not list, but lets apps open (zoom ratio picks the lens).
 * 3. [Mode.Separate] — separate lenses, some of them unlisted; each one is opened on its own.
 *
 * Depth, black-and-white and very low-resolution helper cameras are left out. Every unlisted camera is really
 * opened once before it is trusted. The answer is kept per phone build, so later launches are instant.
 */
class LensScout(private val context: Context) {

    enum class Mode { CameraX, Combined, Separate }

    /** One stop on the zoom row: open [cameraId] at [zoom]; shown as [ratio] (relative to the main lens). */
    data class Entry(val ratio: Float, val cameraId: String, val zoom: Float)

    data class Plan(val mode: Mode, val entries: List<Entry>, val notes: List<String>) {
        fun toJson(): String = JSONObject().apply {
            put("mode", mode.name)
            put("entries", JSONArray().apply {
                entries.forEach { put(JSONObject().put("r", it.ratio.toDouble()).put("id", it.cameraId).put("z", it.zoom.toDouble())) }
            })
            put("notes", JSONArray(notes))
        }.toString()

        companion object {
            fun fromJson(s: String): Plan? = runCatching {
                val o = JSONObject(s)
                val e = o.getJSONArray("entries")
                val n = o.optJSONArray("notes") ?: JSONArray()
                Plan(
                    Mode.valueOf(o.getString("mode")),
                    (0 until e.length()).map { i ->
                        val j = e.getJSONObject(i)
                        Entry(j.getDouble("r").toFloat(), j.getString("id"), j.getDouble("z").toFloat())
                    },
                    (0 until n.length()).map { n.getString(it) },
                )
            }.getOrNull()
        }
    }

    private val prefs = context.getSharedPreferences("lenses", Context.MODE_PRIVATE)
    private val key = "plan:" + Build.FINGERPRINT

    fun cached(): Plan? = prefs.getString(key, null)?.let { Plan.fromJson(it) }

    fun save(plan: Plan) {
        prefs.edit().putString(key, plan.toJson()).apply()
    }

    fun forget() {
        prefs.edit().remove(key).apply()
    }

    private class Cam(
        val id: String,
        val listed: Boolean,
        val eq: Float,
        val logical: Boolean,
        val zoomMin: Float,
        val zoomMax: Float,
        val physical: Set<String>,
    )

    /** Looks at every camera number 0–15. Opens unlisted ones to be sure. Run off the main thread. */
    fun scout(): Plan {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val listed = runCatching { cm.cameraIdList.toList() }.getOrDefault(emptyList())
        val notes = mutableListOf<String>()
        val cams = mutableListOf<Cam>()
        val ids = (listed + (0..15).map { it.toString() }).distinct()
        for (id in ids) {
            val ch = runCatching { cm.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
            val eq = Camera2Backend.equivalent(ch)
            val tag = "相機 $id（${if (id in listed) "公開" else "未公開"}，約 ${eq?.roundToInt() ?: "?"} mm）"
            // keep only cameras made for ordinary photos
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) { notes += "$tag：不是一般拍照用，略過"; continue }
            if (Build.VERSION.SDK_INT >= 28 && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME in caps) { notes += "$tag：黑白鏡頭，略過"; continue }
            val px = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (px != null && px.width.toLong() * px.height < 3_000_000L) { notes += "$tag：畫素太低（多半是景深或微距輔助），略過"; continue }
            if (eq == null) { notes += "$tag：讀不到焦距，略過"; continue }
            val logical = Build.VERSION.SDK_INT >= 28 && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            val zr = if (Build.VERSION.SDK_INT >= 30) ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            val physical = if (Build.VERSION.SDK_INT >= 28) runCatching { ch.physicalCameraIds }.getOrDefault(emptySet()) else emptySet()
            cams += Cam(id, id in listed, eq, logical, zr?.lower ?: 1f, zr?.upper ?: 1f, physical)
            notes += "$tag" + (if (logical) "：組合相機（${physical.sorted().joinToString("、")}）" else "") +
                (if ((zr?.lower ?: 1f) < 0.95f) "，可縮到 ${"%.1f".format(zr!!.lower)}×" else "")
        }
        val main = cams.firstOrNull { it.listed } ?: return Plan(Mode.CameraX, emptyList(), notes + "找不到公開的後鏡頭")
        val mainEq = main.eq

        // 1. the phone already shares its lenses openly
        val listedFocals = cams.filter { it.listed }.map { it.eq }.distinctBy { (it / mainEq * 10).roundToInt() }
        if (main.zoomMin < 0.95f || listedFocals.size >= 2) {
            return Plan(Mode.CameraX, emptyList(), notes + "手機公開了多顆鏡頭，用一般模式")
        }

        val thread = HandlerThread("scout").apply { start() }
        val handler = Handler(thread.looper)
        try {
            // 2. a hidden combined camera that zooms below 1× (picks lenses by zoom, like the phone's own camera)
            val combined = cams.filter { !it.listed && it.zoomMin < 0.95f }.sortedBy { it.zoomMin }
            for (c in combined) {
                if (!tryOpen(cm, c.id, handler)) { notes += "相機 ${c.id} 打不開"; continue }
                val stops = mutableListOf(c.zoomMin, 1f)
                for (pid in c.physical) {
                    val peq = runCatching { Camera2Backend.equivalent(cm.getCameraCharacteristics(pid)) }.getOrNull() ?: continue
                    val r = peq / mainEq
                    if (r > 1.3f && r <= c.zoomMax) stops += r
                }
                val entries = stops.distinctBy { (it * 20).roundToInt() }.sorted().map { Entry(it, c.id, it) }
                return Plan(Mode.Combined, entries, notes + "使用未公開的組合相機 ${c.id}")
            }

            // 3. separate lenses, the hidden ones opened one by one
            val singles = mutableListOf(main)
            for (c in cams.filter { !it.listed && !it.logical }.sortedBy { it.eq }) {
                if (singles.any { abs(it.eq - c.eq) / it.eq < 0.08f }) continue
                if (tryOpen(cm, c.id, handler)) singles += c else notes += "相機 ${c.id} 打不開"
            }
            if (singles.size >= 2) {
                val entries = singles.sortedBy { it.eq }.map { Entry(it.eq / mainEq, it.id, 1f) }
                return Plan(Mode.Separate, entries, notes + "使用分開的鏡頭：" + entries.joinToString("、") { "相機 ${it.cameraId}" })
            }
        } finally {
            thread.quitSafely()
        }
        return Plan(Mode.CameraX, emptyList(), notes + "沒有其他可用的後鏡頭，放大是數位裁切")
    }

    @SuppressLint("MissingPermission")
    private fun tryOpen(cm: CameraManager, id: String, handler: Handler): Boolean {
        val done = CountDownLatch(1)
        var ok = false
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { ok = true; camera.close(); done.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); done.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); done.countDown() }
            }, handler)
        } catch (_: Throwable) {
            return false
        }
        done.await(4, TimeUnit.SECONDS)
        // give the camera service a moment before the next one
        Thread.sleep(150)
        return ok
    }
}
