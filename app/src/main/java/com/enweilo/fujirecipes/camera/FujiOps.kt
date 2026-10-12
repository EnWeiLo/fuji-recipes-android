package com.enweilo.fujirecipes.camera

import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.data.codeOf
import com.enweilo.fujirecipes.data.emptyRecipe
import com.enweilo.fujirecipes.data.labelOf
import kotlin.math.roundToInt

class SlotInfo(val slot: Int, val name: String, val filmSim: String)

/** Property codes Fujifilm cameras use for the C1–C7 custom settings slots. */
private object Prop {
    const val SLOT = 0xD18C
    const val NAME = 0xD18D
    const val DYNAMIC_RANGE = 0xD190
    const val FILM_SIM = 0xD192
    const val GRAIN = 0xD195
    const val COLOR_CHROME = 0xD196
    const val COLOR_CHROME_BLUE = 0xD197
    const val WHITE_BALANCE = 0xD199
    const val SHIFT_R = 0xD19A
    const val SHIFT_B = 0xD19B
    const val KELVIN = 0xD19C
    const val HIGHLIGHT = 0xD19D
    const val SHADOW = 0xD19E
    const val COLOR = 0xD19F
    const val SHARPNESS = 0xD1A0
    const val NOISE_REDUCTION = 0xD1A1
    const val CLARITY = 0xD1A2
}

/** Reading and writing recipes on the camera. Port of readSlots / readSlotAsRecipe / writeRecipeToSlot (web). */
class FujiOps(private val cam: PtpCam, private val log: (String) -> Unit) {

    fun readSlots(): List<SlotInfo> {
        log("讀取 C1–C7…")
        val result = mutableListOf<SlotInfo>()
        for (slot in 1..7) {
            if (!cam.setU16(Prop.SLOT, slot)) {
                log("C$slot 無法切換")
                continue
            }
            Thread.sleep(80)
            val name = cam.getProp(Prop.NAME)?.let { parsePtpString(it) } ?: "(無)"
            val film = cam.getI16(Prop.FILM_SIM)
                ?.let { Tables.FILM.labelOf(it) ?: ("0x" + it.toString(16)) } ?: "?"
            log("C$slot  $name  /  $film")
            result += SlotInfo(slot, name, film)
        }
        return result
    }

    fun readSlotAsRecipe(slot: Int): Recipe {
        log("切到 C$slot 並讀取…")
        if (!cam.setU16(Prop.SLOT, slot)) throw PtpException("無法選擇槽位")
        Thread.sleep(150)

        val slotName = cam.getProp(Prop.NAME)?.let { parsePtpString(it) }?.trim().orEmpty()
        val film = cam.getU16(Prop.FILM_SIM)
        val dr = cam.getU16(Prop.DYNAMIC_RANGE)
        val grain = cam.getU16(Prop.GRAIN)
        val chrome = cam.getU16(Prop.COLOR_CHROME)
        val chromeBlue = cam.getU16(Prop.COLOR_CHROME_BLUE)
        val wb = cam.getU16(Prop.WHITE_BALANCE)
        val shiftR = cam.getI16(Prop.SHIFT_R)
        val shiftB = cam.getI16(Prop.SHIFT_B)
        val kelvin = cam.getU16(Prop.KELVIN)
        val highlight = cam.getI16(Prop.HIGHLIGHT)
        val shadow = cam.getI16(Prop.SHADOW)
        val color = cam.getI16(Prop.COLOR)
        val sharpness = cam.getI16(Prop.SHARPNESS)
        val nr = cam.getU16(Prop.NOISE_REDUCTION)
        val clarity = cam.getI16(Prop.CLARITY)

        val base = emptyRecipe()
        val recipe = base.copy(
            name = slotName.ifEmpty { "相機 C$slot" },
            notes = "從相機 C$slot 讀取",
            filmSim = film?.let { Tables.FILM.labelOf(it) } ?: base.filmSim,
            dr = dr?.let { Tables.DR.labelOf(it) } ?: base.dr,
            grain = grain?.let { Tables.GRAIN.labelOf(it) } ?: base.grain,
            colorChrome = chrome?.let { Tables.FX.labelOf(it) } ?: base.colorChrome,
            colorChromeBlue = chromeBlue?.let { Tables.FX.labelOf(it) } ?: base.colorChromeBlue,
            wb = wb?.let { Tables.WB.labelOf(it) } ?: base.wb,
            kelvin = if (kelvin != null && kelvin != 0) kelvin.toString() else base.kelvin,
            shiftR = signed(shiftR),
            shiftB = signed(shiftB),
            highlight = fmtTone(highlight),
            shadow = fmtTone(shadow),
            color = fmtTone(color),
            sharpness = fmtTone(sharpness),
            clarity = fmtTone(clarity),
            nr = nr?.let { Tables.NR_DECODE[it] }?.toString() ?: base.nr,
        )
        log("已讀 ${recipe.name} / ${recipe.filmSim}")
        return recipe
    }

    fun writeRecipe(recipe: Recipe, slot: Int) {
        val name = recipe.name.ifEmpty { "RECIPE" }.take(24)
        log("切到 C$slot …")
        if (!cam.setU16(Prop.SLOT, slot)) throw PtpException("無法選擇槽位")
        Thread.sleep(120)
        log("寫入名稱…")
        if (!cam.setString(Prop.NAME, name)) log("名稱寫入被拒（可忽略）")

        val jobs = mutableListOf<Pair<String, () -> Boolean>>()
        Tables.FILM.codeOf(recipe.filmSim)?.let { code ->
            jobs += "FilmSim" to { cam.setU16(Prop.FILM_SIM, code) }
        }
        if (recipe.dr != "自動" && recipe.dr != "AUTO") {
            Tables.DR.codeOf(recipe.dr)?.let { code -> jobs += "DR" to { cam.setU16(Prop.DYNAMIC_RANGE, code) } }
        }
        Tables.GRAIN.codeOf(recipe.grain)?.let { code -> jobs += "Grain" to { cam.setU16(Prop.GRAIN, code) } }
        Tables.FX.codeOf(recipe.colorChrome)?.let { code -> jobs += "Chrome" to { cam.setU16(Prop.COLOR_CHROME, code) } }
        Tables.FX.codeOf(recipe.colorChromeBlue)?.let { code -> jobs += "FX Blue" to { cam.setU16(Prop.COLOR_CHROME_BLUE, code) } }
        Tables.WB.codeOf(recipe.wb)?.let { code -> jobs += "WB" to { cam.setU16(Prop.WHITE_BALANCE, code) } }
        jobs += "ShiftR" to { cam.setI16(Prop.SHIFT_R, shiftNumber(recipe.shiftR)) }
        jobs += "ShiftB" to { cam.setI16(Prop.SHIFT_B, shiftNumber(recipe.shiftB)) }
        if (recipe.wb == "色溫" || (recipe.wb == "Kelvin" && recipe.kelvin.isNotEmpty())) {
            jobs += "Kelvin" to { cam.setU16(Prop.KELVIN, recipe.kelvin.toIntOrNull() ?: 5500) }
        }
        jobs += "Highlight" to { cam.setI16(Prop.HIGHLIGHT, tenths(recipe.highlight)) }
        jobs += "Shadow" to { cam.setI16(Prop.SHADOW, tenths(recipe.shadow)) }
        if (recipe.filmSim !in Tables.MONO) {
            jobs += "Color" to { cam.setI16(Prop.COLOR, tenths(recipe.color)) }
        }
        jobs += "Sharp" to { cam.setI16(Prop.SHARPNESS, tenths(recipe.sharpness)) }
        recipe.nr.toIntOrNull()?.let { value ->
            Tables.NR_ENCODE[value]?.let { code -> jobs += "NR" to { cam.setU16(Prop.NOISE_REDUCTION, code) } }
        }
        jobs += "Clarity" to { cam.setI16(Prop.CLARITY, tenths(recipe.clarity)) }

        var ok = 0
        var fail = 0
        for ((label, job) in jobs) {
            val done = job()
            log((if (done) "OK " else "NG ") + label)
            if (done) ok++ else fail++
            Thread.sleep(40)
        }
        log("完成：成功 $ok / 失敗 $fail")
        if (fail > 0 && ok == 0) throw PtpException("全部寫入失敗，檢查連線模式")
    }

    // ---------------- number formats ----------------

    private fun signed(n: Int?): String = if (n == null) "0" else (if (n > 0) "+" else "") + n

    /** The camera stores tone values in tenths: 15 -> "+1.5", -5 -> "-0.5", 0 -> "0". */
    private fun fmtTone(n: Int?): String {
        if (n == null) return "0"
        val v = n / 10.0
        if (v == 0.0) return "0"
        val body = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
        return (if (v > 0) "+" else "") + body
    }

    private fun tenths(text: String): Int {
        val n = text.replace("+", "").toFloatOrNull() ?: 0f
        return (n * 10f).roundToInt()
    }

    private fun shiftNumber(text: String): Int = text.replace("+", "").toIntOrNull() ?: 0
}
