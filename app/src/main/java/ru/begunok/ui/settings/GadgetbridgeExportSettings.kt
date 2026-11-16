package ru.begunok.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ru.begunok.data.SettingsRepository
import ru.begunok.health.GadgetbridgeExtrasImporter
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable
internal fun GadgetbridgeExportSettings(onFolderChanged: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { SettingsRepository(context.applicationContext) }
    val importer = remember(context) { GadgetbridgeExtrasImporter(context) }
    val selected by repository.gadgetbridgeExportTreeUri.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(selected, refresh) {
        error = null
        status = null
        val value = selected ?: return@LaunchedEffect
        checking = true
        try {
            val now = Instant.now()
            val result = importer.read(Uri.parse(value), now.minus(1, ChronoUnit.DAYS), now)
            error = result.message
            if (result.message == null) status = "Папка доступна. SQLite-экспорт читается."
        } finally {
            checking = false
        }
    }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }

    fun releasePermission(value: String?) {
        if (value == null) return
        // A provider can revoke access independently; disconnect must still clear the setting.
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            saving = true
            error = null
            val previous = selected
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                repository.setGadgetbridgeExportTreeUri(uri.toString())
                if (previous != uri.toString()) releasePermission(previous)
                refresh++
                onFolderChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                error = "Не удалось сохранить доступ. Выберите папку заново и разрешите чтение."
            } catch (_: Exception) {
                error = "Не удалось сохранить папку. Повторите выбор."
            } finally {
                saving = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gadgetbridge · стресс и Vitality", style = MaterialTheme.typography.titleLarge)
            Text("Выберите отдельную папку Documents/MyLifeSensors и настройте в Gadgetbridge автоэкспорт базы SQLite (.db) в ту же папку. ZIP-резервная копия не нужна.")
            val configured = selected
            if (configured == null) {
                Text("Папка автоэкспорта не выбрана.")
            } else {
                val folder = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(configured)).substringAfter(':') }
                    .getOrDefault("Выбранная папка")
                Text("Папка: $folder")
                if (checking) Text("Проверяем доступ и SQLite-экспорт…")
                else status?.let { Text(it) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                enabled = !saving,
                onClick = { choose.launch(selected?.let(Uri::parse)) },
            ) { Text(if (configured == null) "Выбрать папку" else "Изменить папку") }
            if (configured != null) {
                OutlinedButton(enabled = !saving && !checking, onClick = { refresh++ }) {
                    Text("Проверить экспорт")
                }
                TextButton(enabled = !saving, onClick = {
                    scope.launch {
                        saving = true
                        try {
                            repository.setGadgetbridgeExportTreeUri(null)
                            releasePermission(configured)
                            error = null
                            status = null
                            onFolderChanged()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = "Не удалось отключить папку. Повторите попытку."
                        } finally {
                            saving = false
                        }
                    }
                }) { Text("Отключить папку") }
            }
            Text("Vitality / PAI — баллы активности, не запас энергии. Настроение отмечается отдельно в самооценке.")
        }
    }
}
