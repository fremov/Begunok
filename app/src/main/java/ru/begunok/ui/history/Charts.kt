package ru.begunok.ui.history

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class ChartLine(
    val values: List<Float?>,
    val color: Color,
    val label: String,
    val dashed: Boolean = false,
    val area: Boolean = false,
)

data class ChartRule(val value: Float, val label: String, val color: Color)

private val shortDate = SimpleDateFormat("d.MM", Locale.forLanguageTag("ru"))

fun niceStep(range: Float, ticks: Int = 4): Float {
    if (range <= 0f) return 1f
    val raw = range / ticks
    val magnitude = 10f.pow(floor(log10(raw)))
    val normalized = raw / magnitude
    val nice = when {
        normalized <= 1f -> 1f
        normalized <= 2f -> 2f
        normalized <= 2.5f -> 2.5f
        normalized <= 5f -> 5f
        else -> 10f
    }
    return nice * magnitude
}

@Composable
private fun rememberReveal(key: Any?): Float {
    val reveal = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    return reveal.value
}

@Composable
fun MetricChart(
    times: List<Long>,
    lines: List<ChartLine>,
    formatY: (Float) -> String,
    modifier: Modifier = Modifier,
    bars: ChartLine? = null,
    invertY: Boolean = false,
    rules: List<ChartRule> = emptyList(),
    selected: Int? = null,
    onSelect: (Int) -> Unit = {},
) {
    val count = times.size
    if (count == 0) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val guideColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val reveal = rememberReveal(times)

    val values = lines.flatMap { it.values.filterNotNull() } + bars?.values.orEmpty().filterNotNull() + rules.map { it.value }
    if (values.isEmpty()) return
    var minV = values.min()
    var maxV = values.max()
    if (bars != null) minV = minOf(minV, 0f)
    if (maxV - minV < 1e-3f) {
        minV -= 1f
        maxV += 1f
    }
    val step = niceStep(maxV - minV)
    val lo = floor(minV / step) * step
    val hi = ceil(maxV / step) * step

    Canvas(
        modifier
            .pointerInput(count) {
                detectTapGestures { offset -> onSelect(indexAt(offset.x, LEFT_AXIS.dp.toPx(), size.width.toFloat(), count)) }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    onSelect(indexAt(change.position.x, LEFT_AXIS.dp.toPx(), size.width.toFloat(), count))
                }
            },
    ) {
        val left = LEFT_AXIS.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - BOTTOM_AXIS.dp.toPx()
        val width = size.width - left
        val slot = width / count
        fun x(i: Int) = left + slot * i + slot / 2
        fun y(v: Float): Float {
            val f = (v - lo) / (hi - lo)
            return if (invertY) top + f * (bottom - top) else bottom - f * (bottom - top)
        }

        var tick = lo
        while (tick <= hi + step / 2) {
            drawLine(gridColor, Offset(left, y(tick)), Offset(size.width, y(tick)), strokeWidth = 1f)
            val label = measurer.measure(formatY(tick), labelStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), y(tick) - label.size.height / 2))
            tick += step
        }

        val labelIndexes = if (count <= 4) (0 until count).toList() else listOf(0, count / 3, count * 2 / 3, count - 1).distinct()
        var lastRight = -1f
        labelIndexes.forEach { i ->
            val label = measurer.measure(shortDate.format(Date(times[i])), labelStyle)
            val lx = (x(i) - label.size.width / 2).coerceIn(left, size.width - label.size.width)
            if (lx > lastRight + 4.dp.toPx()) {
                drawText(label, topLeft = Offset(lx, bottom + 4.dp.toPx()))
                lastRight = lx + label.size.width
            }
        }

        clipRect(right = left + width * reveal) {
            bars?.let { b ->
                val barWidth = (slot * 0.6f).coerceAtMost(28.dp.toPx())
                b.values.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val color = if (selected == null || selected == i) b.color else b.color.copy(alpha = 0.45f)
                    val yTop = y(v)
                    drawRoundRect(
                        color,
                        Offset(x(i) - barWidth / 2, yTop),
                        Size(barWidth, (y(lo) - yTop).coerceAtLeast(2f)),
                        CornerRadius(6.dp.toPx()),
                    )
                }
            }
            lines.forEach { line -> drawSeries(line, ::x, ::y, bottom, surface) }
        }

        rules.forEach { rule ->
            val ry = y(rule.value)
            drawLine(rule.color, Offset(left, ry), Offset(size.width, ry), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            val label = measurer.measure(rule.label, labelStyle.copy(color = rule.color, fontSize = 10.sp))
            drawText(label, topLeft = Offset(size.width - label.size.width, ry - label.size.height - 1.dp.toPx()))
        }

        selected?.takeIf { it in 0 until count }?.let { i ->
            drawLine(guideColor, Offset(x(i), top), Offset(x(i), bottom), 1.dp.toPx())
            lines.forEach { line ->
                line.values.getOrNull(i)?.let { v ->
                    drawCircle(line.color, 7.dp.toPx(), Offset(x(i), y(v)))
                    drawCircle(Color.White, 3.dp.toPx(), Offset(x(i), y(v)))
                }
            }
        }
    }
}

private fun DrawScope.drawSeries(line: ChartLine, x: (Int) -> Float, y: (Float) -> Float, bottom: Float, surface: Color) {
    val path = Path()
    val area = Path()
    var started = false
    var firstX = 0f
    var lastX = 0f
    line.values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        if (!started) {
            path.moveTo(x(i), y(v))
            area.moveTo(x(i), bottom)
            area.lineTo(x(i), y(v))
            firstX = x(i)
            started = true
        } else {
            path.lineTo(x(i), y(v))
            area.lineTo(x(i), y(v))
        }
        lastX = x(i)
    }
    if (!started) return
    if (line.area) {
        area.lineTo(lastX, bottom)
        area.lineTo(firstX, bottom)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(line.color.copy(alpha = 0.28f), line.color.copy(alpha = 0f))))
    }
    drawPath(
        path,
        line.color,
        style = Stroke(
            width = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null,
        ),
    )
    if (!line.dashed) {
        line.values.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            drawCircle(line.color, 4.dp.toPx(), Offset(x(i), y(v)))
            drawCircle(surface, 2.dp.toPx(), Offset(x(i), y(v)))
        }
    }
}

private fun indexAt(px: Float, left: Float, width: Float, count: Int): Int {
    val slot = (width - left) / count
    return ((px - left) / slot).toInt().coerceIn(0, count - 1)
}

@Composable
fun Legend(items: List<Pair<Color, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    goal: ChartRule? = null,
) {
    if (values.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val reveal = rememberReveal(values)
    val maxValue = maxOf(values.max(), goal?.value ?: 0f).coerceAtLeast(1f)
    Canvas(modifier) {
        val top = 18.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val slot = size.width / values.size
        val barWidth = (slot * 0.62f).coerceAtMost(36.dp.toPx())
        fun y(v: Float) = bottom - (bottom - top) * v / maxValue
        values.forEachIndexed { i, v ->
            val x = slot * i + (slot - barWidth) / 2
            drawRoundRect(track, Offset(x, top), Size(barWidth, bottom - top), CornerRadius(8.dp.toPx()))
            val h = (bottom - y(v)) * reveal
            val current = i == values.lastIndex
            if (v > 0) {
                drawRoundRect(
                    if (current) color else color.copy(alpha = 0.55f),
                    Offset(x, bottom - h),
                    Size(barWidth, h),
                    CornerRadius(8.dp.toPx()),
                )
                val text = measurer.measure(format(v), valueStyle)
                drawText(text, topLeft = Offset(x + barWidth / 2 - text.size.width / 2, bottom - h - text.size.height))
            }
            labels.getOrNull(i)?.let {
                val label = measurer.measure(it, labelStyle)
                if (label.size.width < slot * 1.1f || i % 2 == values.lastIndex % 2) {
                    drawText(label, topLeft = Offset(slot * i + slot / 2 - label.size.width / 2, bottom + 4.dp.toPx()))
                }
            }
        }
        goal?.let { g ->
            val gy = y(g.value)
            drawLine(g.color, Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            val label = measurer.measure(g.label, labelStyle.copy(color = g.color, fontSize = 10.sp))
            drawText(label, topLeft = Offset(size.width - label.size.width, gy - label.size.height - 1.dp.toPx()))
        }
    }
}

private const val LEFT_AXIS = 44
private const val BOTTOM_AXIS = 22
