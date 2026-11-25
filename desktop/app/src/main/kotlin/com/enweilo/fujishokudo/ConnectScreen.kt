package com.enweilo.fujishokudo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.enweilo.fujirecipes.camera.SlotInfo
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ConnectScreen(app: AppState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Txt("連線與同步", 20, FontWeight.Bold)
        CameraPanel(app)
        SyncPanel(app)
        LogPanel(app)
    }
}

@Composable
private fun CameraPanel(app: AppState) {
    val cam = app.camera
    val scope = rememberCoroutineScope()
    var slots by remember { mutableStateOf<List<SlotInfo>>(emptyList()) }
    var pending by remember { mutableStateOf<Map<Int, Recipe>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(C.card2), contentAlignment = Alignment.Center) {
                IconView(Ico.Camera, C.accentText, 28.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (cam.state) {
                    CamState.Connected -> {
                        Txt("FUJIFILM ${cam.model}", 18, FontWeight.Bold)
                        Txt("韌體 ${cam.firmware} · 直接連線（WinUSB）", 13, color = C.dim)
                    }
                    CamState.NeedsDriver -> {
                        Txt("找到富士相機，但還不能直接溝通", 18, FontWeight.Bold)
                        Txt("USB ID 04CB ${cam.detail}：這個模式的驅動還是 Windows 原本的，照下面步驟換一次就好。", 13, color = C.warn)
                    }
                    CamState.Busy -> {
                        Txt("相機被別的程式佔用", 18, FontWeight.Bold)
                        Txt("關掉 X RAW STUDIO 或其他用到相機的程式後，拔掉重插。（${cam.detail}）", 13, color = C.warn)
                    }
                    CamState.NoLibrary -> {
                        Txt("缺少 USB 元件", 18, FontWeight.Bold)
                        Txt("找不到 libusb-1.0.dll，請重新安裝富士食堂。", 13, color = C.warn)
                    }
                    CamState.Searching -> {
                        Txt("等待相機", 18, FontWeight.Bold)
                        Txt("相機的連線模式設成 USB RAW CONV./BACKUP RESTORE，用 USB 線接上電腦。", 13, color = C.dim)
                    }
                }
            }
            if (cam.state == CamState.Connected) {
                Btn(if (busy) "讀取中…" else "從相機讀回 C1–C7", {
                    busy = true
                    scope.launch {
                        runCatching { cam.use { c -> FujiOps(c) { cam.log(it) }.readSlots() } }
                            .onSuccess { slots = it; pending = emptyMap() }
                            .onFailure { app.showToast("讀取失敗：${it.message}") }
                        busy = false
                    }
                }, enabled = !busy)
                Btn("把變更寫入相機（${pending.size}）", {
                    busy = true
                    scope.launch {
                        val todo = pending
                        val res = runCatching {
                            cam.use { c ->
                                val ops = FujiOps(c) { cam.log(it) }
                                todo.forEach { (slot, r) -> ops.writeRecipe(r, slot) }
                                ops.readSlots()
                            }
                        }
                        res.onSuccess { slots = it; pending = emptyMap(); app.showToast("已寫入 ${todo.size} 個槽位") }
                            .onFailure { app.showToast("寫入失敗：${it.message}") }
                        busy = false
                    }
                }, primary = true, enabled = pending.isNotEmpty() && !busy)
            }
        }
        if (cam.state == CamState.NeedsDriver || cam.state == CamState.Searching) DriverGuide(app, cam.state == CamState.NeedsDriver)
        if (cam.state == CamState.Connected) {
            Txt(if (slots.isEmpty()) "按「從相機讀回 C1–C7」看看相機裡現在放了什麼，再點格子換成你的食譜。" else "點格子選食譜，選好後按「把變更寫入相機」。", 12, color = C.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in 1..7) {
                    SlotCell(app, s, slots.firstOrNull { it.slot == s }, pending[s], Modifier.weight(1f)) { r ->
                        pending = if (r == null) pending - s else pending + (s to r)
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCell(app: AppState, slot: Int, info: SlotInfo?, chosen: Recipe?, modifier: Modifier, onPick: (Recipe?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).clip(RoundedCornerShape(12.dp)).background(if (chosen != null) C.accentSoft else C.card)
                .border(1.dp, if (chosen != null) C.accent else C.line, RoundedCornerShape(12.dp)).press { open = true }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Txt("C$slot", 12, FontWeight.Medium, C.accentText)
            val film = chosen?.filmSim ?: info?.filmSim
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(film?.let { Tables.simColor(Tables.normalizeFilm(it)) } ?: C.line))
            Txt(chosen?.name ?: info?.name ?: "—", 14, FontWeight.Medium, maxLines = 2)
            Txt(if (chosen != null) "待寫入" else film ?: "未讀取", 12, color = C.dim, maxLines = 1)
        }
        DropdownMenu(open, { open = false }, Modifier.background(C.card2)) {
            if (chosen != null) DropdownMenuItem(text = { Txt("不要換（保留相機原本的）", 14, color = C.sub) }, onClick = { onPick(null); open = false })
            app.repo.recipes.forEach { r ->
                DropdownMenuItem(text = { Txt(r.name.ifEmpty { "未命名" } + " · " + r.filmSim, 14) }, onClick = { onPick(r); open = false })
            }
        }
    }
}

@Composable
private fun DriverGuide(app: AppState, needed: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(if (needed) "換驅動（只要做一次）" else "第一次使用：先換驅動", 15, FontWeight.Medium)
        val steps = listOf(
            "相機設成 USB RAW CONV./BACKUP RESTORE，接上電腦。",
            "下載並執行 Zadig（免安裝）。",
            "選單 Options → 勾 List All Devices。",
            "下拉選單選 X 系列相機，確認 USB ID 是 04CB 開頭（例如 04CB 0306）。",
            "目標驅動選 WinUSB，按 Replace Driver，等它跑完。",
            "拔掉相機重插，這裡就會顯示已連接。",
        )
        steps.forEachIndexed { i, s -> Txt("${i + 1}. $s", 13, color = C.sub) }
        Txt("只影響這個 USB 模式。想換回原本的：裝置管理員 → 找到相機 → 解除安裝裝置（勾選刪除驅動程式）。", 12, color = C.dim)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("打開 Zadig 網站", { app.openUrl("https://zadig.akeo.ie") }, primary = true)
        }
    }
}

@Composable
private fun SyncPanel(app: AppState) {
    val s = app.sync
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt("和手機同步（區網）", 16, FontWeight.Bold)
            Txt(
                when {
                    !s.enabled -> "已關閉"
                    s.running -> "等待手機中 · 同一個 Wi-Fi 下會自動找到這台電腦（${s.pcName}）"
                    else -> s.problem ?: "啟動中…"
                },
                13, color = if (s.problem != null) C.warn else C.dim, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Chip(if (s.enabled) "同步：開" else "同步：關", s.enabled) { s.switchEnabled(!s.enabled) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(C.card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Txt("加入新手機", 14, FontWeight.Medium)
                Txt("手機：設定 → 和電腦同步 → 選這台電腦，輸入配對碼", 13, color = C.sub)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Txt(s.code.chunked(3).joinToString(" "), 30, FontWeight.Bold, C.accentText)
                    Txt("換一組", 13, color = C.dim, modifier = Modifier.press { s.regenerateCode() })
                }
                val addr = s.addresses()
                if (addr.isNotEmpty()) Txt("找不到電腦時，手動輸入：${addr.joinToString("、")}", 12, color = C.dim)
                Txt("第一次啟動時 Windows 會問要不要允許網路存取，請按允許（私人網路）。", 12, color = C.dim)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.devices.isEmpty()) {
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp)).padding(16.dp)) {
                        Txt("還沒有配對的手機", 13, color = C.dim)
                    }
                }
                val fmt = SimpleDateFormat("M/d HH:mm")
                s.devices.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.card).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        IconView(Ico.Phone, C.sub, 24.dp)
                        Column(Modifier.weight(1f)) {
                            Txt(d.name, 14, FontWeight.Medium)
                            Txt(if (d.lastSync > 0) "上次同步 ${fmt.format(Date(d.lastSync))} · ${d.count} 個食譜" else "已配對", 12, color = C.okText)
                        }
                        Txt("移除", 13, color = C.dim, modifier = Modifier.press { s.forget(d.token) })
                    }
                }
            }
        }
        Txt("同一個食譜兩邊都改過時，保留較新的那一份；刪除也會同步。", 12, color = C.dim)
    }
}

@Composable
private fun LogPanel(app: AppState) {
    if (app.camera.logLines.isEmpty()) return
    Panel(Modifier.fillMaxWidth()) {
        Txt("紀錄", 13, color = C.dim)
        app.camera.logLines.takeLast(12).forEach { Txt(it, 12, color = C.sub) }
    }
}

