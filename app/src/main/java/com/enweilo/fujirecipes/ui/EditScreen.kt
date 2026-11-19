package com.enweilo.fujirecipes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.data.Recipe
import com.enweilo.fujirecipes.data.Tables

@Composable
fun EditScreen(c: AppController) {
    val r = c.draft
    val p = Pal
    var sheet by remember { mutableStateOf<SheetSpec?>(null) }
    BackHandler(enabled = sheet != null) { sheet = null }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(6)) { uris ->
        if (uris.isNotEmpty()) c.addPhotos(uris)
    }

    fun choose(title: String, options: List<String>, current: String, set: (Recipe, String) -> Recipe) {
        sheet = SheetSpec(title, options, options.indexOf(current).coerceAtLeast(0)) { i ->
            c.updateDraft { set(it, options[i]) }
        }
    }

    val films = Tables.FILM.map { it.label }
    val drs = Tables.DR.map { it.label }
    val grains = Tables.GRAIN.map { it.label }
    val fx = Tables.FX.map { it.label }
    val wbs = Tables.WB.map { it.label }

    GlassScreen(
        bars = { backdrop ->
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = topInset() + 8.dp, start = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTextButton("取消", backdrop, { c.back() })
                Box(Modifier.weight(1f))
                GlassTextButton("儲存", backdrop, { c.saveDraft() }, accent = true)
            }
            PickerSheet(sheet, backdrop) { sheet = null }
        },
        content = {
            LazyColumn(
                Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topInset() + 70.dp, bottom = 80.dp),
            ) {
                item {
                    Txt(if (c.recipes.any { it.id == r.id }) "編輯食譜" else "新食譜", 30, FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 14.dp))
                    Field(r.name, { v -> c.updateDraft { it.copy(name = v) } }, "食譜名稱（寫入相機時會用這個名字）", size = 17)
                }

                item { SectionLabel("底片模擬") }
                item {
                    Panel {
                        PickRow("底片模擬", r.filmSim) { choose("底片模擬", films, r.filmSim) { x, v -> x.copy(filmSim = v) } }; Divider()
                        PickRow("動態範圍", r.dr) { choose("動態範圍", drs, r.dr) { x, v -> x.copy(dr = v) } }; Divider()
                        PickRow("顆粒效果", r.grain) { choose("顆粒效果", grains, r.grain) { x, v -> x.copy(grain = v) } }; Divider()
                        PickRow("色彩效果", r.colorChrome) { choose("色彩效果", fx, r.colorChrome) { x, v -> x.copy(colorChrome = v) } }; Divider()
                        PickRow("藍色色彩效果", r.colorChromeBlue) { choose("藍色色彩效果", fx, r.colorChromeBlue) { x, v -> x.copy(colorChromeBlue = v) } }
                    }
                }

                item { SectionLabel("白平衡") }
                item {
                    Panel {
                        PickRow("白平衡", r.wb) { choose("白平衡", wbs, r.wb) { x, v -> x.copy(wb = v) } }
                        if (r.wb == "色溫") {
                            Divider()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt("色溫 (K)", 16, modifier = Modifier.weight(1f))
                                Box(Modifier.weight(1f)) {
                                    Field(r.kelvin, { v -> c.updateDraft { it.copy(kelvin = v.filter(Char::isDigit).take(5)) } }, "5500", number = true)
                                }
                            }
                        }
                        Divider()
                        StepSlider("R 偏移", Tables.SHIFT, r.shiftR) { v -> c.updateDraft { it.copy(shiftR = v) } }; Divider()
                        StepSlider("B 偏移", Tables.SHIFT, r.shiftB) { v -> c.updateDraft { it.copy(shiftB = v) } }
                    }
                }

                item { SectionLabel("影像調整") }
                item {
                    Panel {
                        StepSlider("亮部色調", Tables.TONE, r.highlight) { v -> c.updateDraft { it.copy(highlight = v) } }; Divider()
                        StepSlider("陰影色調", Tables.TONE, r.shadow) { v -> c.updateDraft { it.copy(shadow = v) } }; Divider()
                        StepSlider("色彩", Tables.PM4, r.color) { v -> c.updateDraft { it.copy(color = v) } }; Divider()
                        StepSlider("銳利度", Tables.PM4, r.sharpness) { v -> c.updateDraft { it.copy(sharpness = v) } }; Divider()
                        StepSlider("清晰度", Tables.CLARITY, r.clarity) { v -> c.updateDraft { it.copy(clarity = v) } }; Divider()
                        StepSlider("高 ISO 降噪", Tables.PM4, r.nr) { v -> c.updateDraft { it.copy(nr = v) } }
                    }
                }

                item { SectionLabel("拍攝建議（只存在手機，不會寫進相機）") }
                item {
                    Panel(Modifier) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Field(r.iso, { v -> c.updateDraft { it.copy(iso = v) } }, "ISO，例如 Auto 至 6400")
                            Field(r.ev, { v -> c.updateDraft { it.copy(ev = v) } }, "曝光補償，例如 +0.3 ~ +1")
                            Field(r.notes, { v -> c.updateDraft { it.copy(notes = v) } }, "備註", singleLine = false, minHeight = 96.dp)
                        }
                    }
                }

                item { SectionLabel("照片（最多 6 張）") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(r.photos) { id ->
                            Box {
                                PhotoImage(c, id, 300, Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)))
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.55f))
                                        .tap { c.removeDraftPhoto(id) },
                                    contentAlignment = Alignment.Center,
                                ) { GlyphView(Glyph.Close, Color.White, 12.dp) }
                            }
                        }
                        if (r.photos.size < 6) {
                            item {
                                Box(
                                    Modifier
                                        .size(96.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(p.field)
                                        .tap { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    contentAlignment = Alignment.Center,
                                ) { GlyphView(Glyph.Plus, p.sub, 26.dp) }
                            }
                        }
                    }
                }
            }
        },
    )
}
