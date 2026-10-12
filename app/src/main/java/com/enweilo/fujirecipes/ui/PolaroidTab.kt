package com.enweilo.fujirecipes.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enweilo.fujirecipes.AppController
import com.enweilo.fujirecipes.polaroid.Card
import com.enweilo.fujirecipes.polaroid.Fonts
import com.enweilo.fujirecipes.polaroid.Frame
import com.enweilo.fujirecipes.polaroid.PState
import com.enweilo.fujirecipes.polaroid.PolaroidController
import com.enweilo.fujirecipes.polaroid.Ratio
import com.enweilo.fujirecipes.polaroid.Src
import com.kyant.backdrop.Backdrop
import kotlin.math.max
import kotlin.math.min

private val SIZE_LABELS = listOf("小", "中", "大", "特大")
private val SIZE_VALUES = listOf(12, 14, 18, 22)
private val DATE_VALUES = listOf(10, 12, 15, 18)

private val SWATCHES = listOf(
    0xFF000000, 0xFF2B2B2B, 0xFF8A8A8A, 0xFFFFFFFF,
    0xFFF6EFE4, 0xFF3A2E21, 0xFFA67C52, 0xFFE09F3E,
    0xFFF2C14E, 0xFFF0643A, 0xFFD93A3A, 0xFFE26DA0,
    0xFF7A5AF8, 0xFF3A7BD5, 0xFF4C9F70, 0xFF1F5F4A,
).map { it.toInt() }

// ---------------- the tab ----------------

@Composable
fun PolaroidTab(c: AppController, onSheet: (SheetSpec) -> Unit) {
    val pc = c.polaroid
    var section by remember { mutableIntStateOf(0) }
    var colorOpen by remember { mutableStateOf(false) }

    val firstPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) pc.addPhotos(uris, replaceAll = true)
    }
    val morePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) pc.addPhotos(uris, replaceAll = false)
    }
    val replacePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pc.replaceCurrent(uri)
    }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    BackHandler(enabled = colorOpen) { colorOpen = false }
    val listState = rememberLazyListState()
    LaunchedEffect(section) { listState.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(0.44f)
                    .padding(start = 16.dp, end = 16.dp, top = topInset() + 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                CardPreview(
                    pc,
                    onTapEmpty = { firstPicker.launch(imageOnly) },
                    onTapPhoto = { replacePicker.launch(imageOnly) },
                )
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Segmented(listOf("照片", "版型", "文字", "日期"), section) { section = it }
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(0.56f),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 190.dp),
            ) {
                item {
                    when (section) {
                        0 -> PhotoSection(
                            pc, onSheet,
                            onFirst = { firstPicker.launch(imageOnly) },
                            onMore = { morePicker.launch(imageOnly) },
                        )
                        1 -> StyleSection(pc)
                        2 -> TextSection(pc, onSheet, onColor = { colorOpen = true })
                        else -> DateSection(pc, onSheet)
                    }
                }
            }
        }

        // shutter flash
        var flashAlpha by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(pc.flash) {
            if (pc.flash > 0) {
                val t0 = withFrameNanos { it }
                flashAlpha = 0.55f
                while (true) {
                    val t = withFrameNanos { it }
                    val k = (t - t0) / 260_000_000f
                    if (k >= 1f) {
                        flashAlpha = 0f
                        break
                    }
                    flashAlpha = 0.55f * (1f - k)
                }
            }
        }
        if (flashAlpha > 0.01f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))

        if (colorOpen) {
            ColorDialog(pc.current?.state?.color ?: 0xFF000000.toInt(), onPick = { color -> pc.update { it.copy(color = color) } }, onClose = { colorOpen = false })
        }
    }
}

/** Share / save buttons that float above the tab bar. */
@Composable
fun BoxScope.PolaroidActions(c: AppController, backdrop: Backdrop) {
    val pc = c.polaroid
    val p = Pal
    val ready = pc.items.isNotEmpty() && !pc.busy
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 24.dp, bottom = 98.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pc.progress.isNotEmpty()) {
            Box(Modifier.clip(CircleShape).background(Color(0xE61C1C1E)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Txt(pc.progress, 13, FontWeight.Medium, Color.White)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassTextButton("分享", backdrop, { pc.shareCurrent() }, Modifier.weight(1.3f), accent = true, enabled = ready, sidePadding = 6.dp)
            GlassTextButton("儲存此張", backdrop, { pc.saveCurrent() }, Modifier.weight(1.3f), enabled = ready, sidePadding = 6.dp)
            if (pc.items.size > 1) {
                GlassTextButton("ZIP", backdrop, { pc.saveAllZip() }, Modifier.weight(0.9f), enabled = ready, sidePadding = 6.dp)
            }
        }
    }
}

// ---------------- preview with gestures ----------------

@Composable
private fun CardPreview(pc: PolaroidController, onTapEmpty: () -> Unit, onTapPhoto: () -> Unit) {
    val item = pc.current
    val st = item?.state ?: PState()
    val bmp = pc.source?.preview
    val geo = Card.geometry(st, bmp)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val k = min(maxW / geo.totalW, maxH / geo.totalH)
        val w = geo.totalW * k
        val h = geo.totalH * k
        val density = LocalDensity.current
        val wDp = with(density) { w.toDp() }
        val hDp = with(density) { h.toDp() }
        Canvas(
            Modifier
                .size(wDp, hDp)
                .shadow(10.dp, RoundedCornerShape(2.dp))
                .pointerInput(pc) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val it0 = pc.current
                        val src = pc.source?.preview
                        val st0 = it0?.state ?: PState()
                        val g = Card.geometry(st0, src)
                        val scale = g.totalW / max(1f, size.width.toFloat())
                        val startX = down.position.x
                        val startY = down.position.y
                        val eligible = g.inPhoto(startX * scale, startY * scale)

                        // 0 = nothing, 1 = move / zoom the picture, 2 = drag the date stamp
                        var mode = 0
                        if (it0 != null && src != null) {
                            val cx = startX * scale
                            val cy = startY * scale
                            val banner = st0.ratio == Ratio.Banner
                            if (!(banner && !eligible)) {
                                mode = when {
                                    cy > g.top + g.photoH -> 0
                                    !banner && st0.dateOn && cx < g.side * 2 && !st0.dateRight -> 2
                                    !banner && st0.dateOn && cx > g.totalW - g.side * 2 && st0.dateRight -> 2
                                    else -> 1
                                }
                            }
                        }
                        val ox = st0.cropX
                        val oy = st0.cropY
                        val startPos = st0.datePos
                        var startZ = st0.cropZ
                        var pinching = false
                        var startDist = 1f
                        var moved = false
                        var multi = false

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) multi = true
                            if (pressed.size >= 2 && mode == 1 && it0 != null) {
                                val d = (pressed[0].position - pressed[1].position).getDistance()
                                if (!pinching) {
                                    pinching = true
                                    startDist = max(1f, d)
                                    startZ = it0.state.cropZ
                                } else {
                                    val z = (startZ * d / startDist).coerceIn(1f, 4f)
                                    it0.state = it0.state.copy(cropZ = z)
                                }
                                event.changes.forEach { ch -> ch.consume() }
                            } else if (pressed.size == 1 && !pinching) {
                                val pos = pressed[0].position
                                if ((pos - Offset(startX, startY)).getDistance() > slop) moved = true
                                if (mode != 0 && it0 != null) {
                                    val dx = (pos.x - startX) * scale
                                    val dy = (pos.y - startY) * scale
                                    if (mode == 1) {
                                        it0.state = it0.state.copy(cropX = ox + dx, cropY = oy + dy)
                                    } else {
                                        it0.state = it0.state.copy(datePos = (startPos + dy / g.photoH * 100f).coerceIn(4f, 86f))
                                    }
                                    event.changes.forEach { ch -> if (ch.positionChanged()) ch.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (!moved && !multi && !pinching) {
                            if (it0 == null) onTapEmpty() else if (eligible) onTapPhoto()
                        }
                    }
                },
        ) {
            val s = size.width / geo.totalW
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                nc.save()
                nc.clipRect(0f, 0f, size.width, size.height)
                nc.scale(s, s)
                Card.draw(nc, st, bmp, s)
                nc.restore()
            }
        }
    }
}

// ---------------- sections ----------------

@Composable
private fun PhotoSection(pc: PolaroidController, onSheet: (SheetSpec) -> Unit, onFirst: () -> Unit, onMore: () -> Unit) {
    val p = Pal
    var syncPick by remember { mutableIntStateOf(0) }
    Column {
        SectionLabel("選擇照片")
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(if (pc.items.isEmpty()) "選擇照片（最多 20 張）" else "重新選擇照片", onFirst, enabled = !pc.busy)
                if (pc.items.isNotEmpty() && pc.items.size < PolaroidController.MAX_PHOTOS) {
                    SoftButton("加入更多照片", onMore, enabled = !pc.busy)
                }
                if (pc.busy && pc.progress.isEmpty()) Txt("處理中…", 12, color = p.sub)
                else Txt("點預覽也能選照片；點照片可更換這張，拖曳調整裁切，雙指縮放。", 12, color = p.sub)
            }
        }
        if (pc.items.isNotEmpty()) {
            SectionLabel("第 ${pc.index + 1} 張／共 ${pc.items.size} 張")
            Panel {
                LazyRow(
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(pc.items, key = { _, it -> it.id }) { i, item ->
                        val on = i == pc.index
                        Box(Modifier.size(68.dp)) {
                            val thumb = item.thumb
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .align(Alignment.BottomStart)
                                    .clip(shape)
                                    .background(p.field)
                                    .border(if (on) 2.dp else 0.5.dp, if (on) p.accent else p.line, shape)
                                    .tap { pc.select(i) },
                            ) {
                                if (thumb != null) {
                                    val image = remember(item.id) { thumb.asImageBitmap() }
                                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xCC1C1C1E))
                                    .tap(enabled = !pc.busy) { pc.delete(i) },
                                contentAlignment = Alignment.Center,
                            ) { GlyphView(Glyph.Close, Color.White, 12.dp) }
                        }
                    }
                }
            }
        }
        if (pc.items.size > 1) {
            SectionLabel("同步設定")
            val others = pc.items.indices.filter { it != pc.index }
            val pickIndex = syncPick.coerceIn(0, others.lastIndex)
            Panel {
                PickRow("設定來源", "第 ${others[pickIndex] + 1} 張") {
                    onSheet(
                        SheetSpec("同步設定來源", others.map { "第 ${it + 1} 張" }, pickIndex) { syncPick = it },
                    )
                }
                Divider()
                Column(Modifier.padding(12.dp)) {
                    SoftButton("套用到這一張", { pc.syncFrom(others[pickIndex]) })
                }
            }
            Txt("複製文字、日期、字型與顏色設定。", 12, color = p.sub, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
    }
}

@Composable
private fun StyleSection(pc: PolaroidController) {
    val p = Pal
    val st = pc.current?.state ?: PState()
    Column {
        SectionLabel("邊框樣式")
        Panel {
            Box(Modifier.padding(10.dp)) {
                Chips(Frame.entries.map { it.label }, Frame.entries.indexOf(st.frame), 5) { pc.setFrame(Frame.entries[it]) }
            }
        }
        SectionLabel("照片比例")
        Panel {
            Box(Modifier.padding(10.dp)) {
                Chips(Ratio.entries.map { it.label }, Ratio.entries.indexOf(st.ratio), 4) { pc.setRatio(Ratio.entries[it]) }
            }
        }
        Box(Modifier.padding(top = 14.dp)) { SoftButton("重設照片裁切", { pc.resetCrop() }) }
        Txt("拖曳預覽調整裁切，雙指縮放（1–4 倍）。", 12, color = p.sub, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
    }
}

private fun srcLabel(choice: Src, auto: String): String =
    if (choice == Src.Auto) "自動帶入" + (if (auto.isNotEmpty()) "（$auto）" else "（無資料）") else choice.label

@Composable
private fun TextSection(pc: PolaroidController, onSheet: (SheetSpec) -> Unit, onColor: () -> Unit) {
    val p = Pal
    val st = pc.current?.state ?: PState()
    val banner = st.ratio == Ratio.Banner
    fun srcSheet(title: String, current: Src, auto: String, pick: (Src) -> Unit) = onSheet(
        SheetSpec(title, Src.entries.map { srcLabel(it, auto) }, Src.entries.indexOf(current)) { pick(Src.entries[it]) },
    )
    fun fontSheet(title: String, current: String, pick: (String) -> Unit) = onSheet(
        SheetSpec(title, Fonts.choices.map { it.label }, Fonts.choices.indexOfFirst { it.key == current }.coerceAtLeast(0)) {
            pick(Fonts.choices[it].key)
        },
    )
    fun sizeSheet(title: String, current: Int, pick: (Int) -> Unit) = onSheet(
        SheetSpec(title, SIZE_LABELS, SIZE_VALUES.indexOf(current).coerceAtLeast(1)) { pick(SIZE_VALUES[it]) },
    )
    Column {
        SectionLabel("顯示內容")
        Panel {
            ToggleRow("相機品牌／型號", st.showCam) { v -> pc.update { it.copy(showCam = v) } }
            if (banner) {
                Divider()
                ToggleRow("Shot on", st.shotOn) { v -> pc.update { it.copy(shotOn = v) } }
            }
            if (st.hasExposure) {
                Divider()
                ToggleRow("快門／光圈／ISO", st.showExp) { v -> pc.update { it.copy(showExp = v) } }
            }
        }

        SectionLabel("相機資料")
        Panel {
            PickRow("品牌", srcLabel(st.brandChoice, st.autoBrand)) {
                srcSheet("品牌來源", st.brandChoice, st.autoBrand) { v -> pc.update { it.copy(brandChoice = v) } }
            }
            if (st.brandChoice == Src.Manual) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Field(st.manualBrand, { v -> pc.update { it.copy(manualBrand = v) } }, "例如 FUJIFILM")
                }
            }
            Divider()
            PickRow("型號", srcLabel(st.modelChoice, st.autoModel)) {
                srcSheet("型號來源", st.modelChoice, st.autoModel) { v -> pc.update { it.copy(modelChoice = v) } }
            }
            if (st.modelChoice == Src.Manual) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Field(st.manualModel, { v -> pc.update { it.copy(manualModel = v) } }, "例如 X-T50")
                }
            }
            if (!st.isPhone) {
                Divider()
                PickRow("鏡頭", srcLabel(st.lensChoice, st.autoLens)) {
                    srcSheet("鏡頭來源", st.lensChoice, st.autoLens) { v -> pc.update { it.copy(lensChoice = v) } }
                }
                if (st.lensChoice == Src.Manual) {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                        Field(st.manualLens, { v -> pc.update { it.copy(manualLens = v) } }, "XF35mmF1.4 R")
                    }
                }
            }
        }

        SectionLabel("自訂備註")
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(st.noteText, { v -> pc.update { it.copy(noteText = v) } }, "輸入隨想或地點…")
                if (!banner && !Card.hasInfo(st)) {
                    Txt("目前備註以單行向右顯示，不自動換行。", 12, color = p.sub)
                }
            }
        }

        SectionLabel("字體與外觀")
        Panel {
            PickRow("相機／鏡頭字型", Fonts.label(st.font)) {
                fontSheet("相機／鏡頭字型", st.font) { v -> pc.update { it.copy(font = v) } }
            }
            Divider()
            PickRow("相機／鏡頭／曝光大小", SIZE_LABELS[SIZE_VALUES.indexOf(st.sizeLeft).coerceAtLeast(1)]) {
                sizeSheet("相機／鏡頭／曝光大小", st.sizeLeft) { v -> pc.update { it.copy(sizeLeft = v) } }
            }
            Divider()
            PickRow("備註字型", Fonts.label(st.noteFont)) {
                fontSheet("備註字型", st.noteFont) { v -> pc.update { it.copy(noteFont = v) } }
            }
            Divider()
            PickRow("備註大小", SIZE_LABELS[SIZE_VALUES.indexOf(st.noteSize).coerceAtLeast(1)]) {
                sizeSheet("備註大小", st.noteSize) { v -> pc.update { it.copy(noteSize = v) } }
            }
            Divider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(onClick = onColor).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("字體顏色", 16, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(st.color))
                        .border(1.dp, p.line, CircleShape),
                )
                Box(Modifier.width(8.dp))
                GlyphView(Glyph.Chevron, p.sub, 16.dp)
            }
        }
        Box(Modifier.padding(top = 14.dp)) { SoftButton("重設文字位置", { pc.resetTextPosition() }) }
    }
}

@Composable
private fun DateSection(pc: PolaroidController, onSheet: (SheetSpec) -> Unit) {
    val p = Pal
    val st = pc.current?.state ?: PState()
    val banner = st.ratio == Ratio.Banner
    Column {
        SectionLabel("日期戳")
        Panel {
            ToggleRow("顯示日期", st.dateOn) { v -> pc.update { it.copy(dateOn = v) } }
            Divider()
            PickRow("日期字體大小", SIZE_LABELS[DATE_VALUES.indexOf(st.dateSize).coerceAtLeast(1)]) {
                onSheet(
                    SheetSpec("日期字體大小", SIZE_LABELS, DATE_VALUES.indexOf(st.dateSize).coerceAtLeast(1)) { i ->
                        pc.update { it.copy(dateSize = DATE_VALUES[i]) }
                    },
                )
            }
            Divider()
            Box(Modifier.padding(16.dp)) {
                Field(st.dateText, { v -> pc.update { it.copy(dateText = v) } }, "2026.09.07")
            }
        }
        SectionLabel("日期位置")
        Panel {
            Box(Modifier.padding(10.dp)) {
                if (banner) {
                    Segmented(listOf("上", "下"), if (st.bannerDateTop) 0 else 1) { i -> pc.update { it.copy(bannerDateTop = i == 0) } }
                } else {
                    Segmented(listOf("左側", "右側"), if (st.dateRight) 1 else 0) { i -> pc.update { it.copy(dateRight = i == 1) } }
                }
            }
        }
        if (!banner) {
            Txt("在預覽上，沿著日期那一側的邊緣上下拖曳，可以移動日期。", 12, color = p.sub, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
        }
    }
}

// ---------------- small controls ----------------

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = Pal
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(if (on) p.accent else Color.Transparent)
                    .tap { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Txt(label, 15, FontWeight.Medium, if (on) Color.White else p.text, maxLines = 1) }
        }
    }
}

/** Choices laid out in rows of [columns]. */
@Composable
private fun Chips(options: List<String>, selected: Int, columns: Int, onSelect: (Int) -> Unit) {
    val p = Pal
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.indices.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in row) {
                    val on = i == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) p.accent.copy(alpha = 0.16f) else p.field)
                            .border(if (on) 1.5.dp else 0.dp, if (on) p.accent else Color.Transparent, RoundedCornerShape(14.dp))
                            .tap { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) { Txt(options[i], 14, FontWeight.Medium, if (on) p.accent else p.text, maxLines = 1) }
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val p = Pal
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(enabled) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 16, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .width(51.dp)
                .height(31.dp)
                .clip(CircleShape)
                .background(if (checked) p.accent else p.line.copy(alpha = 0.22f))
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(27.dp).clip(CircleShape).background(Color.White))
        }
    }
}

@Composable
private fun BoxScope.ColorDialog(current: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val p = Pal
    val none = remember { MutableInteractionSource() }
    var hex by remember { mutableStateOf(String.format("%06X", current and 0xFFFFFF)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = none, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(28.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (p.dark) Color(0xFF26262C) else Color(0xFFFBFAF8))
                .clickable(interactionSource = none, indication = null, onClick = {})
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Txt("字體顏色", 19, FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SWATCHES.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (color in row) {
                            val on = (color and 0xFFFFFF) == (current and 0xFFFFFF)
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color(color))
                                    .border(if (on) 3.dp else 1.dp, if (on) p.accent else p.line, CircleShape)
                                    .tap {
                                        hex = String.format("%06X", color and 0xFFFFFF)
                                        onPick(color)
                                    },
                            )
                        }
                    }
                }
            }
            Field(
                hex,
                { v ->
                    val clean = v.trim().removePrefix("#").uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    hex = clean
                    if (clean.length == 6) onPick(0xFF000000.toInt() or clean.toInt(16))
                },
                "十六進位色碼，例如 F6EFE4",
            )
            SoftButton("完成", onClose)
        }
    }
}
