package com.enweilo.fujirecipes.polaroid

/** Everything that can be set for one photo. Immutable: the editor replaces it with a changed copy. */

enum class Frame(val label: String, val textColor: Int) {
    White("白框", 0xFF000000.toInt()),
    Black("黑框", 0xFFF6EFE4.toInt()),
    Tint("取色", 0xFFFFFFFF.toInt()),
    Vintage("復古", 0xFF3A2E21.toInt()),
    Film("底片", 0xFFE09F3E.toInt()),
}

enum class Ratio(val label: String) {
    Orig("原圖"),
    Mini("Mini"),
    Square("方框"),
    Wide("Wide"),
    Banner("橫幅"),
    Insta("IG 貼文"),
    Story("IG 限動"),
}

enum class Src(val label: String) {
    Auto("自動帶入"),
    Manual("手動輸入"),
    None("不顯示"),
}

class CamPart(val text: String, val mark: String?)

data class PState(
    val frame: Frame = Frame.White,
    val ratio: Ratio = Ratio.Orig,
    val cropZ: Float = 1f,
    val cropX: Float = 0f,
    val cropY: Float = 0f,
    val dateRight: Boolean = false,
    val bannerDateTop: Boolean = false,
    val datePos: Float = 18f,
    val dateText: String = "",
    val dateOn: Boolean = false,
    val dateSize: Int = 12,
    val noteText: String = "",
    val noteFont: String = "huninn",
    val noteSize: Int = 14,
    val showCam: Boolean = true,
    val showExp: Boolean = false,
    val shotOn: Boolean = true,
    val font: String = "huninn",
    val sizeLeft: Int = 14,
    val color: Int = 0xFF000000.toInt(),
    val brandChoice: Src = Src.Auto,
    val modelChoice: Src = Src.Auto,
    val lensChoice: Src = Src.Auto,
    val manualBrand: String = "",
    val manualModel: String = "",
    val manualLens: String = "",
    val iso: String = "",
    val fnum: String = "",
    val shut: String = "",
    val autoBrand: String = "",
    val autoModel: String = "",
    val autoLens: String = "",
    val rawMake: String = "",
    val rawModel: String = "",
    val isPhone: Boolean = false,
) {
    val brand: String
        get() = normalizeBrand(
            when (brandChoice) {
                Src.Auto -> autoBrand
                Src.Manual -> manualBrand.trim()
                Src.None -> ""
            },
        )

    val model: String
        get() = normalizeCameraModel(
            when (modelChoice) {
                Src.Auto -> autoModel
                Src.Manual -> manualModel.trim()
                Src.None -> ""
            },
            brand,
        )

    val lens: String
        get() = when (lensChoice) {
            Src.Auto -> autoLens
            Src.Manual -> manualLens.trim()
            Src.None -> ""
        }

    val hasExposure: Boolean get() = listOf(iso, fnum, shut).any { it.isNotBlank() }

    /** Brand and model as shown on the card; a part carries a logo name when a known logo should replace the text. */
    val camParts: List<CamPart>
        get() {
            val b = brand
            val m = model
            val brandKey = brandKeyOf(autoBrand)
            val confirmed = when (brandKey) {
                "sony" -> mapOf("α7C II" to "alpha")
                "nikon" -> mapOf("Z5 II" to "z5ii", "Z6" to "z6")
                "fujifilm" -> mapOf("X-T50" to "xt50")
                else -> emptyMap()
            }
            val mark = confirmed[autoModel]
            val useLogo = rawMake.isNotBlank() && rawModel.isNotBlank() && mark != null &&
                brandChoice != Src.Manual && modelChoice != Src.Manual
            val list = ArrayList<CamPart>()
            if (b.isNotEmpty()) list.add(CamPart(b, if (useLogo && brandChoice == Src.Auto) brandKey else null))
            if (m.isNotEmpty()) list.add(CamPart(m, if (useLogo && modelChoice == Src.Auto) mark else null))
            return list
        }

    val expLineCard: String
        get() = if (!hasExposure) "" else listOf(
            fnum.trim(),
            shut.trim(),
            if (iso.isNotBlank()) "ISO " + iso.trim().replace(Regex("^ISO\\s*", RegexOption.IGNORE_CASE), "") else "",
        ).filter { it.isNotEmpty() }.joinToString("   ")

    val expLineBanner: String
        get() = listOf(fnum, shut, if (iso.isNotEmpty()) "ISO $iso" else "").filter { it.isNotEmpty() }.joinToString("  ")
}

fun normalizeBrand(value: String): String {
    val v = value.trim()
    return if (Regex("^nikon(?:\\s+corporation)?$", RegexOption.IGNORE_CASE).matches(v)) "NIKON" else v
}

fun normalizeCameraModel(value: String, brand: String = ""): String {
    var model = value.trim()
    if (normalizeBrand(brand) == "NIKON") {
        model = model.replace(Regex("nikon(?:\\s+corporation)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("^[\\s:;,_/|–—-]+|[\\s:;,_/|–—-]+$"), "")
            .replace(Regex("\\s+"), " ").trim()
    }
    if (Regex("^(?:ILCE-7CM2|α7C\\s*II|a7c\\s*ii)$", RegexOption.IGNORE_CASE).matches(model)) return "α7C II"
    if (Regex("^(?:Z\\s*5\\s*II|Z5_2)$", RegexOption.IGNORE_CASE).matches(model)) return "Z5 II"
    if (Regex("^Z\\s*6$", RegexOption.IGNORE_CASE).matches(model)) return "Z6"
    if (Regex("^X[ -]?T50$", RegexOption.IGNORE_CASE).matches(model)) return "X-T50"
    return model
}

fun brandKeyOf(text: String): String {
    val k = normalizeBrand(text).lowercase()
    return if (k == "sony" || k == "canon" || k == "nikon" || k == "fujifilm") k else ""
}

private val PHONE_RULES = listOf(
    "^apple$" to "^iphone\\b",
    "^google$" to "^pixel\\s*\\d",
    "^samsung$" to "^(?:SM-[AGSFMN]|GT-I|Galaxy\\b)",
    "^(?:sony|sony mobile communications)$" to "^(?:xperia\\b|XQ-)",
    "^(?:xiaomi|redmi|poco)$" to "^(?:Mi\\s+\\d|Redmi\\b|POCO\\b|Xiaomi\\s+\\d)",
    "^(?:huawei|honor)$" to "^(?:HUAWEI|HONOR|VOG-|ELE-|LIO-|ANA-|NOH-|ELS-)",
    "^oneplus$" to "^(?:ONEPLUS|CPH\\d|LE21|NE22)",
    "^oppo$" to "^(?:OPPO|CPH\\d|Find\\b|Reno\\b)",
    "^vivo$" to "^(?:vivo|V\\d{4})",
    "^motorola$" to "^(?:moto\\b|motorola\\b)",
    "^asus$" to "^(?:ASUS_|Zenfone\\b|ROG Phone\\b)",
).map { Regex(it.first, RegexOption.IGNORE_CASE) to Regex(it.second, RegexOption.IGNORE_CASE) }

fun isPhoneExif(make: String, model: String): Boolean =
    PHONE_RULES.any { (a, b) -> a.containsMatchIn(make.trim()) && b.containsMatchIn(model.trim()) }

fun fmtShutter(v: Double): String {
    if (v <= 0 || v.isNaN() || v.isInfinite()) return ""
    if (v >= 1) {
        val s = String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
        return s + "s"
    }
    return "1/" + Math.round(1.0 / v)
}

fun fmtF(v: Double): String {
    if (v <= 0 || v.isNaN() || v.isInfinite()) return ""
    val n = if (v >= 10) String.format(java.util.Locale.US, "%.0f", v) else String.format(java.util.Locale.US, "%.1f", v)
    return "f/" + n.removeSuffix(".0")
}

/** Build a fresh state from what the camera wrote into the picture. */
fun stateFromMeta(meta: Meta): PState {
    val autoBrand = normalizeBrand(meta.make)
    var model = meta.model.trim()
    if (autoBrand.isNotEmpty() && model.lowercase().startsWith(autoBrand.lowercase() + " ")) {
        model = model.substring(autoBrand.length).trim()
    }
    val autoModel = normalizeCameraModel(model, autoBrand)
    var date = ""
    val m = Regex("(\\d{4}).(\\d{2}).(\\d{2})").find(meta.date)
    if (m != null) date = m.groupValues[1] + "." + m.groupValues[2] + "." + m.groupValues[3]
    return PState(
        autoBrand = autoBrand,
        autoModel = autoModel,
        autoLens = meta.lens.trim(),
        rawMake = meta.make,
        rawModel = meta.model,
        isPhone = isPhoneExif(meta.make, meta.model),
        iso = meta.iso,
        fnum = fmtF(meta.fnum),
        shut = fmtShutter(meta.shut),
        dateText = date,
    )
}

class Meta(
    val make: String = "",
    val model: String = "",
    val lens: String = "",
    val iso: String = "",
    val fnum: Double = 0.0,
    val shut: Double = 0.0,
    val date: String = "",
)
