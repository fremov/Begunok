package ru.begunok.sensor

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.begunok.data.SavedSensor
import ru.begunok.data.SettingsRepository

class HeartRateMonitor(
    context: Context,
    settings: SettingsRepository,
    scanner: BleScanner,
    scope: CoroutineScope,
) {
    private val client = BleHeartRateClient(context, scanner)
    private val simulator = SensorSimulator()

    private val _state = MutableStateFlow(SensorState())
    val state: StateFlow<SensorState> = _state.asStateFlow()

    private val _measurements = MutableSharedFlow<HeartRateMeasurement>(extraBufferCapacity = 64)
    val measurements: SharedFlow<HeartRateMeasurement> = _measurements.asSharedFlow()

    @Volatile
    private var lastMeasurementAt = 0L

    private sealed interface Source {
        data object None : Source
        data object Simulation : Source
        data class Ble(val sensor: SavedSensor) : Source
    }

    init {
        scope.launch {
            settings.settings
                .map { s ->
                    when {
                        s.simulation -> Source.Simulation
                        s.sensor != null -> Source.Ble(s.sensor)
                        else -> Source.None
                    }
                }
                .distinctUntilChanged()
                .collectLatest(::runSource)
        }
        scope.launch {
            while (true) {
                delay(1_000)
                if (_state.value.heartRate != null && System.currentTimeMillis() - lastMeasurementAt > STALE_MS) {
                    _state.update { it.copy(heartRate = null) }
                }
            }
        }
    }

    private suspend fun runSource(source: Source) {
        lastMeasurementAt = 0L
        when (source) {
            Source.None -> _state.value = SensorState(status = SensorStatus.NotSelected)
            Source.Simulation -> {
                _state.value = SensorState(status = SensorStatus.Simulation, name = "Симулятор", battery = 100)
                simulator.run(::publish)
            }
            is Source.Ble -> {
                _state.value = SensorState(
                    status = SensorStatus.Searching,
                    name = source.sensor.name,
                    address = source.sensor.address,
                )
                client.run(source.sensor.address, object : BleHeartRateClient.Listener {
                    override fun onStatus(status: SensorStatus) {
                        _state.update {
                            if (status == SensorStatus.Connected) it.copy(status = status)
                            else it.copy(status = status, heartRate = null)
                        }
                    }

                    override fun onDeviceName(name: String) {
                        _state.update { it.copy(name = it.name ?: name) }
                    }

                    override fun onBattery(level: Int) {
                        _state.update { it.copy(battery = level) }
                    }

                    override fun onMeasurement(measurement: HeartRateMeasurement) = publish(measurement)
                })
            }
        }
    }

    private fun publish(measurement: HeartRateMeasurement) {
        if (measurement.bpm <= 0) return
        lastMeasurementAt = System.currentTimeMillis()
        _state.update { it.copy(heartRate = measurement.bpm) }
        _measurements.tryEmit(measurement)
    }

    private companion object {
        const val STALE_MS = 5_000L
    }
}
