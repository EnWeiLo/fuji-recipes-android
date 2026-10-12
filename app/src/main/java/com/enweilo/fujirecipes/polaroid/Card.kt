package com.enweilo.fujirecipes.polaroid

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BRow(val text: String?, val parts: List<CamPart>?, val size: Float)

class BLayout(
    val photoW: Float,
    val photoH: Float,
    val side: Float,
    val top: Float,
    val infoWidth: Float,
    val rows: List<BRow>,
)

class Geo(
    val photoW: Float,
    val photoH: Float,
    val side: Float,
    val top: Float,
    val bottom: Float,
    val safeBottom: Float,
    val totalW: Float,
    val totalH: Float,
    val banner: BLayout?,
) {
    fun inPhoto(x: Float, y: Float) = x >= side && x <= side + photoW && y >= top && y <= top + photoH
}

/**
 * Draws the polaroid card. Everything is in card pixels (the same numbers the web version used),
 * so the same code paints the small preview and the full-size picture that gets saved.
 */
object Card {

    private const val PLACEHOLDER = 0xFFCFC6B8.toInt()

    // ---------------- geometry ----------------

    fun geometry(st: PState, src: Bitmap?): Geo {
        if (st.ratio == Ratio.Banner) {
            val b = bannerLayout(st)
            return Geo(b.photoW, b.photoH, b.side, b.top, 150f, 0f, 1920f, 1080f, b)
        }
        val pw: Float
        val ph: Float
        when {
            st.ratio == Ratio.Story -> { pw = 980f; ph = 1240f }
            st.ratio == Ratio.Insta -> { pw = 1400f; ph = 1645f }
            src == null -> { pw = 1400f; ph = 1050f }
            else -> {
                val r = when (st.ratio) {
                    Ratio.Mini -> 2f / 3f
                    Ratio.Square -> 1f
                    Ratio.Wide -> 3f / 2f
                    else -> src.width.toFloat() / src.height
                }
                pw = 1400f
                ph = (1400f / r).roundToInt().toFloat()
            }
        }
        val story = st.ratio == Ratio.Story
        val side = if (story) 50f else (pw * 0.05f).roundToInt().toFloat()
        val top = if (story) 250f else side
        val bottom = if (story) 180f else (pw * (if (st.ratio == Ratio.Insta) 0.15f else 0.22f)).roundToInt().toFloat()
        val safe = if (story) 250f else 0f
        return Geo(pw, ph, side, top, bottom, safe, pw + side * 2, top + ph + bottom + safe, null)
    }

    /** Where the picture lands inside the photo area: x, y, width, height. */
    fun cover(src: Bitmap, w: Float, h: Float, st: PState): FloatArray {
        val sw = src.width.toFloat()
        val sh = src.height.toFloat()
        val base = max(w / sw, h / sh)
        val s = base * max(1f, st.cropZ)
        val dw = sw * s
        val dh = sh * s
        var x = (w - dw) / 2 + st.cropX
        var y = (h - dh) / 2 + st.cropY
        x = if (dw >= w) min(0f, max(w - dw, x)) else (w - dw) / 2
        y = if (dh >= h) min(0f, max(h - dh, y)) else (h - dh) / 2
        return floatArrayOf(x, y, dw, dh)
    }

    /** True when the card shows camera, lens or exposure text under the picture. */
    fun hasInfo(st: PState): Boolean {
        if (st.showCam && st.camParts.isNotEmpty()) return true
        if (!st.isPhone && st.lens.isNotBlank()) return true
        if (st.showExp && st.hasExposure && st.expLineCard.isNotEmpty()) return true
        return false
    }

    // ---------------- text helpers ----------------

    private enum class Base { Top, Middle, Alpha }

    private fun textPaint(fontKey: String, size: Float, weight: Int, color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = Fonts.typeface(fontKey)
            textSize = max(1f, size)
            this.color = color
            isFakeBoldText = weight >= 600 && !Fonts.isSingleWeight(fontKey)
        }

    private fun Canvas.txt(s: String, x: Float, y: Float, p: Paint, align: Paint.Align, base: Base, maxW: Float? = null) {
        if (s.isEmpty()) return
        p.textAlign = align
        val fm = p.fontMetrics
        val by = when (base) {
            Base.Top -> y - fm.ascent
            Base.Middle -> y - (fm.ascent + fm.descent) / 2f
            Base.Alpha -> y
        }
        if (maxW != null) {
            val w = p.measureText(s)
            if (w > maxW && w > 0f) {
                save()
                translate(x, by)
                scale(maxW / w, 1f)
                drawText(s, 0f, 0f, p)
                restore()
                return
            }
        }
        drawText(s, x, by, p)
    }

    /** Top and bottom of the ink, measured from the "top" text line. */
    private fun inkBounds(p: Paint, s: String): FloatArray {
        val r = Rect()
        p.getTextBounds(s, 0, s.length, r)
        val asc = p.fontMetrics.ascent
        return floatArrayOf(r.top - asc, r.bottom - asc)
    }

    private fun wrapLines(p: Paint, text: String, width: Float): List<String> {
        val lines = ArrayList<String>()
        var line = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += Character.charCount(cp)
            if (ch == "\n") {
                lines.add(line.toString())
                line = StringBuilder()
                continue
            }
            if (line.isNotEmpty() && p.measureText(line.toString() + ch) > width) {
                lines.add(line.toString())
                line = StringBuilder(ch)
            } else {
                line.append(ch)
            }
        }
        if (line.isNotEmpty()) lines.add(line.toString())
        return lines
    }

    // ---------------- logos and camera line ----------------

    private fun drawLogo(c: Canvas, mark: String, left: Float, top: Float, w: Float, h: Float, color: Int, pxScale: Float) {
        val mask = Logos.mask(mark) ?: return
        val tw = max(1, ceil(w * pxScale).toInt())
        val th = max(1, (tw.toFloat() * mask.height / mask.width).roundToInt())
        val dst = RectF(left, top, left + w, top + h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(Logos.scaledTo(mask, mark, tw, th), null, dst, p)
        val accent = Logos.accent(mark)
        if (accent != null) {
            val ap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            c.drawBitmap(Logos.scaledTo(accent, "$mark-accent", tw, th), null, dst, ap)
        }
    }

    private fun partWidth(p: Paint, part: CamPart, height: Float): Float {
        val mark = part.mark
        return if (mark != null && Logos.has(mark)) Logos.layout(mark, height).width else p.measureText(part.text)
    }

    private fun cameraLine(c: Canvas, parts: List<CamPart>, x: Float, y: Float, h: Float, color: Int, fontKey: String, maxW: Float, pxScale: Float) {
        val p = textPaint(fontKey, h, 700, color)
        val gap = if (parts.size > 1) h * 0.45f else 0f
        val widths = parts.map { partWidth(p, it, h) }
        val total = widths.sum() + gap * (parts.size - 1)
        val scale = min(1f, maxW / max(1f, total))
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        var left = -total / 2f
        parts.forEachIndexed { i, part ->
            val mark = part.mark
            if (mark != null && Logos.has(mark)) {
                val box = Logos.layout(mark, h)
                drawLogo(c, mark, left, box.top, box.width, box.height, color, pxScale * scale)
            } else {
                c.txt(part.text, left, 0f, p, Paint.Align.LEFT, Base.Top)
            }
            left += widths[i] + gap
        }
        c.restore()
    }

    // ---------------- frame backgrounds ----------------

    private var dotTile: BitmapShader? = null

    private fun dots(): BitmapShader {
        dotTile?.let { return it }
        val tile = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val tc = Canvas(tile)
        val p = Paint().apply { color = Color.argb(13, 110, 80, 40) }
        // 4px dots on an 8px grid wherever (column + row) is a multiple of 3
        for ((a, b) in listOf(0 to 0, 1 to 2, 2 to 1)) tc.drawRect(a * 8f, b * 8f, a * 8f + 4f, b * 8f + 4f, p)
        val shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        dotTile = shader
        return shader
    }

    private fun fill(c: Canvas, color: Int, w: Float, h: Float) {
        c.drawRect(0f, 0f, w, h, Paint().apply { this.color = color })
    }

    private fun tintOf(st: PState, src: Bitmap?, g: Geo): Int {
        if (src == null) return Color.argb(148, 100, 80, 70)
        val r = cover(src, g.photoW, g.photoH, st)
        val step = max(12, floor(min(g.photoW, g.photoH) / 20f).toInt())
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        var y = 0
        while (y < g.photoH) {
            var x = 0
            while (x < g.photoW) {
                val px = ((x - r[0]) / r[2] * src.width).toInt().coerceIn(0, src.width - 1)
                val py = ((y - r[1]) / r[3] * src.height).toInt().coerceIn(0, src.height - 1)
                val v = src.getPixel(px, py)
                sr += Color.red(v)
                sg += Color.green(v)
                sb += Color.blue(v)
                n++
                x += step
            }
            y += step
        }
        if (n == 0) return Color.argb(148, 100, 80, 70)
        return Color.argb(148, (sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    private fun frameBackground(c: Canvas, st: PState, src: Bitmap?, g: Geo, banner: Boolean) {
        val w = g.totalW
        val h = g.totalH
        when (st.frame) {
            Frame.Black -> fill(c, 0xFF111111.toInt(), w, h)
            Frame.Tint -> {
                if (src != null) {
                    val blur = Blurs.of(src)
                    val p = Paint(Paint.FILTER_BITMAP_FLAG)
                    p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) })
                    c.drawBitmap(blur, null, RectF(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f), p)
                }
                fill(c, tintOf(st, src, g), w, h)
            }
            Frame.Vintage -> {
                fill(c, 0xFFEBDCC4.toInt(), w, h)
                c.drawRect(0f, 0f, w, h, Paint().apply { shader = dots() })
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = Color.argb(51, 120, 90, 50)
                    strokeWidth = if (banner) 4f else (w * 0.005f).roundToInt().toFloat()
                }
                if (banner) c.drawRect(10f, 10f, w - 10f, h - 10f, stroke)
                else c.drawRect(g.side * 0.4f, g.side * 0.4f, w - g.side * 0.4f, h - g.side * 0.4f, stroke)
            }
            Frame.Film -> {
                val sideM = if (banner) 60f else g.side
                fill(c, 0xFF0D0D0D.toInt(), w, h)
                val hole = Paint().apply { color = 0xFF1F1F1F.toInt() }
                val holeW = (sideM * 0.4f).roundToInt().toFloat()
                val holeH = (holeW * 1.3f).roundToInt().toFloat()
                if (holeH > 0f) {
                    var y = sideM
                    while (y < h - sideM) {
                        c.drawRect(sideM * 0.3f, y, sideM * 0.3f + holeW, y + holeH, hole)
                        c.drawRect(w - sideM * 0.3f - holeW, y, w - sideM * 0.3f, y + holeH, hole)
                        y += holeH * 1.8f
                    }
                }
                val amber = 0xFFE09F3E.toInt()
                if (banner) {
                    val p = textPaint("mono", 18f, 700, amber)
                    // the web version put this text at the bottom-left, 12px above the edge
                    val fm = p.fontMetrics
                    c.drawText("▶ 12A", 60f, h - 12f - fm.descent, p.apply { textAlign = Paint.Align.LEFT })
                } else {
                    val p = textPaint("mono", (w * 0.02f).roundToInt().toFloat(), 700, amber)
                    c.txt("▶ 12A", w - sideM * 2, h - g.safeBottom - g.bottom * 0.25f, p, Paint.Align.LEFT, Base.Alpha)
                }
            }
            Frame.White -> fill(c, if (banner) 0xFFFFFFFF.toInt() else 0xFFF7F3EC.toInt(), w, h)
        }
    }

    private fun drawPhoto(c: Canvas, st: PState, src: Bitmap?, g: Geo, x: Float, y: Float) {
        c.save()
        c.clipRect(x, y, x + g.photoW, y + g.photoH)
        if (src != null) {
            val r = cover(src, g.photoW, g.photoH, st)
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.drawBitmap(src, null, RectF(x + r[0], y + r[1], x + r[0] + r[2], y + r[1] + r[3]), p)
        } else {
            fill2(c, PLACEHOLDER, x, y, x + g.photoW, y + g.photoH)
        }
        c.restore()
    }

    private fun fill2(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l, t, r, b, Paint().apply { this.color = color })
    }

    // ---------------- normal card ----------------

    /** Paints the whole card. pxScale = screen pixels per card pixel (1 when saving), used to pick sharp logo sizes. */
    fun draw(c: Canvas, st: PState, src: Bitmap?, pxScale: Float = 1f) {
        val g = geometry(st, src)
        if (g.banner != null) {
            drawBanner(c, st, src, g, g.banner, pxScale)
            return
        }
        if (src == null) {
            fill(c, 0xFFF7F3EC.toInt(), g.totalW, g.totalH)
            fill2(c, PLACEHOLDER, g.side, g.top, g.side + g.photoW, g.top + g.photoH)
            return
        }
        val totalW = g.totalW
        val totalH = g.totalH
        val photoX = g.side
        val photoY = g.top
        frameBackground(c, st, src, g, false)
        drawPhoto(c, st, src, g, photoX, photoY)

        val fontKey = Fonts.usable(st.font)
        val textColor = st.color
        val fontSizeLeft = (Fonts.sizeFor(fontKey, st.sizeLeft) * (totalW / 360f)).roundToInt().toFloat()

        val lensText = if (!st.isPhone) st.lens.trim() else ""
        val noteText = st.noteText.trim()
        val expText = if (st.hasExposure && st.showExp) st.expLineCard else ""

        class Row(val text: String?, val parts: List<CamPart>?, var size: Float, val weight: Int, val logo: Boolean)

        val rows = ArrayList<Row>()
        val cameraParts = if (st.showCam) st.camParts else emptyList()
        if (cameraParts.isNotEmpty()) rows.add(Row(null, cameraParts, fontSizeLeft, 700, cameraParts.any { it.mark != null }))
        if (lensText.isNotEmpty()) rows.add(Row(lensText, null, fontSizeLeft * 0.82f, 500, false))
        if (expText.isNotEmpty()) rows.add(Row(expText, null, fontSizeLeft * 0.7f, 500, false))
        rows.forEach { if (it.logo) it.size *= 0.8f }

        val rowGap = fontSizeLeft * (if (expText.isNotEmpty()) 0.25f else 0.4f)
        val available = g.bottom * (if (expText.isNotEmpty()) 0.82f else 0.58f)
        val natural = rows.sumOf { it.size.toDouble() }.toFloat() + max(0, rows.size - 1) * rowGap
        val factor = min(1f, available / max(1f, natural))
        var currentY = photoY + g.photoH + g.bottom * (if (expText.isNotEmpty()) 0.49f else 0.46f) - natural * factor / 2f
        val centerWidth = g.photoW * 0.92f

        if (st.ratio == Ratio.Story && rows.isNotEmpty()) {
            // centre the visible text in the lower caption area, keeping 100px clear for the reply bar
            val infoCenter = (photoY + g.photoH + totalH - 100f) / 2f
            var visualTop = Float.POSITIVE_INFINITY
            var visualBottom = Float.NEGATIVE_INFINITY
            var offset = 0f
            for (row in rows) {
                val size = row.size * factor
                val p = textPaint(fontKey, size, row.weight, textColor)
                var top: Float
                var bottom: Float
                if (row.parts != null) {
                    val tops = ArrayList<Float>()
                    val bottoms = ArrayList<Float>()
                    var width = 0f
                    for (part in row.parts) {
                        val mark = part.mark
                        if (mark != null && Logos.has(mark)) {
                            val box = Logos.layout(mark, size)
                            width += box.width
                            tops.add(box.top)
                            bottoms.add(box.top + box.height)
                        } else {
                            width += p.measureText(part.text)
                            val b = inkBounds(p, part.text)
                            tops.add(b[0])
                            bottoms.add(b[1])
                        }
                    }
                    width += max(0, row.parts.size - 1) * size * 0.45f
                    val scale = min(1f, centerWidth / max(1f, width))
                    top = tops.min() * scale
                    bottom = bottoms.max() * scale
                } else {
                    val b = inkBounds(p, row.text ?: "")
                    top = b[0]
                    bottom = b[1]
                }
                visualTop = min(visualTop, offset + top)
                visualBottom = max(visualBottom, offset + bottom)
                offset += size + rowGap * factor
            }
            currentY = infoCenter - (visualTop + visualBottom) / 2f
        }

        class Bounds(val left: Float, val top: Float, val bottom: Float)

        val infoBounds = ArrayList<Bounds>()
        for (row in rows) {
            val size = row.size * factor
            val p = textPaint(fontKey, size, row.weight, textColor)
            if (row.parts != null) {
                val widths = row.parts.map { partWidth(p, it, size) }
                val width = min(centerWidth, widths.sum() + max(0, widths.size - 1) * size * 0.45f)
                infoBounds.add(Bounds((totalW - width) / 2f, currentY, currentY + size * 1.25f))
                cameraLine(c, row.parts, totalW / 2f, currentY, size, textColor, fontKey, centerWidth, pxScale)
            } else {
                val text = row.text ?: ""
                val b = inkBounds(p, text)
                val width = min(centerWidth, p.measureText(text))
                infoBounds.add(Bounds((totalW - width) / 2f, currentY + b[0], currentY + b[1]))
                c.txt(text, totalW / 2f, currentY, p, Paint.Align.CENTER, Base.Top, centerWidth)
            }
            currentY += size + rowGap * factor
        }

        val hasCameraInfo = rows.isNotEmpty()
        if (noteText.isNotEmpty()) {
            val noteFont = Fonts.usable(st.noteFont)
            val noteSize = Fonts.sizeFor(noteFont, st.noteSize) * (totalW / 360f) * 0.85f
            val noteY = photoY + g.photoH + g.bottom * 0.04f
            val boxBottom = photoY + g.photoH + g.bottom * 0.32f
            var noteWidth = g.photoW * 0.33f
            if (hasCameraInfo) {
                for (b in infoBounds) {
                    if (b.top < boxBottom && b.bottom > noteY) {
                        noteWidth = min(noteWidth, max(0f, b.left - photoX - g.photoW * 0.015f))
                    }
                }
            } else {
                noteWidth = g.photoW
            }
            c.save()
            c.clipRect(photoX, noteY, photoX + noteWidth, if (hasCameraInfo) boxBottom else totalH - g.safeBottom)
            val p = textPaint(noteFont, noteSize, 500, textColor)
            if (!hasCameraInfo) {
                c.txt(noteText, photoX, noteY, p, Paint.Align.LEFT, Base.Top)
            } else if (noteWidth > 0f) {
                val lineHeight = noteSize * 1.2f
                val maxLines = max(1, floor((boxBottom - noteY) / lineHeight).toInt())
                wrapLines(p, noteText, noteWidth).take(maxLines).forEachIndexed { i, line ->
                    c.txt(line, photoX, noteY + i * lineHeight, p, Paint.Align.LEFT, Base.Top)
                }
            }
            c.restore()
        }

        val ds = st.dateText.trim()
        if (st.dateOn && ds.isNotEmpty()) {
            val dsize = (st.dateSize * (totalW / 360f)).roundToInt().toFloat()
            val p = textPaint(fontKey, dsize, 700, textColor)
            val tx = if (!st.dateRight) g.side / 2f else totalW - g.side / 2f
            val ty = photoY + st.datePos / 100f * g.photoH
            c.save()
            c.translate(tx, ty)
            c.rotate(90f)
            c.txt(ds, 0f, 0f, p, Paint.Align.CENTER, Base.Middle)
            c.restore()
        }
    }

    // ---------------- banner (1920 x 1080) ----------------

    fun bannerLayout(st: PState): BLayout {
        val font = Fonts.usable(st.font)
        val size = Fonts.sizeFor(font, st.sizeLeft) * 1920f / 360f * 0.26f
        val rows = ArrayList<BRow>()
        val parts = if (st.showCam) st.camParts else emptyList()
        if (parts.isNotEmpty() && st.shotOn) rows.add(BRow("Shot on", null, size * 1.15f))
        if (parts.isNotEmpty()) rows.add(BRow(null, parts, size * 0.8f))
        if (!st.isPhone && st.lens.isNotEmpty()) rows.add(BRow(st.lens, null, size * 0.72f))
        if (st.showExp && st.hasExposure) rows.add(BRow(st.expLineBanner, null, size * 0.56f))
        var wanted = 290f
        for (row in rows) {
            val p = textPaint(font, row.size, 500, 0)
            val width = if (row.parts != null) {
                row.parts.sumOf { partWidth(p, it, row.size).toDouble() }.toFloat() + (row.parts.size - 1) * row.size * 0.45f
            } else {
                p.measureText(row.text ?: "")
            }
            wanted = max(wanted, width + 48f)
        }
        val infoWidth = if (st.sizeLeft == 22) 290f else ceil(min(740f, wanted))
        val side = 60f + infoWidth + 50f
        val photoW = floor(min(1455f, 1860f - side) / 3f) * 3f
        val photoH = photoW * 2f / 3f
        val top = 55f + (970f - photoH) / 2f
        return BLayout(photoW, photoH, side, top, infoWidth, rows)
    }

    private fun drawBanner(c: Canvas, st: PState, src: Bitmap?, g: Geo, b: BLayout, pxScale: Float) {
        val x = g.side
        val y = g.top
        frameBackground(c, st, src, g, true)
        drawPhoto(c, st, src, g, x, y)

        val font = Fonts.usable(st.font)
        val color = st.color
        val info = b.infoWidth
        val rows = ArrayList<BRow>()
        for (row in b.rows) {
            var size = row.size
            val p = textPaint(font, size, 500, color)
            if (st.sizeLeft == 22) {
                val measured = if (row.parts != null) {
                    row.parts.sumOf { partWidth(p, it, size).toDouble() }.toFloat() + max(0, row.parts.size - 1) * size * 0.45f
                } else {
                    p.measureText(row.text ?: "")
                }
                if (row.parts != null && row.parts.none { it.mark != null } && measured > (info - 40f) / 0.6f) {
                    size *= 0.6f
                    p.textSize = size
                    for (t in wrapLines(p, row.parts.joinToString(" ") { it.text }, info - 40f)) rows.add(BRow(t, null, size))
                    continue
                }
                size *= min(1f, max(if (row.parts != null) 0f else 0.6f, (info - 40f) / max(1f, measured)))
                p.textSize = size
            }
            if (row.parts != null) rows.add(BRow(null, row.parts, size))
            else for (t in wrapLines(p, row.text ?: "", info - 40f)) rows.add(BRow(t, null, size))
        }
        val gap = 6f
        val natural = rows.sumOf { (it.size * 1.25f).toDouble() }.toFloat() + max(0, rows.size - 1) * gap
        val scale = min(1f, 900f / max(1f, natural))
        c.save()
        c.clipRect(60f, 60f, 60f + info, 1020f)
        var rowY = 540f - natural * scale / 2f
        for (row in rows) {
            val h = row.size * scale
            if (row.parts != null) {
                cameraLine(c, row.parts, 60f + info / 2f, rowY, h, color, font, info - 40f, pxScale)
            } else {
                val p = textPaint(font, h, 500, color)
                c.txt(row.text ?: "", 60f + info / 2f, rowY + h * 0.625f, p, Paint.Align.CENTER, Base.Middle, info - 40f)
            }
            rowY += h * 1.25f + gap * scale
        }
        c.restore()

        val captionY = y + b.photoH + 10f
        val captionH = min(140f, 1080f - captionY - 10f)
        val date = if (st.dateOn) st.dateText.trim() else ""
        val dateTop = st.bannerDateTop
        val dateAreaH = if (dateTop) y - 20f else 1080f - captionY - 10f
        var dateSize = min(st.dateSize * 1920f / 360f * 0.38f, max(1f, dateAreaH / 1.2f))
        var dp = textPaint(font, dateSize, 500, color)
        val naturalDateWidth = if (date.isNotEmpty()) dp.measureText(date) else 0f
        if (naturalDateWidth > b.photoW * 0.42f) {
            dateSize *= b.photoW * 0.42f / naturalDateWidth
            dp = textPaint(font, dateSize, 500, color)
        }
        val dateWidth = if (date.isNotEmpty()) dp.measureText(date) else 0f
        val dateY = if (dateTop) y - 10f - dateSize * 1.2f else captionY
        if (date.isNotEmpty()) {
            c.save()
            c.clipRect(x, if (dateTop) 10f else captionY, x + b.photoW, (if (dateTop) 10f else captionY) + max(0f, dateAreaH))
            c.txt(date, x + b.photoW, dateY, dp, Paint.Align.RIGHT, Base.Top)
            c.restore()
        }
        if (st.noteText.isNotEmpty()) {
            val noteFont = Fonts.usable(st.noteFont)
            val width = b.photoW - (if (date.isNotEmpty() && !dateTop) dateWidth + 28f else 0f)
            val size = Fonts.sizeFor(noteFont, st.noteSize) * 1920f / 360f * 0.30f
            val p = textPaint(noteFont, size, 500, color)
            c.save()
            c.clipRect(x, captionY, x + width, captionY + captionH)
            val maxLines = max(1, floor(captionH / (size * 1.2f)).toInt())
            wrapLines(p, st.noteText, width).take(maxLines).forEachIndexed { i, line ->
                c.txt(line, x, captionY + i * size * 1.2f, p, Paint.Align.LEFT, Base.Top)
            }
            c.restore()
        }
    }

    /** The card as a full-size picture. */
    fun render(st: PState, src: Bitmap): Bitmap {
        val g = geometry(st, src)
        val bmp = Bitmap.createBitmap(g.totalW.roundToInt(), g.totalH.roundToInt(), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), st, src, 1f)
        return bmp
    }
}

/** Soft, blurred copy of a picture for the "取色" frame. */
object Blurs {
    private var lastSrc: Bitmap? = null
    private var lastOut: Bitmap? = null

    @Synchronized
    fun of(src: Bitmap): Bitmap {
        val cached = lastOut
        if (lastSrc === src && cached != null) return cached
        var cur = src
        val target = 96
        while (cur.width / 2 >= target) cur = Bitmap.createScaledBitmap(cur, cur.width / 2, max(1, cur.height / 2), true)
        val w = target
        val h = max(8, (target.toFloat() * src.height / src.width).roundToInt())
        val small = Bitmap.createScaledBitmap(cur, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { boxBlur(px, w, h, 2) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        lastSrc = src
        lastOut = out
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, true)
        pass(tmp, px, w, h, r, false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val count = 2 * r + 1
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var a = 0
                var rr = 0
                var g = 0
                var b = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[l * w + j] else src[j * w + l]
                    a += p ushr 24
                    rr += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                val out = ((a / count) shl 24) or ((rr / count) shl 16) or ((g / count) shl 8) or (b / count)
                if (horizontal) dst[l * w + i] = out else dst[i * w + l] = out
            }
        }
    }
}
