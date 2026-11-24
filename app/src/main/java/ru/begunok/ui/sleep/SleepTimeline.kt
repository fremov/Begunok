package ru.begunok.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ru.begunok.health.SleepReport
import ru.begunok.health.SleepStageKind
import ru.begunok.health.fractionalSeconds
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val chartOrder = listOf(SleepStageKind.AWAKE, SleepStageKind.REM, SleepStageKind.LIGHT, SleepStageKind.DEEP,
    SleepStageKind.SLEEPING, SleepStageKind.UNKNOWN)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun clock(time: Instant, zone: ZoneId): String = time.atZone(zone).format(timeFormat)

internal fun phaseLabel(phase: SleepStageKind): String = when (phase) {
    SleepStageKind.AWAKE, SleepStageKind.OUT_OF_BED -> "Бодрствование"
    SleepStageKind.REM -> "REM"
    SleepStageKind.LIGHT -> "Лёгкий сон"
    SleepStageKind.DEEP -> "Глубокий сон"
    SleepStageKind.SLEEPING -> "Сон без детализации"
    SleepStageKind.UNKNOWN -> "Нет данных"
}

@Composable
internal fun SleepTimeline(report: SleepReport, zone: ZoneId) {
    val rows = remember(report) { chartOrder.filter { it in report.phaseDurations } }
    val spans = report.intervals
    val start = spans.first().start
    val end = spans.last().end
    val elapsed = Duration.between(start, end).fractionalSeconds()
    val from = clock(start, zone)
    val to = clock(end, zone)
    val colors = MaterialTheme.colorScheme
    val label = MaterialTheme.typography.labelSmall
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clearAndSetSemantics {
        contentDescription = "График фаз сна с $from до $to. Под графиком доступен список интервалов."
    }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            for (phase in rows) {
                val text = if (phase == SleepStageKind.SLEEPING) "Без детализации" else phaseLabel(phase)
                Text(text, Modifier.padding(vertical = 5.dp), style = label, color = colors.onSurfaceVariant)
            }
            Text("Время", style = label, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Canvas(Modifier.fillMaxWidth().weight(1f)) {
                val rowHeight = size.height / rows.size
                fun y(index: Int) = (index + .5f) * rowHeight
                fun x(instant: Instant) = (Duration.between(start, instant).fractionalSeconds() / elapsed * size.width).toFloat()
                val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                for (i in rows.indices) drawLine(colors.outlineVariant, Offset(0f, y(i)), Offset(size.width, y(i)), 1.dp.toPx())
                var prior: SleepStageKind? = null
                for (span in spans) {
                    val kind = span.kind
                    val left = x(span.start)
                    val level = y(rows.indexOf(kind))
                    if (prior != null && prior != SleepStageKind.UNKNOWN && kind != SleepStageKind.UNKNOWN) {
                        drawLine(colors.outline, Offset(left, y(rows.indexOf(prior))), Offset(left, level), 1.dp.toPx())
                    }
                    val color = when (kind) {
                        SleepStageKind.AWAKE, SleepStageKind.OUT_OF_BED -> colors.primary
                        SleepStageKind.REM -> colors.tertiary
                        SleepStageKind.LIGHT, SleepStageKind.DEEP -> colors.secondary
                        SleepStageKind.UNKNOWN, SleepStageKind.SLEEPING -> colors.onSurfaceVariant
                    }
                    drawLine(color, Offset(left, level), Offset(x(span.end), level), 4.dp.toPx(),
                        pathEffect = if (kind == SleepStageKind.UNKNOWN) dash else null)
                    prior = kind
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(from, style = label, color = colors.onSurfaceVariant)
                Text(to, style = label, color = colors.onSurfaceVariant)
            }
        }
    }
}
