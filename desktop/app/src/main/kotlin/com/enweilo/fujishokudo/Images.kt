package com.enweilo.fujishokudo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.enweilo.fujirecipes.camera.RafInfo
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam

object Images {
    /** Header of a RAF file, or null if it is not one. */
    fun rafInfo(f: File): RafInfo? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(100)
            r.readFully(head)
            RafInfo.parse(head)
        }
    }.getOrNull()

    /** The JPEG the camera stored inside the RAF. */
    fun rafPreview(f: File, info: RafInfo): ByteArray? = runCatching {
        if (info.jpegLength <= 0) return null
        RandomAccessFile(f, "r").use { r ->
            r.seek(info.jpegOffset.toLong())
            ByteArray(info.jpegLength).also { r.readFully(it) }
        }
    }.getOrNull()

    /** Decodes a JPEG at about [maxSide] on its long side (cheap: the decoder skips pixels), upright by EXIF. */
    fun decode(bytes: ByteArray, maxSide: Int): BufferedImage? = runCatching {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                var step = 1
                while (maxOf(w, h) / (step * 2) >= maxSide) step *= 2
                val p: ImageReadParam = reader.defaultReadParam
                if (step > 1) p.setSourceSubsampling(step, step, 0, 0)
                val img = reader.read(0, p)
                orient(img, exifOrientation(bytes))
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

    fun decodeBitmap(bytes: ByteArray, maxSide: Int): ImageBitmap? = decode(bytes, maxSide)?.toComposeImageBitmap()

    fun decodeFileBitmap(f: File, maxSide: Int): ImageBitmap? = runCatching { decodeBitmap(f.readBytes(), maxSide) }.getOrNull()

    /** EXIF orientation (1–8) of a JPEG, 1 when there is none. */
    fun exifOrientation(b: ByteArray): Int {
        var i = 2
        while (i + 4 < b.size && (b[i].toInt() and 0xFF) == 0xFF) {
            val marker = b[i + 1].toInt() and 0xFF
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (marker == 0xE1 && i + 10 < b.size && String(b, i + 4, 4, Charsets.US_ASCII) == "Exif") {
                val t = i + 10
                val le = b[t].toInt() == 0x49
                fun u16(o: Int) = if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
                else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
                fun u32(o: Int) = if (le) u16(o) or (u16(o + 2) shl 16) else (u16(o) shl 16) or u16(o + 2)
                val ifd = t + u32(t + 4)
                if (ifd + 2 >= b.size) return 1
                val n = u16(ifd)
                for (k in 0 until n) {
                    val e = ifd + 2 + k * 12
                    if (e + 12 > b.size) break
                    if (u16(e) == 0x0112) return u16(e + 8).coerceIn(1, 8)
                }
                return 1
            }
            if (marker == 0xDA) break
            i += 2 + len
        }
        return 1
    }

    private fun orient(img: BufferedImage, o: Int): BufferedImage {
        if (o == 1) return img
        val w = img.width.toDouble()
        val h = img.height.toDouble()
        val t = AffineTransform()
        when (o) {
            2 -> { t.scale(-1.0, 1.0); t.translate(-w, 0.0) }
            3 -> { t.translate(w, h); t.rotate(Math.PI) }
            4 -> { t.scale(1.0, -1.0); t.translate(0.0, -h) }
            5 -> { t.rotate(-Math.PI / 2); t.scale(-1.0, 1.0) }
            6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        }
        val swap = o >= 5
        val out = BufferedImage(if (swap) img.height else img.width, if (swap) img.width else img.height, BufferedImage.TYPE_INT_RGB)
        AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR).filter(toRgb(img), out)
        return out
    }

    private fun toRgb(img: BufferedImage): BufferedImage {
        if (img.type == BufferedImage.TYPE_INT_RGB) return img
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        return out
    }
}
