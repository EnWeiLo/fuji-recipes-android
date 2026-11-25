package com.enweilo.fujishokudo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.camera.FujiBaker
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun BakeScreen(app: AppState) {
    val b = app.bake
    val r = b.currentRecipe()
    Column(Modifier.fillMaxSize()) {
        // ---- header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("烘焙", 20, FontWeight.Bold)
            Txt(b.folder?.let { "${it.absolutePath} · ${b.files.size} 張 RAW" } ?: "還沒開資料夾", 13, color = C.dim, maxLines = 1, modifier = Modifier.weight(1f))
            CamPill(app)
            Btn("開啟資料夾", { Pick.folder(b.folder)?.let { b.openFolder(it) } }, icon = Ico.Folder)
            Btn(
                if (b.batchRunning) "停止批次（${b.batchDone}／${b.batchTotal}）" else "全部用這個食譜烘（${b.files.size}）",
                { b.batch() },
                primary = !b.batchRunning,
                enabled = b.files.isNotEmpty() && r != null && (b.batchRunning || app.camera.state == CamState.Connected),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // ---- picture + strip ----
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(C.card).padding(3.dp)) {
                        for (m in ViewMode.entries) {
                            val on = b.view == m
                            Box(
                                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF3A3530) else Color.Transparent)
                                    .press { b.view = m }.padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Txt(when (m) { ViewMode.Original -> "拍攝時"; ViewMode.Baked -> "相機烘焙"; ViewMode.Split -> "左右對照" }, 13, color = if (on) C.text else C.sub)
                            }
                        }
                    }
                    Chip(if (b.autoBake) "點選就自動烘：開" else "點選就自動烘：關", b.autoBake) { b.changeAutoBake(!b.autoBake) }
                    Spacer(Modifier.weight(1f))
                    b.current?.let { Txt("${it.file.name} · ${it.model} · ${"%.1f".format(it.file.length() / 1048576f)} MB", 12, color = C.dim, maxLines = 1) }
                }
                Viewer(app, Modifier.weight(1f).fillMaxWidth())
                Strip(app)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
            RecipeSide(app, Modifier.width(320.dp).fillMaxHeight())
        }

        // ---- footer ----
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val done = b.baked.size
            Txt(if (b.batchRunning) "批次：${b.batchDone}／${b.batchTotal}" else "這個食譜已烘：$done／${b.files.size}", 12, color = C.dim)
            val frac = if (b.batchRunning) b.batchDone / b.batchTotal.coerceAtLeast(1).toFloat() else done / b.files.size.coerceAtLeast(1).toFloat()
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.line)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac.coerceIn(0f, 1f)).background(C.accent))
            }
            Txt("輸出：${b.outputDir()?.absolutePath ?: "—"}", 12, color = C.dim, maxLines = 1, modifier = Modifier.press { b.outputDir()?.let { app.openFolder(it) } })
            Txt("← → 換照片　1–9 換食譜　按住空白鍵看拍攝時", 12, color = C.dim, maxLines = 1)
        }
    }
}

@Composable
fun CamPill(app: AppState) {
    val c = app.camera
    val (bg, fg, text) = when (c.state) {
        CamState.Connected -> Triple(C.okSoft, C.okText, "${c.model} 已連接" + if (c.busy) " · 忙碌中" else "")
        CamState.NeedsDriver -> Triple(C.warnSoft, C.warn, "找到相機，要先換驅動")
        CamState.Busy -> Triple(C.warnSoft, C.warn, "相機被佔用")
        CamState.NoLibrary -> Triple(C.warnSoft, C.warn, "缺少 USB 元件")
        CamState.Searching -> Triple(C.card, C.dim, "等待相機（RAW CONV. 模式）")
    }
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg).press { app.screen = Screen.Connect }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (c.state == CamState.Connected) C.ok else fg))
        Txt(text, 13, color = fg, maxLines = 1)
    }
}

@Composable
private fun Viewer(app: AppState, modifier: Modifier) {
    val b = app.bake
    val pop = remember { Animatable(1f) }
    LaunchedEffect(b.served) {
        if (b.served > 0) {
            pop.snapTo(0.97f)
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 400f))
        }
    }
    var split by remember { mutableFloatStateOf(0.5f) }
    var mouseHold by remember { mutableStateOf(false) }
    val showOriginal = app.holding || mouseHold || b.view == ViewMode.Original
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.photoBg)
            .pointerInput(b.view) {
                if (b.view != ViewMode.Split) detectTapGestures(onPress = { mouseHold = true; tryAwaitRelease(); mouseHold = false })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (b.files.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OvenDrawing(false, Modifier.size(170.dp, 134.dp))
                Btn("開啟放 RAF 的資料夾", { Pick.folder(b.folder)?.let { b.openFolder(it) } }, primary = true, icon = Ico.Folder)
                Txt("必須是接著的這台相機拍的 RAW", 13, color = C.dim)
            }
            return@Box
        }
        val result = b.result
        val original = b.original
        Box(Modifier.fillMaxSize().padding(14.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }, contentAlignment = Alignment.Center) {
            if (b.view == ViewMode.Split && result != null && original != null) {
                SplitView(original, result, split) { split = it }
            } else {
                val img = if (showOriginal) original ?: result else result ?: original
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        val label = when {
            b.view == ViewMode.Split && result != null -> "左：拍攝時　右：${b.currentRecipe()?.name ?: ""}"
            showOriginal || result == null -> "拍攝時"
            else -> "${b.currentRecipe()?.name ?: ""} · 相機烘焙"
        }
        Box(Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape).background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Txt(label, 13, maxLines = 1)
        }
        val msg = b.mismatch() ?: b.error ?: when {
            b.result != null || b.stage >= 0 -> null
            app.camera.state != CamState.Connected -> "接上相機（USB RAW CONV. 模式）就能烘焙"
            !b.autoBake -> "按右邊「烘焙這張」，或按 Enter"
            b.batchRunning -> "批次進行中，結束後再顯示"
            else -> null
        }
        if (msg != null && b.stage < 0) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xD9000000)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt(msg, 13)
                if (b.error != null && app.camera.state == CamState.Connected) Txt("再試一次", 13, FontWeight.Medium, C.accentText, modifier = Modifier.press { b.bake() })
            }
        }
        val k by animateFloatAsState(if (b.stage >= 0) 1f else 0f, tween(220))
        if (k > 0.01f) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(Color(0xD9151312)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OvenDrawing(true, Modifier.size(170.dp, 134.dp))
                    BakeSteps(b.stage.coerceAtLeast(0), b.progress)
                }
            }
        }
    }
}

@Composable
private fun SplitView(a: ImageBitmap, b: ImageBitmap, split: Float, onSplit: (Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val scale = min(wPx / b.width, hPx / b.height)
        val dw = b.width * scale
        val dh = b.height * scale
        val x0 = (wPx - dw) / 2f
        val y0 = (hPx - dh) / 2f
        Canvas(
            Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Crosshair).pointerInput(dw) {
                detectDragGestures(onDragStart = { p -> onSplit(((p.x - x0) / dw).coerceIn(0f, 1f)) }) { ch, _ ->
                    onSplit(((ch.position.x - x0) / dw).coerceIn(0f, 1f))
                }
            },
        ) {
            val cut = x0 + dw * split
            fun draw(img: ImageBitmap) = drawImage(
                img, IntOffset.Zero, IntSize(img.width, img.height),
                IntOffset(x0.roundToInt(), y0.roundToInt()), IntSize(dw.roundToInt(), dh.roundToInt()),
            )
            clipRect(x0, y0, cut, y0 + dh) { draw(a) }
            clipRect(cut, y0, x0 + dw, y0 + dh) { draw(b) }
            drawLine(Color.White, Offset(cut, y0), Offset(cut, y0 + dh), 2f)
            drawCircle(Color.White, 12f, Offset(cut, y0 + dh / 2))
        }
    }
}

@Composable
private fun Strip(app: AppState) {
    val b = app.bake
    val state = rememberLazyListState()
    LaunchedEffect(b.index) { if (b.files.isNotEmpty()) state.animateScrollToItem((b.index - 3).coerceAtLeast(0)) }
    if (b.files.isEmpty()) return
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 2.dp)) {
        itemsIndexed(b.files, key = { _, f -> f.file.absolutePath }) { i, rf ->
            val sel = i == b.index
            val done = rf.file.absolutePath in b.baked
            Column(Modifier.width(96.dp).press { b.select(i) }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(8.dp)).background(C.card)
                        .border(2.dp, if (sel) C.accent else Color.Transparent, RoundedCornerShape(8.dp)),
                ) {
                    rf.thumb?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (sel) C.accent else if (done) C.ok else Color(0xFF4A443E)))
                    Txt(rf.file.nameWithoutExtension, 11, color = C.dim, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RecipeSide(app: AppState, modifier: Modifier) {
    val b = app.bake
    var query by remember { mutableStateOf("") }
    val list = app.repo.recipes.filter { query.isBlank() || it.name.contains(query, true) || it.filmSim.contains(query, true) }
    Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("搜尋食譜", query, { query = it })
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(list, key = { _, r -> r.id }) { _, r ->
                val sel = r.id == b.recipeId
                val n = app.repo.recipes.indexOf(r)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (sel) C.accentSoft else Color.Transparent)
                        .border(1.dp, if (sel) C.accent else Color.Transparent, RoundedCornerShape(10.dp))
                        .press { b.chooseRecipe(r.id) }.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Tables.simColor(Tables.normalizeFilm(r.filmSim))))
                    Column(Modifier.weight(1f)) {
                        Txt(r.name.ifEmpty { "未命名" }, 14, FontWeight.Medium, maxLines = 1)
                        Txt(r.filmSim, 12, color = C.dim, maxLines = 1)
                    }
                    if (n in 0..8) Txt("${n + 1}", 11, color = C.dim)
                }
            }
        }
        b.currentRecipe()?.let { r ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.card).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt(r.name.ifEmpty { "未命名" }, 15, FontWeight.Medium, maxLines = 1)
                RecipeFacts(r, 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (b.result == null) {
                        Btn(if (b.stage >= 0) "烘焙中…" else "烘焙這張", { b.bake() }, Modifier.weight(1f), primary = true,
                            enabled = b.stage < 0 && app.camera.state == CamState.Connected && b.current != null)
                    } else {
                        Btn("存成 JPG", { b.save() }, Modifier.weight(1f), primary = true)
                    }
                    Btn("做成拍立得", {
                        b.save { f -> if (f != null) { app.polaroid.addFiles(listOf(f)); app.screen = Screen.Polaroid } }
                    }, Modifier.weight(1f), enabled = b.result != null)
                }
            }
        }
    }
}

/** The recipe's settings as short lines, [cols] per row. */
@Composable
fun RecipeFacts(r: Recipe, cols: Int) {
    val facts = buildList {
        add(r.filmSim)
        add(if (r.dr == "自動") "DR 自動" else r.dr)
        add("顆粒 ${r.grain}")
        add("色彩 ${r.color}")
        add("高光 ${r.highlight}")
        add("陰影 ${r.shadow}")
        add("白平衡 ${if (r.wb == "色溫") r.kelvin + "K" else r.wb} R${r.shiftR} B${r.shiftB}")
        add("色彩效果 ${r.colorChrome}／藍 ${r.colorChromeBlue}")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        facts.chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { Txt(it, 12, color = C.sub, maxLines = 1, modifier = Modifier.weight(1f)) }
                repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BakeSteps(stage: Int, progress: Float) {
    val names = listOf("傳送", "送相機", "烘焙中", "出爐")
    val blink by rememberInfiniteTransition().animateFloat(0.35f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse))
    Row {
        names.forEachIndexed { i, n ->
            Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(12.dp).graphicsLayer { alpha = if (i == stage) blink else 1f }.clip(CircleShape).background(if (i <= stage) C.accent else Color(0xFF3A3530)))
                Spacer(Modifier.height(6.dp))
                val pct = if (i == stage && (stage == FujiBaker.STAGE_SEND || stage == FujiBaker.STAGE_OUT) && progress > 0f) " ${(progress * 100).toInt()}%" else ""
                Txt(n + pct, 12, if (i == stage) FontWeight.Medium else FontWeight.Normal, if (i <= stage) C.text else C.dim, maxLines = 1)
            }
        }
    }
}

private val OvenOrange = Color(0xFFF26B3A)
private val OvenDark = Color(0xFFE05A2B)
private val Cream = Color(0xFFFFF4E6)

/** The little oven with a roll of film inside (same drawing as on the phone). */
@Composable
fun OvenDrawing(baking: Boolean, modifier: Modifier) {
    val phase by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)))
    Canvas(modifier.clipToBounds()) {
        val w = size.width
        val h = size.height
        val u = w / 240f
        val top = 40f * u
        if (baking) for (k in 0 until 3) {
            val q = (phase * 1.2f + k / 3f) % 1f
            val x = (80f + 40f * k) * u
            val y = top - 4f * u - q * 26f * u
            val a = (if (q < 0.4f) q / 0.4f else (1f - q) / 0.6f).coerceIn(0f, 1f) * 0.8f
            val path = Path().apply {
                moveTo(x, y + 14f * u)
                quadraticTo(x - 6f * u, y + 7f * u, x, y)
                quadraticTo(x + 6f * u, y - 7f * u, x, y - 14f * u)
            }
            drawPath(path, Color(0xFFC9C2B8).copy(alpha = a), style = Stroke(3f * u, cap = StrokeCap.Round))
        }
        drawRoundRect(OvenOrange, Offset(0f, top), Size(w, h - top - 8f * u), CornerRadius(22f * u))
        drawRoundRect(OvenDark, Offset(0f, top), Size(w, 40f * u), CornerRadius(22f * u))
        drawRect(OvenDark, Offset(0f, top + 22f * u), Size(w, 18f * u))
        drawCircle(Color(0xFFFFE3B3), 5f * u, Offset(26f * u, top + 20f * u))
        drawCircle(Color(0xFFFFE3B3).copy(alpha = 0.6f), 5f * u, Offset(46f * u, top + 20f * u))
        val dial = Offset(206f * u, top + 30f * u)
        drawCircle(Cream, 16f * u, dial)
        rotate(if (baking) phase * 360f else 0f, dial) {
            drawLine(OvenDark, dial, Offset(dial.x, dial.y - 12f * u), 3f * u, StrokeCap.Round)
        }
        val wx = 18f * u
        val wy = top + 54f * u
        val ww = 164f * u
        val wh = h - wy - 20f * u
        drawRoundRect(Color(0xFF3A2A22), Offset(wx, wy), Size(ww, wh), CornerRadius(12f * u))
        val glow = if (baking) 0.55f + 0.35f * sin(phase * 2f * Math.PI.toFloat() * 2f) else 0.25f
        drawRoundRect(Color(0xFFFFB347).copy(alpha = glow), Offset(wx + 4f * u, wy + 4f * u), Size(ww - 8f * u, wh - 8f * u), CornerRadius(10f * u))
        val bob = if (baking) sin(phase * 2f * Math.PI.toFloat() * 2f) * 2f * u else 0f
        val fx = wx + ww / 2f - 36f * u
        val fy = wy + wh / 2f - 16f * u + bob
        drawRoundRect(Color(0xFF2B2B2B), Offset(fx, fy), Size(72f * u, 32f * u), CornerRadius(4f * u))
        drawRoundRect(Color(0xFFFF8A3D), Offset(fx + 6f * u, fy + 6f * u), Size(60f * u, 20f * u), CornerRadius(2f * u))
        for (i in 0 until 6) {
            drawRect(Cream, Offset(fx + (4f + i * 11.5f) * u, fy + 1.5f * u), Size(5f * u, 3f * u))
            drawRect(Cream, Offset(fx + (4f + i * 11.5f) * u, fy + 27.5f * u), Size(5f * u, 3f * u))
        }
        drawRoundRect(OvenDark, Offset(196f * u, wy + 6f * u), Size(20f * u, wh - 12f * u), CornerRadius(8f * u))
        drawRoundRect(Color(0xFFC4522A), Offset(18f * u, h - 10f * u), Size(22f * u, 10f * u), CornerRadius(3f * u))
        drawRoundRect(Color(0xFFC4522A), Offset(200f * u, h - 10f * u), Size(22f * u, 10f * u), CornerRadius(3f * u))
    }
}
