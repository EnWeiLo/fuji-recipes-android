package com.enweilo.fujirecipes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.CamStatus

/** Camera tab. Sheets and dialogs are shown by MainScreen (they must sit outside the blurred layer). */
@Composable
fun CameraTab(c: AppController, onPickRecipe: (SheetSpec) -> Unit, onAskWrite: () -> Unit) {
    val p = Pal
    var showLog by remember { mutableStateOf(false) }
    val recipe = c.recipes.firstOrNull { it.id == c.camRecipeId }
    val connected = c.camStatus == CamStatus.Connected

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 16.dp, bottom = 130.dp),
    ) {
        item {
            Column(Modifier.padding(start = 6.dp, bottom = 10.dp)) {
                Txt("相機", 34, FontWeight.Bold)
                Txt("用 OTG 線接上富士相機，寫入或讀取 C1–C7", 15, color = p.sub)
            }
        }

        // ---- connection ----
        item {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
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
                                CamStatus.Disconnected -> "未連接"
                            },
                            17, FontWeight.SemiBold,
                        )
                        if (!connected) Txt("相機 USB 模式請選「USB RAW CONV./BACKUP RESTORE」或 Tether", 12, color = p.sub, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (connected) SoftButton("中斷連線", { c.disconnectCamera() }, enabled = !c.camBusy)
                    else SoftButton(if (c.camStatus == CamStatus.Connecting) "連接中…" else "連接相機", { c.connectCamera() }, enabled = c.camStatus == CamStatus.Disconnected)
                }
            }
        }

        // ---- write / read ----
        item { SectionLabel("寫入相機") }
        item {
            Panel {
                PickRow("食譜", recipe?.name ?: "選擇…") {
                    onPickRecipe(SheetSpec("選擇食譜", c.recipes.map { it.name.ifEmpty { "未命名" } }, c.recipes.indexOfFirst { it.id == c.camRecipeId }.coerceAtLeast(0)) { i ->
                        c.camRecipeId = c.recipes[i].id
                    })
                }
                Divider()
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt("目標槽位", 14, color = p.sub)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (slot in 1..7) {
                            val on = c.camSlot == slot
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(42.dp)
                                    .clip(CircleShape)
                                    .background(if (on) p.accent else p.field)
                                    .tap { c.camSlot = slot },
                                contentAlignment = Alignment.Center,
                            ) { Txt("C$slot", 14, FontWeight.SemiBold, if (on) Color.White else p.text) }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(
                    if (c.camBusy) "處理中…" else "寫入 C${c.camSlot}",
                    onAskWrite,
                    enabled = connected && !c.camBusy && recipe != null,
                )
                SoftButton("把相機 C${c.camSlot} 讀成新食譜", { c.readSlotToRecipe() }, enabled = connected && !c.camBusy)
                SoftButton("查看 C1–C7 目前內容", { c.listSlots() }, enabled = connected && !c.camBusy)
            }
        }

        if (c.slotInfos.isNotEmpty()) {
            item { SectionLabel("相機目前的 C1–C7") }
            item {
                Panel {
                    c.slotInfos.forEachIndexed { i, s ->
                        if (i > 0) Divider()
                        ValueRow("C${s.slot}  ${s.name}", s.filmSim)
                    }
                }
            }
        }

        item { SectionLabel("連線紀錄") }
        item {
            Panel {
                Row(Modifier.fillMaxWidth().tap { showLog = !showLog }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(if (showLog) "收起" else "展開（${c.camLog.size} 行）", 15, modifier = Modifier.weight(1f))
                    if (showLog && c.camLog.isNotEmpty()) Txt("清除", 15, color = p.accent, modifier = Modifier.tap { c.clearLog() })
                }
                if (showLog) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (c.camLog.isEmpty()) Txt("（還沒有紀錄）", 13, color = p.sub)
                        c.camLog.forEach { Txt(it, 12, color = p.sub) }
                    }
                }
            }
        }
    }
}
