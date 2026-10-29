package ru.begunok.ui.shower

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.shower.ShowerProgression
import ru.begunok.ui.Format
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.Confetti
import ru.begunok.ui.common.RingTimer
import ru.begunok.ui.common.appear
import ru.begunok.ui.common.pressScale
import ru.begunok.ui.home.KeepScreenOn
import ru.begunok.ui.theme.ZoneColors
import ru.begunok.ui.theme.numberStyle

private val Cold = Color(0xFF2E9BEA)
private val ColdDeep = Color(0xFF2557D6)

@Composable
fun ShowerScreen(viewModel: ShowerViewModel) {
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    if (timer != null) KeepScreenOn()

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Холодная вода в конце обычного душа",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.appear(0),
            )
            val target = timer?.targetSec ?: overview?.status?.targetSec ?: ShowerProgression.START_SEC
            Box(Modifier.appear(1)) { TimerHero(target, timer) }
            Box(Modifier.appear(2)) {
                AnimatedContent(
                    targetState = timer != null,
                    transitionSpec = { (fadeIn(tween(300)) + scaleIn(initialScale = 0.95f)) togetherWith fadeOut(tween(200)) },
                    label = "showerButton",
                ) { running ->
                    if (running) StopButton(viewModel::stop) else StartShowerButton(viewModel::start)
                }
            }
            result?.let { ResultCard(it, onDismiss = viewModel::dismissResult) }
            overview?.let {
                Box(Modifier.appear(3)) { ProgressCard(it) }
                Box(Modifier.appear(4)) { WeekCard(it) }
                Box(Modifier.appear(5)) { HistoryCard(it.recent.take(HISTORY_SIZE), viewModel::delete) }
            }
            Text(
                "Ориентир — 3 минуты холодной воды в день, около 11 минут в неделю. Польза в регулярности: " +
                    "дольше 3 минут эффект почти не растёт. Если есть болезни сердца или давления, сначала посоветуйтесь с врачом.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Confetti(trigger = result?.takeIf { it.completed }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun TimerHero(target: Int, timer: ShowerTimer?) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Cold, ColdDeep)))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (timer == null) "ЦЕЛЬ" else "ОСТАЛОСЬ",
            style = numberStyle(28),
            color = Color.White,
            modifier = Modifier.fillMaxWidth(),
        )
        val remaining = timer?.remainingSec ?: target
        RingTimer(
            progress = remaining.toFloat() / target.coerceAtLeast(1),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            modifier = Modifier.size(220.dp),
        ) { AnimatedNumber(Format.duration(remaining * 1000L), numberStyle(60), color = Color.White) }
        Text(
            if (timer == null) "Включите холодную воду и нажмите «Старт»" else "Дышите медленно и ровно",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.9f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StartShowerButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .pressScale(interaction),
        shape = RoundedCornerShape(38.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ColdDeep, contentColor = Color.White),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(8.dp))
        Text("Старт", style = numberStyle(28))
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        shape = RoundedCornerShape(38.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Не выдержал, стоп", fontSize = 20.sp)
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ResultCard(result: ShowerResult, onDismiss: () -> Unit) {
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (result.completed) "Готово!" else "Записано",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (result.completed) ZoneColors.Normal else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (result.completed) {
                        "Холодная вода ${Format.duration(result.durationSec * 1000L)}"
                    } else {
                        "${Format.duration(result.durationSec * 1000L)} — цель в следующий раз останется прежней"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Закрыть") }
        }
    }
}

@Composable
private fun ProgressCard(overview: ShowerOverview) {
    val status = overview.status
    val needed = ShowerProgression.LEVEL_UP_SESSIONS
    PanelCard {
        Text("Прогрессия", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            repeat(needed) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (i < status.streak) Cold else MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            }
        }
        Text(
            if (status.atMax && status.streak >= needed) {
                "Цель 3:00 достигнута — держите её каждый день"
            } else {
                "Цель ${Format.duration(status.targetSec * 1000L)}: успешных подряд ${status.streak} из $needed"
            },
        )
        val span = (ShowerProgression.MAX_SEC - ShowerProgression.START_SEC).toFloat()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1:00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(
                progress = { ((status.targetSec - ShowerProgression.START_SEC) / span).coerceIn(0f, 1f) },
                modifier = Modifier.weight(1f),
                color = Cold,
            )
            Text("3:00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "Выдержали цель $needed раза подряд — она вырастет на ${ShowerProgression.STEP_SEC} секунд. " +
                "Не выдержали — цель останется прежней.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekCard(overview: ShowerOverview) {
    PanelCard {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Stat(Format.duration(overview.weekSec * 1000L), "за 7 дней", Modifier.weight(1f))
            Stat("${overview.dayStreak}", "дней подряд", Modifier.weight(1f))
        }
        LinearProgressIndicator(
            progress = { overview.weekSec.toFloat() / ShowerProgression.WEEKLY_GOAL_SEC },
            modifier = Modifier.fillMaxWidth(),
            color = Cold,
        )
        Text(
            "Ориентир — ${ShowerProgression.WEEKLY_GOAL_SEC / 60} минут холода в неделю",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        AnimatedNumber(value, numberStyle(34))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryCard(recent: List<ColdShowerEntity>, onDelete: (Long) -> Unit) {
    if (recent.isEmpty()) return
    PanelCard {
        Text("Последние", style = MaterialTheme.typography.titleMedium)
        recent.forEach { shower ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (shower.completed) ZoneColors.Normal else MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (shower.completed) "✓" else "✗",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(Format.dateTime(shower.time), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Format.duration(shower.durationSec * 1000L)} / ${Format.duration(shower.targetSec * 1000L)}",
                    style = numberStyle(18),
                )
                IconButton(onClick = { onDelete(shower.id) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Удалить",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

private const val HISTORY_SIZE = 10
