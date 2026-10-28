package ru.begunok.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.begunok.alerts.AlertPlayer
import ru.begunok.alerts.Cue
import ru.begunok.alerts.CueId
import ru.begunok.alerts.Cues
import ru.begunok.alerts.VoiceOption
import ru.begunok.alerts.VoicePack
import ru.begunok.data.AppSettings
import ru.begunok.data.SettingsRepository
import ru.begunok.sensor.SensorState
import ru.begunok.sensor.SensorStatus
import ru.begunok.ui.common.BatteryOptimizationCard
import ru.begunok.ui.common.SensorBadge
import ru.begunok.workout.IntervalKind
import ru.begunok.workout.IntervalPosition
import ru.begunok.workout.IntervalProgram
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

class SettingsViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val voicePack: VoicePack,
    val sensor: StateFlow<SensorState>,
) : ViewModel() {
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private var player: AlertPlayer? = null
    private var playerEngine: String? = null

    private val _engines = MutableStateFlow<List<VoiceOption>>(emptyList())
    val engines: StateFlow<List<VoiceOption>> = _engines.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    val packKeys: StateFlow<Set<String>> = voicePack.keys

    private val _packBusy = MutableStateFlow(false)
    val packBusy: StateFlow<Boolean> = _packBusy.asStateFlow()

    private val _packStatus = MutableStateFlow<String?>(null)
    val packStatus: StateFlow<String?> = _packStatus.asStateFlow()

    private val _exported = MutableStateFlow<File?>(null)
    val exported: StateFlow<File?> = _exported.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.map { it.voice.engine }.distinctUntilChanged().collect { engine ->
                val p = playerFor(engine)
                _engines.value = p.engines()
                _voices.value = p.russianVoices()
            }
        }
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

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setSimulation(value: Boolean) = launch { repository.setSimulation(value) }
    fun setUpperLimit(value: Int) = launch { repository.setUpperLimit(value.coerceIn(60, 220)) }
    fun setLowerLimit(value: Int?) = launch { repository.setLowerLimit(value?.coerceIn(40, 200)) }
    fun setVibration(value: Boolean) = launch { repository.setVibration(value) }
    fun setVoice(value: Boolean) = launch { repository.setVoice(value) }
    fun setSound(value: Boolean) = launch { repository.setSound(value) }
    fun saveProgram(program: IntervalProgram) = launch { repository.saveProgram(program) }
    fun deleteProgram(id: String) = launch { repository.deleteProgram(id) }
    fun resetPrograms() = launch { repository.resetPrograms() }
    fun setEngine(engine: String?) = launch { repository.setTtsEngine(engine) }
    fun setVoiceName(name: String?) = launch { repository.setTtsVoice(name) }
    fun setPitch(value: Float) = launch { repository.setTtsPitch(value) }
    fun setRate(value: Float) = launch { repository.setTtsRate(value) }

    fun animeStyle() = launch {
        repository.setTtsPitch(1.6f)
        repository.setTtsRate(1.1f)
    }

    fun setClip(id: CueId, uri: Uri?) = launch {
        val resolver = appContext.contentResolver
        settings.value.voice.clips[id]?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uri != null) {
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        repository.setClip(id, uri?.toString())
    }

    fun setUsePack(value: Boolean) = launch { repository.setUseVoicePack(value) }

    fun exportPack() {
        if (_packBusy.value) return
        _packBusy.value = true
        viewModelScope.launch {
            try {
                val s = repository.settings.first()
                val file = voicePack.export(playerFor(s.voice.engine), Cues.packPhrases(s.programs), s.voice) { i, n ->
                    _packStatus.value = "Синтез фраз: $i из $n…"
                }
                _packStatus.value = "Файл для переозвучки готов (${file.length() / 1024} КБ)"
                _exported.value = file
            } catch (e: Exception) {
                _packStatus.value = e.message ?: "Не удалось сделать экспорт"
            } finally {
                _packBusy.value = false
            }
        }
    }

    fun consumeExported() {
        _exported.value = null
    }

    fun importPack(uri: Uri) {
        if (_packBusy.value) return
        _packBusy.value = true
        _packStatus.value = "Импорт…"
        viewModelScope.launch {
            try {
                val count = voicePack.import(uri)
                _packStatus.value = "Готово: импортировано фраз — $count"
            } catch (e: Exception) {
                _packStatus.value = e.message ?: "Не удалось импортировать файл"
            } finally {
                _packBusy.value = false
            }
        }
    }

    fun clearPack() {
        voicePack.clear()
        _packStatus.value = null
    }

    fun test(cue: Cue) {
        val s = settings.value
        playerFor(s.voice.engine).play(cue, s.alertOptions())
    }

    override fun onCleared() {
        player?.release()
    }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onOpenSensor: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sensor by viewModel.sensor.collectAsStateWithLifecycle()
    val engines by viewModel.engines.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<IntervalProgram?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineSmall)

        Section("Датчик пульса") {
            SensorBadge(sensor)
            OutlinedButton(onClick = onOpenSensor) {
                Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(if (sensor.status == SensorStatus.NotSelected) "  Выбрать датчик" else "  Подключение и поиск")
            }
        }

        Section("Граница пульса") {
            StepperRow("Верхняя граница", settings.upperLimit, viewModel::setUpperLimit)
            SwitchRow(
                title = "Нижняя граница",
                subtitle = "Сигнал «Пульс низкий». Во время шага в интервальной программе не срабатывает",
                checked = settings.lowerLimit != null,
                onCheckedChange = { on -> viewModel.setLowerLimit(if (on) (settings.upperLimit - 25) else null) },
            )
            settings.lowerLimit?.let { lower ->
                StepperRow("Нижняя граница", lower) { viewModel.setLowerLimit(it) }
            }
        }

        Section("Сигналы") {
            SwitchRow("Звук", "Короткие звуковые сигналы", settings.sound, viewModel::setSound)
            SwitchRow("Голос", "Голосовые подсказки на русском", settings.voiceEnabled, viewModel::setVoice)
            SwitchRow("Вибрация", null, settings.vibration, viewModel::setVibration)
            Text(
                "Сигнал о высоком пульсе — если пульс выше границы дольше 5 секунд. " +
                    "Повтор не чаще раза в 30 секунд, пока пульс не опустится на 3 удара ниже границы.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.test(Cues.highHeartRate) }) { Text("Высокий") }
                OutlinedButton(onClick = { viewModel.test(Cues.lowHeartRate) }) { Text("Низкий") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.test(Cues.interval(IntervalPosition(IntervalKind.Run, 1, 8, 60, 0), 60, false))
                }) { Text("Бег") }
                OutlinedButton(onClick = {
                    viewModel.test(Cues.interval(IntervalPosition(IntervalKind.Walk, 1, 8, 120, 1), 120, false))
                }) { Text("Шаг") }
            }
        }

        VoiceSection(settings, engines, voices, viewModel)
        VoicePackSection(settings, viewModel)

        Section("Интервальные программы") {
            settings.programs.forEach { program ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(program.name, style = MaterialTheme.typography.titleMedium)
                        Text(program.description, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { editing = program }) { Icon(Icons.Filled.Edit, contentDescription = "Изменить") }
                    IconButton(onClick = { viewModel.deleteProgram(program.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                    }
                }
                HorizontalDivider()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    editing = IntervalProgram(UUID.randomUUID().toString(), "Новая программа", 60, 60, 5)
                }) { Text("Добавить") }
                TextButton(onClick = viewModel::resetPrograms) { Text("Вернуть стандартные") }
            }
        }

        Section("Проверка без датчика") {
            SwitchRow(
                title = "Режим симуляции",
                subtitle = "Генерировать пульс 110–170 без датчика, чтобы проверить приложение",
                checked = settings.simulation,
                onCheckedChange = viewModel::setSimulation,
            )
        }

        BatteryOptimizationCard()
    }

    editing?.let { program ->
        ProgramDialog(
            program = program,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveProgram(it)
                editing = null
            },
        )
    }
}

@Composable
private fun VoiceSection(
    settings: AppSettings,
    engines: List<VoiceOption>,
    voices: List<VoiceOption>,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val voice = settings.voice
    Section("Голос") {
        Picker(
            title = "Движок синтеза речи",
            selected = engines.firstOrNull { it.name == voice.engine }?.label ?: "Системный по умолчанию",
            options = listOf(null to "Системный по умолчанию") + engines.map { it.name to it.label },
            onSelect = viewModel::setEngine,
        )
        Picker(
            title = "Голос",
            selected = voices.firstOrNull { it.name == voice.voiceName }?.label ?: "По умолчанию",
            options = listOf(null to "По умолчанию") + voices.map { it.name to it.label },
            onSelect = viewModel::setVoiceName,
        )
        if (voices.isEmpty()) {
            Text(
                "Русских голосов у этого движка не найдено. Установите русский язык в системных настройках синтеза речи.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        SliderRow("Высота голоса", voice.pitch, viewModel::setPitch)
        SliderRow("Скорость речи", voice.rate, viewModel::setRate)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::animeStyle) { Text("Аниме-стиль") }
            OutlinedButton(onClick = {
                viewModel.setPitch(1f)
                viewModel.setRate(1f)
            }) { Text("Обычный") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Настройки речи Android") }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }) { Text("Скачать голоса") }
        }

        HorizontalDivider()
        Text("Свои аудиофайлы", style = MaterialTheme.typography.titleMedium)
        Text(
            "Вместо синтеза речи для каждого события можно выбрать свой звук (mp3, ogg, wav и т.п.), " +
                "например фразу в голосе любимого персонажа.",
            style = MaterialTheme.typography.bodySmall,
        )
        CueId.entries.forEach { id -> ClipRow(id, voice.clips[id], viewModel) }
    }
}

@Composable
private fun VoicePackSection(settings: AppSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val keys by viewModel.packKeys.collectAsStateWithLifecycle()
    val busy by viewModel.packBusy.collectAsStateWithLifecycle()
    val status by viewModel.packStatus.collectAsStateWithLifecycle()
    val exported by viewModel.exported.collectAsStateWithLifecycle()
    val needed = remember(settings.programs) { Cues.packPhrases(settings.programs).map { it.key } }
    val covered = needed.count { it in keys }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importPack(uri)
    }

    LaunchedEffect(exported) {
        val file = exported ?: return@LaunchedEffect
        viewModel.consumeExported()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("audio/wav")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(send, "Отправить файл для переозвучки")) }
    }

    Section("Голосовой пакет") {
        Text(
            "Все фразы, включая «Бег, 2 минуты. Интервал 3 из 7», можно переозвучить любым голосом через RVC-сервис:\n" +
                "1. «Экспорт фраз» — приложение запишет все фразы в один WAV-файл и предложит его отправить (в Telegram, на почту, в Google Drive).\n" +
                "2. Загрузите этот файл в сервис переозвучки целиком, не обрезая, и скачайте результат.\n" +
                "3. «Импорт» — выберите полученный файл, приложение само нарежет его на фразы.\n" +
                "Если поменяете программы — повторите экспорт и импорт.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (keys.isEmpty()) "Пакет не загружен" else "В пакете есть $covered из ${needed.size} фраз для ваших программ",
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = viewModel::exportPack) { Text("1. Экспорт фраз") }
            OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("audio/*")) }) { Text("2. Импорт") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (keys.isNotEmpty()) {
            SwitchRow("Использовать пакет", "Если фразы нет в пакете, звучит синтез речи", settings.voice.usePack, viewModel::setUsePack)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.test(Cues.sample(CueId.Run)) }) { Text("Проверить") }
                TextButton(onClick = viewModel::clearPack) { Text("Удалить пакет") }
            }
        }
    }
}

@Composable
private fun ClipRow(id: CueId, clip: String?, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.setClip(id, uri)
    }
    val fileName = remember(clip) { clip?.let { displayName(context, it.toUri()) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(id.label, style = MaterialTheme.typography.bodyLarge)
            Text(fileName ?: "синтез речи", style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { viewModel.test(Cues.sample(id)) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Проверить")
        }
        IconButton(onClick = { picker.launch(arrayOf("audio/*")) }) {
            Icon(Icons.Filled.FolderOpen, contentDescription = "Выбрать файл")
        }
        if (clip != null) {
            IconButton(onClick = { viewModel.setClip(id, null) }) {
                Icon(Icons.Filled.Close, contentDescription = "Убрать файл")
            }
        }
    }
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: "файл недоступен"

@Composable
private fun SliderRow(title: String, value: Float, onChange: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$title: ${"%.1f".format(current)}", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = current,
            onValueChange = { current = (it * 10).roundToInt() / 10f },
            onValueChangeFinished = { onChange(current) },
            valueRange = 0.5f..2.0f,
            steps = 14,
        )
    }
}

@Composable
private fun Picker(
    title: String,
    selected: String,
    options: List<Pair<String?, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun StepperRow(title: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = { onChange(value - 1) }) { Icon(Icons.Filled.Remove, contentDescription = "Меньше") }
        Text("$value", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
        FilledTonalIconButton(onClick = { onChange(value + 1) }) { Icon(Icons.Filled.Add, contentDescription = "Больше") }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun spanText(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

private fun parseSpan(text: String): Int? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    return if (':' in t) {
        val (m, s) = t.split(':', limit = 2)
        val min = m.toIntOrNull() ?: return null
        val sec = s.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        min * 60 + sec
    } else {
        t.toDoubleOrNull()?.let { (it * 60).roundToInt() }
    }
}

@Composable
private fun ProgramDialog(program: IntervalProgram, onDismiss: () -> Unit, onSave: (IntervalProgram) -> Unit) {
    var name by remember { mutableStateOf(program.name) }
    var run by remember { mutableStateOf(spanText(program.runSec)) }
    var walk by remember { mutableStateOf(spanText(program.walkSec)) }
    var repeats by remember { mutableStateOf(program.repeats.toString()) }

    val runSec = parseSpan(run)?.takeIf { it > 0 }
    val walkSec = parseSpan(walk)?.takeIf { it >= 0 }
    val repeatCount = repeats.trim().toIntOrNull()?.takeIf { it in 1..99 }
    val valid = name.isNotBlank() && runSec != null && walkSec != null && repeatCount != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Программа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                OutlinedTextField(
                    run, { run = it },
                    label = { Text("Бег, мин:сек") },
                    isError = runSec == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                )
                OutlinedTextField(
                    walk, { walk = it },
                    label = { Text("Шаг, мин:сек (0 — без шага)") },
                    isError = walkSec == null,
                    singleLine = true,
                )
                OutlinedTextField(
                    repeats, { repeats = it },
                    label = { Text("Повторов") },
                    isError = repeatCount == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    "Например, 1:30 — полторы минуты, 20 — двадцать минут. Для непрерывного бега: шаг 0, повторов 1.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(program.copy(name = name.trim(), runSec = runSec!!, walkSec = walkSec!!, repeats = repeatCount!!))
                },
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
