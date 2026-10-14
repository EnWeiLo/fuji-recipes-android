package com.enweilo.fujirecipes.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Looks for a newer build on the GitHub "latest" release, downloads it and hands it to the installer. */
object Updater {
    const val REPO = "EnWeiLo/fuji-recipes-android"

    class Release(val build: Int, val name: String, val apkUrl: String, val size: Long)

    /** Tests can pretend an older build is installed. */
    @Volatile
    var installedBuildOverride: Long? = null

    fun installedBuild(context: Context): Long {
        installedBuildOverride?.let { return it }
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    fun isNewer(release: Release, installed: Long) = release.build > installed

    /** Null when offline, rate-limited, or the release does not look right. */
    fun fetchLatest(): Release? = try {
        val conn = URL("https://api.github.com/repos/$REPO/releases/tags/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "fuji-recipes-android")
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val name = json.optString("name")
                val build = Regex("build\\s*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
                val assets = json.optJSONArray("assets")
                var url = ""
                var size = 0L
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                if (build == null || !url.startsWith("https://")) null else Release(build, name, url, size)
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    /** Downloads to the cache folder. [onProgress] gets 0..100. Returns null on any failure. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File? = try {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val part = File(dir, "fuji-recipes.apk.part")
        val target = File(dir, "fuji-recipes.apk")
        part.delete()
        target.delete()
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "fuji-recipes-android")
        try {
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct / 5 != lastPct / 5) onProgress(pct)
                            lastPct = pct
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(target)) null else {
            val info = context.packageManager.getPackageArchiveInfo(target.path, 0)
            val ok = info != null && info.packageName == context.packageName &&
                PackageInfoCompat.getLongVersionCode(info) == release.build.toLong()
            if (ok) target else { target.delete(); null }
        }
    } catch (e: Exception) {
        null
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
