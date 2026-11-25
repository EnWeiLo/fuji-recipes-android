package com.enweilo.fujishokudo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(app: AppState) {
    var outDir by remember { mutableStateOf(app.settings.str("outputDir")) }
    var cacheMb by remember { mutableStateOf(0L) }
    var checking by remember { mutableStateOf(false) }
    var latest by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        cacheMb = withContext(Dispatchers.IO) { (Paths.cache.listFiles()?.sumOf { it.length() } ?: 0L) / 1048576 }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Txt("設定", 20, FontWeight.Bold)
        Panel(Modifier.fillMaxWidth()) {
            Txt("烘焙輸出", 16, FontWeight.Bold)
            Txt(
                if (outDir.isEmpty()) "存在 RAF 所在資料夾裡的「富士食堂」子資料夾" else "固定存到：$outDir",
                14, color = C.sub,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("改成固定資料夾…", {
                    Pick.folder(null)?.let { outDir = it.absolutePath; app.settings.put("outputDir", outDir) }
                })
                if (outDir.isNotEmpty()) Btn("改回 RAF 旁邊", { outDir = ""; app.settings.put("outputDir", "") })
                app.bake.outputDir()?.let { d -> Btn("打開輸出資料夾", { app.openFolder(d) }, icon = Ico.Folder) }
            }
            Txt("檔名：原本的檔名_食譜名稱.jpg，例如 DSCF6914_暖陽街拍.jpg", 12, color = C.dim)
        }
        Panel(Modifier.fillMaxWidth()) {
            Txt("暫存", 16, FontWeight.Bold)
            Txt("烘過的照片會先暫存，換回同一個食譜時不用再烘一次。目前 $cacheMb MB（上限 3 GB，滿了自動清最舊的）。", 14, color = C.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn("清空暫存", {
                    Paths.cache.listFiles()?.forEach { it.delete() }
                    cacheMb = 0
                    app.showToast("已清空")
                })
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Txt("相機驅動", 16, FontWeight.Bold)
            Txt("電腦要直接跟相機溝通，相機在 USB RAW CONV. 模式下的驅動要用 Zadig 換成 WinUSB（只要做一次）。步驟在「連線與同步」頁。", 14, color = C.sub)
            Row { Btn("前往連線與同步", { app.screen = Screen.Connect }) }
        }
        Panel(Modifier.fillMaxWidth()) {
            Txt("版本", 16, FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(if (Update.build > 0) "富士食堂 電腦版 build ${Update.build}" else "富士食堂 電腦版（自行編譯）", 14, color = C.sub, modifier = Modifier.weight(1f))
                Btn(if (checking) "檢查中…" else "檢查更新", {
                    checking = true
                    app.scope.launchIo {
                        val l = Update.latest()
                        withContext(Dispatchers.Main) {
                            latest = l
                            checking = false
                            app.showToast(
                                when {
                                    l == null -> "連不到 GitHub"
                                    l > Update.build -> "有新版本 build $l"
                                    else -> "已經是最新版"
                                },
                            )
                        }
                    }
                }, enabled = !checking)
                if ((latest ?: 0) > Update.build) Btn("下載新版", { app.openUrl(Update.PAGE) }, primary = true)
            }
            Txt("資料存在 ${Paths.home.absolutePath}", 12, color = C.dim)
        }
        Panel(Modifier.fillMaxWidth()) {
            Txt("致謝", 16, FontWeight.Bold)
            Txt("相機 RAW 轉檔的通訊方式參考 FilmKit（github.com/eggricesoy/filmkit，MIT 授權），以及 rawji、fudge、libgphoto2 的研究。USB 使用 libusb（LGPL-2.1）。", 13, color = C.sub)
        }
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchIo(block: suspend () -> Unit) {
    launch(Dispatchers.IO) { block() }
}
