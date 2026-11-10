package ru.begunok.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.begunok.BegunokApp
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
                val settings = SettingsRepository(context.applicationContext).settings.first()
                ReminderScheduler(context).schedule(settings)
                val container = (context.applicationContext as BegunokApp).container
                container.mentalReminderScheduler.schedule(
                    container.mental.observeResults().first(),
                    settings.mentalReminderEnabled,
                )
            } finally {
                pending.finish()
            }
        }
    }
}
