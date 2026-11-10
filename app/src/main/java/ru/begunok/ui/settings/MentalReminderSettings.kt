package ru.begunok.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.begunok.system.Permissions
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CheckInReminderTimes(morningMinute: Int, eveningMinute: Int, onChange: (Boolean, Int) -> Unit) {
    var editingMorning by rememberSaveable { mutableStateOf<Boolean?>(null) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { editingMorning = true }, modifier = Modifier.weight(1f)) {
            Text("Утро · ${formatMinute(morningMinute)}")
        }
        OutlinedButton(onClick = { editingMorning = false }, modifier = Modifier.weight(1f)) {
            Text("Вечер · ${formatMinute(eveningMinute)}")
        }
    }
    editingMorning?.let { morning ->
        key(morning) {
            val minute = if (morning) morningMinute else eveningMinute
            val picker = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
            AlertDialog(
                onDismissRequest = { editingMorning = null },
                title = { Text(if (morning) "Утренняя отметка" else "Вечерняя отметка") },
                text = { TimeInput(state = picker) },
                confirmButton = {
                    TextButton(onClick = {
                        onChange(morning, picker.hour * 60 + picker.minute)
                        editingMorning = null
                    }) { Text("Сохранить") }
                },
                dismissButton = { TextButton(onClick = { editingMorning = null }) { Text("Отмена") } },
            )
        }
    }
}

@Composable
internal fun MentalReminderPermissionNotice(periodicEnabled: Boolean, checkInEnabled: Boolean) {
    if (!periodicEnabled && !checkInEnabled) return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var revision by remember { mutableStateOf(0) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++ }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val granted = remember(revision) {
        Build.VERSION.SDK_INT < 33 || Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(granted) {
        if (!granted && !requested) {
            requested = true
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    val appEnabled = remember(revision) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val blockedChannels = remember(revision, periodicEnabled, checkInEnabled) {
        buildList {
            if (periodicEnabled && manager.getNotificationChannel("mental_retests")?.importance == NotificationManager.IMPORTANCE_NONE) {
                add("mental_retests" to "Повторные психологические тесты")
            }
            if (checkInEnabled && manager.getNotificationChannel("reminders")?.importance == NotificationManager.IMPORTANCE_NONE) {
                add("reminders" to "Быстрые отметки состояния")
            }
        }
    }
    if (!granted || !appEnabled || blockedChannels.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android блокирует часть напоминаний. Разрешите уведомления в системных настройках.")
            if (!granted) {
                TextButton(onClick = {
                    requested = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Разрешить уведомления") }
            }
            TextButton(onClick = { openReminderSettings(context, null) }) { Text("Настройки уведомлений") }
            blockedChannels.forEach { (id, title) ->
                TextButton(onClick = { openReminderSettings(context, id) }) { Text("Разрешить: $title") }
            }
        }
    }
}

private fun openReminderSettings(context: Context, channel: String?) {
    val intent = Intent(if (channel == null) Settings.ACTION_APP_NOTIFICATION_SETTINGS else Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (channel != null) intent.putExtra(Settings.EXTRA_CHANNEL_ID, channel)
    context.startActivity(intent)
}

private fun formatMinute(value: Int): String = String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)
