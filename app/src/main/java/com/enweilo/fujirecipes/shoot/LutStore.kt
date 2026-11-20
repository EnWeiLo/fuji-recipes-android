package com.enweilo.fujirecipes.shoot

import android.content.Context
import android.net.Uri
import android.util.Half
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Fujifilm's official film simulation LUTs (F-Log2 → film simulation, BT.709).
 *
 * They are not shipped with the app: the app downloads Fujifilm's own zip, keeps only the F-Log2 33-point cubes and
 * turns each one into a "phone LUT" that takes an ordinary phone picture (sRGB) directly, so the shader needs
 * a single 3D lookup. Stored as half floats in files/luts/<KEY>.lut.
 */
class LutStore(private val context: Context) {

    companion object {
        const val GRID = 33
        const val PAGE = "https://www.fujifilm-x.com/en-us/support/download/lut/"
        const val FALLBACK_URL = "https://dl.fujifilm-x.com/support/lut/gfx-eterna-55-3d-lut-v110.zip"

        /** Keys as they appear in Fujifilm's file names, with the names shown in the app. */
        val NAMES = linkedMapOf(
            "PROVIA" to "PROVIA", "Velvia" to "Velvia", "ASTIA" to "ASTIA",
            "CLASSIC-CHROME" to "Classic Chrome", "REALA-ACE" to "REALA ACE",
            "PRO-Neg.Std" to "PRO Neg. Std", "CLASSIC-Neg." to "Classic Neg.",
            "ETERNA" to "ETERNA", "ETERNA-BB" to "ETERNA 漂白", "ACROS" to "ACROS",
        )

        private val NAME_RE = Regex("""FLog2_to_(.+?)_(\d+)grid""", RegexOption.IGNORE_CASE)

        fun keyOf(fileName: String): String? {
            val m = NAME_RE.find(fileName.substringAfterLast('/').substringAfterLast('\\')) ?: return null
            val raw = m.groupValues[1]
            return NAMES.keys.firstOrNull { it.equals(raw, ignoreCase = true) }
        }
    }

    private val dir = File(context.filesDir, "luts").apply { mkdirs() }

    fun installed(): Set<String> =
        NAMES.keys.filter { File(dir, "$it.lut").length() == (GRID * GRID * GRID * 8).toLong() }.toSet()

    /** Film simulations whose original F-Log2 LUT is kept too (needed to develop RAW photos). */
    fun installedFlog(): Set<String> =
        NAMES.keys.filter { File(dir, "$it.flog").length() == (GRID * GRID * GRID * 8).toLong() }.toSet()

    /** Fujifilm's own F-Log2 → film simulation LUT (33 points, half-float RGBA), or null. */
    fun loadFlog(key: String): ByteBuffer? {
        val f = File(dir, "$key.flog")
        if (f.length() != (GRID * GRID * GRID * 8).toLong()) return null
        val bytes = f.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); position(0) }
    }

    /** The curve that undoes a phone's own picture look (display value → scene light), 256 steps. */
    fun phoneCurve(): FloatArray? = loadCurve()

    /** Half-float RGBA data ready for glTexImage3D, or null. */
    fun load(key: String): ByteBuffer? {
        val f = File(dir, "$key.lut")
        if (!f.exists()) return null
        val bytes = f.readBytes()
        if (bytes.size != GRID * GRID * GRID * 8) return null
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); position(0) }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    // ---------------- download ----------------

    /** Finds the newest zip on Fujifilm's page; falls back to the address known when this was written. */
    private fun findUrl(): String = try {
        val c = URL(PAGE).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FujiRecipes")
        val html = c.inputStream.bufferedReader().use { it.readText() }
        Regex("""https?://dl\.fujifilm-x\.com/[^"'\s]*gfx-eterna-55-3d-lut-v(\d+)\.zip""")
            .findAll(html).maxByOrNull { it.groupValues[1].toIntOrNull() ?: 0 }?.value ?: FALLBACK_URL
    } catch (_: Throwable) {
        FALLBACK_URL
    }

    /** Size in bytes of the file that will be downloaded, or -1. */
    fun probeSize(): Pair<String, Long> {
        val url = findUrl()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "HEAD"
            c.connectTimeout = 10000; c.readTimeout = 15000
            url to c.contentLengthLong
        } catch (_: Throwable) {
            url to -1L
        }
    }

    /**
     * Downloads and installs. [progress] gets (fraction 0..1, text). Returns the number of film simulations installed.
     * Throws with a readable message on failure.
     */
    fun download(url: String, progress: (Float, String) -> Unit): Int {
        val zip = File(context.cacheDir, "fuji-lut.zip")
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FujiRecipes")
            if (c.responseCode !in 200..299) error("富士官網回應 ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) {
                            last = done
                            val f = if (total > 0) done.toFloat() / total else 0f
                            progress(f * 0.8f, "下載中 ${done / 1_000_000} / ${if (total > 0) total / 1_000_000 else "?"} MB")
                        }
                    }
                }
            }
            progress(0.82f, "解壓縮中…")
            return zip.inputStream().use { installZip(it, progress) }
        } finally {
            zip.delete()
        }
    }

    /** From a file the user picked: a Fujifilm zip or one or more .cube files. */
    fun importUris(uris: List<Uri>, progress: (Float, String) -> Unit): Int {
        val cubes = mutableMapOf<String, Cube>()
        for (uri in uris) {
            val name = uri.lastPathSegment ?: ""
            val head = context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(2).also { s.read(it) } }
            val isZip = head != null && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            context.contentResolver.openInputStream(uri)?.use { s ->
                if (isZip) collectZip(s, cubes) else {
                    val key = keyOf(name) ?: displayKey(uri) ?: return@use
                    cubes[key] = parseCube(s)
                }
            }
        }
        return bakeAll(cubes, progress)
    }

    private fun displayKey(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) keyOf(it.getString(0) ?: "") else null }
    } catch (_: Throwable) {
        null
    }

    private fun installZip(input: InputStream, progress: (Float, String) -> Unit): Int {
        val cubes = mutableMapOf<String, Cube>()
        collectZip(input, cubes)
        return bakeAll(cubes, progress)
    }

    /** Keeps only the F-Log2 (not F-Log2C) 33-point cubes. */
    private fun collectZip(input: InputStream, into: MutableMap<String, Cube>) {
        val z = ZipInputStream(input.buffered())
        while (true) {
            val e = z.nextEntry ?: break
            val name = e.name.replace('\\', '/')
            if (e.isDirectory || !name.endsWith(".cube", ignoreCase = true)) continue
            val file = name.substringAfterLast('/')
            if (!file.startsWith("FLog2_to_", ignoreCase = true)) continue
            val inLogFolder = name.contains("/F-Log2/", ignoreCase = true) || !name.contains('/')
            if (!inLogFolder) continue
            val key = keyOf(file) ?: continue
            val grid = NAME_RE.find(file)?.groupValues?.get(2)?.toIntOrNull() ?: 0
            if (grid != 33) continue // the 65-point files are 8× bigger and look the same after baking
            into[key] = parseCube(z)
        }
    }

    // ---------------- maths ----------------

    class Cube(val size: Int, val data: FloatArray)

    private fun parseCube(input: InputStream): Cube {
        val r = BufferedReader(InputStreamReader(input), 1 shl 16)
        var size = 0
        var data = FloatArray(0)
        var n = 0
        while (true) {
            val line = r.readLine() ?: break
            val t = line.trim()
            if (t.isEmpty() || t[0] == '#') continue
            if (t.startsWith("LUT_3D_SIZE")) {
                size = t.substringAfter("LUT_3D_SIZE").trim().toInt()
                data = FloatArray(size * size * size * 3)
                continue
            }
            if (!(t[0].isDigit() || t[0] == '-' || t[0] == '.')) continue
            if (size == 0) continue
            var i = 0
            var start = -1
            for (k in 0..t.length) {
                val ch = if (k < t.length) t[k] else ' '
                if (ch == ' ' || ch == '\t') {
                    if (start >= 0 && i < 3 && n < data.size) {
                        data[n++] = t.substring(start, k).toFloat(); i++
                    }
                    start = -1
                } else if (start < 0) start = k
            }
        }
        if (size == 0 || n != data.size) error("LUT 檔案格式不對")
        return Cube(size, data)
    }

    private fun sample(c: Cube, r0: Float, g0: Float, b0: Float, out: FloatArray) {
        val n = c.size
        val m = n - 1
        val x = r0.coerceIn(0f, 1f) * m
        val y = g0.coerceIn(0f, 1f) * m
        val z = b0.coerceIn(0f, 1f) * m
        val xi = min(floor(x).toInt(), m - 1); val yi = min(floor(y).toInt(), m - 1); val zi = min(floor(z).toInt(), m - 1)
        val fx = x - xi; val fy = y - yi; val fz = z - zi
        val d = c.data
        for (ch in 0 until 3) {
            fun at(dx: Int, dy: Int, dz: Int) = d[(((zi + dz) * n + (yi + dy)) * n + (xi + dx)) * 3 + ch]
            val c00 = at(0, 0, 0) * (1 - fx) + at(1, 0, 0) * fx
            val c01 = at(0, 1, 0) * (1 - fx) + at(1, 1, 0) * fx
            val c10 = at(0, 0, 1) * (1 - fx) + at(1, 0, 1) * fx
            val c11 = at(0, 1, 1) * (1 - fx) + at(1, 1, 1) * fx
            val c0 = c00 * (1 - fy) + c01 * fy
            val c1 = c10 * (1 - fy) + c11 * fy
            out[ch] = c0 * (1 - fz) + c1 * fz
        }
    }

    private fun flog2(x: Float): Float =
        if (x >= 0.000889f) (0.245281 * log10(5.555556 * x + 0.064829) + 0.384316).toFloat() else 8.799461f * x + 0.092864f

    private fun dec(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun enc(l: Float): Float =
        if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()

    /**
     * Phone pictures are already "finished" (tone mapped), while the LUTs expect scene light in F-Log2.
     * Build the curve that undoes the phone look so that PROVIA reproduces a grey ramp unchanged.
     */
    private fun inverseTone(provia: Cube): FloatArray {
        val steps = 4000
        val xs = FloatArray(steps)
        val gs = FloatArray(steps)
        val tmp = FloatArray(3)
        val lo = kotlin.math.ln(1e-4); val hi = kotlin.math.ln(200.0)
        var best = 0f
        for (i in 0 until steps) {
            val x = kotlin.math.exp(lo + (hi - lo) * i / (steps - 1)).toFloat()
            val f = flog2(x)
            sample(provia, f, f, f, tmp)
            best = max(best, tmp[1])
            xs[i] = x; gs[i] = best
        }
        val t = FloatArray(256)
        var j = 0
        for (k in 0 until 256) {
            val v = k / 255f
            while (j < steps - 2 && gs[j + 1] < v) j++
            val g0 = gs[j]; val g1 = gs[j + 1]
            val f = if (g1 > g0) ((v - g0) / (g1 - g0)).coerceIn(0f, 1f) else 0f
            t[k] = min(xs[j] + (xs[j + 1] - xs[j]) * f, 12f)
        }
        t[0] = 0f
        return t
    }

    private fun bakeAll(cubes: Map<String, Cube>, progress: (Float, String) -> Unit): Int {
        val provia = cubes["PROVIA"] ?: if (installed().isEmpty()) error("檔案裡找不到 F-Log2 的 PROVIA LUT") else null
        val curve = provia?.let { inverseTone(it) } ?: loadCurve() ?: error("請匯入包含 PROVIA 的富士 LUT")
        saveCurve(curve)
        var done = 0
        for ((key, cube) in cubes) {
            progress(0.85f + 0.15f * done / max(1, cubes.size), "處理 ${NAMES[key] ?: key}…")
            bake(key, cube, curve)
            done++
        }
        progress(1f, "完成")
        return done
    }

    private fun saveCurve(t: FloatArray) {
        val b = ByteBuffer.allocate(t.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        t.forEach { b.putFloat(it) }
        File(dir, "curve.bin").writeBytes(b.array())
    }

    private fun loadCurve(): FloatArray? {
        val f = File(dir, "curve.bin")
        if (f.length() != 1024L) return null
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(256) { b.float }
    }

    /** Keeps Fujifilm's LUT as it is (resampled to 33 points if needed), for RAW photos. */
    private fun saveFlog(key: String, cube: Cube) {
        val n = GRID
        val out = ByteBuffer.allocate(n * n * n * 8).order(ByteOrder.nativeOrder())
        val tmp = FloatArray(3)
        val one = Half.toHalf(1f)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            if (cube.size == n) {
                val i = ((b * n + g) * n + r) * 3
                tmp[0] = cube.data[i]; tmp[1] = cube.data[i + 1]; tmp[2] = cube.data[i + 2]
            } else {
                sample(cube, r / (n - 1f), g / (n - 1f), b / (n - 1f), tmp)
            }
            out.putShort(Half.toHalf(tmp[0].coerceIn(0f, 1f)))
            out.putShort(Half.toHalf(tmp[1].coerceIn(0f, 1f)))
            out.putShort(Half.toHalf(tmp[2].coerceIn(0f, 1f)))
            out.putShort(one)
        }
        val t = File(dir, "$key.flog.tmp")
        t.writeBytes(out.array())
        t.renameTo(File(dir, "$key.flog"))
    }

    private fun bake(key: String, cube: Cube, curve: FloatArray) {
        saveFlog(key, cube)
        val n = GRID
        val out = ByteBuffer.allocate(n * n * n * 8).order(ByteOrder.nativeOrder())
        val tmp = FloatArray(3)
        val one = Half.toHalf(1f)
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val lr = dec(r / (n - 1f)); val lg = dec(g / (n - 1f)); val lb = dec(b / (n - 1f))
            val y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            var x0 = 0f; var x1 = 0f; var x2 = 0f
            if (y > 1e-6f) {
                val yd = enc(y).coerceIn(0f, 1f) * 255f
                val i = min(floor(yd).toInt(), 254)
                val ys = curve[i] + (curve[i + 1] - curve[i]) * (yd - i)
                val k = ys / y
                val sr = lr * k; val sg = lg * k; val sb = lb * k
                // BT.709 → F-Gamut (BT.2020 primaries)
                x0 = 0.6274f * sr + 0.3293f * sg + 0.0433f * sb
                x1 = 0.0691f * sr + 0.9195f * sg + 0.0114f * sb
                x2 = 0.0164f * sr + 0.0880f * sg + 0.8956f * sb
            }
            sample(cube, flog2(max(x0, 0f)), flog2(max(x1, 0f)), flog2(max(x2, 0f)), tmp)
            out.putShort(Half.toHalf(tmp[0].coerceIn(0f, 1f)))
            out.putShort(Half.toHalf(tmp[1].coerceIn(0f, 1f)))
            out.putShort(Half.toHalf(tmp[2].coerceIn(0f, 1f)))
            out.putShort(one)
        }
        val tmpFile = File(dir, "$key.tmp")
        tmpFile.writeBytes(out.array())
        tmpFile.renameTo(File(dir, "$key.lut"))
    }
}
