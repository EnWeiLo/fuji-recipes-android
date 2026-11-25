package com.enweilo.fujirecipes.camera

import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.data.codeOf
import com.enweilo.fujirecipes.data.emptyRecipe
import com.enweilo.fujirecipes.data.labelOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** What the header of a Fujifilm RAF file says. */
class RafInfo(val model: String, val jpegOffset: Int, val jpegLength: Int) {
    companion object {
        /** Reads the first 100 bytes of a RAF. Null when it is not one. */
        fun parse(head: ByteArray): RafInfo? {
            if (head.size < 92) return null
            val magic = String(head, 0, 15, Charsets.US_ASCII)
            if (!magic.startsWith("FUJIFILMCCD-RAW")) return null
            val model = String(head, 0x1C, 32, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            val bb = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
            return RafInfo(model, bb.getInt(84), bb.getInt(88))
        }
    }
}

/**
 * The camera's own RAW conversion (what FUJIFILM X RAW STUDIO does): the RAF goes into the camera, the camera's
 * processor develops it with the settings we send, and a JPEG comes back.
 *
 * Protocol as documented by FilmKit (github.com/eggricesoy/filmkit, MIT), built on rawji, fudge and libgphoto2.
 * Needs the camera's connection mode on USB RAW CONV./BACKUP RESTORE.
 */
class FujiBaker(private val cam: PtpCam, private val log: (String) -> Unit) {
    /** The camera's profile for the RAF it holds; null until one is loaded. */
    var baseProfile: ByteArray? = null
        private set

    /** Uploads a RAF into the camera's memory and reads back its as-shot profile. */
    fun load(raf: ByteArray, onProgress: (Float) -> Unit) {
        baseProfile = null
        log("傳送 RAW（${"%.1f".format(raf.size / 1048576f)} MB）…")
        val name = ptpStringBytes("FUP_FILE.dat")
        val oi = ByteBuffer.allocate(52 + name.size + 3).order(ByteOrder.LITTLE_ENDIAN)
        oi.putInt(0)            // StorageID
        oi.putShort(0xF802.toShort()) // ObjectFormat: RAF (anything else fails silently)
        oi.putShort(0)          // ProtectionStatus
        oi.putInt(raf.size)     // CompressedSize
        oi.putShort(0)          // ThumbFormat
        oi.putInt(0); oi.putInt(0); oi.putInt(0) // thumb size, width, height
        oi.putInt(0); oi.putInt(0); oi.putInt(0) // image width, height, bit depth
        oi.putInt(0)            // ParentObject
        oi.putShort(0)          // AssociationType
        oi.putInt(0)            // AssociationDesc
        oi.putInt(0)            // SequenceNumber
        oi.put(name)
        oi.put(0); oi.put(0); oi.put(0) // capture date, modification date, keywords
        val r1 = cam.cmd(SEND_OBJECT_INFO, intArrayOf(0, 0, 0), oi.array())
        if (r1.code != PTP_OK) throw PtpException("相機拒收 RAW 資訊（${r1.code.toString(16)}），確認 USB 模式是 RAW CONV.")
        val r2 = cam.cmd(SEND_OBJECT2, IntArray(0), raf, waitMs = 60_000) { done, total -> onProgress(done.toFloat() / total) }
        if (r2.code != PTP_OK) throw PtpException("RAW 傳送失敗（${r2.code.toString(16)}）")
        val prof = cam.getProp(PROP_PROFILE) ?: throw PtpException("讀不到相機的轉檔設定")
        if (prof.size < 100) throw PtpException("轉檔設定長度異常（${prof.size}）")
        baseProfile = prof
        log("RAW 已載入，轉檔設定 ${prof.size} bytes")
    }

    /** Develops the loaded RAF with [recipe]. Returns the JPEG. */
    fun bake(recipe: Recipe, onStage: (Int) -> Unit, onProgress: (Float) -> Unit): ByteArray {
        val base = baseProfile ?: throw PtpException("還沒有載入 RAW")
        // anything left over from an earlier, interrupted conversion would be mistaken for the result
        clearObjects()
        val prof = patch(base, recipe)
        if (cam.cmd(SET_PROP, intArrayOf(PROP_PROFILE), prof).code != PTP_OK) throw PtpException("相機拒收食譜設定")
        onStage(STAGE_BAKING)
        val go = cam.cmd(SET_PROP, intArrayOf(PROP_START), byteArrayOf(0, 0))
        if (go.code != PTP_OK) throw PtpException("相機沒有開始烘焙（${go.code.toString(16)}）")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 45_000) {
            val h = firstHandle()
            if (h != null) {
                onStage(STAGE_OUT)
                val r = cam.cmd(GET_OBJECT, intArrayOf(h), waitMs = 60_000) { done, total -> onProgress(done.toFloat() / total) }
                if (r.code != PTP_OK || r.data.isEmpty()) throw PtpException("取回成品失敗（${r.code.toString(16)}）")
                runCatching { cam.cmd(DELETE_OBJECT, intArrayOf(h)) }
                log("出爐：${"%.1f".format(r.data.size / 1048576f)} MB，${(System.currentTimeMillis() - start) / 100 / 10f} 秒")
                return r.data
            }
            Thread.sleep(400)
        }
        throw PtpException("相機烘焙逾時")
    }

    private fun firstHandle(): Int? {
        val r = cam.cmd(GET_OBJECT_HANDLES, intArrayOf(-1, 0, 0))
        if (r.code != PTP_OK) throw PtpException("查詢成品失敗（${r.code.toString(16)}）")
        if (r.data.size < 8) return null
        val bb = ByteBuffer.wrap(r.data).order(ByteOrder.LITTLE_ENDIAN)
        return if (bb.getInt(0) > 0) bb.getInt(4) else null
    }

    private fun clearObjects() {
        repeat(4) {
            val h = runCatching { firstHandle() }.getOrNull() ?: return
            runCatching { cam.cmd(DELETE_OBJECT, intArrayOf(h)) }
        }
    }

    companion object {
        const val STAGE_SEND = 0
        const val STAGE_CAMERA = 1
        const val STAGE_BAKING = 2
        const val STAGE_OUT = 3

        private const val SET_PROP = 0x1016
        private const val GET_OBJECT_HANDLES = 0x1007
        private const val GET_OBJECT = 0x1009
        private const val DELETE_OBJECT = 0x100B
        private const val SEND_OBJECT_INFO = 0x900C
        private const val SEND_OBJECT2 = 0x900D
        private const val PROP_START = 0xD183
        private const val PROP_PROFILE = 0xD185

        // field positions in the camera's own profile (FilmKit, confirmed on X100VI)
        private const val DYNAMIC_RANGE = 6
        private const val FILM = 8
        private const val GRAIN = 9
        private const val CHROME = 10
        private const val WB = 12
        private const val SHIFT_R = 13
        private const val SHIFT_B = 14
        private const val KELVIN = 15
        private const val HIGHLIGHT = 16
        private const val SHADOW = 17
        private const val COLOR = 18
        private const val SHARPNESS = 19
        private const val NR = 20
        private const val CHROME_BLUE = 25
        private const val CLARITY = 27

        /**
         * The camera's as-shot profile with the recipe's settings written over it. Fields the recipe leaves
         * open keep what the camera had (as shot), and exposure is never touched: the photo is already exposed.
         * The values use the same codes as the C1–C7 slots (see [Tables]).
         */
        fun patch(base: ByteArray, r: Recipe): ByteArray {
            val out = base.copyOf()
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getShort(0).toInt() and 0xFFFF
            val off = out.size - n * 4
            fun set(idx: Int, v: Int) {
                if (idx < n && off + idx * 4 + 4 <= out.size && off >= 2) bb.putInt(off + idx * 4, v)
            }
            fun tenths(t: String) = ((t.replace("+", "").toFloatOrNull() ?: 0f) * 10f).roundToInt()
            fun whole(t: String) = t.replace("+", "").toIntOrNull() ?: 0

            Tables.FILM.codeOf(Tables.normalizeFilm(r.filmSim))?.let { set(FILM, it) }
            if (r.dr != "自動" && r.dr != "AUTO") Tables.DR.codeOf(r.dr)?.takeIf { it > 0 }?.let { set(DYNAMIC_RANGE, it) }
            Tables.GRAIN.codeOf(Tables.normalizeLabel(r.grain))?.let { set(GRAIN, it) }
            Tables.FX.codeOf(Tables.normalizeLabel(r.colorChrome))?.let { set(CHROME, it) }
            Tables.FX.codeOf(Tables.normalizeLabel(r.colorChromeBlue))?.let { set(CHROME_BLUE, it) }
            Tables.WB.codeOf(Tables.normalizeLabel(r.wb))?.let { code ->
                set(WB, code)
                if (code == 0x8007) set(KELVIN, r.kelvin.filter { it.isDigit() }.toIntOrNull() ?: 5500)
            }
            set(SHIFT_R, whole(r.shiftR))
            set(SHIFT_B, whole(r.shiftB))
            set(HIGHLIGHT, tenths(r.highlight))
            set(SHADOW, tenths(r.shadow))
            if (Tables.normalizeFilm(r.filmSim) !in Tables.MONO) set(COLOR, tenths(r.color))
            set(SHARPNESS, tenths(r.sharpness))
            r.nr.replace("+", "").toIntOrNull()?.let { v -> Tables.NR_ENCODE[v]?.let { set(NR, it) } }
            set(CLARITY, tenths(r.clarity))
            return out
        }

        /** The other way round: what a RAF was shot with, as a recipe (from the profile the camera reports). */
        fun recipeFromProfile(profile: ByteArray, name: String): Recipe {
            val bb = ByteBuffer.wrap(profile).order(ByteOrder.LITTLE_ENDIAN)
            val n = bb.getShort(0).toInt() and 0xFFFF
            val off = profile.size - n * 4
            fun get(idx: Int): Int? = if (idx < n && off >= 2 && off + idx * 4 + 4 <= profile.size) bb.getInt(off + idx * 4) else null
            fun tone(v: Int?): String {
                if (v == null) return "0"
                val x = v / 10f
                val t = if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()
                return if (x > 0) "+$t" else t
            }
            fun signed(v: Int?) = if (v == null) "0" else if (v > 0) "+$v" else "$v"
            val base = emptyRecipe()
            val wb = get(WB)?.let { Tables.WB.labelOf(it) } ?: base.wb
            return base.copy(
                name = name,
                notes = "從 RAW 檔讀出的拍攝設定",
                filmSim = get(FILM)?.let { Tables.FILM.labelOf(it) } ?: base.filmSim,
                dr = get(DYNAMIC_RANGE)?.let { Tables.DR.labelOf(it) } ?: "自動",
                grain = get(GRAIN)?.let { Tables.GRAIN.labelOf(it) } ?: base.grain,
                colorChrome = get(CHROME)?.let { Tables.FX.labelOf(it) } ?: base.colorChrome,
                colorChromeBlue = get(CHROME_BLUE)?.let { Tables.FX.labelOf(it) } ?: base.colorChromeBlue,
                wb = wb,
                kelvin = get(KELVIN)?.takeIf { it in 2500..10000 }?.toString() ?: base.kelvin,
                shiftR = signed(get(SHIFT_R)),
                shiftB = signed(get(SHIFT_B)),
                highlight = tone(get(HIGHLIGHT)),
                shadow = tone(get(SHADOW)),
                color = tone(get(COLOR)),
                sharpness = tone(get(SHARPNESS)),
                nr = get(NR)?.let { Tables.NR_DECODE[it] }?.let { if (it > 0) "+$it" else "$it" } ?: base.nr,
                clarity = tone(get(CLARITY)),
                ev = "",
            )
        }

        private fun ptpStringBytes(s: String): ByteArray {
            val n = s.length + 1
            val bb = ByteBuffer.allocate(1 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.put(n.toByte())
            s.forEach { bb.putShort(it.code.toShort()) }
            return bb.array()
        }
    }
}
