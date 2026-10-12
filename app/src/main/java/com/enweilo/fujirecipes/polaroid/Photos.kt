package com.enweilo.fujirecipes.polaroid

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

/** Reading pictures (with their camera data) and writing finished cards. */
object Photos {

    const val MAX_SIDE = 3200
    const val PREVIEW_SIDE = 1800

    fun readMeta(context: Context, uri: Uri): Meta {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                    ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)
                    ?: ""
                return Meta(
                    make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim() ?: "",
                    model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "",
                    lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim() ?: "",
                    iso = iso.trim(),
                    fnum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0),
                    shut = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0),
                    date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                        ?: "",
                )
            }
        } catch (_: Exception) {
        }
        return Meta()
    }

    /** The picture, turned upright and no larger than [maxSide] on its long edge. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxSide) {
                        val k = maxSide.toFloat() / longest
                        decoder.setTargetSize(
                            max(1, (info.size.width * k).toInt()),
                            max(1, (info.size.height * k).toInt()),
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return decodeLegacy(context, uri, maxSide)
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            }
            if (!m.isIdentity) {
                val turned = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (turned !== bmp) bmp.recycle()
                bmp = turned
            }
            return bmp
        } catch (_: Throwable) {
            return null
        }
    }

    fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        var cur = src
        val k = maxSide.toFloat() / longest
        val tw = max(1, (src.width * k).toInt())
        val th = max(1, (src.height * k).toInt())
        while (cur.width / 2 >= tw && cur.height / 2 >= th) {
            val half = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src) cur.recycle()
            cur = half
        }
        val out = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && cur !== out) cur.recycle()
        return out
    }

    // ---------------- output ----------------

    fun png(bmp: Bitmap, out: OutputStream) {
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    /** Saves a picture into the gallery. Returns a short description of where it went, or null on failure. */
    fun saveImage(context: Context, bmp: Bitmap, name: String): String? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/拍立得")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
                resolver.openOutputStream(uri)?.use { png(bmp, it) } ?: return null
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "相簿 / 拍立得"
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            dir.mkdirs()
            val file = File(dir, name)
            file.outputStream().use { png(bmp, it) }
            return file.absolutePath
        } catch (_: Throwable) {
            return null
        }
    }

    /** A temporary file the share sheet can read. */
    fun shareFile(context: Context, bmp: Bitmap, name: String): File? {
        return try {
            val dir = File(context.cacheDir, "share")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, name)
            file.outputStream().use { png(bmp, it) }
            file
        } catch (_: Throwable) {
            null
        }
    }

    /** Opens a zip file in Downloads; the caller writes the entries and calls [finishZip]. */
    class ZipTarget(val stream: ZipOutputStream, val uri: Uri?, val values: ContentValues?, val description: String)

    fun startZip(context: Context, name: String): ZipTarget? {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                val out = context.contentResolver.openOutputStream(uri) ?: return null
                return ZipTarget(ZipOutputStream(out.buffered()), uri, values, "下載 / $name")
            }
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            dir.mkdirs()
            val file = File(dir, name)
            return ZipTarget(ZipOutputStream(file.outputStream().buffered()), null, null, file.absolutePath)
        } catch (_: Throwable) {
            return null
        }
    }

    fun addToZip(target: ZipTarget, name: String, bmp: Bitmap) {
        target.stream.putNextEntry(ZipEntry(name))
        png(bmp, target.stream)
        target.stream.closeEntry()
    }

    fun finishZip(context: Context, target: ZipTarget) {
        target.stream.close()
        if (target.uri != null && target.values != null) {
            target.values.clear()
            target.values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(target.uri, target.values, null, null)
        }
    }

    fun abortZip(context: Context, target: ZipTarget) {
        try {
            target.stream.close()
        } catch (_: Throwable) {
        }
        if (target.uri != null) {
            try {
                context.contentResolver.delete(target.uri, null, null)
            } catch (_: Throwable) {
            }
        }
    }
}
