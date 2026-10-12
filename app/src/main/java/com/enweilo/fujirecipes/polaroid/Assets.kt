package com.enweilo.fujirecipes.polaroid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import com.enweilo.fujirecipes.R

class FontChoice(val key: String, val label: String)

/** Fonts for the card. Files that are missing fall back to a system font, so drawing never fails. */
object Fonts {
    val choices = listOf(
        FontChoice("serif", "經典明體"),
        FontChoice("mono", "等寬數字"),
        FontChoice("hand", "手寫英文 Caveat"),
        FontChoice("round", "圓體 Zen Maru"),
        FontChoice("kiwi", "溫潤明體 Kiwi Maru"),
        FontChoice("yuji", "懷舊墨楷 Yuji Syuku"),
        FontChoice("chen", "辰宇落雁 2.0（細體）"),
        FontChoice("huninn", "jf open 粉圓 2.1"),
        FontChoice("genryu-m", "源流明體 TW（中等）"),
        FontChoice("genryu-sb", "源流明體 TW（半粗）"),
        FontChoice("genryu-b", "源流明體 TW（粗體）"),
        FontChoice("genryu-h", "源流明體 TW（特粗）"),
    )

    /** Fonts that only come in one weight; the web version never makes them bold. */
    private val single = setOf("chen", "huninn", "genryu-m", "genryu-sb", "genryu-b", "genryu-h")

    private val profiles: Map<String, FloatArray> = mapOf(
        "huninn" to floatArrayOf(12f, 14f, 18f, 22f),
        "serif" to floatArrayOf(12.4f, 14.5f, 18.6f, 22.7f),
        "mono" to floatArrayOf(13.4f, 15.6f, 20.1f, 24.6f),
        "hand" to floatArrayOf(16.2f, 18.7f, 23.8f, 29.0f),
        "round" to floatArrayOf(12.7f, 14.8f, 19.0f, 23.2f),
        "kiwi" to floatArrayOf(12.6f, 14.7f, 18.9f, 23.1f),
        "yuji" to floatArrayOf(13.4f, 15.5f, 19.8f, 24.1f),
        "chen" to floatArrayOf(20.4f, 23.1f, 29.0f, 35.2f),
        "genryu-m" to floatArrayOf(12.3f, 14.4f, 18.5f, 22.6f),
        "genryu-sb" to floatArrayOf(12.2f, 14.2f, 18.3f, 22.4f),
        "genryu-b" to floatArrayOf(12.1f, 14.1f, 18.1f, 22.2f),
        "genryu-h" to floatArrayOf(12f, 14f, 18f, 22f),
    )

    private val files = mapOf(
        "mono" to "sharetechmono.ttf",
        "hand" to "caveat.ttf",
        "round" to "zenmaru.ttf",
        "kiwi" to "kiwimaru.ttf",
        "yuji" to "yuji.ttf",
        "chen" to "chen.ttf",
        "huninn" to "huninn.ttf",
        "genryu-m" to "genryu_m.otf",
        "genryu-sb" to "genryu_sb.otf",
        "genryu-b" to "genryu_b.otf",
        "genryu-h" to "genryu_h.otf",
    )

    private val cache = HashMap<String, Typeface>()
    private var context: Context? = null

    fun init(c: Context) {
        context = c.applicationContext
    }

    fun typeface(key: String): Typeface = synchronized(cache) {
        cache.getOrPut(key) { load(key) }
    }

    private fun load(key: String): Typeface {
        val file = files[key]
        val c = context
        if (file != null && c != null) {
            try {
                return Typeface.createFromAsset(c.assets, "polaroid/fonts/$file")
            } catch (_: Exception) {
            }
        }
        return when (key) {
            "mono" -> Typeface.MONOSPACE
            "hand" -> Typeface.create("cursive", Typeface.NORMAL)
            "round" -> Typeface.DEFAULT
            "serif", "kiwi", "yuji" -> Typeface.SERIF
            else -> Typeface.DEFAULT
        }
    }

    fun isSingleWeight(key: String) = key in single

    fun usable(key: String): String = if (choices.any { it.key == key }) key else "huninn"

    /** Pixel size at 360px card width for the 小/中/大/特大 setting (12/14/18/22). */
    fun sizeFor(key: String, level: Int): Float {
        val i = listOf(12, 14, 18, 22).indexOf(level)
        return profiles[key]?.get(if (i < 0) 1 else i) ?: level.toFloat()
    }

    fun label(key: String): String = choices.firstOrNull { it.key == key }?.label ?: ""
}

/** Camera maker logos, stored as black-on-transparent masks so they can be tinted to the text colour. */
object Logos {
    private val ids = mapOf(
        "sony" to R.drawable.logo_sony,
        "alpha" to R.drawable.logo_alpha,
        "nikon" to R.drawable.logo_nikon,
        "fujifilm" to R.drawable.logo_fujifilm,
        "z5ii" to R.drawable.logo_z5ii,
        "z6" to R.drawable.logo_z6,
        "xt50" to R.drawable.logo_xt50,
    )
    private val accentIds = mapOf("z5ii" to R.drawable.logo_z5ii_accent)

    private val masks = HashMap<String, Bitmap?>()
    private val accents = HashMap<String, Bitmap?>()
    private val scaled = HashMap<String, Bitmap>()
    private var context: Context? = null

    fun init(c: Context) {
        context = c.applicationContext
    }

    private fun decode(id: Int): Bitmap? {
        val c = context ?: return null
        return try {
            val o = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(c.resources, id, o)
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun mask(mark: String): Bitmap? = masks.getOrPut(mark) { ids[mark]?.let { decode(it) } }

    @Synchronized
    fun accent(mark: String): Bitmap? = accents.getOrPut(mark) { accentIds[mark]?.let { decode(it) } }

    fun has(mark: String?): Boolean = mark != null && mask(mark) != null

    /** Width, height and vertical offset of a logo inside a text line of the given height. */
    fun layout(mark: String, height: Float): LogoBox {
        val m = mask(mark) ?: return LogoBox(0f, height, 0f)
        val h = height * (if (mark == "xt50") 0.8f else 1f)
        return LogoBox(h * m.width / m.height, h, (height - h) / 2f)
    }

    /** A copy of the bitmap near the wanted size; large reductions are done in halves so edges stay clean. */
    @Synchronized
    fun scaledTo(source: Bitmap, key: String, w: Int, h: Int): Bitmap {
        val id = "$key:$w"
        scaled[id]?.let { return it }
        var current = source
        while (current.width / 2 >= w && current.width / 2 >= 1 && current.height / 2 >= 1) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val result = if (current.width == w && current.height == h) current
        else Bitmap.createScaledBitmap(current, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
        if (scaled.size > 40) scaled.clear()
        scaled[id] = result
        return result
    }
}

class LogoBox(val width: Float, val height: Float, val top: Float)
