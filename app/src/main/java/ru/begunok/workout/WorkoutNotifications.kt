package ru.begunok.workout

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import ru.begunok.R
import ru.begunok.system.Permissions
import ru.begunok.ui.Format
import ru.begunok.ui.MainActivity

class WorkoutNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Тренировка")
                .setDescription("Время, пульс и дистанция во время тренировки")
                .setShowBadge(false)
                .build()
        )
    }

    fun build(state: WorkoutState): Notification {
        val paused = state.phase == WorkoutPhase.Paused
        val interval = state.interval?.takeIf { !it.finished }?.let {
            "${if (it.kind == IntervalKind.Run) "Бег" else "Шаг"} ${Format.duration(it.remainingSec * 1000L)} (${it.repeat}/${it.repeats}) · "
        }.orEmpty()
        val title = (if (paused) "Пауза · " else "") + interval + Format.duration(state.activeMs)
        val text = "♥ ${Format.heartRate(state.heartRate)} · ${Format.distanceKm(state.distanceM)} км"
        val toggle = if (paused) {
            NotificationCompat.Action(0, "Продолжить", servicePendingIntent(WorkoutService.ACTION_RESUME))
        } else {
            NotificationCompat.Action(0, "Пауза", servicePendingIntent(WorkoutService.ACTION_PAUSE))
        }
        val stop = PendingIntent.getActivity(
            context,
            REQUEST_STOP,
            MainActivity.intent(context, confirmStop = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            MainActivity.intent(context, confirmStop = false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(toggle)
            .addAction(NotificationCompat.Action(0, "Стоп", stop))
            .build()
    }

    @SuppressLint("MissingPermission")
    fun update(state: WorkoutState) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Permissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return
        manager.notify(NOTIFICATION_ID, build(state))
    }

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode(),
        Intent(context, WorkoutService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "workout"
        private const val REQUEST_OPEN = 10
        private const val REQUEST_STOP = 11
    }
}
