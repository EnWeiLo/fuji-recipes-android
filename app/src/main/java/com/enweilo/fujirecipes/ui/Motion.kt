package com.enweilo.fujirecipes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings switch: slow background drift on or off. */
val LocalMotion = staticCompositionLocalOf { true }

private val seenKeys = HashSet<String>()

/**
 * The first time a row shows up it fades in and rises a little, one after another.
 * After that (scrolling back, switching tabs) it simply appears.
 */
@Composable
fun Modifier.appear(key: String, index: Int): Modifier {
    val first = remember(key) { seenKeys.add(key) }
    val progress = remember(key) { Animatable(if (first) 0f else 1f) }
    LaunchedEffect(key) {
        if (first) {
            delay(minOf(index, 8) * 45L)
            progress.animateTo(1f, tween(440, easing = FastOutSlowInEasing))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 22.dp.toPx()
    }
}

/** A slim bar that grows from empty to [fraction] when it appears. */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier) {
    val p = Pal
    var target by remember { mutableStateOf(0f) }
    LaunchedEffect(fraction) { target = fraction }
    val shown by animateFloatAsState(target, tween(650, easing = FastOutSlowInEasing), label = "meter")
    Box(modifier.width(52.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.line)) {
        Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.accent))
    }
}

/** Sliding pill selector. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val p = Pal
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.field)
            .padding(3.dp),
    ) {
        val segW: Dp = maxWidth / options.size
        val x by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMedium), label = "segment")
        Box(
            Modifier
                .offset(x = segW * x)
                .width(segW)
                .height(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(p.accent),
        )
        Row {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(40.dp)
                        .tap {
                            if (!on) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(i)
                        },
                    contentAlignment = Alignment.Center,
                ) { Txt(label, 15, FontWeight.Medium, if (on) Color.White else p.text, maxLines = 1) }
            }
        }
    }
}

private fun nearestIndex(options: List<String>, value: String): Int {
    val exact = options.indexOf(value)
    if (exact >= 0) return exact
    val v = value.replace("+", "").toFloatOrNull()
    if (v != null) {
        var best = 0
        var bestDist = Float.MAX_VALUE
        options.forEachIndexed { i, o ->
            val n = o.replace("+", "").toFloatOrNull() ?: return@forEachIndexed
            val d = abs(n - v)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return best
    }
    return options.indexOf("0").coerceAtLeast(0)
}

/**
 * A slider that snaps to the entries of [options] (e.g. "-2" … "+4"). Fills from the "0" mark when there is one,
 * ticks lightly while dragging, and also accepts a tap on the track.
 */
@Composable
fun StepSlider(label: String, options: List<String>, value: String, onChange: (String) -> Unit) {
    val p = Pal
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val n = options.size
    val idx = nearestIndex(options, value)
    val center = options.indexOf("0")
    val currentIdx by rememberUpdatedState(idx)
    val latestChange by rememberUpdatedState(onChange)
    var dragging by remember { mutableStateOf(false) }

    val pos by animateFloatAsState(
        idx.toFloat() / (n - 1).coerceAtLeast(1),
        spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
        label = "pos",
    )
    val grow by animateFloatAsState(if (dragging) 1.18f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "grow")
    val padPx = with(density) { 16.dp.toPx() }

    fun setFrom(x: Float, width: Float) {
        val frac = ((x - padPx) / (width - 2 * padPx)).coerceIn(0f, 1f)
        val i = (frac * (n - 1)).roundToInt()
        if (i != currentIdx) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            latestChange(options[i])
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(label, 16, modifier = Modifier.weight(1f))
            Txt(value, 16, FontWeight.SemiBold, if (value == "0") p.sub else p.accent)
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .pointerInput(n) { detectTapGestures { setFrom(it.x, size.width.toFloat()) } }
                .pointerInput(n) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; setFrom(it.x, size.width.toFloat()) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        setFrom(change.position.x, size.width.toFloat())
                    }
                },
        ) {
            val pad = 16.dp.toPx()
            val trackW = size.width - 2 * pad
            val cy = size.height / 2
            val h = 6.dp.toPx()
            // empty track
            drawLine(p.sub.copy(alpha = 0.22f), Offset(pad, cy), Offset(pad + trackW, cy), strokeWidth = h, cap = StrokeCap.Round)
            // little marks, one per step
            if (n <= 21) {
                for (i in 0 until n) {
                    val x = pad + trackW * i / (n - 1)
                    val big = i == center
                    drawCircle(
                        if (big) p.sub.copy(alpha = 0.7f) else p.sub.copy(alpha = 0.35f),
                        radius = if (big) 2.6.dp.toPx() else 1.5.dp.toPx(),
                        center = Offset(x, cy + if (big) 0f else 0f),
                    )
                }
            }
            // filled part
            val thumbX = pad + trackW * pos
            val startX = if (center >= 0) pad + trackW * center / (n - 1) else pad
            drawLine(p.accent, Offset(startX, cy), Offset(thumbX, cy), strokeWidth = h, cap = StrokeCap.Round)
            // thumb
            val r = 13.dp.toPx() * grow
            drawCircle(Color.Black.copy(alpha = 0.16f), radius = r + 1.5.dp.toPx(), center = Offset(thumbX, cy + 1.5.dp.toPx()))
            drawCircle(Color.White, radius = r, center = Offset(thumbX, cy))
            drawCircle(p.accent.copy(alpha = 0.9f), radius = 4.dp.toPx(), center = Offset(thumbX, cy))
        }
    }
}
