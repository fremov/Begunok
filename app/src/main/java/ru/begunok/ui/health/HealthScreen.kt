package ru.begunok.ui.health

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.health.HealthAvailability
import ru.begunok.health.HealthDay
import ru.begunok.health.HealthRepository
import ru.begunok.ui.common.AnimatedNumber
import ru.begunok.ui.common.appear
import ru.begunok.ui.theme.numberStyle
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val Pulse = Color(0xFFE5486B)
private val PulseDeep = Color(0xFFB23A7A)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFormat = DateTimeFormatter.ofPattern("d.MM HH:mm")

@Composable
fun HealthScreen(viewModel: HealthViewModel) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        viewModel.refresh()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.appear(1)) {
            StatusCard(
                ui = ui,
                total = viewModel.permissions.size,
                onRequest = { request.launch(viewModel.permissions) },
                onInstall = { context.openHealthConnectStore() },
                onManage = { context.openHealthConnectSettings() },
            )
        }
        ui.day?.let { day ->
            Box(Modifier.appear(2)) { TodayCard(day) }
            Box(Modifier.appear(3)) { SourcesCard(day.totals.sources) }
        }
        Box(Modifier.appear(4)) { HowToCard() }
        if (ui.availability == HealthAvailability.Available) {
            OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Обновить")
            }
        }
    }
}

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun StatusCard(ui: HealthUi, total: Int, onRequest: () -> Unit, onInstall: () -> Unit, onManage: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(listOf(Pulse, PulseDeep)))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.MonitorHeart, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text("  HEALTH CONNECT", style = numberStyle(26), color = Color.White)
        }
        val white = Color.White.copy(alpha = 0.92f)
        when {
            ui.availability == HealthAvailability.Unavailable -> {
                Text("Health Connect не поддерживается на этом телефоне.", color = white)
            }
            ui.availability == HealthAvailability.NeedsUpdate -> {
                Text("Установите или обновите приложение Health Connect из Google Play.", color = white)
                Button(onClick = onInstall) { Text("Открыть Google Play") }
            }
            ui.granted.isEmpty() -> {
                Text(
                    "Разрешите Бегунку читать шаги, пульс, SpO2, сон, калории и тренировки. " +
                        "MyLife только читает данные и ничего не записывает.",
                    color = white,
                )
                Button(onClick = onRequest) { Text("Разрешить доступ") }
            }
            else -> {
                Text("Доступ разрешён: ${ui.granted.size} из $total типов данных", color = white)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.granted.size < total) Button(onClick = onRequest) { Text("Дать остальные") }
                    OutlinedButton(onClick = onManage) { Text("Настройки", color = Color.White) }
                }
            }
        }
    }
}

@Composable
private fun TodayCard(day: HealthDay) {
    val t = day.totals
    PanelCard {
        Text("Сегодня", style = MaterialTheme.typography.titleLarge)
        val items = listOf(
            "Шаги" to (t.steps?.toString() ?: "—"),
            "Активные ккал" to (t.activeKcal?.roundToInt()?.toString() ?: "—"),
            "Пульс ср." to (t.avgHr?.toString() ?: "—"),
            "Пульс мин–макс" to (if (t.minHr != null && t.maxHr != null) "${t.minHr}–${t.maxHr}" else "—"),
            "SpO2, %" to (day.spo2?.percent?.roundToInt()?.toString() ?: "—"),
            "Сон" to (day.sleep?.let(::formatSleep) ?: "—"),
        )
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        AnimatedNumber(value, numberStyle(30))
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val notes = listOfNotNull(
            day.lastHr?.let { "Последний пульс ${it.bpm} в ${it.time.local(timeFormat)}" },
            day.spo2?.let { "SpO2 измерен ${it.time.local(dateTimeFormat)}" },
        )
        notes.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourcesCard(sources: Set<String>) {
    val context = LocalContext.current
    PanelCard {
        Text("Откуда данные", style = MaterialTheme.typography.titleMedium)
        if (sources.isEmpty()) {
            Text(
                "За сегодня данных нет. Проверьте, что синхронизация с Health Connect включена в приложении браслета, " +
                    "и синхронизируйте браслет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sources.sorted().forEach { pkg ->
            Text("• ${context.appLabel(pkg)}", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun HowToCard() {
    PanelCard {
        Text("Как подключить браслет Xiaomi", style = MaterialTheme.typography.titleMedium)
        listOf(
            "Откройте Mi Fitness (или Zepp Life для старых Mi Band).",
            "В профиле найдите раздел сторонних сервисов и включите Health Connect, разрешите запись нужных данных.",
            "Синхронизируйте браслет в Mi Fitness, затем вернитесь сюда и нажмите «Обновить».",
        ).forEachIndexed { i, step ->
            Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Данные приходят не мгновенно, а после синхронизации браслета с его приложением. " +
                "Названия пунктов меню зависят от версии Mi Fitness.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatSleep(d: Duration): String = "%d:%02d".format(d.toHours(), d.toMinutes() % 60)

private fun Instant.local(format: DateTimeFormatter): String = atZone(ZoneId.systemDefault()).format(format)

private fun Context.appLabel(pkg: String): String = runCatching {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

private fun Context.openHealthConnectSettings() {
    runCatching { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
}

private fun Context.openHealthConnectStore() {
    val uri = "market://details?id=${HealthRepository.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding".toUri()
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending")) }
}
