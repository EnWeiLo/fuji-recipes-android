package com.enweilo.fujishokudo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

object Update {
    const val PAGE = "https://github.com/EnWeiLo/fuji-recipes-android/releases/tag/desktop-latest"
    private const val VERSION = "https://github.com/EnWeiLo/fuji-recipes-android/releases/download/desktop-latest/version.json"

    /** This build's number (written by the build), 0 for a local build. */
    val build: Int by lazy {
        runCatching {
            Update::class.java.getResourceAsStream("/build.txt")?.bufferedReader()?.readText()?.trim()?.toInt()
        }.getOrNull() ?: 0
    }

    /** The newest build on GitHub, or null if it could not be read. */
    fun latest(): Int? = runCatching {
        var url = URI(VERSION).toURL()
        repeat(5) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000
            c.readTimeout = 8000
            val code = c.responseCode
            if (code in 300..399) {
                url = URI(c.getHeaderField("Location")).toURL()
            } else {
                return@runCatching JSONObject(c.inputStream.bufferedReader().readText()).getInt("build")
            }
        }
        null
    }.getOrNull()
}
