package ru.begunok.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.begunok.data.AppSettings
import ru.begunok.data.SettingsRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.location.GpsStatus
import ru.begunok.location.LocationClient
import ru.begunok.sensor.HeartRateMonitor
import ru.begunok.sensor.SensorState
import ru.begunok.workout.WorkoutService
import ru.begunok.workout.WorkoutState
import ru.begunok.workout.WorkoutSummary

class HomeViewModel(
    private val appContext: Context,
    monitor: HeartRateMonitor,
    private val settings: SettingsRepository,
    private val workouts: WorkoutRepository,
    locationClient: LocationClient,
) : ViewModel() {
    val sensor: StateFlow<SensorState> = monitor.state
    val workout: StateFlow<WorkoutState> = WorkoutService.state
    val appSettings: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val gps: StateFlow<GpsStatus> =
        locationClient.status().stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), GpsStatus.Searching)

    private val _recovery = MutableStateFlow<WorkoutEntity?>(null)
    val recovery: StateFlow<WorkoutEntity?> = _recovery.asStateFlow()

    private val recovered = MutableStateFlow<WorkoutSummary?>(null)
    val summary: StateFlow<WorkoutSummary?> = combine(WorkoutService.state, recovered) { state, rec ->
        state.lastFinished ?: rec
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        checkUnfinished()
    }

    private fun checkUnfinished() {
        viewModelScope.launch {
            if (!WorkoutService.state.value.isActive) _recovery.value = workouts.unfinished()
        }
    }

    fun saveRecovered() {
        val workout = _recovery.value ?: return
        _recovery.value = null
        viewModelScope.launch {
            workouts.recover(workout)?.let { recovered.value = it }
            checkUnfinished()
        }
    }

    fun discardRecovered() {
        val workout = _recovery.value ?: return
        _recovery.value = null
        viewModelScope.launch {
            workouts.delete(workout.id)
            checkUnfinished()
        }
    }

    fun start() {
        recovered.value = null
        WorkoutService.clearLastFinished()
        WorkoutService.start(appContext)
    }

    fun pause() = WorkoutService.send(appContext, WorkoutService.ACTION_PAUSE)

    fun resume() = WorkoutService.send(appContext, WorkoutService.ACTION_RESUME)

    fun stop() = WorkoutService.send(appContext, WorkoutService.ACTION_STOP)

    fun dismissSummary() {
        recovered.value = null
        WorkoutService.clearLastFinished()
    }

    fun setUpperLimit(value: Int) {
        viewModelScope.launch { settings.setUpperLimit(value.coerceIn(MIN_LIMIT, MAX_LIMIT)) }
    }

    fun selectProgram(id: String?) {
        viewModelScope.launch { settings.selectProgram(id) }
    }

    fun dismissBatteryHint() {
        viewModelScope.launch { settings.setBatteryHintDismissed(true) }
    }

    companion object {
        const val MIN_LIMIT = 60
        const val MAX_LIMIT = 220
    }
}
