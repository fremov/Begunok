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
import ru.begunok.data.db.ExerciseSetEntity
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
    private var lastSegment = Int.MIN_VALUE
    private var lastCountdown = -1
    private var plan: DayPlan? = null
    private var targets: Map<String, Int> = emptyMap()
    private var stage = Stage.Run
    private var stageStartMs = 0L
    private var strength: StrengthSession? = null
    private var locationJob: Job? = null
    private var runCompleted: Boolean? = null
    private var levelUps: List<LevelUp> = emptyList()

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
            ACTION_BREAK -> toBreak(auto = false)
            ACTION_STRENGTH -> toStrength()
            ACTION_SET_DONE -> setDone(intent.getIntExtra(EXTRA_REPS, 0))
            ACTION_START_TIMED -> strengthAction { s, now -> s.startTimed(now); emptyList() }
            ACTION_SKIP_REST -> strengthAction { s, now -> s.skipRest(now) }
            ACTION_RUN_RESULT -> setRunResult(intent.getBooleanExtra(EXTRA_COMPLETED, false))
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
        plan = null
        targets = emptyMap()
        stage = Stage.Run
        stageStartMs = 0
        strength = null
        runCompleted = null
        levelUps = emptyList()
        lastSegment = Int.MIN_VALUE
        lastCountdown = -1
        _state.value = initial

        session = lifecycleScope.launch {
            locationJob = launch {
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
            val dayPlan = settings.dayType?.let(settings::dayPlan)
            plan = dayPlan
            program = dayPlan?.runProgram(settings.selectedProgram) ?: settings.selectedProgram
            if (dayPlan != null) {
                val last = container.workouts.observeLastSessions(dayPlan.strength.exercises.map { it.id }).first()
                targets = Progression.targets(dayPlan.strength, last)
            }
            alerts = AlertPlayer(this@WorkoutService, lifecycleScope, settings.voice.engine, container.voicePack)
            launch { container.settings.settings.collect { settings = it } }
            val id = container.workouts.create(startTime, settings.upperLimit, settings.lowerLimit, program?.name, dayPlan?.type)
            workoutId = id

            val origin = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val hr = container.heartRateMonitor.state.value.heartRate
                t.tick(now, hr)
                handleAlerts(t, hr, now)
                handleStrengthTick(t)
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

    private fun toBreak(auto: Boolean) {
        val t = tracker ?: return
        if (plan == null || stage != Stage.Run) return
        stage = Stage.Break
        stageStartMs = t.activeMs
        hrAlarm.reset()
        if (auto) alerts?.play(Cues.runFinished, settings.alertOptions())
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun toStrength() {
        val t = tracker ?: return
        val p = plan ?: return
        if (stage == Stage.Strength) return
        if (t.paused) t.resume(SystemClock.elapsedRealtime())
        stage = Stage.Strength
        stageStartMs = t.activeMs
        locationJob?.cancel()
        locationJob = null
        val session = StrengthSession(p.strength, targets)
        strength = session
        handleStrength(session.begin(t.activeMs / 1000))
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun setDone(reps: Int) = strengthAction { s, now ->
        s.completeSet(s.timedElapsed(now) ?: reps, now)
    }

    private fun strengthAction(action: (StrengthSession, Long) -> List<StrengthEvent>) {
        val t = tracker ?: return
        val s = strength ?: return
        handleStrength(action(s, t.activeMs / 1000))
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun handleStrengthTick(t: WorkoutTracker) {
        val s = strength ?: return
        if (t.paused) return
        val now = t.activeMs / 1000
        handleStrength(s.tick(now))
        val remaining = s.remainingSec(now)
        if (remaining != null && remaining in 1..3 && remaining != lastCountdown) {
            lastCountdown = remaining
            alerts?.play(Cues.countdown, settings.alertOptions().copy(voiceEnabled = false, vibration = false))
        }
    }

    private fun handleStrength(events: List<StrengthEvent>) {
        if (events.isEmpty()) return
        lastCountdown = -1
        val options = settings.alertOptions()
        for (event in events) {
            when (event) {
                is StrengthEvent.SetDone -> saveSet(event)
                is StrengthEvent.Work -> alerts?.play(
                    Cues.exercise(event.exercise, event.target, event.set, event.sets, event.circuit, event.firstInRound),
                    options,
                )
                is StrengthEvent.Rest -> alerts?.play(Cues.rest(event.sec, event.next, event.sameExercise), options)
                StrengthEvent.Finished -> {
                    alerts?.play(Cues.programFinished, options)
                    applyLevelUps()
                }
            }
        }
    }

    private fun applyLevelUps() {
        val p = plan ?: return
        val s = strength ?: return
        levelUps = Progression.levelUps(p.strength, s.doneSets())
        if (levelUps.isEmpty()) return
        val repository = container.settings
        val changes = levelUps.map { it.from.variationId to it.to.id }
        container.appScope.launch {
            changes.forEach { (from, to) -> repository.replaceVariation(p.type, from, to) }
        }
    }

    private fun setRunResult(completed: Boolean) {
        val t = tracker ?: return
        val id = workoutId ?: return
        runCompleted = completed
        val repository = container.workouts
        container.appScope.launch { repository.setProgramCompleted(id, completed) }
        publish(t, container.heartRateMonitor.state.value.heartRate)
    }

    private fun saveSet(event: StrengthEvent.SetDone) {
        val id = workoutId ?: return
        val entity = ExerciseSetEntity(
            workoutId = id,
            time = System.currentTimeMillis(),
            exerciseId = event.exercise.id,
            exerciseName = event.exercise.name,
            setNumber = event.number,
            reps = event.set.reps,
            target = event.set.target,
            timed = event.exercise.timed,
        )
        val repository = container.workouts
        container.appScope.launch { repository.addSet(entity) }
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
        if (t.paused || stage != Stage.Run) {
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
                if (position.finished) {
                    onProgramCompleted()
                    if (plan != null) {
                        toBreak(auto = true)
                        return
                    }
                }
                val segmentSec = when (position.kind) {
                    IntervalKind.Warmup -> p.warmupSec
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
        val lowEnabled = position?.kind != IntervalKind.Walk && position?.kind != IntervalKind.Warmup
        hrAlarm.update(now, hr, settings.upperLimit, settings.lowerLimit, lowEnabled)
            ?.let { player.play(Cues.hr(it), options) }
    }

    private fun onProgramCompleted() {
        runCompleted = true
        val id = workoutId ?: return
        val repository = container.workouts
        container.appScope.launch { repository.setProgramCompleted(id, true) }
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
            interval = program?.takeIf { stage == Stage.Run }?.positionAt(t.activeMs / 1000),
            day = plan?.type,
            stage = stage,
            stageStartMs = stageStartMs,
            strength = strength?.view(t.activeMs / 1000),
            runCompleted = runCompleted,
            levelUps = levelUps,
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
        const val ACTION_BREAK = "ru.begunok.workout.BREAK"
        const val ACTION_STRENGTH = "ru.begunok.workout.STRENGTH"
        const val ACTION_SET_DONE = "ru.begunok.workout.SET_DONE"
        const val ACTION_START_TIMED = "ru.begunok.workout.START_TIMED"
        const val ACTION_SKIP_REST = "ru.begunok.workout.SKIP_REST"
        private const val ACTION_RUN_RESULT = "ru.begunok.workout.RUN_RESULT"
        private const val EXTRA_REPS = "reps"
        private const val EXTRA_COMPLETED = "completed"

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

        fun setRunResult(context: Context, completed: Boolean) {
            context.startService(intent(context, ACTION_RUN_RESULT).putExtra(EXTRA_COMPLETED, completed))
        }

        fun setDone(context: Context, reps: Int) {
            context.startService(intent(context, ACTION_SET_DONE).putExtra(EXTRA_REPS, reps))
        }

        fun clearLastFinished() {
            _state.value = _state.value.copy(lastFinished = null)
        }

        private fun intent(context: Context, action: String) =
            Intent(context, WorkoutService::class.java).setAction(action)
    }
}
