package ru.begunok.ui.sensor

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.begunok.data.SettingsRepository
import ru.begunok.sensor.BleScanner
import ru.begunok.sensor.HeartRateMonitor
import ru.begunok.sensor.SensorState
import ru.begunok.sensor.SensorStatus
import ru.begunok.system.Permissions
import ru.begunok.ui.common.BluetoothGate
import ru.begunok.ui.common.LocationServiceGate
import ru.begunok.ui.common.PermissionGate
import ru.begunok.ui.common.SensorBadge

data class FoundDevice(val address: String, val name: String?, val rssi: Int)

class SensorViewModel(
    monitor: HeartRateMonitor,
    private val scanner: BleScanner,
    private val settings: SettingsRepository,
) : ViewModel() {
    val sensor: StateFlow<SensorState> = monitor.state

    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var scanJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        _error.value = null
        _scanning.value = true
        scanJob = viewModelScope.launch {
            withTimeoutOrNull(SCAN_DURATION_MS) {
                scanner.scanHeartRateSensors()
                    .catch { _error.value = "Не удалось начать поиск. Проверьте Bluetooth и разрешения." }
                    .collect { result ->
                        val name = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName
                        val device = FoundDevice(result.device.address, name, result.rssi)
                        _devices.update { list ->
                            (list.filterNot { it.address == device.address } + device).sortedByDescending { it.rssi }
                        }
                    }
            }
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _scanning.value = false
    }

    fun select(device: FoundDevice) {
        stopScan()
        viewModelScope.launch {
            settings.setSimulation(false)
            settings.setSensor(device.address, device.name)
        }
    }

    fun forget() {
        viewModelScope.launch { settings.forgetSensor() }
    }

    private companion object {
        const val SCAN_DURATION_MS = 20_000L
    }
}

@Composable
fun SensorScreen(viewModel: SensorViewModel, onBack: () -> Unit) {
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    DisposableEffect(Unit) { onDispose { viewModel.stopScan() } }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
            Text("Датчик пульса", style = MaterialTheme.typography.titleLarge)
        }
        SensorContent(sensor, devices, scanning, error, viewModel)
    }
}

@Composable
private fun SensorContent(
    sensor: SensorState,
    devices: List<FoundDevice>,
    scanning: Boolean,
    error: String?,
    viewModel: SensorViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CurrentSensorCard(sensor, onForget = viewModel::forget)

        PermissionGate(
            permissions = Permissions.bluetooth,
            title = "Доступ к Bluetooth",
            rationale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "Нужен, чтобы найти пульсометр рядом и подключиться к нему."
            } else {
                "На Android до 12 версии поиск Bluetooth-устройств требует доступа к геолокации. " +
                    "Местоположение для этого не используется."
            },
        ) {
            BluetoothGate {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    LocationServiceGate("На этой версии Android поиск Bluetooth-датчиков работает только с включённой геолокацией.") {
                        ScanSection(devices, scanning, error, viewModel)
                    }
                } else {
                    ScanSection(devices, scanning, error, viewModel)
                }
            }
        }
    }
}

@Composable
private fun CurrentSensorCard(sensor: SensorState, onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SensorBadge(sensor)
            sensor.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            sensor.heartRate?.let { Text("Пульс: $it уд/мин", style = MaterialTheme.typography.titleMedium) }
            if (sensor.status == SensorStatus.Simulation) {
                Text("Включён режим симуляции. Отключить его можно в настройках.", style = MaterialTheme.typography.bodySmall)
            }
            if (sensor.address != null) {
                OutlinedButton(onClick = onForget) { Text("Забыть датчик") }
            }
        }
    }
}

@Composable
private fun ScanSection(
    devices: List<FoundDevice>,
    scanning: Boolean,
    error: String?,
    viewModel: SensorViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (scanning) {
                OutlinedButton(onClick = viewModel::stopScan) { Text("Остановить") }
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text("Ищу датчики…")
            } else {
                Button(onClick = viewModel::startScan) { Text("Искать датчики") }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!scanning && devices.isEmpty()) {
            Text(
                "Наденьте пульсометр и смочите электроды: датчик включается при контакте с кожей.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn {
            items(devices, key = { it.address }) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.select(device) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name ?: "Без имени", style = MaterialTheme.typography.titleMedium)
                        Text(device.address, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("${device.rssi} дБм", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
