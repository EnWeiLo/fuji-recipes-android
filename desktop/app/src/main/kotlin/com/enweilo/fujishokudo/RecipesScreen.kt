package com.enweilo.fujishokudo

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.camera.FujiOps
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import com.enweilo.fujirecipes.data.emptyRecipe
import com.enweilo.fujirecipes.data.newId
import com.enweilo.fujirecipes.data.recipeFromJson
import com.enweilo.fujirecipes.data.toJson
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private enum class Filter(val label: String) { All("全部"), Color("彩色"), Mono("黑白") }

@Composable
fun RecipesScreen(app: AppState) {
    val repo = app.repo
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(Filter.All) }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var writing by remember { mutableStateOf<Recipe?>(null) }
    var confirmDelete by remember { mutableStateOf<Recipe?>(null) }
    val list = repo.recipes.filter { r ->
        (query.isBlank() || r.name.contains(query, true) || r.filmSim.contains(query, true) || r.notes.contains(query, true)) &&
            when (filter) {
                Filter.All -> true
                Filter.Color -> Tables.normalizeFilm(r.filmSim) !in Tables.MONO
                Filter.Mono -> Tables.normalizeFilm(r.filmSim) in Tables.MONO
            }
    }
    val sel = repo.recipes.firstOrNull { it.id == app.selectedRecipe } ?: list.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Txt("食譜", 20, FontWeight.Bold)
                Txt("${repo.recipes.size} 個食譜", 13, color = C.dim, modifier = Modifier.weight(1f))
                Btn("匯入", { importRecipes(app) })
                Btn("匯出全部", { exportRecipes(app) })
                Btn("從 RAF 讀取設定", {
                    Pick.file("選一個 RAF", app.bake.folder, "raf")?.let { f ->
                        app.bake.recipeFromRaf(f) { r -> if (r != null) editing = r }
                    }
                })
                Btn("新食譜", { editing = emptyRecipe().copy(name = "") }, primary = true, icon = Ico.Plus)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(360.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field("", query, { query = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Filter.entries.forEach { f -> Chip(f.label, filter == f) { filter = f } }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(list, key = { it.id }) { r ->
                            val on = r.id == sel?.id
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) C.accentSoft else Color.Transparent)
                                    .border(1.dp, if (on) C.accent else Color.Transparent, RoundedCornerShape(12.dp))
                                    .press { app.selectedRecipe = r.id }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(Modifier.size(48.dp, 36.dp).clip(RoundedCornerShape(8.dp)).background(Tables.simColor(Tables.normalizeFilm(r.filmSim))))
                                Column(Modifier.weight(1f)) {
                                    Txt(r.name.ifEmpty { "未命名" }, 14, FontWeight.Medium, maxLines = 1)
                                    Txt(r.filmSim, 12, color = C.dim, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(C.line))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (sel == null) {
                        Txt("還沒有食譜，按右上角「新食譜」開始。", 15, color = C.sub)
                    } else {
                        RecipeDetail(app, sel, onEdit = { editing = sel }, onWrite = { writing = sel }, onDelete = { confirmDelete = sel })
                    }
                }
            }
        }
        editing?.let { r -> RecipeEditor(r, onCancel = { editing = null }, onSave = { saved -> repo.save(saved); app.selectedRecipe = saved.id; editing = null; app.showToast("已儲存") }) }
        writing?.let { r -> WriteDialog(app, r) { writing = null } }
        confirmDelete?.let { r ->
            Dialog("刪除「${r.name.ifEmpty { "未命名" }}」？", { confirmDelete = null }) {
                Txt("手機同步後也會一起刪掉。", 14, color = C.sub)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.weight(1f))
                    Btn("取消", { confirmDelete = null })
                    Btn("刪除", { repo.delete(r.id); confirmDelete = null; app.selectedRecipe = repo.recipes.firstOrNull()?.id }, danger = true)
                }
            }
        }
    }
}

@Composable
private fun RecipeDetail(app: AppState, r: Recipe, onEdit: () -> Unit, onWrite: () -> Unit, onDelete: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(240.dp, 160.dp).clip(RoundedCornerShape(14.dp)).background(Tables.simColor(Tables.normalizeFilm(r.filmSim))), contentAlignment = Alignment.BottomStart) {
            Txt(r.filmSim, 13, FontWeight.Medium, Color.White, modifier = Modifier.padding(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Txt(r.name.ifEmpty { "未命名" }, 28, FontWeight.Bold)
            if (r.notes.isNotBlank()) Txt(r.notes, 14, color = C.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("寫入相機…", onWrite, primary = true, enabled = app.camera.state == CamState.Connected)
                Btn("拿去烘焙", { app.bake.chooseRecipe(r.id); app.screen = Screen.Bake }, icon = Ico.Oven)
                Btn("編輯", onEdit)
                Btn("複製一份", {
                    val c = r.copy(id = newId(), name = r.name + " 複本", createdAt = System.currentTimeMillis())
                    app.repo.save(c); app.selectedRecipe = c.id
                }, icon = Ico.Copy)
                Btn("刪除", onDelete, danger = true, icon = Ico.Trash)
            }
            if (app.camera.state != CamState.Connected) Txt("寫入相機要先接上相機。", 12, color = C.dim)
        }
    }
    val groups = listOf(
        "影像" to listOf("底片模擬" to r.filmSim, "動態範圍" to r.dr, "顆粒" to r.grain, "色彩效果" to r.colorChrome, "藍色彩效果" to r.colorChromeBlue),
        "色調" to listOf("高光" to r.highlight, "陰影" to r.shadow, "色彩" to r.color, "銳利度" to r.sharpness, "清晰度" to r.clarity),
        "白平衡與其他" to listOf(
            "白平衡" to (if (r.wb == "色溫") "${r.kelvin}K" else r.wb), "偏移" to "R${r.shiftR} B${r.shiftB}",
            "高感光降噪" to r.nr, "曝光補償" to r.ev.ifEmpty { "—" }, "ISO" to r.iso,
        ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        groups.forEach { (title, rows) ->
            Panel(Modifier.weight(1f)) {
                Txt(title, 12, color = C.dim)
                rows.forEach { (k, v) ->
                    Row {
                        Txt(k, 14, color = C.sub, modifier = Modifier.weight(1f))
                        Txt(v, 14, FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** Full editor, every field the camera knows. */
@Composable
fun RecipeEditor(initial: Recipe, onCancel: () -> Unit, onSave: (Recipe) -> Unit) {
    var r by remember(initial.id) { mutableStateOf(initial) }
    Dialog(if (initial.name.isEmpty() && initial.notes.isEmpty()) "新食譜" else "編輯食譜", onCancel, width = 760) {
        Field("名稱", r.name, { r = r.copy(name = it) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Select("底片模擬", r.filmSim, Tables.FILM.map { it.label }, { r = r.copy(filmSim = it) }, Modifier.weight(1f))
            Select("動態範圍", r.dr, Tables.DR.map { it.label }, { r = r.copy(dr = it) }, Modifier.weight(1f))
            Select("顆粒", r.grain, Tables.GRAIN.map { it.label }, { r = r.copy(grain = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Select("色彩效果", r.colorChrome, Tables.FX.map { it.label }, { r = r.copy(colorChrome = it) }, Modifier.weight(1f))
            Select("藍色彩效果", r.colorChromeBlue, Tables.FX.map { it.label }, { r = r.copy(colorChromeBlue = it) }, Modifier.weight(1f))
            Select("白平衡", r.wb, Tables.WB.map { it.label }, { r = r.copy(wb = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (r.wb == "色溫") Field("色溫（K）", r.kelvin, { r = r.copy(kelvin = it.filter { c -> c.isDigit() }.take(5)) }, Modifier.weight(1f))
            Select("偏移 R", r.shiftR, Tables.SHIFT, { r = r.copy(shiftR = it) }, Modifier.weight(1f))
            Select("偏移 B", r.shiftB, Tables.SHIFT, { r = r.copy(shiftB = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Select("高光", r.highlight, Tables.TONE, { r = r.copy(highlight = it) }, Modifier.weight(1f))
            Select("陰影", r.shadow, Tables.TONE, { r = r.copy(shadow = it) }, Modifier.weight(1f))
            Select("色彩", r.color, Tables.PM4, { r = r.copy(color = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Select("銳利度", r.sharpness, Tables.PM4, { r = r.copy(sharpness = it) }, Modifier.weight(1f))
            Select("高感光降噪", r.nr, Tables.PM4, { r = r.copy(nr = it) }, Modifier.weight(1f))
            Select("清晰度", r.clarity, Tables.CLARITY, { r = r.copy(clarity = it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("曝光補償（拍攝參考）", r.ev, { r = r.copy(ev = it) }, Modifier.weight(1f))
            Field("ISO（拍攝參考）", r.iso, { r = r.copy(iso = it) }, Modifier.weight(1f))
        }
        Field("筆記", r.notes, { r = r.copy(notes = it) }, singleLine = false, minHeight = 70.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            Btn("取消", onCancel)
            Btn("儲存", { onSave(r) }, primary = true)
        }
    }
}

@Composable
private fun WriteDialog(app: AppState, r: Recipe, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var slot by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    Dialog("把「${r.name.ifEmpty { "未命名" }}」寫進相機", onClose) {
        Txt("選一個自訂槽位，原本的設定會被蓋掉。", 14, color = C.sub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in 1..7) Chip("C$s", slot == s) { slot = s }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.weight(1f))
            Btn("取消", onClose)
            Btn(if (busy) "寫入中…" else "寫入 C$slot", {
                busy = true
                scope.launch {
                    val ok = runCatching { app.camera.use { cam -> FujiOps(cam) { app.camera.log(it) }.writeRecipe(r, slot) } }
                    busy = false
                    app.showToast(if (ok.isSuccess) "已寫入 C$slot" else "寫入失敗：${ok.exceptionOrNull()?.message}")
                    if (ok.isSuccess) onClose()
                }
            }, primary = true, enabled = !busy)
        }
    }
}

private fun importRecipes(app: AppState) {
    val f = Pick.file("匯入食譜（手機或網頁版匯出的 JSON）", null, "json") ?: return
    runCatching {
        val text = f.readText()
        val arr = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("recipes") ?: JSONArray()
        val list = (0 until arr.length()).map { recipeFromJson(arr.getJSONObject(it)) }
        app.repo.importAll(list)
        app.showToast("匯入 ${list.size} 個食譜")
    }.onFailure { app.showToast("匯入失敗：${it.message}") }
}

private fun exportRecipes(app: AppState) {
    val f = Pick.save("匯出食譜", "富士食堂食譜.json", "json") ?: return
    runCatching {
        f.writeText(JSONArray().apply { app.repo.recipes.forEach { put(it.toJson(includePhotos = false)) } }.toString(2))
        app.showToast("已匯出 ${app.repo.recipes.size} 個食譜")
    }.onFailure { app.showToast("匯出失敗：${it.message}") }
}

/** A centred card over a dimmed window. */
@Composable
fun Dialog(title: String, onDismiss: () -> Unit, width: Int = 520, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).press(onClick = {}), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = width.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(16.dp)).background(C.side)
                .border(1.dp, C.line, RoundedCornerShape(16.dp)).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(title, 18, FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(Modifier.size(30.dp).clip(CircleShape).press(onClick = onDismiss), contentAlignment = Alignment.Center) { IconView(Ico.Close, C.sub, 16.dp) }
            }
            content()
        }
    }
}
