package ru.begunok.ui.shower

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.begunok.alerts.AlertPlayer
import ru.begunok.alerts.Cues
import ru.begunok.alerts.VoicePack
import ru.begunok.data.AppSettings
import ru.begunok.data.SettingsRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.shower.ShowerProgression
import ru.begunok.shower.ShowerSession
import ru.begunok.shower.ShowerStatus

data class ShowerOverview(
    val status: ShowerStatus,
    val weekSec: Int,
    val dayStreak: Int,
    val recent: List<ColdShowerEntity>,
)

data class ShowerTimer(val targetSec: Int, val elapsedSec: Int) {
    val remainingSec: Int get() = (targetSec - elapsedSec).coerceAtLeast(0)
}

data class ShowerResult(val durationSec: Int, val completed: Boolean)

class ShowerViewModel(
    private val appContext: Context,
    private val showers: ShowerRepository,
    private val settings: SettingsRepository,
    private val voicePack: VoicePack,
) : ViewModel() {
    val overview: StateFlow<ShowerOverview?> = showers.observeRecent().map { recent ->
        val sessions = recent.map { ShowerSession(it.time, it.durationSec, it.targetSec, it.completed) }
        val now = System.currentTimeMillis()
        ShowerOverview(
            status = ShowerProgression.status(sessions),
            weekSec = ShowerProgression.weekSec(sessions, now),
            dayStreak = ShowerProgression.dayStreak(sessions, now),
            recent = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _timer = MutableStateFlow<ShowerTimer?>(null)
    val timer: StateFlow<ShowerTimer?> = _timer.asStateFlow()

    private val _result = MutableStateFlow<ShowerResult?>(null)
    val result: StateFlow<ShowerResult?> = _result.asStateFlow()

    private var job: Job? = null
    private var startedAt = 0L
    private var player: AlertPlayer? = null
    private var playerEngine: String? = null

    fun start() {
        if (job != null) return
        _result.value = null
        job = viewModelScope.launch {
            val target = overview.filterNotNull().first().status.targetSec
            val prefs = settings.settings.first()
            val alerts = playerFor(prefs.voice.engine)
            startedAt = SystemClock.elapsedRealtime()
            _timer.value = ShowerTimer(target, 0)
            alerts.play(Cues.showerStart(target), prefs.alertOptions())
            while (isActive) {
                val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                val timer = ShowerTimer(target, elapsed)
                if (timer.elapsedSec != _timer.value?.elapsedSec) onSecond(timer, alerts, prefs)
                _timer.value = timer
                if (timer.remainingSec == 0) break
                delay(TICK_MS - (SystemClock.elapsedRealtime() - startedAt) % TICK_MS)
            }
            finish(target, target)
        }
    }

    fun stop() {
        val timer = _timer.value ?: return
        job?.cancel()
        val elapsed = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
        finish(timer.targetSec, elapsed.coerceAtMost(timer.targetSec))
    }

    fun dismissResult() {
        _result.value = null
    }

    fun delete(id: Long) {
        viewModelScope.launch { showers.delete(id) }
    }

    private fun onSecond(timer: ShowerTimer, alerts: AlertPlayer, prefs: AppSettings) {
        val options = prefs.alertOptions()
        when (timer.remainingSec) {
            0 -> alerts.play(Cues.showerDone, options)
            30 -> if (timer.targetSec > 30) alerts.play(Cues.showerHalfMinute, options)
            in 1..3 -> alerts.play(Cues.countdown, options.copy(voiceEnabled = false, vibration = false))
        }
    }

    private fun finish(target: Int, durationSec: Int) {
        job = null
        _timer.value = null
        if (durationSec < MIN_SAVED_SEC) return
        val completed = durationSec >= target
        _result.value = ShowerResult(durationSec, completed)
        viewModelScope.launch { showers.add(System.currentTimeMillis(), durationSec, target, completed) }
    }

    private fun playerFor(engine: String?): AlertPlayer {
        val current = player
        if (current != null && playerEngine == engine) return current
        current?.release()
        return AlertPlayer(appContext, viewModelScope, engine, voicePack).also {
            player = it
            playerEngine = engine
        }
    }

    override fun onCleared() {
        player?.release()
        player = null
    }

    private companion object {
        const val TICK_MS = 250L
        const val MIN_SAVED_SEC = 5
    }
}
