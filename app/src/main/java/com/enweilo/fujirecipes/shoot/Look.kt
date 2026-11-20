package com.enweilo.fujirecipes.shoot

import com.enweilo.fujirecipes.data.Recipe
import kotlin.math.exp

/**
 * Everything the shader needs to draw one recipe. Built from a [Recipe]; the film simulation itself comes from
 * Fujifilm's official F-Log2 LUT named by [lutKey], the other recipe values are applied around it.
 */
data class Look(
    val lutKey: String?,
    /** true when the film simulation has no official LUT and is imitated instead */
    val approx: Boolean,
    val gain: FloatArray = floatArrayOf(1f, 1f, 1f),
    val dr: Float = 0f,
    /** turn the picture grey before the LUT, mixing the channels with [monoW] (colour filters for black & white) */
    val preMono: Boolean = false,
    val monoW: FloatArray = LUMA,
    /** turn the result grey after the LUT and multiply by [tint] */
    val postMono: Boolean = false,
    val tint: FloatArray = floatArrayOf(1f, 1f, 1f),
    val contrast: Float = 0f,
    val warmHi: Float = 0f,
    val sat: Float = 1f,
    val hi: Float = 0f,
    val sh: Float = 0f,
    val chrome: Float = 0f,
    val chromeBlue: Float = 0f,
    val grain: Float = 0f,
    val grainCells: Float = 1400f,
    /** recipe sharpness, noise reduction and clarity (−4…+4); used when developing RAW */
    val sharp: Float = 0f,
    val nr: Float = 0f,
    val clarity: Float = 0f,
    /** colour temperature recipes: turns the camera's nearest white balance preset into the asked Kelvin */
    val wbAdapt: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    companion object {
        val LUMA = floatArrayOf(0.2126f, 0.7152f, 0.0722f)
        private val YE = floatArrayOf(0.34f, 0.60f, 0.06f)
        private val RED = floatArrayOf(0.62f, 0.34f, 0.04f)
        private val GREEN = floatArrayOf(0.14f, 0.78f, 0.08f)
        private val SEPIA = floatArrayOf(1.08f, 0.96f, 0.78f)

        /** Plain picture, nothing applied. */
        val NONE = Look(lutKey = null, approx = false)

        fun of(r: Recipe): Look {
            val sim = r.filmSim
            var key: String? = "PROVIA"
            var approx = false
            var preMono = false
            var monoW = LUMA
            var postMono = false
            var tint = floatArrayOf(1f, 1f, 1f)
            var contrast = 0f
            var warmHi = 0f
            var satK = 1f

            fun filter(name: String): FloatArray = when {
                name.endsWith("+Ye") || name.endsWith("+Y") -> YE
                name.endsWith("+R") -> RED
                name.endsWith("+G") -> GREEN
                else -> LUMA
            }

            when {
                sim.startsWith("PROVIA") -> key = "PROVIA"
                sim.startsWith("Velvia") -> key = "Velvia"
                sim.startsWith("ASTIA") -> key = "ASTIA"
                sim == "Classic Chrome" -> key = "CLASSIC-CHROME"
                sim == "Classic Neg." -> key = "CLASSIC-Neg."
                sim == "ETERNA Bleach Bypass" -> key = "ETERNA-BB"
                sim.startsWith("ETERNA") -> key = "ETERNA"
                sim == "REALA ACE" -> key = "REALA-ACE"
                sim == "PRO Neg. Std" -> key = "PRO-Neg.Std"
                sim == "PRO Neg. Hi" -> { key = "PRO-Neg.Std"; approx = true; contrast = 0.35f }
                sim == "Nostalgic Neg." -> { key = "PROVIA"; approx = true; warmHi = 1f; satK = 0.9f; contrast = -0.15f }
                sim.startsWith("ACROS") -> {
                    key = "ACROS"
                    if (sim != "ACROS") { approx = true; preMono = true; monoW = filter(sim) }
                }
                sim == "Sepia" -> { key = "PROVIA"; approx = true; preMono = true; postMono = true; tint = SEPIA }
                sim.startsWith("Monochrome") -> {
                    key = "PROVIA"; approx = true; preMono = true; postMono = true; monoW = filter(sim)
                }
                else -> { key = "PROVIA"; approx = true }
            }

            // White balance shift: each step nudges red or blue a little; keep brightness the same.
            val shiftR = num(r.shiftR)
            val shiftB = num(r.shiftB)
            val warm = if (r.wb == "自動（氣氛優先）") 1.5f else 0f
            var gr = exp(0.045f * (shiftR + warm))
            var gb = exp(0.045f * (shiftB - warm))
            var gg = 1f
            val y = LUMA[0] * gr + LUMA[1] * gg + LUMA[2] * gb
            gr /= y; gg /= y; gb /= y

            val dr = when (r.dr) { "DR200" -> 1f; "DR400" -> 2f; else -> 0f }

            val grain = when {
                r.grain.startsWith("強") -> 0.075f
                r.grain.startsWith("弱") -> 0.045f
                else -> 0f
            }
            val cells = if (r.grain.endsWith("大")) 820f else 1500f

            fun fx(v: String) = when (v) { "強" -> 1f; "弱" -> 0.55f; else -> 0f }

            return Look(
                lutKey = key,
                approx = approx,
                gain = floatArrayOf(gr, gg, gb),
                dr = dr,
                preMono = preMono,
                monoW = monoW,
                postMono = postMono,
                tint = tint,
                contrast = contrast,
                warmHi = warmHi,
                sat = (1f + 0.1f * num(r.color)) * satK,
                hi = num(r.highlight),
                sh = num(r.shadow),
                chrome = fx(r.colorChrome),
                chromeBlue = fx(r.colorChromeBlue),
                grain = grain,
                grainCells = cells,
                sharp = num(r.sharpness),
                nr = num(r.nr),
                clarity = num(r.clarity),
                wbAdapt = kelvinAdapt(r),
            )
        }

        /** Same as the RAW path: the camera runs the preset nearest the recipe's Kelvin, the rest is done in light. */
        fun kelvinAdapt(r: Recipe): FloatArray {
            if (r.wb != "色溫") return floatArrayOf(1f, 1f, 1f)
            val k = r.kelvin.filter { it.isDigit() }.toFloatOrNull() ?: return floatArrayOf(1f, 1f, 1f)
            val from = when {
                k < 3500 -> 2850f
                k < 4600 -> 4200f
                k < 5900 -> 5500f
                k < 7000 -> 6500f
                else -> 7500f
            }
            return RawDeveloper.adapt(from, k)
        }

        fun num(text: String): Float = text.replace("+", "").replace("＋", "").trim().toFloatOrNull() ?: 0f

        /** Reads exposure compensation such as "+1/3", "-2/3 至 +1" or "+0.7" (first number wins). */
        fun ev(text: String): Float? {
            val m = Regex("""([+\-−]?)\s*(\d+)(?:\s*/\s*(\d+)|\.(\d+))?""").find(text) ?: return null
            val sign = if (m.groupValues[1] == "-" || m.groupValues[1] == "−") -1f else 1f
            val whole = m.groupValues[2].toFloat()
            val v = when {
                m.groupValues[3].isNotEmpty() -> whole / m.groupValues[3].toFloat()
                m.groupValues[4].isNotEmpty() -> ("$whole".substringBefore('.') + "." + m.groupValues[4]).toFloat()
                else -> whole
            }
            return sign * v
        }
    }
}
