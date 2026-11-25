package com.enweilo.fujishokudo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.RecipeSync
import com.enweilo.fujirecipes.data.recipeFromJson
import com.enweilo.fujirecipes.data.toJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Where the app keeps its things: %APPDATA%\FujiShokudo (or ~/.fujishokudo elsewhere). */
object Paths {
    val home: File by lazy {
        val appData = System.getenv("APPDATA")
        (if (appData != null) File(appData, "FujiShokudo") else File(System.getProperty("user.home"), ".fujishokudo")).apply { mkdirs() }
    }
    val cache: File by lazy {
        val local = System.getenv("LOCALAPPDATA")
        (if (local != null) File(local, "FujiShokudo/cache") else File(home, "cache")).apply { mkdirs() }
    }
}

private fun writeAtomic(f: File, text: String) {
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(text, Charsets.UTF_8)
    if (!tmp.renameTo(f)) {
        f.delete()
        tmp.renameTo(f)
    }
}

/** Small settings, one JSON file. */
class Settings {
    private val file = File(Paths.home, "settings.json")
    private val o: JSONObject = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    fun str(key: String, def: String = ""): String = o.optString(key, def)
    fun bool(key: String, def: Boolean): Boolean = o.optBoolean(key, def)
    fun obj(key: String): JSONObject = o.optJSONObject(key) ?: JSONObject().also { o.put(key, it) }

    fun put(key: String, v: Any?) {
        o.put(key, v)
        save()
    }

    fun save() = writeAtomic(file, o.toString(2))
}

/** The recipes, in one JSON file in the same format as the phone's export, plus deletions for sync. */
class RecipeRepo {
    private val file = File(Paths.home, "recipes.json")
    private val tombFile = File(Paths.home, "deleted.json")

    var recipes by mutableStateOf<List<Recipe>>(emptyList())
        private set
    var tombstones: Map<String, Long> = emptyMap()
        private set
    /** bumps whenever the list changes (for sync) */
    var revision by mutableStateOf(0)
        private set

    init {
        recipes = runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { recipeFromJson(arr.getJSONObject(it)) }
        }.getOrElse {
            if (file.exists()) file.renameTo(File(Paths.home, "recipes.broken.json"))
            listOf(sample())
        }
        tombstones = runCatching {
            val t = JSONObject(tombFile.readText())
            t.keySet().associateWith { t.getLong(it) }
        }.getOrElse { emptyMap() }
    }

    private fun sample() = Recipe(
        id = "demo-gold", name = "Kodak Gold 200 範例", grain = "強／小", colorChrome = "弱",
        shiftR = "+4", shiftB = "-5", highlight = "-1.5", shadow = "+0.5", color = "+2", wb = "晴天",
        createdAt = 0, updatedAt = 0,
    )

    private fun persist() {
        writeAtomic(file, JSONArray().apply { recipes.forEach { put(it.toJson()) } }.toString())
        writeAtomic(tombFile, JSONObject().apply { tombstones.forEach { (k, v) -> put(k, v) } }.toString())
        revision++
    }

    fun save(r: Recipe) {
        val now = System.currentTimeMillis()
        val idx = recipes.indexOfFirst { it.id == r.id }
        val saved = r.copy(updatedAt = now, name = r.name.trim())
        recipes = if (idx >= 0) recipes.toMutableList().also { it[idx] = saved } else listOf(saved) + recipes
        persist()
    }

    fun delete(id: String) {
        recipes = recipes.filterNot { it.id == id }
        tombstones = tombstones + (id to System.currentTimeMillis())
        persist()
    }

    fun importAll(list: List<Recipe>) {
        var cur = recipes
        for (r in list) {
            val i = cur.indexOfFirst { it.id == r.id }
            cur = if (i >= 0) cur.toMutableList().also { it[i] = r } else cur + r
        }
        recipes = cur
        persist()
    }

    /** Merges what a phone sent; returns the merged set to send back. */
    @Synchronized
    fun mergeFrom(theirs: List<Recipe>, theirTombs: Map<String, Long>): RecipeSync.Result {
        val m = RecipeSync.merge(recipes, tombstones, theirs, theirTombs)
        recipes = m.recipes
        tombstones = m.tombstones
        persist()
        return m
    }
}
