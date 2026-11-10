package ru.begunok.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.begunok.BegunokApp
import ru.begunok.R
import ru.begunok.mental.MentalResult
import ru.begunok.mental.MentalTest
import ru.begunok.ui.MainActivity
import java.time.LocalDate
import java.time.ZonedDateTime

class MentalReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = NotificationManagerCompat.from(context)
    private val delivered = context.getSharedPreferences("mental_reminders", Context.MODE_PRIVATE)

    init {
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Повторные психологические тесты")
                .setDescription("Напоминание после прохождения: через 14 или 30 дней")
                .setShowBadge(false)
                .build(),
        )
    }

    fun schedule(results: List<MentalResult>, enabled: Boolean, now: ZonedDateTime = ZonedDateTime.now()) {
        MentalTest.entries.forEach { test ->
            val latest = results.firstOrNull { it.test == test }
            val notified = notifiedCompletion(test)
            alarms.cancel(alarmIntent(test, latest?.completedAt ?: 0))
            if (!enabled || latest == null || notified != latest.completedAt) notifications.cancel(requestCode(test))
            if (enabled && notificationsAllowed() && latest != null) {
                MentalReminderSchedule.nextAt(test, latest.completedAt, notified, now)?.let { next ->
                    alarms.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        next.toInstant().toEpochMilli(),
                        alarmIntent(test, latest.completedAt),
                    )
                }
            }
        }
    }

    fun deliver(result: MentalResult) {
        if (result.test.nextDue(result.completedAt).isAfter(LocalDate.now())) return
        if (notifiedCompletion(result.test) == result.completedAt) return
        if (!notificationsAllowed()) return
        val open = PendingIntent.getActivity(
            context,
            requestCode(result.test),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_MENTAL, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            requestCode(result.test),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle("Пора повторить опрос")
                .setContentText("Откройте психологические тесты, чтобы обновить результаты.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
        delivered.edit().putLong(result.test.name, result.completedAt).apply()
    }

    private fun notificationsAllowed(): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE

    private fun notifiedCompletion(test: MentalTest): Long? =
        if (delivered.contains(test.name)) delivered.getLong(test.name, 0) else null

    private fun alarmIntent(test: MentalTest, completedAt: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode(test),
        Intent(context, MentalReminderReceiver::class.java)
            .setData(Uri.parse("mylife://mental-retest/${test.name}"))
            .putExtra(EXTRA_TEST, test.name)
            .putExtra(EXTRA_COMPLETED_AT, completedAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestCode(test: MentalTest) = 4300 + test.ordinal

    companion object {
        const val EXTRA_TEST = "mental_test"
        const val EXTRA_COMPLETED_AT = "mental_completed_at"
        private const val CHANNEL = "mental_retests"
    }
}

class MentalReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val test = MentalTest.entries.firstOrNull { it.name == intent?.getStringExtra(MentalReminderScheduler.EXTRA_TEST) } ?: return
        val completedAt = intent?.getLongExtra(MentalReminderScheduler.EXTRA_COMPLETED_AT, Long.MIN_VALUE) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as BegunokApp).container
                val results = container.mental.observeResults().first()
                val settings = container.settings.settings.first()
                val latest = results.filter { it.test == test }.maxByOrNull { it.completedAt }
                if (settings.mentalReminderEnabled && latest?.completedAt == completedAt) {
                    container.mentalReminderScheduler.deliver(latest)
                }
                container.mentalReminderScheduler.schedule(results, settings.mentalReminderEnabled)
            } finally {
                pending.finish()
            }
        }
    }
}
