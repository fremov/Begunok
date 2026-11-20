package ru.begunok.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.begunok.analytics.ANALYTICS_USB_ENDPOINT
import ru.begunok.analytics.AnalyticsSync
import ru.begunok.analytics.AnalyticsSyncState.Phase
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun AnalyticsSyncSettings(sync: AnalyticsSync) {
    val state by sync.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    Text(
        "Отправляет сохранённые показатели на ваш частный сервер. Заметки и отдельные ответы на вопросы не отправляются.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        when (state.phase) {
            Phase.Loading -> "Чтение настроек…"
            Phase.Disabled -> "Выключено"
            Phase.Queued -> "Ожидает запуска"
            Phase.Running -> "Синхронизация…"
            Phase.Synced -> "Синхронизация завершена"
            Phase.Retry -> "Повторится автоматически"
            Phase.Failed -> "Синхронизация не завершена"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    state.lastSuccess?.let { time ->
        val local = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(time)
        Text("Последняя успешная проверка: $local", style = MaterialTheme.typography.bodySmall)
    }
    state.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (state.endpoint != null) {
        OutlinedButton(
            enabled = state.phase != Phase.Running,
            onClick = { scope.launch { sync.force() } },
        ) { Text("Отправить сейчас") }
    }
    if (state.phase != Phase.Loading) {
        TextButton(onClick = { editing = true }) { Text(if (state.endpoint == null) "Подключить" else "Настроить") }
    }
    if (editing) {
        var endpoint by remember { mutableStateOf(state.endpoint ?: ANALYTICS_USB_ENDPOINT) }
        var token by remember { mutableStateOf("") }
        var usb by remember { mutableStateOf(state.usbFallback) }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!saving) editing = false },
            title = { Text("Частный сервер аналитики") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it; error = null },
                        label = { Text("Адрес сервера") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; error = null },
                        label = { Text("Токен отправки") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !saving,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (endpoint.trim().trimEnd('/') != ANALYTICS_USB_ENDPOINT) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Резерв через USB")
                                Text("Требуется adb reverse", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = usb, onCheckedChange = { usb = it }, enabled = !saving)
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.endpoint != null) {
                        TextButton(enabled = !saving, onClick = {
                            editing = false
                            disconnecting = true
                        }) { Text("Отключить отправку") }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !saving && token.isNotBlank() && endpoint.isNotBlank(), onClick = {
                    saving = true
                    scope.launch {
                        error = sync.configure(endpoint, token, usb)
                        saving = false
                        if (error == null) editing = false
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(enabled = !saving, onClick = { editing = false }) { Text("Отмена") } },
        )
    }
    if (disconnecting) {
        AlertDialog(
            onDismissRequest = { disconnecting = false },
            title = { Text("Отключить отправку?") },
            text = { Text("Настройки подключения будут удалены с телефона. Уже отправленные показатели останутся на сервере.") },
            confirmButton = { TextButton(onClick = {
                disconnecting = false
                scope.launch { sync.disable() }
            }) { Text("Отключить") } },
            dismissButton = { TextButton(onClick = { disconnecting = false }) { Text("Отмена") } },
        )
    }
}
