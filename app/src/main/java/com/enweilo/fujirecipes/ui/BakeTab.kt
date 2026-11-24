package com.enweilo.fujirecipes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.CamStatus
import com.enweilo.fujirecipes.Tab
import com.enweilo.fujirecipes.camera.FujiBaker
import com.enweilo.fujirecipes.data.Tables
import kotlin.math.sin

private val OvenOrange = Color(0xFFF26B3A)
private val OvenDark = Color(0xFFE05A2B)
private val Cream = Color(0xFFFFF4E6)

/** 烘焙: pick RAW files, pick a recipe, the camera bakes it. */
@Composable
fun BakeTab(c: AppController) {
    val p = Pal
    val b = c.bake
    val haptic = LocalHapticFeedback.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        b.addFiles(uris)
    }
    val connected = c.camStatus == CamStatus.Connected
    val recipes = c.recipes
    // the first recipe is chosen for you
    LaunchedEffect(recipes.firstOrNull()?.id) {
        if (b.recipeId == null || recipes.none { it.id == b.recipeId }) recipes.firstOrNull()?.let { b.chooseRecipe(it.id) }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 16.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(start = 6.dp)) {
                Txt("烘焙", 34, FontWeight.Bold)
                Txt("把 RAW 交給相機，用食譜烘出正宗的富士色", 15, color = p.sub)
            }
        }

        // ---- the camera ----
        item {
            Panel {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).clip(CircleShape).background(
                            when (c.camStatus) {
                                CamStatus.Connected -> Color(0xFF34C759)
                                CamStatus.Connecting -> Color(0xFFFFB020)
                                CamStatus.Disconnected -> p.sub.copy(alpha = 0.5f)
                            },
                        ),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Txt(
                            when (c.camStatus) {
                                CamStatus.Connected -> c.camModel.ifEmpty { "已連線" }
                                CamStatus.Connecting -> "連接中…"
                                CamStatus.Disconnected -> "相機未連接"
                            },
                            16, FontWeight.SemiBold,
                        )
                        if (!connected) Txt("USB 模式設成 RAW CONV./BACKUP RESTORE", 12, color = p.sub)
                    }
                    if (!connected) {
                        Box(
                            Modifier.clip(CircleShape).background(p.accent).tap(c.camStatus == CamStatus.Disconnected) { c.connectCamera() }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        ) { Txt("連接", 14, FontWeight.SemiBold, Color.White) }
                    }
                }
            }
        }

        // ---- the picture ----
        item {
            BakeStage(c, onAdd = { picker.launch(arrayOf("*/*")) })
        }

        // ---- RAW files ----
        if (b.items.isNotEmpty()) item {
            val list = rememberLazyListState()
            LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                itemsIndexed(b.items, key = { _, it -> it.uri.toString() }) { i, item ->
                    val sel = i == b.index
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(12.dp))
                            .border(if (sel) 2.5.dp else 0.dp, if (sel) p.accent else Color.Transparent, RoundedCornerShape(12.dp))
                            .background(p.field)
                            .pointerInput(i) {
                                detectTapGestures(
                                    onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); b.select(i) },
                                    onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); b.remove(i) },
                                )
                            },
                    ) {
                        item.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
                item {
                    Box(
                        Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)).background(p.field).tap { picker.launch(arrayOf("*/*")) },
                        contentAlignment = Alignment.Center,
                    ) { GlyphView(Glyph.Plus, p.sub, 22.dp) }
                }
            }
        }

        // ---- recipes ----
        if (b.items.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                itemsIndexed(recipes, key = { _, r -> r.id }) { _, r ->
                    val sel = r.id == b.recipeId
                    Row(
                        Modifier.height(40.dp).clip(CircleShape)
                            .background(if (sel) p.accent.copy(alpha = 0.16f) else p.card)
                            .border(if (sel) 1.5.dp else 0.7.dp, if (sel) p.accent else p.line, CircleShape)
                            .tap {
                                if (!sel) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                b.chooseRecipe(r.id)
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(Tables.simColor(Tables.normalizeFilm(r.filmSim))))
                        Spacer(Modifier.width(8.dp))
                        Txt(r.name.ifEmpty { "未命名" }, 14, if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }

        // ---- keeping it ----
        if (b.result != null) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("存到相簿", { b.save() }, Modifier.weight(1f))
                SoftButton("做成拍立得", {
                    b.save { uri ->
                        if (uri != null) {
                            c.polaroid.addPhotos(listOf(uri), false)
                            c.tab = Tab.Polaroid
                        }
                    }
                }, Modifier.weight(1f))
            }
        }

        if (b.items.isNotEmpty()) item {
            Txt(
                "按住照片可以看拍攝時的樣子。長按上面的縮圖可以移除。",
                12, color = p.sub, modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/** The big picture: the camera's bake, or 拍攝時 while there is none; the oven while it bakes. */
@Composable
private fun BakeStage(c: AppController, onAdd: () -> Unit) {
    val p = Pal
    val b = c.bake
    val haptic = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(b.served) {
        if (b.served > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            pop.snapTo(0.94f)
            pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 380f))
        }
    }
    val result = b.result
    val original = b.original
    val shown = if (holding) original ?: result else result ?: original
    val ratio = shown?.let { it.width.toFloat() / it.height } ?: 1.5f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(0.6f, 1.8f))
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .clip(RoundedCornerShape(22.dp))
            .background(if (p.dark) Color(0xFF1E1C1A) else Color(0xFFE9E4DC))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    holding = true
                    tryAwaitRelease()
                    holding = false
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (b.items.isEmpty()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OvenDrawing(baking = false, modifier = Modifier.size(150.dp, 118.dp))
                Box(
                    Modifier.clip(CircleShape).background(p.accent).tap(onClick = onAdd).padding(horizontal = 22.dp, vertical = 11.dp),
                ) { Txt("加入 RAW 檔（.RAF）", 15, FontWeight.SemiBold, Color.White) }
                Txt("必須是接著的這台相機拍的", 12, color = p.sub)
            }
            return@Box
        }
        if (shown != null) {
            Image(shown.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        // what we are looking at
        val label = when {
            shown == null -> null
            shown === result && !holding -> b.currentRecipe()?.name?.let { "$it · 相機烘焙" } ?: "相機烘焙"
            else -> "拍攝時"
        }
        if (label != null) {
            Box(
                Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape).background(Color(0x99000000))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) { Txt(label, 12, FontWeight.Medium, Color.White, maxLines = 1) }
        }
        val msg = b.modelMismatch() ?: b.error
            ?: if (result == null && b.stage < 0 && c.camStatus != CamStatus.Connected) "接上相機就能烘焙" else null
        if (msg != null && b.stage < 0) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Txt(msg, 13, FontWeight.Medium, Color.White, align = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        if (result == null && b.stage < 0 && b.error != null && c.camStatus == CamStatus.Connected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(10.dp).clip(CircleShape).background(p.accent).tap { b.bake() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Txt("再試一次", 12, FontWeight.SemiBold, Color.White) }
        }
        // the oven
        val k by animateFloatAsState(if (b.stage >= 0) 1f else 0f, tween(260), label = "oven")
        if (k > 0.01f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = k }.background(if (p.dark) Color(0xE6151312) else Color(0xEBF6F1EA)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OvenDrawing(baking = true, modifier = Modifier.size(150.dp, 118.dp))
                    BakeSteps(b.stage.coerceAtLeast(0), b.progress)
                }
            }
        }
    }
}

/** 傳送 → 送相機 → 烘焙中 → 出爐, with a percentage while bytes move. */
@Composable
private fun BakeSteps(stage: Int, progress: Float) {
    val p = Pal
    val names = listOf("傳送", "送相機", "烘焙中", "出爐")
    val blink = rememberInfiniteTransition(label = "steps").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "b",
    )
    Row(verticalAlignment = Alignment.Top) {
        names.forEachIndexed { i, n ->
            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(12.dp).graphicsLayer { alpha = if (i == stage) blink.value else 1f }.clip(CircleShape)
                        .background(if (i <= stage) OvenOrange else p.sub.copy(alpha = 0.25f)),
                )
                Spacer(Modifier.height(6.dp))
                val pct = if (i == stage && (stage == FujiBaker.STAGE_SEND || stage == FujiBaker.STAGE_OUT) && progress > 0f) " ${(progress * 100).toInt()}%" else ""
                Txt(n + pct, 12, if (i == stage) FontWeight.SemiBold else FontWeight.Normal, if (i <= stage) p.text else p.sub, maxLines = 1)
            }
        }
    }
}

/** A little orange oven with a roll of film inside; it glows, the dial turns and it steams while [baking]. */
@Composable
private fun OvenDrawing(baking: Boolean, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "oven")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val u = w / 240f
        val top = 40f * u
        // steam
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
        // body
        drawRoundRect(OvenOrange, Offset(0f, top), Size(w, h - top - 8f * u), CornerRadius(22f * u))
        drawRoundRect(OvenDark, Offset(0f, top), Size(w, 40f * u), CornerRadius(22f * u))
        drawRect(OvenDark, Offset(0f, top + 22f * u), Size(w, 18f * u))
        drawCircle(Color(0xFFFFE3B3), 5f * u, Offset(26f * u, top + 20f * u))
        drawCircle(Color(0xFFFFE3B3).copy(alpha = 0.6f), 5f * u, Offset(46f * u, top + 20f * u))
        // dial
        val dial = Offset(206f * u, top + 30f * u)
        drawCircle(Cream, 16f * u, dial)
        rotate(if (baking) phase * 360f else 0f, dial) {
            drawLine(OvenDark, dial, Offset(dial.x, dial.y - 12f * u), 3f * u, StrokeCap.Round)
        }
        // window
        val wx = 18f * u
        val wy = top + 54f * u
        val ww = 164f * u
        val wh = h - wy - 20f * u
        drawRoundRect(Color(0xFF3A2A22), Offset(wx, wy), Size(ww, wh), CornerRadius(12f * u))
        val glow = if (baking) 0.55f + 0.35f * sin(phase * 2f * Math.PI.toFloat() * 2f) else 0.25f
        drawRoundRect(Color(0xFFFFB347).copy(alpha = glow), Offset(wx + 4f * u, wy + 4f * u), Size(ww - 8f * u, wh - 8f * u), CornerRadius(10f * u))
        // film inside
        val bob = if (baking) sin(phase * 2f * Math.PI.toFloat() * 2f) * 2f * u else 0f
        val fx = wx + ww / 2f - 36f * u
        val fy = wy + wh / 2f - 16f * u + bob
        drawRoundRect(Color(0xFF2B2B2B), Offset(fx, fy), Size(72f * u, 32f * u), CornerRadius(4f * u))
        drawRoundRect(Color(0xFFFF8A3D), Offset(fx + 6f * u, fy + 6f * u), Size(60f * u, 20f * u), CornerRadius(2f * u))
        for (i in 0 until 6) {
            drawRect(Cream, Offset(fx + (4f + i * 11.5f) * u, fy + 1.5f * u), Size(5f * u, 3f * u))
            drawRect(Cream, Offset(fx + (4f + i * 11.5f) * u, fy + 27.5f * u), Size(5f * u, 3f * u))
        }
        // handle
        drawRoundRect(OvenDark, Offset(196f * u, wy + 6f * u), Size(20f * u, wh - 12f * u), CornerRadius(8f * u))
        // feet
        drawRoundRect(Color(0xFFC4522A), Offset(18f * u, h - 10f * u), Size(22f * u, 10f * u), CornerRadius(3f * u))
        drawRoundRect(Color(0xFFC4522A), Offset(200f * u, h - 10f * u), Size(22f * u, 10f * u), CornerRadius(3f * u))
    }
}
