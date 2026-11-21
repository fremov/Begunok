package ru.begunok.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.begunok.data.SettingsRepository

class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
            )
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val repository = SettingsRepository(context.applicationContext)
                if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    removeRetiredReminders(context)
                    repository.removeRetiredReminderPreferences()
                }
                ReminderScheduler(context).schedule(repository.settings.first())
            } finally {
                pending.finish()
            }
        }
    }

    private fun removeRetiredReminders(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        // The retired daily check-ins shared the general reminders receiver and channel.
        val receiver = Intent(context, ReminderReceiver::class.java)
        for (id in 4400..4401) {
            PendingIntent.getBroadcast(context, id, receiver, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                ?.let { alarms.cancel(it) }
            notifications.cancel(id)
        }
        notifications.deleteNotificationChannel("mental_retests")
        context.deleteSharedPreferences("mental_reminders")
    }
}
