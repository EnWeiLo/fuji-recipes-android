package com.enweilo.fujirecipes.shoot

import android.hardware.camera2.CameraCharacteristics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How the viewfinder turns the phone's finished picture back into scene light, shared by the live preview and
 * the JPEG photo path. Starts as the fixed curve from the LUT install; while RAW is on it is kept matched to
 * small RAW frames taken in the background, so the viewfinder shows what the RAW photo will look like.
 */
object ToneCalib {
    /** display value (0..255) → scene light; null = the fixed curve */
    @Volatile var curve: FloatArray? = null
        private set
    /** per-channel colour correction in scene light */
    @Volatile var fix: FloatArray = floatArrayOf(1f, 1f, 1f)
        private set
    /** bumps on every change, so GL programs know to upload again */
    @Volatile var version = 0
        private set
    @Volatile var count = 0
        private set
    @Volatile var lastScale = 0f
        private set

    fun reset() {
        curve = null
        fix = floatArrayOf(1f, 1f, 1f)
        count = 0
        version++
    }

    /** Takes in a new measurement, blending with the last one so the picture does not jump. */
    fun update(newCurve: FloatArray, newFix: FloatArray, scale: Float) {
        val old = curve
        val k = if (old == null) 1f else 0.5f
        curve = if (old == null) newCurve else FloatArray(256) { i ->
            val a = max(old[i], 1e-6f)
            val b = max(newCurve[i], 1e-6f)
            if (i == 0) 0f else exp(ln(a) + (ln(b) - ln(a)) * k)
        }
        val f = fix
        fix = FloatArray(3) { f[it] + (newFix[it] - f[it]) * k }
        lastScale = scale
        count++
        version++
    }
}

/** A few thousand 2×2 cells of a RAW frame, taken for [ToneCalib] (not a photo). */
class RawProbe(
    val frame: Camera2Backend.RawFrame,
    val gw: Int,
    val gh: Int,
    /** x, y (in RAW pixels, even) of each cell */
    val xs: IntArray,
    val ys: IntArray,
    /** 4 values per cell: (0,0) (1,0) (0,1) (1,1) */
    val cells: ShortArray,
)

object PreviewFit {
    private fun dec(s: Float): Float = if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    private fun enc(l: Float): Float = if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1 / 2.4) - 0.055).toFloat()

    class Fit(val curve: FloatArray, val fix: FloatArray, val scale: Float)

    /**
     * Matches the viewfinder picture ([prev], ARGB, [pw]×[ph], the camera's stream) to the RAW cells by
     * brightness rank: the n-th brightest spot of one is the n-th brightest of the other. That needs no exact
     * alignment and copes with the phone's local tone mapping as well as one curve can.
     * [crop] is how much the stream is zoomed into the RAW frame; [static] the fixed curve, for the overall level.
     */
    fun fit(
        p: RawProbe, black: FloatArray, white: Float, gains: FloatArray, ccm: FloatArray, cfaRed: IntArray,
        shading: android.hardware.camera2.params.LensShadingMap?,
        prev: IntArray, pw: Int, ph: Int, crop: Float, static: FloatArray,
    ): Fit? {
        val f = p.frame
        val ch = f.chars
        val pa = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sx = if (pa != null && pa.width > 0) f.width.toFloat() / pa.width else 1f
        val sy = if (pa != null && pa.height > 0) f.height.toFloat() / pa.height else 1f
        val act = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val ax0 = (act?.left ?: 0) * sx
        val ay0 = (act?.top ?: 0) * sy
        val aw = (act?.width() ?: f.width) * sx
        val ah = (act?.height() ?: f.height) * sy
        val k = crop.coerceAtLeast(1f)
        val rw = aw / k
        val rh = ah / k
        val rx0 = ax0 + (aw - rw) / 2f
        val ry0 = ay0 + (ah - rh) / 2f

        val ri = cfaRed[0] + 2 * cfaRed[1]
        val bi = 3 - ri
        // which shading channel each 2×2 position is: R, G (red row), G (blue row), B
        val shadeCh = IntArray(4) { pos ->
            when (pos) { ri -> 0; bi -> 3; else -> if (pos / 2 == ri / 2) 1 else 2 }
        }
        val rawL = ArrayList<Float>(p.gw * p.gh)
        val rawRgb = ArrayList<FloatArray>(p.gw * p.gh)
        for (i in 0 until p.gw * p.gh) {
            val u = (p.xs[i] - rx0) / rw
            val v = (p.ys[i] - ry0) / rh
            if (u < 0.08f || u > 0.92f || v < 0.08f || v > 0.92f) continue
            val q = FloatArray(4)
            for (j in 0 until 4) {
                val raw = (p.cells[i * 4 + j].toInt() and 0xFFFF).toFloat()
                var x = ((raw - black[j]) / (white - black[j])).coerceAtLeast(0f)
                if (shading != null) {
                    val cx = ((p.xs[i] - ax0) / aw * (shading.columnCount - 1)).toInt().coerceIn(0, shading.columnCount - 1)
                    val cy = ((p.ys[i] - ay0) / ah * (shading.rowCount - 1)).toInt().coerceIn(0, shading.rowCount - 1)
                    x *= shading.getGainFactor(shadeCh[j], cx, cy)
                }
                q[j] = x
            }
            val rr = q[ri] * gains[0]
            val bb = q[bi] * gains[2]
            val gg = (q.sum() - q[ri] - q[bi]) / 2f * gains[1]
            val lr = max(0f, ccm[0] * rr + ccm[1] * gg + ccm[2] * bb)
            val lg = max(0f, ccm[3] * rr + ccm[4] * gg + ccm[5] * bb)
            val lb = max(0f, ccm[6] * rr + ccm[7] * gg + ccm[8] * bb)
            rawL += 0.2126f * lr + 0.7152f * lg + 0.0722f * lb
            rawRgb += floatArrayOf(lr, lg, lb)
        }
        val prevD = ArrayList<Float>(pw * ph)
        val prevLin = ArrayList<FloatArray>(pw * ph)
        for (y in (ph * 0.08f).toInt() until (ph * 0.92f).toInt()) for (x in (pw * 0.08f).toInt() until (pw * 0.92f).toInt()) {
            val c = prev[y * pw + x]
            val r = dec(((c shr 16) and 255) / 255f)
            val g = dec(((c shr 8) and 255) / 255f)
            val b = dec((c and 255) / 255f)
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            prevD += enc(l).coerceIn(0f, 1f)
            prevLin += floatArrayOf(r, g, b)
        }
        if (rawL.size < 400 || prevD.size < 400) return null
        val rs = rawL.sorted()
        val ps = prevD.sorted()
        fun rawAt(pr: Float): Float {
            val x = pr.coerceIn(0f, 1f) * (rs.size - 1)
            val i = x.toInt().coerceAtMost(rs.size - 2)
            return rs[i] + (rs[i + 1] - rs[i]) * (x - i)
        }
        // fraction of the viewfinder darker than display value v (middle of ties)
        fun rankOf(v: Float): Float {
            var lo = 0; var hi = ps.size
            while (lo < hi) { val m = (lo + hi) / 2; if (ps[m] < v) lo = m + 1 else hi = m }
            val below = lo
            lo = below; hi = ps.size
            while (lo < hi) { val m = (lo + hi) / 2; if (ps[m] <= v) lo = m + 1 else hi = m }
            return (below + lo) / 2f / ps.size
        }
        val qLo = ps[(ps.size * 0.01f).toInt()]
        val qHi = ps[(ps.size * 0.99f).toInt().coerceAtMost(ps.size - 1)]
        val matched = FloatArray(256)
        val valid = BooleanArray(256)
        for (i in 1 until 256) {
            val v = i / 255f
            if (v < qLo || v > qHi) continue
            matched[i] = rawAt(rankOf(v))
            valid[i] = matched[i] > 0f
        }
        // overall level: agree with the fixed curve in the middle tones (as the RAW photo's exposure does)
        val ratios = ArrayList<Float>()
        for (i in 1 until 256) if (valid[i] && i / 255f in 0.15f..0.75f) ratios += static[i] / matched[i]
        if (ratios.size < 12) return null
        ratios.sort()
        val scale = ratios[ratios.size / 2]
        val first = (1 until 256).firstOrNull { valid[it] } ?: return null
        val last = (1 until 256).lastOrNull { valid[it] } ?: return null
        val c = FloatArray(256)
        for (i in 1 until 256) {
            c[i] = when {
                i < first -> static[i] * (matched[first] * scale / max(static[first], 1e-6f))
                i > last -> static[i] * (matched[last] * scale / max(static[last], 1e-6f))
                valid[i] -> matched[i] * scale
                else -> static[i]
            }
        }
        // smooth a little (log domain), then never let it go down
        val lg = FloatArray(256) { ln(max(c[it], 1e-6f)) }
        repeat(2) {
            val t = lg.copyOf()
            for (i in 2 until 254) lg[i] = (t[i - 2] + t[i - 1] + t[i] + t[i + 1] + t[i + 2]) / 5f
        }
        val out = FloatArray(256)
        var mx = 0f
        for (i in 1 until 256) { mx = max(mx, exp(lg[i])); out[i] = min(mx, 40f) }
        out[0] = 0f

        // colour: the middle tones of both, in scene light
        var pr = 0.0; var pg = 0.0; var pb = 0.0
        for (j in prevD.indices) {
            val d = prevD[j]
            if (d < 0.2f || d > 0.8f) continue
            val lin = prevLin[j]
            val l = 0.2126f * lin[0] + 0.7152f * lin[1] + 0.0722f * lin[2]
            if (l <= 1e-5f) continue
            pr += lin[0] / l; pg += lin[1] / l; pb += lin[2] / l
        }
        val lo2 = out[(0.2f * 255).toInt()] / scale
        val hi2 = out[(0.8f * 255).toInt()] / scale
        var qr = 0.0; var qg = 0.0; var qb = 0.0
        for (j in rawL.indices) {
            val l = rawL[j]
            if (l < lo2 || l > hi2 || l <= 1e-6f) continue
            val rgb = rawRgb[j]
            qr += rgb[0] / l; qg += rgb[1] / l; qb += rgb[2] / l
        }
        var fix = floatArrayOf(1f, 1f, 1f)
        if (pg > 0 && qg > 0) {
            val a = floatArrayOf((qr / pr).toFloat(), (qg / pg).toFloat(), (qb / pb).toFloat())
            val l = 0.2126f * a[0] + 0.7152f * a[1] + 0.0722f * a[2]
            if (l > 0f && a.all { it.isFinite() }) fix = FloatArray(3) { (a[it] / l).coerceIn(0.8f, 1.25f) }
        }
        if (out.any { !it.isFinite() } || abs(ln(scale)) > 6f) return null
        return Fit(out, fix, scale)
    }
}
