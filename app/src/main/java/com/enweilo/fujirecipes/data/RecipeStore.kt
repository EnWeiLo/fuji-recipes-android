package com.enweilo.fujirecipes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** Recipes live in one JSON file, photos are small JPEGs next to it. Everything stays on the phone. */
class RecipeStore(private val context: Context) {

    private val file = File(context.filesDir, "recipes.json")
    private val photoDir = File(context.filesDir, "photos").also { it.mkdirs() }

    private val bitmapCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(): List<Recipe> {
        if (!file.exists()) return listOf(demoRecipe())
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { recipeFromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // keep the unreadable file around instead of overwriting it with an empty list
            file.renameTo(File(context.filesDir, "recipes.broken.json"))
            emptyList()
        }
    }

    fun save(recipes: List<Recipe>) {
        val arr = JSONArray()
        recipes.forEach { arr.put(it.toJson()) }
        val tmp = File(context.filesDir, "recipes.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    private fun demoRecipe() = Recipe(
        id = "demo-gold",
        name = "Kodak Gold 200 範例",
        grain = "強／小",
        colorChrome = "弱",
        shiftR = "+4",
        shiftB = "-5",
        highlight = "-1.5",
        shadow = "+0.5",
        color = "+3",
        sharpness = "-2",
        clarity = "-2",
        notes = "示範用，可刪。Classic Chrome + 暖色偏移。",
    )

    // ---------------- photos ----------------

    private fun photoFile(id: String) = File(photoDir, "$id.jpg")

    /** Reads a picked picture, shrinks it (longest side 1200 px, JPEG 72 %) and stores it. Returns its id. */
    fun importPhoto(uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null

            var sample = 1
            while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return null

            val orientation = resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            val scale = min(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
            if (scale < 1f) matrix.postScale(scale, scale)

            val result = if (matrix.isIdentity) decoded
            else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

            val id = "p_" + newId()
            FileOutputStream(photoFile(id)).use { result.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            id
        } catch (e: Exception) {
            null
        }
    }

    fun deletePhoto(id: String) {
        photoFile(id).delete()
    }

    /** Returns the photo scaled so its longest side is about [maxSide] px (cached). */
    fun loadBitmap(id: String, maxSide: Int): Bitmap? {
        val key = "$id@$maxSide"
        bitmapCache.get(key)?.let { return it }
        val f = photoFile(id)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    // ---------------- import / export (same format as the web app) ----------------

    fun exportJson(recipes: List<Recipe>): String {
        val arr = JSONArray()
        recipes.filter { it.id != "demo-gold" }.forEach { arr.put(it.toJson(includePhotos = false)) }
        return JSONObject().apply {
            put("app", "富士食譜工具")
            put("version", 1)
            put("exportedAt", Instant.now().toString())
            put("recipes", arr)
        }.toString(2)
    }

    /** Accepts the web app's export ({recipes:[...]}) or a bare array. Returns null if it is neither. */
    fun parseImport(text: String): List<Recipe>? {
        return try {
            val root = JSONTokener(text).nextValue()
            val arr = when (root) {
                is JSONObject -> root.optJSONArray("recipes")
                is JSONArray -> root
                else -> null
            } ?: return null
            val out = mutableListOf<Recipe>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = recipeFromJson(o)
                if (r.name.isNotBlank()) out += r.copy(photos = emptyList())
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_SIDE = 1200
    }
}
