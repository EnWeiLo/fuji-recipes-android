package com.enweilo.fujirecipes.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Every value is kept as text, exactly like the web version, so exports from either app can be exchanged. */
data class Recipe(
    val id: String,
    val name: String = "",
    val filmSim: String = "Classic Chrome",
    val dr: String = "DR400",
    val grain: String = "弱／小",
    val colorChrome: String = "關",
    val colorChromeBlue: String = "關",
    val wb: String = "自動",
    val kelvin: String = "5500",
    val shiftR: String = "0",
    val shiftB: String = "0",
    val highlight: String = "0",
    val shadow: String = "0",
    val color: String = "0",
    val sharpness: String = "0",
    val nr: String = "-4",
    val clarity: String = "0",
    val iso: String = "Auto 至 6400",
    val ev: String = "",
    val notes: String = "",
    val photos: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

fun newId(): String =
    System.currentTimeMillis().toString(36) + Random.nextInt(36 * 36 * 36 * 36 * 36).toString(36)

fun emptyRecipe(): Recipe = Recipe(id = newId())

fun Recipe.toJson(includePhotos: Boolean = true): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("filmSim", filmSim)
    put("dr", dr)
    put("grain", grain)
    put("colorChrome", colorChrome)
    put("colorChromeBlue", colorChromeBlue)
    put("wb", wb)
    put("kelvin", kelvin)
    put("shiftR", shiftR)
    put("shiftB", shiftB)
    put("highlight", highlight)
    put("shadow", shadow)
    put("color", color)
    put("sharpness", sharpness)
    put("nr", nr)
    put("clarity", clarity)
    put("iso", iso)
    put("ev", ev)
    put("notes", notes)
    put("photos", JSONArray(if (includePhotos) photos else emptyList<String>()))
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
}

fun recipeFromJson(o: JSONObject): Recipe {
    val base = Recipe(id = "")

    fun text(key: String, default: String): String =
        if (o.has(key) && !o.isNull(key)) o.get(key).toString() else default

    val photoList = mutableListOf<String>()
    o.optJSONArray("photos")?.let { arr ->
        for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let(photoList::add)
    }

    val id = text("id", "").ifEmpty { newId() }
    return Recipe(
        id = id,
        name = text("name", ""),
        filmSim = Tables.normalizeFilm(text("filmSim", base.filmSim)),
        dr = Tables.normalizeLabel(text("dr", base.dr)),
        grain = Tables.normalizeLabel(text("grain", base.grain)),
        colorChrome = Tables.normalizeLabel(text("colorChrome", base.colorChrome)),
        colorChromeBlue = Tables.normalizeLabel(text("colorChromeBlue", base.colorChromeBlue)),
        wb = Tables.normalizeLabel(text("wb", base.wb)),
        kelvin = text("kelvin", base.kelvin),
        shiftR = text("shiftR", base.shiftR),
        shiftB = text("shiftB", base.shiftB),
        highlight = text("highlight", base.highlight),
        shadow = text("shadow", base.shadow),
        color = text("color", base.color),
        sharpness = text("sharpness", base.sharpness),
        nr = text("nr", base.nr),
        clarity = text("clarity", base.clarity),
        iso = text("iso", base.iso),
        ev = text("ev", base.ev),
        notes = text("notes", base.notes),
        photos = photoList,
        createdAt = o.optLong("createdAt", System.currentTimeMillis()),
        updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
    )
}

/** Position of a tone-style value (e.g. "+1.5") on a -5..+5 scale, as 0..1, for the little meters. */
fun toneFraction(value: String): Float {
    val n = value.replace("+", "").toFloatOrNull() ?: return 0.5f
    return ((n + 5f) / 10f).coerceIn(0.04f, 1f)
}
