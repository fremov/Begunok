package ru.begunok.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    highlightLast: Boolean = true,
) {
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    val muted = color.copy(alpha = 0.45f)
    val maxValue = values.max().coerceAtLeast(1f)
    Canvas(modifier) {
        val top = 16.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val slot = size.width / values.size
        val barWidth = slot * 0.6f
        values.forEachIndexed { i, v ->
            val h = (bottom - top) * v / maxValue
            val x = slot * i + (slot - barWidth) / 2
            val barColor = if (highlightLast && i == values.lastIndex) color else muted
            if (v > 0) {
                drawRoundRect(barColor, Offset(x, bottom - h), Size(barWidth, h), CornerRadius(4.dp.toPx()))
                val text = measurer.measure(format(v), valueStyle)
                drawText(text, topLeft = Offset(x + barWidth / 2 - text.size.width / 2, bottom - h - text.size.height))
            }
            labels.getOrNull(i)?.let {
                val label = measurer.measure(it, labelStyle)
                drawText(label, topLeft = Offset(slot * i + slot / 2 - label.size.width / 2, bottom + 2.dp.toPx()))
            }
        }
    }
}

@Composable
fun TrendChart(
    values: List<Float?>,
    color: Color,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    reference: List<Float?>? = null,
    referenceColor: Color = MaterialTheme.colorScheme.outline,
) {
    val all = values.filterNotNull() + reference.orEmpty().filterNotNull()
    if (all.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val rawMin = all.min()
    val rawMax = all.max()
    val span = rawMax - rawMin
    val pad = if (span > 0f) span * 0.2f else (abs(rawMax) * 0.1f).coerceAtLeast(0.5f)
    val minY = rawMin - pad
    val maxY = rawMax + pad
    Canvas(modifier) {
        val left = 44.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - 6.dp.toPx()
        val width = size.width - left - 6.dp.toPx()
        fun x(i: Int) = left + if (values.size <= 1) width / 2 else width * i / (values.size - 1)
        fun y(v: Float) = bottom - (v - minY) / (maxY - minY) * (bottom - top)

        var lastY: Float? = null
        var lastText: String? = null
        listOf(rawMax, (rawMin + rawMax) / 2, rawMin).forEach { tick ->
            val text = format(tick)
            val label = measurer.measure(text, labelStyle)
            val ty = y(tick)
            val previous = lastY
            if (text == lastText || (previous != null && ty - previous < label.size.height + 2.dp.toPx())) return@forEach
            drawLine(gridColor, Offset(left, ty), Offset(size.width, ty), strokeWidth = 1f)
            drawText(label, topLeft = Offset(0f, ty - label.size.height / 2))
            lastY = ty
            lastText = text
        }

        fun series(points: List<Float?>, lineColor: Color, dashed: Boolean) {
            val path = Path()
            var drawing = false
            points.forEachIndexed { i, v ->
                if (v == null) {
                    drawing = false
                    return@forEachIndexed
                }
                if (drawing) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
                drawing = true
                if (!dashed) drawCircle(lineColor, 3.dp.toPx(), Offset(x(i), y(v)))
            }
            drawPath(
                path,
                lineColor,
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null,
                ),
            )
        }
        reference?.let { series(it, referenceColor, dashed = true) }
        series(values, color, dashed = false)
    }
}
