package com.enweilo.fujirecipes.ui

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
                Row(Modifier.fillMaxWidth().padding(8.dp).clip(RoundedCornerShape(16.dp)).background(p.field).padding(3.dp)) {
                    listOf("跟隨系統", "淺色", "深色").forEachIndexed { i, label ->
                        val on = c.themeMode == i
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(if (on) p.accent else Color.Transparent)
                                .tap { c.setTheme(i) },
                            contentAlignment = Alignment.Center,
                        ) { Txt(label, 15, FontWeight.Medium, if (on) Color.White else p.text) }
                    }
                }
            }
        }

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
                ValueRow("版本", version); Divider()
                ValueRow("資料位置", "只存在這支手機")
            }
        }
    }
}
