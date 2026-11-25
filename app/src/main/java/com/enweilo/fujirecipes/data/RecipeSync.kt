package com.enweilo.fujirecipes.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone ↔ computer recipe sync over the local network, shared by both apps.
 *
 * The computer runs a small HTTP server and announces itself by UDP broadcast; the phone pairs once with a
 * six-digit code and then sends its recipes and deletions, gets the merged set back.
 * Merge rule: per recipe the newer [Recipe.updatedAt] wins; a deletion (tombstone) wins over any edit older
 * than it. Sample photos stay on the phone: the computer keeps whatever photo list it was sent.
 */
object RecipeSync {
    const val HTTP_PORT = 48650
    const val UDP_PORT = 48651
    const val APP = "fujishokudo"

    class Result(val recipes: List<Recipe>, val tombstones: Map<String, Long>)

    fun merge(mine: List<Recipe>, myTombs: Map<String, Long>, theirs: List<Recipe>, theirTombs: Map<String, Long>): Result {
        val tombs = HashMap(myTombs)
        for ((id, t) in theirTombs) tombs[id] = maxOf(t, tombs[id] ?: 0L)
        val byId = LinkedHashMap<String, Recipe>()
        for (r in mine) byId[r.id] = r
        for (r in theirs) {
            val cur = byId[r.id]
            byId[r.id] = when {
                cur == null -> r
                r.updatedAt > cur.updatedAt -> r.copy(photos = r.photos.ifEmpty { cur.photos })
                else -> cur.copy(photos = cur.photos.ifEmpty { r.photos })
            }
        }
        val out = byId.values.filter { r -> (tombs[r.id] ?: -1L) < r.updatedAt }
        // forget deletions older than 90 days
        val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        return Result(out, tombs.filterValues { it > cutoff })
    }

    fun toJson(recipes: List<Recipe>, tombstones: Map<String, Long>): JSONObject = JSONObject().apply {
        put("recipes", JSONArray().apply { recipes.forEach { put(it.toJson()) } })
        put("tombstones", JSONObject().apply { tombstones.forEach { (k, v) -> put(k, v) } })
    }

    fun fromJson(o: JSONObject): Result {
        val arr = o.optJSONArray("recipes") ?: JSONArray()
        val recipes = (0 until arr.length()).map { recipeFromJson(arr.getJSONObject(it)) }
        val t = o.optJSONObject("tombstones")
        val tombs = HashMap<String, Long>()
        t?.keys()?.forEach { k -> tombs[k] = t.optLong(k) }
        return Result(recipes, tombs)
    }
}
