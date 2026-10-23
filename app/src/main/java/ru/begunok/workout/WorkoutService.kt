package ru.begunok.workout

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.begunok.AppContainer
import ru.begunok.BegunokApp
import ru.begunok.alerts.AlertOptions
import ru.begunok.alerts.AlertPlayer
import ru.begunok.alerts.Cues
import ru.begunok.data.AppSettings
import ru.begunok.data.db.RrIntervalEntity
import ru.begunok.data.db.SampleEntity
import ru.begunok.location.FixQuality
import ru.begunok.location.GpsStatus
import ru.begunok.location.elapsedMs
import ru.begunok.system.Permissions

class WorkoutService : LifecycleService() {
    private val container: AppContainer get() = (application as BegunokApp).container
    private lateinit var notifications: WorkoutNotifications

    private var session: Job? = null
    private var tracker: WorkoutTracker? = null
    private var workoutId: Long? = null
    private var startTime = 0L
    private val fixQuality = FixQuality()
    private val pendingRr = mutableListOf<Pair<Long, Int>>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var alerts: AlertPlayer? = null
    private val hrAlarm = HrAlarm()
    private var settings = AppSettings()
    private var program: IntervalProgram? = null
    private var lastSegment = -1
    private var lastCountdown = -1

    override fun onCreate() {
        super.onCreate()
        notifications = WorkoutNotifications(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            else -> if (tracker == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (tracker != null) return
        startTime = System.currentTimeMillis()
        val initial = WorkoutState(phase = WorkoutPhase.Running, startTime = startTime)
        if (!startInForeground(initial)) {
            stopSelf()
            return
        }
        acquireWakeLock()
        val t = WorkoutTracker()
        tracker = t
        fixQuality.reset()
        pendingRr.clear()
        hrAlarm.reset()
        program = null
        lastSegment = -1
        lastCountdown = -1
        _state.value = initial

        session = lifecycleScope.launch {
            launch {
                container.locationClient.updates().collect { location ->
                    fixQuality.onLocation(location)
                    if (location.hasAccuracy()) {
                        t.onFix(Fix(location.elapsedMs(), location.latitude, location.longitude, location.accuracy))
                    }
                }
            }
            launch {
                container.heartRateMonitor.measurements.collect { m ->
                    m.rrIntervalsMs.forEach { pendingRr += m.timestampMs to it }
                }
            }
            settings = container.settings.settings.first()
            program = settings.selectedProgram
            alerts = AlertPlayer(this@WorkoutService, lifecycleScope, settings.voice.engine, container.voicePack)
            launch { container.settings.settings.collect { settings = it } }
            val id = container.workouts.create(startTime, settings.upperLimit, settings.lowerLimit, program?.name)
            workoutId = id

            val origin = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val hr = container.heartRateMonitor.state.value.heartRate
                t.tick(now, hr)
                handleAlerts(t, hr, now)
                persist(id, t, now, hr)
                publish(t, hr)
                delay(TICK_MS - (SystemClock.elapsedRealtime() - origin) % TICK_MS)
            }
        }
    }

    private fun pause() {
        val t = tracker ?: return
        t.pause(SystemClock.elapsedRealtime())
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun resume() {
        val t = tracker ?: return
        t.resume(SystemClock.elapsedRealtime())
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun stop() {
        val t = tracker
        if (t == null) {
            stopSelf()
            return
        }
        session?.cancel()
        session = null
        t.tick(SystemClock.elapsedRealtime(), null)
        val id = workoutId
        val summary = id?.let {
            WorkoutSummary(it, startTime, t.activeMs, t.distanceM, t.avgHr, t.maxHr)
        }
        val rr = id?.let { drainRr(it) }.orEmpty()
        val endTime = System.currentTimeMillis()
        tracker = null
        workoutId = null
        releaseAlerts()
        _state.value = WorkoutState(lastFinished = summary)

        val repository = container.workouts
        container.appScope.launch {
            repository.addRrIntervals(rr)
            if (summary != null) repository.finish(summary, endTime)
        }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        session?.cancel()
        if (tracker != null) _state.value = WorkoutState()
        tracker = null
        releaseAlerts()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun handleAlerts(t: WorkoutTracker, hr: Int?, now: Long) {
        val player = alerts ?: return
        if (t.paused) {
            hrAlarm.reset()
            return
        }
        val options = settings.alertOptions()
        val p = program
        val position = p?.positionAt(t.activeMs / 1000)
        if (p != null && position != null) {
            if (position.segmentIndex != lastSegment) {
                lastSegment = position.segmentIndex
                lastCountdown = -1
                val segmentSec = when (position.kind) {
                    IntervalKind.Run -> p.runSec
                    IntervalKind.Walk -> p.walkSec
                    null -> 0
                }
                player.play(Cues.interval(position, segmentSec, p.isContinuous), options)
            } else if (!position.finished && position.remainingSec in 1..3 && position.remainingSec != lastCountdown) {
                lastCountdown = position.remainingSec
                player.play(Cues.countdown, options.copy(voiceEnabled = false, vibration = false))
            }
        }
        val lowEnabled = position?.kind != IntervalKind.Walk
        hrAlarm.update(now, hr, settings.upperLimit, settings.lowerLimit, lowEnabled)
            ?.let { player.play(Cues.hr(it), options) }
    }

    private fun releaseAlerts() {
        alerts?.release()
        alerts = null
    }

    private fun persist(id: Long, t: WorkoutTracker, now: Long, hr: Int?) {
        val fix = t.lastFix?.takeIf { now - it.timeMs <= FIX_FOR_SAMPLE_MS }
        val sample = SampleEntity(
            workoutId = id,
            time = System.currentTimeMillis(),
            heartRate = hr,
            latitude = fix?.lat,
            longitude = fix?.lon,
            accuracy = fix?.accuracy,
            speed = t.currentSpeedMps,
            paused = t.paused,
        )
        val rr = drainRr(id)
        val repository = container.workouts
        container.appScope.launch {
            repository.addSample(sample)
            repository.addRrIntervals(rr)
        }
    }

    private fun drainRr(id: Long): List<RrIntervalEntity> {
        val result = pendingRr.map { (time, rr) -> RrIntervalEntity(workoutId = id, time = time, rrMs = rr) }
        pendingRr.clear()
        return result
    }

    private fun publish(t: WorkoutTracker, hr: Int?) {
        val previous = _state.value
        val state = WorkoutState(
            phase = if (t.paused) WorkoutPhase.Paused else WorkoutPhase.Running,
            workoutId = workoutId,
            startTime = startTime,
            activeMs = t.activeMs,
            distanceM = t.distanceM,
            speedMps = t.currentSpeedMps,
            avgPaceSecPerKm = t.avgPaceSecPerKm,
            heartRate = hr,
            avgHr = t.avgHr,
            maxHr = t.maxHr,
            gps = GpsStatus.of(this, fixQuality),
            programName = program?.name,
            interval = program?.positionAt(t.activeMs / 1000),
            route = if (previous.route.size == t.route.size) previous.route else t.route.toList(),
        )
        _state.value = state
        notifications.update(state)
    }

    private fun startInForeground(state: WorkoutState): Boolean {
        var type = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Permissions.hasFineLocation(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Permissions.canConnect(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return runCatching {
            ServiceCompat.startForeground(this, WorkoutNotifications.NOTIFICATION_ID, notifications.build(state), type)
        }.isSuccess
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Begunok:workout")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_START = "ru.begunok.workout.START"
        const val ACTION_PAUSE = "ru.begunok.workout.PAUSE"
        const val ACTION_RESUME = "ru.begunok.workout.RESUME"
        const val ACTION_STOP = "ru.begunok.workout.STOP"

        private const val TICK_MS = 1_000L
        private const val FIX_FOR_SAMPLE_MS = 3_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60_000L

        private val _state = MutableStateFlow(WorkoutState())
        val state: StateFlow<WorkoutState> = _state.asStateFlow()

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        fun send(context: Context, action: String) {
            context.startService(intent(context, action))
        }

        fun clearLastFinished() {
            _state.value = _state.value.copy(lastFinished = null)
        }

        private fun intent(context: Context, action: String) =
            Intent(context, WorkoutService::class.java).setAction(action)
    }
}
