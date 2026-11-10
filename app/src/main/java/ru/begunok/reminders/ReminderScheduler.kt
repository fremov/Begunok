package ru.begunok.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.begunok.R
import ru.begunok.data.AppSettings
import ru.begunok.data.SettingsRepository
import ru.begunok.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    init {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Напоминания")
                .setDescription("Взвешивание, разминки и быстрые отметки состояния")
                .setShowBadge(false)
                .build(),
        )
    }

    fun schedule(settings: AppSettings, now: LocalDateTime = LocalDateTime.now()) {
        cancelAll()
        if (settings.weightReminderEnabled) {
            schedule(Kind.Weight, 0, ReminderSchedule.nextWeight(now))
        }
        if (settings.movementReminderEnabled) {
            ReminderSchedule.MOVEMENT_TIMES.indices.forEach { slot ->
                schedule(Kind.Movement, slot, ReminderSchedule.nextMovement(slot, now))
            }
        }
        if (settings.checkInReminderEnabled) {
            schedule(Kind.CheckInMorning, 0, ReminderSchedule.nextCheckIn(now, settings.checkInMorningMinute))
            schedule(Kind.CheckInEvening, 0, ReminderSchedule.nextCheckIn(now, settings.checkInEveningMinute))
        }
    }

    private fun schedule(kind: Kind, slot: Int, dateTime: LocalDateTime) {
        val triggerAt = dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent(kind, slot),
        )
    }

    private fun cancelAll() {
        alarmManager.cancel(pendingIntent(Kind.Weight, 0))
        ReminderSchedule.MOVEMENT_TIMES.indices.forEach { slot -> alarmManager.cancel(pendingIntent(Kind.Movement, slot)) }
        alarmManager.cancel(pendingIntent(Kind.CheckInMorning, 0))
        alarmManager.cancel(pendingIntent(Kind.CheckInEvening, 0))
    }

    private fun pendingIntent(kind: Kind, slot: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        kind.requestCode(slot),
        Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_SLOT, slot),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    enum class Kind {
        Weight,
        Movement,
        CheckInMorning,
        CheckInEvening,
        ;

        fun requestCode(slot: Int): Int = when (this) {
            Weight -> WEIGHT_REQUEST
            Movement -> WALK_REQUEST_BASE + slot
            CheckInMorning -> 4400
            CheckInEvening -> 4401
        }
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_SLOT = "slot"
        private const val WEIGHT_REQUEST = 4100
        private const val WALK_REQUEST_BASE = 4200
        private const val CHANNEL_ID = "reminders"

        fun notification(context: Context, kind: Kind, slot: Int): android.app.Notification {
            val (title, text, id) = when (kind) {
                Kind.Weight -> Triple("Время взвеситься", "Взвесьтесь утром и запишите результат.", 4100)
                Kind.Movement -> Triple("Пора размяться", "Встаньте и немного походите после сидения.", 4200 + slot)
                Kind.CheckInMorning, Kind.CheckInEvening -> Triple(
                    "Как ты себя сейчас чувствуешь?",
                    "Отметь настроение, напряжение и энергию.",
                    kind.requestCode(slot),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_MENTAL, kind == Kind.CheckInMorning || kind == Kind.CheckInEvening),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val kind = intent?.getStringExtra(ReminderScheduler.EXTRA_KIND)
            ?.let { runCatching { ReminderScheduler.Kind.valueOf(it) }.getOrNull() }
            ?: return
        val slot = intent.getIntExtra(ReminderScheduler.EXTRA_SLOT, 0)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(context.applicationContext).settings.first()
                val enabled = when (kind) {
                    ReminderScheduler.Kind.Weight -> settings.weightReminderEnabled
                    ReminderScheduler.Kind.Movement -> settings.movementReminderEnabled
                    ReminderScheduler.Kind.CheckInMorning, ReminderScheduler.Kind.CheckInEvening -> settings.checkInReminderEnabled
                }
                if (enabled && BuildConfigCompat.notificationsAllowed(context)) {
                    NotificationManagerCompat.from(context).notify(
                        kind.requestCode(slot),
                        ReminderScheduler.notification(context, kind, slot),
                    )
                }
                ReminderScheduler(context).schedule(settings, LocalDateTime.now())
            } finally {
                pending.finish()
            }
        }
    }
}

private object BuildConfigCompat {
    fun notificationsAllowed(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
}
