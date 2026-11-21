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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.begunok.reminders.ReminderScheduler
import ru.begunok.system.Permissions

@Composable
internal fun ReminderPermissionNotice() {
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
    val channelBlocked = remember(revision) {
        manager.getNotificationChannel(ReminderScheduler.CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
    }
    if (!granted || !appEnabled || channelBlocked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android блокирует часть напоминаний. Разрешите уведомления в системных настройках.")
            if (!granted) {
                TextButton(onClick = {
                    requested = true
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Разрешить уведомления") }
            }
            TextButton(onClick = { openReminderSettings(context, null) }) { Text("Настройки уведомлений") }
            if (channelBlocked) {
                TextButton(onClick = { openReminderSettings(context, ReminderScheduler.CHANNEL_ID) }) {
                    Text("Разрешить: Напоминания")
                }
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
