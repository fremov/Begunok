package ru.begunok.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.HealthAvailability
import ru.begunok.health.HealthRepository

@Composable
fun HealthConnectSettings(sync: HealthAutoSync) {
    val state by sync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { sync.refresh() }
    }
    LifecycleResumeEffect(sync) {
        scope.launch { sync.refresh() }
        onPauseOrDispose {}
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Health Connect", style = MaterialTheme.typography.titleLarge)
            when (state.availability) {
                HealthAvailability.Unavailable -> Text("Health Connect недоступен на этом телефоне.")
                HealthAvailability.NeedsUpdate -> Button(onClick = {
                    val uri = "market://details?id=${HealthRepository.PROVIDER_PACKAGE}".toUri()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { error = "Не удалось открыть Google Play." }
                }) { Text("Обновить Health Connect") }
                HealthAvailability.Available -> {
                    val permissions = sync.permissions
                    Text("Разрешения: ${state.granted.intersect(permissions).size} из ${permissions.size}")
                    Text(
                        if (HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in state.granted)
                            "Автоматическое чтение: в приложении и в фоне"
                        else "Автоматическое чтение: пока приложение открыто",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.granted.containsAll(permissions)) {
                            Button(onClick = { request.launch(permissions) }) { Text("Разрешить чтение") }
                        }
                        OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) }
                                .onFailure { error = "Не удалось открыть Health Connect." }
                        }) { Text("Управление доступом") }
                    }
                }
            }
            (error ?: state.message)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    GadgetbridgeExportSettings { scope.launch { sync.refresh() } }
}
