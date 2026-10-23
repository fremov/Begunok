package ru.begunok.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.begunok.data.db.SampleEntity
import ru.begunok.ui.Format
import ru.begunok.ui.theme.ZoneColors
import kotlin.math.ceil
import kotlin.math.floor

data class HrPoint(val seconds: Float, val bpm: Int?)

fun heartRateSeries(samples: List<SampleEntity>, maxPoints: Int = 600): List<HrPoint> {
    val points = mutableListOf<HrPoint>()
    var active = 0L
    var prev: SampleEntity? = null
    for (s in samples) {
        val p = prev
        if (p != null && !s.paused && !p.paused) active += (s.time - p.time).coerceIn(0, 5_000)
        prev = s
        if (s.paused) continue
        points += HrPoint(active / 1000f, s.heartRate)
    }
    if (points.size <= maxPoints) return points
    val bucket = ceil(points.size / maxPoints.toDouble()).toInt()
    return points.chunked(bucket).map { chunk ->
        val values = chunk.mapNotNull { it.bpm }
        HrPoint(chunk.first().seconds, if (values.isEmpty()) null else values.average().toInt())
    }
}

@Composable
fun HeartRateChart(series: List<HrPoint>, upperLimit: Int, lowerLimit: Int?, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val values = remember(series) { series.mapNotNull { it.bpm } }
    if (values.isEmpty()) return

    val minY = floor((minOf(values.min(), lowerLimit ?: upperLimit, upperLimit) - 5) / 10.0).toFloat() * 10
    val maxY = ceil((maxOf(values.max(), upperLimit) + 5) / 10.0).toFloat() * 10
    val maxX = series.last().seconds.coerceAtLeast(1f)

    Canvas(modifier) {
        val left = 36.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val width = size.width - left
        fun x(sec: Float) = left + sec / maxX * width
        fun y(bpm: Float) = bottom - (bpm - minY) / (maxY - minY) * bottom

        var tick = minY
        while (tick <= maxY) {
            drawLine(gridColor, Offset(left, y(tick)), Offset(size.width, y(tick)), strokeWidth = 1f)
            val label = measurer.measure(tick.toInt().toString(), labelStyle)
            drawText(label, topLeft = Offset(0f, y(tick) - label.size.height / 2))
            tick += if (maxY - minY > 60) 20 else 10
        }
        listOf(0f, maxX / 2, maxX).forEach { sec ->
            val label = measurer.measure(Format.duration((sec * 1000).toLong()), labelStyle)
            val lx = (x(sec) - label.size.width / 2).coerceIn(left, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, bottom + 2.dp.toPx()))
        }

        val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        drawLine(ZoneColors.Above, Offset(left, y(upperLimit.toFloat())), Offset(size.width, y(upperLimit.toFloat())), 2.dp.toPx(), pathEffect = dash)
        lowerLimit?.let {
            drawLine(ZoneColors.Below, Offset(left, y(it.toFloat())), Offset(size.width, y(it.toFloat())), 2.dp.toPx(), pathEffect = dash)
        }

        val path = Path()
        var drawing = false
        for (p in series) {
            val bpm = p.bpm
            if (bpm == null) {
                drawing = false
                continue
            }
            if (drawing) path.lineTo(x(p.seconds), y(bpm.toFloat())) else path.moveTo(x(p.seconds), y(bpm.toFloat()))
            drawing = true
        }
        drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
