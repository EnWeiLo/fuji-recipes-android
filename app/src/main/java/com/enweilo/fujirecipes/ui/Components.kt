package com.enweilo.fujirecipes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enweilo.fujirecipes.AppController
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------- basics ----------------

@Composable
fun Txt(
    text: String,
    size: Int = 16,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Pal.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun topInset(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
fun bottomInset(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * A screen: soft coloured background and the scrolling content live in one layer that the glass
 * controls (drawn afterwards, on top) bend and blur.
 */
@Composable
fun GlassScreen(
    bars: @Composable BoxScope.(Backdrop) -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val p = Pal
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop)
                .background(p.bg)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(listOf(p.blobA, Color.Transparent), Offset(size.width * 0.9f, size.height * 0.05f), size.width * 0.9f),
                        radius = size.width * 0.9f,
                        center = Offset(size.width * 0.9f, size.height * 0.05f),
                    )
                    drawCircle(
                        Brush.radialGradient(listOf(p.blobB, Color.Transparent), Offset(size.width * 0.05f, size.height * 0.75f), size.width * 1.0f),
                        radius = size.width * 1.0f,
                        center = Offset(size.width * 0.05f, size.height * 0.75f),
                    )
                },
            content = content,
        )
        bars(backdrop)
    }
}

// ---------------- buttons ----------------

@Composable
fun GlassCircleButton(
    glyph: Glyph,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Boolean = false,
    danger: Boolean = false,
) {
    val p = Pal
    Box(
        modifier
            .size(size)
            .glass(backdrop, tint = if (accent) p.accent else Color.Unspecified)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphView(glyph, if (accent) Color.White else if (danger) p.danger else p.text, 22.dp)
    }
}

@Composable
fun GlassTextButton(
    text: String,
    backdrop: Backdrop,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 44.dp,
    sidePadding: Dp = 20.dp,
) {
    val p = Pal
    Box(
        modifier
            .height(height)
            .glass(backdrop, tint = if (accent) p.accent else Color.Unspecified)
            .tap(enabled, onClick)
            .padding(horizontal = sidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 16, FontWeight.SemiBold, if (accent) Color.White else p.text, maxLines = 1)
    }
}

/** Big full-width action button (glass, tinted with the accent colour). */
@Composable
fun BigAction(text: String, backdrop: Backdrop, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    GlassTextButton(text, backdrop, onClick, modifier.fillMaxWidth(), accent = true, enabled = enabled, height = 54.dp)
}

/** Quiet button on a plain surface. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val p = Pal
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, 16, FontWeight.Medium, if (danger) p.danger else p.text, maxLines = 1)
    }
}

// ---------------- surfaces ----------------

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = Pal
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(p.card)
            .border(0.5.dp, p.line, RoundedCornerShape(22.dp)),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) {
    Txt(text, 13, FontWeight.SemiBold, Pal.sub, Modifier.padding(start = 8.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Pal.line))
}

/** One line in a panel: label on the left, value on the right, optional tiny meter. */
@Composable
fun ValueRow(label: String, value: String, meter: Float? = null) {
    val p = Pal
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 16, color = p.text, modifier = Modifier.weight(1f))
        if (meter != null) {
            Box(Modifier.width(52.dp).height(4.dp).clip(CircleShape).background(p.line)) {
                Box(Modifier.fillMaxWidth(meter).height(4.dp).clip(CircleShape).background(p.accent))
            }
            Box(Modifier.width(12.dp))
        }
        Txt(value, 16, FontWeight.Medium, p.sub, maxLines = 1)
    }
}

@Composable
fun PickRow(label: String, value: String, onClick: () -> Unit) {
    val p = Pal
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(label, 16, modifier = Modifier.weight(1f))
        Txt(value, 16, FontWeight.Medium, p.accent, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp))
        GlyphView(Glyph.Chevron, p.sub, 16.dp)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    number: Boolean = false,
    minHeight: Dp = 48.dp,
    size: Int = 16,
) {
    val p = Pal
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        textStyle = TextStyle(color = p.text, fontSize = size.sp),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(RoundedCornerShape(14.dp))
                    .background(p.field)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (value.isEmpty()) Txt(placeholder, size, color = p.sub.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}

@Composable
fun Tag(text: String) {
    val p = Pal
    Box(Modifier.clip(CircleShape).background(p.field).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Txt(text, 12, FontWeight.Medium, p.sub, maxLines = 1)
    }
}

// ---------------- photos ----------------

@Composable
fun PhotoImage(controller: AppController, id: String, maxSide: Int, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(null, id, maxSide) {
        value = withContext(Dispatchers.IO) { controller.store.loadBitmap(id, maxSide) }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(Pal.field))
    }
}

// ---------------- overlays ----------------

class SheetSpec(val title: String, val options: List<String>, val selected: Int, val onPick: (Int) -> Unit)

/** Bottom sheet made of glass with a list of choices. */
@Composable
fun BoxScope.PickerSheet(spec: SheetSpec?, backdrop: Backdrop, onDismiss: () -> Unit) {
    if (spec == null) return
    val p = Pal
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (p.dark) 0.5f else 0.28f))
            .clickable(interactionSource = none, indication = null, onClick = onDismiss),
    )
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (spec.selected - 3).coerceAtLeast(0))
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .fillMaxWidth()
            .glass(backdrop, radius = 32.dp, fill = p.sheet)
            .clickable(interactionSource = none, indication = null, onClick = {}),
    ) {
        Txt(spec.title, 17, FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 20.dp, bottom = 8.dp, end = 24.dp))
        LazyColumn(Modifier.heightIn(max = 400.dp), state = listState, contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(spec.options) { index, label ->
                val chosen = index == spec.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .tap { spec.onPick(index); onDismiss() }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(label, 17, if (chosen) FontWeight.SemiBold else FontWeight.Normal, if (chosen) p.accent else p.text, Modifier.weight(1f))
                    if (chosen) GlyphView(Glyph.Check, p.accent, 20.dp)
                }
            }
        }
    }
}

/** Centered question with two buttons. */
@Composable
fun BoxScope.ConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val p = Pal
    val none = remember { MutableInteractionSource() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = none, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(if (p.dark) Color(0xFF26262C) else Color(0xFFFBFAF8))
                .clickable(interactionSource = none, indication = null, onClick = {})
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt(title, 19, FontWeight.SemiBold)
            Txt(message, 15, color = p.sub)
            Box(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("取消", onDismiss, Modifier.weight(1f))
                SoftButton(confirmText, { onConfirm(); onDismiss() }, Modifier.weight(1f), danger = danger)
            }
        }
    }
}

@Composable
fun BoxScope.ToastPill(text: String) {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = topInset() + 12.dp)
            .clip(CircleShape)
            .background(Color(0xE61C1C1E))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Txt(text, 15, FontWeight.Medium, Color.White)
    }
}
