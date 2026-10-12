package com.enweilo.fujirecipes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRenderEffectSupported
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

@Immutable
class Palette(
    val dark: Boolean,
    val bg: Color,
    val blobA: Color,
    val blobB: Color,
    val text: Color,
    val sub: Color,
    val card: Color,
    val line: Color,
    val field: Color,
    val accent: Color,
    val glassFill: Color,
    val glassFallback: Color,
    val sheet: Color,
    val danger: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFF3F0EB),
    blobA = Color(0x66FF9E73),
    blobB = Color(0x55A9C7E8),
    text = Color(0xFF1B1B1F),
    sub = Color(0xFF6C6C75),
    card = Color(0xB8FFFFFF),
    line = Color(0x14000000),
    field = Color(0x0F000000),
    accent = Color(0xFFF0643A),
    glassFill = Color(0x66FFFFFF),
    glassFallback = Color(0xF2FAFAFA),
    sheet = Color(0xB3FFFFFF),
    danger = Color(0xFFD93A3A),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF0B0B0D),
    blobA = Color(0x55F0643A),
    blobB = Color(0x553A5C8A),
    text = Color(0xFFF2F2F5),
    sub = Color(0xFF9B9BA6),
    card = Color(0xB3202026),
    line = Color(0x1AFFFFFF),
    field = Color(0x1AFFFFFF),
    accent = Color(0xFFFF7448),
    glassFill = Color(0x331C1C22),
    glassFallback = Color(0xF21E1E24),
    sheet = Color(0x99202026),
    danger = Color(0xFFFF6464),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val Pal: Palette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

/** Liquid glass surface: blurs and bends whatever is behind it. Falls back to a plain tinted panel on old Androids. */
@Composable
fun Modifier.glass(
    backdrop: Backdrop,
    radius: Dp? = null,
    tint: Color = Color.Unspecified,
    fill: Color = Pal.glassFill,
): Modifier {
    val p = Pal
    val shapeFn: () -> Shape = { if (radius == null) Capsule() else RoundedRectangle(radius) }
    if (!isRenderEffectSupported()) {
        val fallbackShape: Shape = if (radius == null) RoundedCornerShape(percent = 50) else RoundedCornerShape(radius)
        val color = if (tint.isSpecified) tint else p.glassFallback
        return this.clip(fallbackShape).background(color)
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = shapeFn,
        effects = {
            vibrancy()
            blur(8f.dp.toPx())
            lens(10f.dp.toPx(), 20f.dp.toPx())
        },
        onDrawSurface = {
            if (tint.isSpecified) {
                drawRect(tint, blendMode = BlendMode.Hue)
                drawRect(tint.copy(alpha = 0.78f))
            } else {
                drawRect(fill)
            }
        },
    )
}

/** Clickable without ripple; dims slightly while pressed. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    return this
        .graphicsLayer { alpha = if (!enabled) 0.4f else if (pressed) 0.6f else 1f }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}
