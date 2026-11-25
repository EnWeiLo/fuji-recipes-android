package com.enweilo.fujishokudo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 暗房食堂: warm darkroom greys with the app's orange. */
object C {
    val bg = Color(0xFF171513)
    val side = Color(0xFF1F1C19)
    val line = Color(0xFF2E2A26)
    val card = Color(0xFF24211E)
    val card2 = Color(0xFF2A2622)
    val field = Color(0xFF24211E)
    val text = Color(0xFFF3EEE7)
    val sub = Color(0xFFB9B0A6)
    val dim = Color(0xFF8E857B)
    val accent = Color(0xFFF26B3A)
    val accentSoft = Color(0xFF3A2A20)
    val accentText = Color(0xFFF4A07E)
    val ok = Color(0xFF5CC46A)
    val okText = Color(0xFFA8DDA0)
    val okSoft = Color(0xFF22301F)
    val warn = Color(0xFFFFB020)
    val warnSoft = Color(0xFF3A3020)
    val danger = Color(0xFFFF7A6B)
    val photoBg = Color(0xFF0F0E0D)
}

@Composable
fun Txt(
    text: String,
    size: Int = 14,
    weight: FontWeight = FontWeight.Normal,
    color: Color = C.text,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size.sp, fontWeight = weight, textAlign = align, lineHeight = (size * 1.45f).sp),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default).clickable(enabled = enabled, onClick = onClick)

@Composable
fun Btn(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: Ico? = null,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val bg = when {
        !enabled -> C.card
        primary -> if (hovered) Color(0xFFFF7D4D) else C.accent
        else -> if (hovered) C.card2 else C.card
    }
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, Color(0xFF3A3530), RoundedCornerShape(10.dp)))
            .hoverable(hover).press(enabled, onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val color = when {
            !enabled -> C.dim
            primary -> Color.White
            danger -> C.danger
            else -> C.text
        }
        if (icon != null) IconView(icon, color, 16.dp)
        Txt(text, 14, if (primary) FontWeight.Medium else FontWeight.Normal, color, maxLines = 1)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.side).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (selected) C.accentSoft else C.card)
            .then(if (onClick != null) Modifier.press(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) { Txt(text, 12, color = if (selected) C.accentText else C.sub, maxLines = 1) }
}

/** A labelled text box. */
@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = true, minHeight: Dp = 40.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label.isNotEmpty()) Txt(label, 12, color = C.dim)
        BasicTextField(
            value, onChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = C.text, fontSize = 14.sp),
            cursorBrush = SolidColor(C.accent),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.field)
                .border(1.dp, Color(0xFF3A3530), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp)
                .then(if (!singleLine) Modifier.height(minHeight) else Modifier),
        )
    }
}

/** A labelled drop-down of fixed choices. */
@Composable
fun Select(label: String, value: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Txt(label, 12, color = C.dim)
        Box {
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.field)
                    .border(1.dp, Color(0xFF3A3530), RoundedCornerShape(10.dp)).press { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(value.ifEmpty { "—" }, 14, modifier = Modifier.weight(1f), maxLines = 1)
                IconView(Ico.Down, C.dim, 14.dp)
            }
            DropdownMenu(open, { open = false }, Modifier.background(C.card2)) {
                options.forEach { o ->
                    DropdownMenuItem(
                        text = { Txt(o, 14, color = if (o == value) C.accentText else C.text) },
                        onClick = { onPick(o); open = false },
                    )
                }
            }
        }
    }
}

enum class Ico { Recipes, Link, Oven, Polaroid, Gear, Down, Plus, Folder, Camera, Phone, Search, Close, Check, Trash, Copy }

@Composable
fun IconView(i: Ico, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 24f
        val st = Stroke(1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(a: Offset, b: Offset) = drawLine(color, a, b, 1.8f * s, StrokeCap.Round)
        fun rrect(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(color, p(x, y), Size(w * s, h * s), CornerRadius(r * s), style = st)
        fun path(vararg pts: Pair<Float, Float>, close: Boolean = false) {
            val pa = Path()
            pts.forEachIndexed { k, (x, y) -> if (k == 0) pa.moveTo(x * s, y * s) else pa.lineTo(x * s, y * s) }
            if (close) pa.close()
            drawPath(pa, color, style = st)
        }
        when (i) {
            Ico.Recipes -> { line(p(4f, 6f), p(20f, 6f)); line(p(4f, 12f), p(20f, 12f)); line(p(4f, 18f), p(14f, 18f)) }
            Ico.Link -> { line(p(9f, 3f), p(9f, 8f)); line(p(15f, 3f), p(15f, 8f)); rrect(7f, 8f, 10f, 7f, 4f); line(p(12f, 15f), p(12f, 21f)) }
            Ico.Oven -> { rrect(3f, 5f, 18f, 14f, 3f); line(p(3f, 9f), p(21f, 9f)); rrect(6f, 11.5f, 9f, 5f, 1f); drawCircle(color, 1f * s, p(18f, 14f)) }
            Ico.Polaroid -> { rrect(5f, 3f, 14f, 18f, 2f); rrect(7.5f, 5.5f, 9f, 9f, 0.5f) }
            Ico.Gear -> { drawCircle(color, 3f * s, p(12f, 12f), style = st); drawCircle(color, 8f * s, p(12f, 12f), style = st) }
            Ico.Down -> path(6f to 9f, 12f to 15f, 18f to 9f)
            Ico.Plus -> { line(p(12f, 5f), p(12f, 19f)); line(p(5f, 12f), p(19f, 12f)) }
            Ico.Folder -> path(3f to 6f, 9f to 6f, 11f to 8f, 21f to 8f, 21f to 19f, 3f to 19f, close = true)
            Ico.Camera -> { rrect(3f, 7f, 18f, 13f, 3f); drawCircle(color, 3.6f * s, p(12f, 13.5f), style = st); path(8f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 16f to 7f) }
            Ico.Phone -> { rrect(7f, 2.5f, 10f, 19f, 2.5f); line(p(11f, 18.5f), p(13f, 18.5f)) }
            Ico.Search -> { drawCircle(color, 6f * s, p(10.5f, 10.5f), style = st); line(p(15f, 15f), p(20f, 20f)) }
            Ico.Close -> { line(p(6f, 6f), p(18f, 18f)); line(p(18f, 6f), p(6f, 18f)) }
            Ico.Check -> path(5f to 12.5f, 10f to 17f, 19f to 7f)
            Ico.Trash -> { line(p(4f, 7f), p(20f, 7f)); rrect(6f, 7f, 12f, 13f, 2f); line(p(10f, 4f), p(14f, 4f)) }
            Ico.Copy -> { rrect(8f, 8f, 12f, 12f, 2f); path(16f to 8f, 16f to 4f, 4f to 4f, 4f to 16f, 8f to 16f) }
        }
    }
}

