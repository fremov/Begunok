package ru.begunok.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.workout.RouteColor
import ru.begunok.workout.RouteSegment
import kotlin.math.cos

private fun RouteColor.color(): Color = when (this) {
    RouteColor.Green -> ZoneColors.Normal
    RouteColor.Yellow -> Color(0xFFF9A825)
    RouteColor.Red -> ZoneColors.Above
    RouteColor.Gray -> Color(0xFF909090)
}

@Composable
fun RouteShape(segments: List<RouteSegment>, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.surface
    val points = remember(segments) { segments.flatMap { it.points } }
    if (points.isEmpty()) {
        Box(modifier.background(background), contentAlignment = Alignment.Center) {
            Text("Нет данных GPS для маршрута", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val kx = remember(points) { cos(Math.toRadians(points.map { it.lat }.average())) }

    Canvas(modifier.background(background).padding(24.dp)) {
        val xs = points.map { it.lon * kx }
        val ys = points.map { -it.lat }
        val minX = xs.min()
        val minY = ys.min()
        val spanX = (xs.max() - minX).coerceAtLeast(1e-9)
        val spanY = (ys.max() - minY).coerceAtLeast(1e-9)
        val scale = minOf(size.width / spanX, size.height / spanY)
        val offsetX = (size.width - spanX * scale) / 2
        val offsetY = (size.height - spanY * scale) / 2
        fun project(lat: Double, lon: Double) = Offset(
            (offsetX + (lon * kx - minX) * scale).toFloat(),
            (offsetY + (-lat - minY) * scale).toFloat(),
        )

        val width = 5.dp.toPx()
        val paths = segments.map { segment ->
            Path().apply {
                segment.points.forEachIndexed { i, p ->
                    val o = project(p.lat, p.lon)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            } to segment.color.color()
        }
        paths.forEach { (path, _) ->
            drawPath(path, outline, style = Stroke(width * 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        paths.forEach { (path, color) ->
            drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val radius = 7.dp.toPx()
        listOf(points.first() to ZoneColors.Normal, points.last() to ZoneColors.Above).forEach { (p, color) ->
            val o = project(p.lat, p.lon)
            drawCircle(Color.White, radius + 2.dp.toPx(), o)
            drawCircle(color, radius, o)
        }
    }
}
