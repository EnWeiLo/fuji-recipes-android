package com.enweilo.fujirecipes.data

import androidx.compose.ui.graphics.Color

/** One choice shown to the user (label) together with the value the camera uses (code). */
class Option(val label: String, val code: Int)

fun List<Option>.codeOf(label: String): Int? = firstOrNull { it.label == label }?.code
fun List<Option>.labelOf(code: Int): String? = firstOrNull { it.code == code }?.label

/** Same tables as the web version (index.html), so recipes and camera codes stay compatible. */
object Tables {
    val FILM = listOf(
        Option("PROVIA / Standard", 0x01), Option("Velvia / Vivid", 0x02), Option("ASTIA / Soft", 0x03),
        Option("PRO Neg. Hi", 0x04), Option("PRO Neg. Std", 0x05),
        Option("Monochrome", 0x06), Option("Monochrome+Ye", 0x07), Option("Monochrome+R", 0x08),
        Option("Monochrome+G", 0x09), Option("Sepia", 0x0A), Option("Classic Chrome", 0x0B),
        Option("ACROS", 0x0C), Option("ACROS+Ye", 0x0D), Option("ACROS+R", 0x0E), Option("ACROS+G", 0x0F),
        Option("ETERNA / Cinema", 0x10), Option("Classic Neg.", 0x11), Option("ETERNA Bleach Bypass", 0x12),
        Option("Nostalgic Neg.", 0x13), Option("REALA ACE", 0x14),
    )

    val WB = listOf(
        Option("自動", 0x0002), Option("自動（氣氛優先）", 0x8021), Option("晴天", 0x0004),
        Option("陰影", 0x8006), Option("螢光燈1", 0x8001), Option("螢光燈2", 0x8002),
        Option("螢光燈3", 0x8003), Option("白熾燈", 0x0006), Option("水中", 0x0008),
        Option("色溫", 0x8007),
    )

    /** "自動" has no code of its own: the app never writes the dynamic range in that case. */
    val DR = listOf(Option("自動", 0), Option("DR100", 100), Option("DR200", 200), Option("DR400", 400))
    val GRAIN = listOf(Option("關", 1), Option("弱／小", 2), Option("強／小", 3), Option("弱／大", 4), Option("強／大", 5))
    val FX = listOf(Option("關", 1), Option("弱", 2), Option("強", 3))

    val TONE = listOf("-2", "-1.5", "-1", "-0.5", "0", "+0.5", "+1", "+1.5", "+2", "+2.5", "+3", "+3.5", "+4")
    val PM4 = listOf("-4", "-3", "-2", "-1", "0", "+1", "+2", "+3", "+4")
    val CLARITY = listOf("-5", "-4", "-3", "-2", "-1", "0", "+1", "+2", "+3", "+4", "+5")
    val SHIFT = (-9..9).map { if (it > 0) "+$it" else "$it" }

    val MONO = setOf(
        "Monochrome", "Monochrome+Ye", "Monochrome+Y", "Monochrome+R", "Monochrome+G", "Sepia",
        "ACROS", "ACROS+Ye", "ACROS+Y", "ACROS+R", "ACROS+G",
    )

    /** Camera encodes high ISO noise reduction as odd-looking codes. */
    val NR_DECODE = mapOf(
        0x8000 to -4, 0x7000 to -3, 0x4000 to -2, 0x3000 to -1, 0x2000 to 0,
        0x1000 to 1, 0x0000 to 2, 0x6000 to 3, 0x5000 to 4,
    )
    val NR_ENCODE: Map<Int, Int> = NR_DECODE.entries.associate { (code, value) -> value to code }

    private val SIM_COLOR = mapOf(
        "PROVIA / Standard" to 0xFFC45C4A, "Velvia / Vivid" to 0xFFD21E2B, "ASTIA / Soft" to 0xFFD9A07A,
        "Classic Chrome" to 0xFFB85A3A, "Classic Neg." to 0xFF8A5A3C, "Nostalgic Neg." to 0xFF6E4A38,
        "ETERNA / Cinema" to 0xFF5A6A6E, "ETERNA Bleach Bypass" to 0xFF7A7A7A, "REALA ACE" to 0xFFC48A5A,
        "PRO Neg. Hi" to 0xFFC07060, "PRO Neg. Std" to 0xFFC9A090, "ACROS" to 0xFF3A3A3A,
        "ACROS+Ye" to 0xFF6A5A30, "ACROS+R" to 0xFF5A3030, "ACROS+G" to 0xFF30503A,
        "Monochrome" to 0xFF444444, "Monochrome+Ye" to 0xFF6A5A30, "Monochrome+R" to 0xFF5A3030,
        "Monochrome+G" to 0xFF30503A, "Sepia" to 0xFF8A6A40,
    )

    fun simColor(filmSim: String): Color = Color(SIM_COLOR[filmSim] ?: 0xFFE6502B)

    // ---- labels written by older versions of the web app -> current labels ----
    private val LEGACY = mapOf(
        "Auto" to "自動", "Ambience Priority" to "自動（氣氛優先）", "Daylight" to "晴天", "Shade" to "陰影",
        "Fluorescent 1" to "螢光燈1", "Fluorescent 2" to "螢光燈2", "Fluorescent 3" to "螢光燈3",
        "Incandescent" to "白熾燈", "Underwater" to "水中", "Kelvin" to "色溫",
        "AUTO" to "自動", "Off" to "關", "Weak" to "弱", "Strong" to "強",
        "Weak Small" to "弱／小", "Strong Small" to "強／小", "Weak Large" to "弱／大", "Strong Large" to "強／大",
    )

    private val FILM_EN = mapOf(
        "PROVIA／標準" to "PROVIA / Standard", "Velvia／鮮豔" to "Velvia / Vivid", "ASTIA／柔和" to "ASTIA / Soft",
        "PRO負片 高對比" to "PRO Neg. Hi", "PRO負片 標準" to "PRO Neg. Std",
        "黑白" to "Monochrome", "黑白＋黃" to "Monochrome+Ye", "黑白＋紅" to "Monochrome+R", "黑白＋綠" to "Monochrome+G",
        "棕褐" to "Sepia", "經典正片" to "Classic Chrome",
        "ACROS＋黃" to "ACROS+Ye", "ACROS＋紅" to "ACROS+R", "ACROS＋綠" to "ACROS+G",
        "ETERNA／電影" to "ETERNA / Cinema", "經典負片" to "Classic Neg.", "ETERNA漂白效果" to "ETERNA Bleach Bypass",
        "懷舊負片" to "Nostalgic Neg.", "Reala Ace" to "REALA ACE",
        "Monochrome+Y" to "Monochrome+Ye", "ACROS+Y" to "ACROS+Ye", "ETERNA" to "ETERNA / Cinema",
    )

    /** Old exports may hold English labels for non-film fields; the film simulation always uses English names. */
    fun normalizeLabel(value: String): String = LEGACY[value] ?: value

    fun normalizeFilm(value: String): String = FILM_EN[value] ?: value
}
