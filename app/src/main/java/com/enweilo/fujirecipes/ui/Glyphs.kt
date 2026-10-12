package com.enweilo.fujirecipes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class Glyph { Plus, Back, Chevron, Check, Close, Search, Edit, Trash, Recipes, Polaroid, Camera, Gear }

@Composable
fun GlyphView(glyph: Glyph, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun poly(vararg pts: Pair<Float, Float>, close: Boolean = false) {
            val path = Path()
            pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * s, y * s) else path.lineTo(x * s, y * s) }
            if (close) path.close()
            drawPath(path, color, style = stroke)
        }
        fun line(a: Offset, b: Offset) = drawLine(color, a, b, strokeWidth = 2f * s, cap = StrokeCap.Round)
        when (glyph) {
            Glyph.Plus -> { line(p(12f, 5f), p(12f, 19f)); line(p(5f, 12f), p(19f, 12f)) }
            Glyph.Back -> poly(15f to 5f, 8f to 12f, 15f to 19f)
            Glyph.Chevron -> poly(9f to 5f, 16f to 12f, 9f to 19f)
            Glyph.Check -> poly(5f to 13f, 10f to 18f, 19f to 7f)
            Glyph.Close -> { line(p(6f, 6f), p(18f, 18f)); line(p(18f, 6f), p(6f, 18f)) }
            Glyph.Search -> {
                drawCircle(color, radius = 6f * s, center = p(11f, 11f), style = stroke)
                line(p(15.5f, 15.5f), p(19.5f, 19.5f))
            }
            Glyph.Edit -> poly(4f to 20f, 5f to 15f, 16f to 4f, 20f to 8f, 9f to 19f, close = true)
            Glyph.Trash -> {
                line(p(5f, 7f), p(19f, 7f))
                poly(10f to 7f, 10f to 4.5f, 14f to 4.5f, 14f to 7f)
                poly(7f to 7f, 8f to 19.5f, 16f to 19.5f, 17f to 7f)
            }
            Glyph.Recipes -> {
                line(p(4f, 7f), p(20f, 7f)); line(p(4f, 12f), p(20f, 12f)); line(p(4f, 17f), p(20f, 17f))
                drawCircle(color, radius = 2.6f * s, center = p(9f, 7f))
                drawCircle(color, radius = 2.6f * s, center = p(15f, 12f))
                drawCircle(color, radius = 2.6f * s, center = p(8f, 17f))
            }
            Glyph.Polaroid -> {
                drawRoundRect(color, topLeft = p(4.5f, 3f), size = Size(15f * s, 18f * s), cornerRadius = CornerRadius(2.5f * s), style = stroke)
                drawRoundRect(color, topLeft = p(7.5f, 6f), size = Size(9f * s, 8.5f * s), cornerRadius = CornerRadius(1f * s), style = stroke)
                line(p(9f, 17.8f), p(15f, 17.8f))
            }
            Glyph.Camera -> {
                drawRoundRect(color, topLeft = p(3f, 7f), size = Size(18f * s, 13f * s), cornerRadius = CornerRadius(3f * s), style = stroke)
                drawCircle(color, radius = 3.6f * s, center = p(12f, 13.5f), style = stroke)
                poly(8f to 7f, 9.5f to 4.5f, 14.5f to 4.5f, 16f to 7f)
            }
            Glyph.Gear -> {
                drawCircle(color, radius = 3f * s, center = p(12f, 12f), style = stroke)
                drawCircle(color, radius = 7f * s, center = p(12f, 12f), style = stroke)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val dx = Math.cos(a).toFloat()
                    val dy = Math.sin(a).toFloat()
                    line(p(12f + dx * 7.5f, 12f + dy * 7.5f), p(12f + dx * 10f, 12f + dy * 10f))
                }
            }
        }
    }
}
