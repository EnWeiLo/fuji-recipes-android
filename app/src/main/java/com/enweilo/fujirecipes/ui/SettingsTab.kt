package com.enweilo.fujirecipes.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController

@Composable
fun SettingsTab(c: AppController) {
    val p = Pal
    val context = LocalContext.current
    val version = remember(context) {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" } catch (_: Exception) { "" }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) c.exportTo(uri)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) c.importFrom(uri)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 16.dp, bottom = 130.dp),
    ) {
        item { Txt("設定", 34, FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)) }

        item { SectionLabel("外觀") }
        item {
            Panel {
                Box(Modifier.padding(10.dp)) {
                    SegmentedControl(listOf("跟隨系統", "淺色", "深色"), c.themeMode) { c.setTheme(it) }
                }
                Divider()
                ToggleRow("背景緩慢流動", c.bgMotion) { c.changeBgMotion(it) }
            }
        }

        item { SectionLabel("更新") }
        item {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    ToggleRow("自動檢查更新", c.autoUpdate) { c.changeAutoUpdate(it) }
                    Divider()
                    ValueRow("狀態", c.updateNote.ifEmpty { "尚未檢查" })
                    val latest = c.latest
                    if (latest != null) {
                        SoftButton("下載並安裝 build ${latest.build}", { c.startUpdate() }, enabled = c.updateStatus.isEmpty())
                    }
                    SoftButton(if (c.checking) "檢查中…" else "立即檢查更新", { c.checkForUpdate(true) }, enabled = !c.checking && c.updateStatus.isEmpty())
                    Txt("只會連到 GitHub 查看最新版本，不會上傳任何資料。第一次更新需要允許「安裝未知應用程式」。", 12, color = p.sub)
                }
            }
        }

        item { SectionLabel("和電腦同步") }
        item { PcSyncPanel(c) }

        item { SectionLabel("資料") }
        item {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    SoftButton("匯出食譜（JSON）", { exporter.launch("fuji-recipes.json") })
                    SoftButton("匯入食譜（JSON）", { importer.launch(arrayOf("*/*")) })
                    Txt("格式和網頁版相同，可以直接匯入網頁版匯出的檔案。照片不含在匯出裡。", 12, color = p.sub)
                }
            }
        }

        item { SectionLabel("關於") }
        item {
            Panel {
                ValueRow("名稱", "富士食堂"); Divider()
                ValueRow("版本", version); Divider()
                ValueRow("資料位置", "只存在這支手機")
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun PcSyncPanel(c: com.enweilo.fujirecipes.AppController) {
    val p = Pal
    val sync = c.pcSync
    var pairing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.enweilo.fujirecipes.sync.FoundPc?>(null) }
    var code by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var manual by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            if (sync.paired) {
                ValueRow("電腦", sync.pcName)
                Divider()
                ValueRow("上次同步", if (sync.lastSync > 0) java.text.SimpleDateFormat("M/d HH:mm").format(java.util.Date(sync.lastSync)) else "還沒有")
                if (sync.status.isNotEmpty()) Txt(sync.status, 12, color = p.sub)
                Divider()
                ToggleRow("自動同步", sync.auto) { sync.changeAuto(it) }
                SoftButton(if (sync.busy) "同步中…" else "立即同步", { sync.syncNow(quiet = false) }, enabled = !sync.busy)
                SoftButton("取消配對", { sync.unpair() }, danger = true)
            } else {
                val target = pairing
                if (target == null) {
                    Txt("電腦版富士食堂開著、在同一個 Wi-Fi 下，就能互相同步食譜。", 13, color = p.sub)
                    SoftButton(if (sync.searching) "尋找中…" else "尋找電腦", { sync.search() }, enabled = !sync.searching)
                    sync.found.forEach { pc ->
                        SoftButton("${pc.name}（${pc.host}）", { pairing = pc; code = "" })
                    }
                    if (!sync.searching && sync.found.isEmpty()) {
                        Txt("找不到時，輸入電腦的 IP（電腦版「連線與同步」頁有寫）", 12, color = p.sub)
                        Field(manual, { manual = it }, "例如 192.168.1.20")
                        if (manual.isNotBlank()) SoftButton("用這個 IP", {
                            pairing = com.enweilo.fujirecipes.sync.FoundPc(manual.trim(), manual.trim(), com.enweilo.fujirecipes.data.RecipeSync.HTTP_PORT)
                            code = ""
                        })
                    }
                } else {
                    Txt("輸入 ${target.name} 上「連線與同步」頁顯示的 6 位數配對碼", 13, color = p.sub)
                    Field(code, { code = it.filter { ch -> ch.isDigit() }.take(6) }, "配對碼", number = true)
                    SoftButton(if (sync.busy) "配對中…" else "配對", { sync.pair(target, code) { ok -> if (ok) pairing = null } }, enabled = code.length == 6 && !sync.busy)
                    SoftButton("取消", { pairing = null })
                }
            }
        }
    }
}
