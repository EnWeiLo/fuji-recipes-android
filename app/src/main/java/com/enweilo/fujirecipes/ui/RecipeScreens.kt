package com.enweilo.fujirecipes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.Tab
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.data.toneFraction
import com.kyant.backdrop.Backdrop

// ---------------- main (three tabs) ----------------

@Composable
fun MainScreen(c: AppController, listState: LazyListState) {
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    var askWrite by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = sheet != null || askWrite) { sheet = null; askWrite = false }
    GlassScreen(
        bars = { backdrop ->
            AnimatedVisibility(
                c.tab == Tab.Recipes,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 96.dp),
                enter = fadeIn(tween(220, delayMillis = 80)) + scaleIn(tween(220, delayMillis = 80), initialScale = 0.85f),
                exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.85f),
            ) {
                GlassCircleButton(Glyph.Plus, backdrop, onClick = { c.startNew() }, size = 56.dp, accent = true)
            }
            AnimatedVisibility(
                c.tab == Tab.Polaroid,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(220, delayMillis = 80)),
                exit = fadeOut(tween(120)),
            ) { PolaroidActions(c, backdrop) }
            if (c.updateStatus.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = topInset() + 12.dp)
                        .clip(CircleShape)
                        .background(Color(0xE61C1C1E))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Txt(c.updateStatus, 15, FontWeight.Medium, Color.White) }
            }
            ConfirmDialog(
                c.updatePrompt, "有新版本可以更新",
                "目前 build ${c.installedBuild}，最新 build ${c.latest?.build ?: ""}" +
                    (c.latest?.size?.takeIf { it > 0 }?.let { "（約 ${it / 1_000_000} MB）" } ?: "") +
                    "。更新後會保留你的食譜。",
                "更新",
                onConfirm = { c.startUpdate() }, onDismiss = { c.dismissUpdatePrompt() },
            )
            TabBar(c, backdrop, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp))
            PickerSheet(sheet, backdrop) { sheet = null }
            val target = c.recipes.firstOrNull { it.id == c.camRecipeId }
            ConfirmDialog(
                askWrite, "寫入 C${c.camSlot}？", "相機 C${c.camSlot} 目前的設定會被「${target?.name ?: ""}」覆蓋。", "寫入",
                onConfirm = { c.writeToCamera() }, onDismiss = { askWrite = false },
            )
        },
        content = {
            AnimatedContent(
                targetState = c.tab,
                transitionSpec = {
                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (fadeIn(tween(240, delayMillis = 70)) + slideInHorizontally(tween(300)) { dir * it / 10 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally(tween(300)) { -dir * it / 10 })
                },
                label = "tab",
            ) { tab ->
                when (tab) {
                    Tab.Recipes -> RecipeListTab(c, listState)
                    Tab.Camera -> CameraTab(c, onPickRecipe = { sheet = it }, onAskWrite = { askWrite = true })
                    Tab.Polaroid -> PolaroidTab(c, onSheet = { sheet = it })
                    Tab.Settings -> SettingsTab(c)
                }
            }
        },
    )
}

@Composable
private fun TabBar(c: AppController, backdrop: Backdrop, modifier: Modifier) {
    val p = Pal
    Row(
        modifier.fillMaxWidth().height(66.dp).glass(backdrop).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val items = listOf(
            Triple(Tab.Recipes, Glyph.Recipes, "食譜"),
            Triple(Tab.Camera, Glyph.Camera, "相機"),
            Triple(Tab.Polaroid, Glyph.Polaroid, "拍立得"),
            Triple(Tab.Settings, Glyph.Gear, "設定"),
        )
        for ((tab, glyph, label) in items) {
            val on = c.tab == tab
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(animateColorAsState(if (on) p.accent.copy(alpha = 0.16f) else Color.Transparent, tween(250), label = "bg").value)
                    .tap { c.tab = tab },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val tint by animateColorAsState(if (on) p.accent else p.sub, tween(250), label = "tint")
                GlyphView(glyph, tint, 22.dp)
                Txt(label, 11, FontWeight.Medium, tint, Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ---------------- recipe list ----------------

@Composable
private fun RecipeListTab(c: AppController, listState: LazyListState) {
    val p = Pal
    val q = c.query.trim().lowercase()
    val shownRecipes = if (q.isEmpty()) c.recipes else c.recipes.filter {
        it.name.lowercase().contains(q) || it.filmSim.lowercase().contains(q) || it.notes.lowercase().contains(q)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 16.dp, bottom = 190.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(start = 6.dp, bottom = 6.dp)) {
                Txt("食譜", 34, FontWeight.Bold)
                Txt("${c.recipes.size} 道", 15, color = p.sub)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphView(Glyph.Search, p.sub, 18.dp)
                Box(Modifier.width(8.dp))
                Field(c.query, { c.query = it }, "搜尋名稱或底片模擬", Modifier.weight(1f).background(Color.Transparent), minHeight = 44.dp)
            }
        }
        if (shownRecipes.isEmpty()) {
            item {
                Txt(
                    if (c.recipes.isEmpty()) "還沒有食譜，按右下角的 + 新增，或到「設定」匯入。" else "找不到符合的食譜",
                    15, color = p.sub, modifier = Modifier.padding(top = 40.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
        items(shownRecipes, key = { it.id }) { recipe -> RecipeCard(c, recipe) }
    }
}

@Composable
private fun RecipeCard(c: AppController, r: Recipe) {
    val p = Pal
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .tap { c.openDetail(r.id) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(c, r, 58)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Txt(r.name.ifEmpty { "未命名" }, 17, FontWeight.SemiBold, maxLines = 1)
            Txt(r.filmSim, 14, color = p.sub, maxLines = 1)
            Txt("${r.dr} · ${r.grain} · ${r.wb}", 12, color = p.sub.copy(alpha = 0.8f), maxLines = 1)
        }
        GlyphView(Glyph.Chevron, p.sub, 16.dp)
    }
}

@Composable
private fun Thumb(c: AppController, r: Recipe, size: Int) {
    val shape = RoundedCornerShape((size * 0.26f).dp)
    val first = r.photos.firstOrNull()
    if (first != null) {
        PhotoImage(c, first, 240, Modifier.size(size.dp).clip(shape))
    } else {
        val base = Tables.simColor(r.filmSim)
        Box(
            Modifier.size(size.dp).clip(shape).background(Brush.linearGradient(listOf(base.copy(alpha = 0.55f), base))),
            contentAlignment = Alignment.Center,
        ) {
            Txt(r.filmSim.firstOrNull()?.uppercase() ?: "F", 22, FontWeight.Bold, Color.White)
        }
    }
}

// ---------------- detail ----------------

@Composable
fun DetailScreen(c: AppController, id: String) {
    val r = c.recipes.firstOrNull { it.id == id } ?: return
    val p = Pal
    var askDelete by remember { mutableStateOf(false) }

    GlassScreen(
        bars = { backdrop ->
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = topInset() + 8.dp, start = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircleButton(Glyph.Back, backdrop, { c.back() })
                Box(Modifier.weight(1f))
                GlassCircleButton(Glyph.Trash, backdrop, { askDelete = true }, danger = true)
                GlassCircleButton(Glyph.Edit, backdrop, { c.startEdit(r.id) })
            }
            BigAction(
                "傳送到相機", backdrop,
                onClick = { c.camRecipeId = r.id; c.stack.clear(); c.tab = Tab.Camera },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
            )
            ConfirmDialog(askDelete, "刪除「${r.name}」？", "這道食譜和它的照片會從手機移除，無法復原。", "刪除", danger = true,
                onConfirm = { c.deleteRecipe(r.id) }, onDismiss = { askDelete = false })
        },
        content = {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 70.dp, bottom = 110.dp),
            ) {
                item {
                    Column(Modifier.padding(horizontal = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Tables.simColor(r.filmSim)))
                            Txt(r.filmSim, 14, FontWeight.Medium, p.sub, Modifier.padding(start = 8.dp))
                        }
                        Txt(r.name.ifEmpty { "未命名" }, 30, FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        if (r.notes.isNotBlank()) Txt(r.notes, 15, color = p.sub, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (r.photos.isNotEmpty()) {
                    item {
                        LazyRow(
                            Modifier.padding(top = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(r.photos) { photo ->
                                PhotoImage(c, photo, 900, Modifier.height(230.dp).width(190.dp).clip(RoundedCornerShape(20.dp)))
                            }
                        }
                    }
                }
                item { SectionLabel("底片模擬") }
                item {
                    Panel {
                        ValueRow("底片模擬", r.filmSim); Divider()
                        ValueRow("動態範圍", r.dr); Divider()
                        ValueRow("顆粒效果", r.grain); Divider()
                        ValueRow("色彩效果", r.colorChrome); Divider()
                        ValueRow("藍色色彩效果", r.colorChromeBlue)
                    }
                }
                item { SectionLabel("白平衡") }
                item {
                    Panel {
                        ValueRow("白平衡", if (r.wb == "色溫") "色溫 ${r.kelvin}K" else r.wb); Divider()
                        ValueRow("R 偏移", r.shiftR); Divider()
                        ValueRow("B 偏移", r.shiftB)
                    }
                }
                item { SectionLabel("影像調整") }
                item {
                    Panel {
                        ValueRow("亮部色調", r.highlight, toneFraction(r.highlight)); Divider()
                        ValueRow("陰影色調", r.shadow, toneFraction(r.shadow)); Divider()
                        ValueRow("色彩", r.color, toneFraction(r.color)); Divider()
                        ValueRow("銳利度", r.sharpness, toneFraction(r.sharpness)); Divider()
                        ValueRow("清晰度", r.clarity, toneFraction(r.clarity)); Divider()
                        ValueRow("高 ISO 降噪", r.nr, toneFraction(r.nr))
                    }
                }
                if (r.iso.isNotBlank() || r.ev.isNotBlank()) {
                    item { SectionLabel("拍攝建議") }
                    item {
                        Panel {
                            if (r.iso.isNotBlank()) ValueRow("ISO", r.iso)
                            if (r.iso.isNotBlank() && r.ev.isNotBlank()) Divider()
                            if (r.ev.isNotBlank()) ValueRow("曝光補償", r.ev)
                        }
                    }
                }
            }
        },
    )
}
