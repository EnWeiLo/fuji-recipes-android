package com.enweilo.fujishokudo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.imageio.ImageIO
import java.awt.Color as AwtColor

enum class Frame(val label: String) { White("白框"), Black("黑框"), Tint("取色"), Vintage("復古"), Film("底片") }

/** Instant film sizes in mm: whole card, picture. */
enum class Ratio(val label: String, val cardW: Float, val cardH: Float, val picW: Float, val picH: Float, val top: Float) {
    Mini("Mini", 54f, 86f, 46f, 62f, 6.5f),
    Square("方框", 72f, 86f, 62f, 62f, 6.5f),
    Wide("Wide", 108f, 86f, 99f, 62f, 6.5f),
}

class PolaroidModel(private val scope: CoroutineScope, private val settings: Settings, private val toast: (String) -> Unit) {
    var photos by mutableStateOf<List<File>>(emptyList())
        private set
    var index by mutableIntStateOf(0)
    var frame by mutableStateOf(Frame.White)
    var ratio by mutableStateOf(Ratio.Mini)
    var caption by mutableStateOf("")
    var dateStamp by mutableStateOf(true)
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    val thumbs = mutableMapOf<File, ImageBitmap?>()
    private val sources = HashMap<File, BufferedImage>()

    fun addFiles(fs: List<File>) {
        photos = (photos + fs).distinct()
        index = photos.indexOf(fs.firstOrNull()).coerceAtLeast(0)
    }

    fun remove(i: Int) {
        photos = photos.toMutableList().also { if (i in it.indices) it.removeAt(i) }
        index = index.coerceAtMost(photos.lastIndex).coerceAtLeast(0)
    }

    private fun source(f: File): BufferedImage? = sources[f] ?: runCatching { Images.decode(f.readBytes(), 2000) }.getOrNull()?.also {
        if (sources.size > 12) sources.clear()
        sources[f] = it
    }

    fun thumb(f: File): ImageBitmap? = thumbs[f]

    fun loadThumb(f: File, done: () -> Unit) {
        if (thumbs.containsKey(f)) return
        thumbs[f] = null
        scope.launch {
            val b = withContext(Dispatchers.IO) { runCatching { Images.decodeBitmap(f.readBytes(), 200) }.getOrNull() }
            thumbs[f] = b
            done()
        }
    }

    fun refresh() {
        val f = photos.getOrNull(index) ?: run { preview = null; return }
        val fr = frame; val ra = ratio; val cap = caption; val ds = dateStamp
        scope.launch {
            val img = withContext(Dispatchers.Default) { source(f)?.let { render(it, fr, ra, cap, ds, f, 8f) } }
            preview = img?.toComposeImageBitmap()
        }
    }

    fun exportCurrent() {
        val f = photos.getOrNull(index) ?: return
        val out = Pick.save("存成拍立得", f.nameWithoutExtension + "_拍立得.png", "png") ?: return
        val fr = frame; val ra = ratio; val cap = caption; val ds = dateStamp
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ImageIO.write(render(source(f)!!, fr, ra, cap, ds, f, 300f / 25.4f), "png", out) }.isSuccess
            }
            toast(if (ok) "已存成 ${out.name}（300 dpi，可以直接拿去印）" else "儲存失敗")
        }
    }

    fun exportAll() {
        if (photos.isEmpty()) return
        val dir = Pick.folder(photos.first().parentFile) ?: return
        val list = photos.toList()
        val fr = frame; val ra = ratio; val cap = caption; val ds = dateStamp
        scope.launch {
            var n = 0
            withContext(Dispatchers.IO) {
                for (f in list) runCatching {
                    ImageIO.write(render(source(f)!!, fr, ra, cap, ds, f, 300f / 25.4f), "png", File(dir, f.nameWithoutExtension + "_拍立得.png"))
                    n++
                }
            }
            toast("已存 $n 張拍立得到 ${dir.name}")
        }
    }

    companion object {
        /** Draws the card at [pxPerMm]. */
        fun render(src: BufferedImage, frame: Frame, ratio: Ratio, caption: String, date: Boolean, file: File, pxPerMm: Float): BufferedImage {
            val w = (ratio.cardW * pxPerMm).toInt()
            val h = (ratio.cardH * pxPerMm).toInt()
            val pw = (ratio.picW * pxPerMm).toInt()
            val ph = (ratio.picH * pxPerMm).toInt()
            val px = (w - pw) / 2
            val py = (ratio.top * pxPerMm).toInt()
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val avg = average(src)
            val (bg, ink) = when (frame) {
                Frame.White -> AwtColor(0xF7F5F0) to AwtColor(0x2A2522)
                Frame.Black -> AwtColor(0x161514) to AwtColor(0xF6EFE4)
                Frame.Tint -> avg to (if (lum(avg) > 0.55) AwtColor(0x221E1A) else AwtColor(0xFFFFFF))
                Frame.Vintage -> AwtColor(0xEFE3CB) to AwtColor(0x3A2E21)
                Frame.Film -> AwtColor(0x1B1A18) to AwtColor(0xE09F3E)
            }
            g.color = bg
            g.fillRect(0, 0, w, h)
            if (frame == Frame.Film) {
                g.color = AwtColor(0x2E2C29)
                val hole = (2.2f * pxPerMm).toInt()
                var x = (2f * pxPerMm).toInt()
                while (x < w) {
                    g.fillRoundRect(x, (1f * pxPerMm).toInt(), hole, (1.4f * pxPerMm).toInt(), hole / 3, hole / 3)
                    g.fillRoundRect(x, h - (2.4f * pxPerMm).toInt(), hole, (1.4f * pxPerMm).toInt(), hole / 3, hole / 3)
                    x += (4f * pxPerMm).toInt()
                }
            }
            // picture: centre crop to the window
            val sr = src.width.toFloat() / src.height
            val dr = pw.toFloat() / ph
            val (cw, ch) = if (sr > dr) (src.height * dr).toInt() to src.height else src.width to (src.width / dr).toInt()
            val cx = (src.width - cw) / 2
            val cy = (src.height - ch) / 2
            g.drawImage(src, px, py, px + pw, py + ph, cx, cy, cx + cw, cy + ch, null)
            if (date) {
                val d = SimpleDateFormat("''yy MM dd").format(Date(file.lastModified()))
                g.font = Font(Font.MONOSPACED, Font.BOLD, (3.2f * pxPerMm).toInt())
                g.color = AwtColor(255, 150, 60, 230)
                val fm = g.fontMetrics
                g.drawString(d, px + pw - fm.stringWidth(d) - (2f * pxPerMm).toInt(), py + ph - (2f * pxPerMm).toInt())
            }
            if (caption.isNotBlank()) {
                g.font = Font("Microsoft JhengHei", Font.PLAIN, (4.2f * pxPerMm).toInt()).let { if (it.canDisplayUpTo(caption) == -1) it else Font(Font.SANS_SERIF, Font.PLAIN, it.size) }
                g.color = ink
                val fm = g.fontMetrics
                val bottom = py + ph
                val y = bottom + (h - bottom) / 2 + fm.ascent / 2 - fm.descent / 2
                g.drawString(caption, (w - fm.stringWidth(caption)) / 2, y)
            }
            g.dispose()
            return out
        }

        private fun average(img: BufferedImage): AwtColor {
            var r = 0L; var gg = 0L; var b = 0L; var n = 0
            val step = maxOf(1, minOf(img.width, img.height) / 40)
            var y = 0
            while (y < img.height) {
                var x = 0
                while (x < img.width) {
                    val c = img.getRGB(x, y)
                    r += (c shr 16) and 255; gg += (c shr 8) and 255; b += c and 255; n++
                    x += step
                }
                y += step
            }
            return AwtColor((r / n).toInt(), (gg / n).toInt(), (b / n).toInt())
        }

        private fun lum(c: AwtColor) = (0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue) / 255.0
    }
}

@Composable
fun PolaroidScreen(app: AppState) {
    val p = app.polaroid
    var tick by androidx.compose.runtime.remember { mutableIntStateOf(0) }
    LaunchedEffect(p.photos, p.index, p.frame, p.ratio, p.caption, p.dateStamp) { p.refresh() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("拍立得", 20, FontWeight.Bold)
            Txt("${p.photos.size} 張照片", 13, color = C.dim, modifier = Modifier.weight(1f))
            Btn("加入照片", {
                Pick.file("選一張照片", app.bake.outputDir(), "jpg", "jpeg", "png")?.let { p.addFiles(listOf(it)) }
            }, icon = Ico.Plus)
            Btn("全部存成拍立得", { p.exportAll() }, enabled = p.photos.isNotEmpty())
            Btn("存這張", { p.exportCurrent() }, primary = true, enabled = p.photos.isNotEmpty())
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF2B2825)), contentAlignment = Alignment.Center) {
                    val img = p.preview
                    if (p.photos.isEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Txt("烘焙頁按「做成拍立得」，或在這裡加入照片。", 14, color = C.sub)
                        }
                    } else if (img != null) {
                        Image(img, null, Modifier.fillMaxSize().padding(28.dp), contentScale = ContentScale.Fit)
                    }
                }
                if (p.photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(p.photos, key = { _, f -> f.absolutePath }) { i, f ->
                        p.loadThumb(f) { tick++ }
                        val t = if (tick >= 0) p.thumb(f) else null
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                Modifier.size(84.dp, 60.dp).clip(RoundedCornerShape(8.dp)).background(C.card)
                                    .border(2.dp, if (i == p.index) C.accent else Color.Transparent, RoundedCornerShape(8.dp)).press { p.index = i },
                            ) { t?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                            Txt("移除", 11, color = C.dim, modifier = Modifier.press { p.remove(i) })
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
            Column(Modifier.width(320.dp).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Txt("相框", 12, color = C.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Frame.entries.forEach { f -> Chip(f.label, p.frame == f) { p.frame = f } } }
                Txt("尺寸", 12, color = C.dim)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Ratio.entries.forEach { r -> Chip(r.label, p.ratio == r) { p.ratio = r } } }
                Field("底部文字", p.caption, { p.caption = it })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(if (p.dateStamp) "日期戳記：開" else "日期戳記：關", p.dateStamp) { p.dateStamp = !p.dateStamp }
                }
                Spacer(Modifier.weight(1f))
                Txt("存出來是 300 dpi 的 PNG，尺寸跟真的拍立得底片一樣，可以直接拿去沖印。", 12, color = C.dim)
            }
        }
    }
}
