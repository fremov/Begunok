package ru.begunok.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.begunok.alerts.AlertOptions
import ru.begunok.alerts.CueId
import ru.begunok.alerts.VoiceSettings
import ru.begunok.workout.DayConfig
import ru.begunok.workout.DayPlan
import ru.begunok.workout.DayType
import ru.begunok.workout.Slot
import ru.begunok.workout.IntervalProgram

private val Context.dataStore by preferencesDataStore("settings")

data class SavedSensor(val address: String, val name: String?)

data class AppSettings(
    val sensor: SavedSensor? = null,
    val simulation: Boolean = false,
    val upperLimit: Int = DEFAULT_UPPER_LIMIT,
    val lowerLimit: Int? = null,
    val vibration: Boolean = true,
    val voiceEnabled: Boolean = true,
    val sound: Boolean = true,
    val voice: VoiceSettings = VoiceSettings(),
    val batteryHintDismissed: Boolean = false,
    val programs: List<IntervalProgram> = IntervalProgram.defaults,
    val selectedProgramId: String? = null,
    val dayType: DayType? = null,
    val dayConfigs: Map<DayType, DayConfig> = emptyMap(),
) {
    val selectedProgram: IntervalProgram? get() = programs.firstOrNull { it.id == selectedProgramId }

    fun dayConfig(type: DayType): DayConfig = dayConfigs[type] ?: DayConfig.default(type)

    fun dayPlan(type: DayType): DayPlan = DayPlan.of(type, dayConfig(type))

    fun alertOptions() = AlertOptions(sound = sound, voiceEnabled = voiceEnabled, vibration = vibration, voice = voice)

    companion object {
        const val DEFAULT_UPPER_LIMIT = 145
    }
}

class SettingsRepository(private val context: Context) {
    private object Keys {
        val sensorAddress = stringPreferencesKey("sensor_address")
        val sensorName = stringPreferencesKey("sensor_name")
        val simulation = booleanPreferencesKey("simulation")
        val upperLimit = intPreferencesKey("upper_limit")
        val lowerLimit = intPreferencesKey("lower_limit")
        val vibration = booleanPreferencesKey("vibration")
        val voice = booleanPreferencesKey("voice")
        val sound = booleanPreferencesKey("sound")
        val batteryHintDismissed = booleanPreferencesKey("battery_hint_dismissed")
        val programs = stringPreferencesKey("programs")
        val selectedProgram = stringPreferencesKey("selected_program")
        val ttsEngine = stringPreferencesKey("tts_engine")
        val ttsVoice = stringPreferencesKey("tts_voice")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsRate = floatPreferencesKey("tts_rate")
        val usePack = booleanPreferencesKey("use_voice_pack")
        val dayType = stringPreferencesKey("day_type")
        fun dayConfig(type: DayType) = stringPreferencesKey("day_config_${type.name}")
        fun clip(id: CueId) = stringPreferencesKey("clip_${id.name}")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .map { it.toSettings() }
        .distinctUntilChanged()

    private fun Preferences.toSettings() = AppSettings(
        sensor = this[Keys.sensorAddress]?.let { SavedSensor(it, this[Keys.sensorName]) },
        simulation = this[Keys.simulation] ?: false,
        upperLimit = this[Keys.upperLimit] ?: AppSettings.DEFAULT_UPPER_LIMIT,
        lowerLimit = this[Keys.lowerLimit]?.takeIf { it > 0 },
        vibration = this[Keys.vibration] ?: true,
        voiceEnabled = this[Keys.voice] ?: true,
        sound = this[Keys.sound] ?: true,
        voice = VoiceSettings(
            engine = this[Keys.ttsEngine],
            voiceName = this[Keys.ttsVoice],
            pitch = this[Keys.ttsPitch] ?: 1f,
            rate = this[Keys.ttsRate] ?: 1f,
            usePack = this[Keys.usePack] ?: true,
            clips = CueId.entries.mapNotNull { id -> this[Keys.clip(id)]?.let { id to it } }.toMap(),
        ),
        batteryHintDismissed = this[Keys.batteryHintDismissed] ?: false,
        programs = this[Keys.programs]?.let(::decodePrograms) ?: IntervalProgram.defaults,
        selectedProgramId = this[Keys.selectedProgram],
        dayType = this[Keys.dayType]?.let { name -> DayType.entries.firstOrNull { it.name == name } },
        dayConfigs = DayType.entries.mapNotNull { type -> this[Keys.dayConfig(type)]?.let(::decodeDayConfig)?.let { type to it } }.toMap(),
    )

    suspend fun setSensor(address: String, name: String?) = context.dataStore.edit {
        it[Keys.sensorAddress] = address
        if (name != null) it[Keys.sensorName] = name else it.remove(Keys.sensorName)
    }

    suspend fun forgetSensor() = context.dataStore.edit {
        it.remove(Keys.sensorAddress)
        it.remove(Keys.sensorName)
    }

    suspend fun setSimulation(value: Boolean) = context.dataStore.edit { it[Keys.simulation] = value }

    suspend fun setUpperLimit(value: Int) = context.dataStore.edit { it[Keys.upperLimit] = value }

    suspend fun setLowerLimit(value: Int?) = context.dataStore.edit {
        if (value == null) it.remove(Keys.lowerLimit) else it[Keys.lowerLimit] = value
    }

    suspend fun setVibration(value: Boolean) = context.dataStore.edit { it[Keys.vibration] = value }

    suspend fun setVoice(value: Boolean) = context.dataStore.edit { it[Keys.voice] = value }

    suspend fun setTtsEngine(engine: String?) = context.dataStore.edit {
        if (engine == null) it.remove(Keys.ttsEngine) else it[Keys.ttsEngine] = engine
        it.remove(Keys.ttsVoice)
    }

    suspend fun setTtsVoice(name: String?) = context.dataStore.edit {
        if (name == null) it.remove(Keys.ttsVoice) else it[Keys.ttsVoice] = name
    }

    suspend fun setUseVoicePack(value: Boolean) = context.dataStore.edit { it[Keys.usePack] = value }

    suspend fun setTtsPitch(value: Float) = context.dataStore.edit { it[Keys.ttsPitch] = value }

    suspend fun setTtsRate(value: Float) = context.dataStore.edit { it[Keys.ttsRate] = value }

    suspend fun setClip(id: CueId, uri: String?) = context.dataStore.edit {
        if (uri == null) it.remove(Keys.clip(id)) else it[Keys.clip(id)] = uri
    }

    suspend fun setSound(value: Boolean) = context.dataStore.edit { it[Keys.sound] = value }

    suspend fun selectProgram(id: String?) = context.dataStore.edit {
        if (id == null) it.remove(Keys.selectedProgram) else it[Keys.selectedProgram] = id
    }

    suspend fun setDayType(type: DayType?) = context.dataStore.edit {
        if (type == null) it.remove(Keys.dayType) else it[Keys.dayType] = type.name
    }

    suspend fun setDayConfig(type: DayType, config: DayConfig?) = context.dataStore.edit {
        if (config == null) it.remove(Keys.dayConfig(type)) else it[Keys.dayConfig(type)] = encodeDayConfig(config)
    }

    suspend fun replaceVariation(type: DayType, from: String, to: String) = context.dataStore.edit { prefs ->
        val config = prefs[Keys.dayConfig(type)]?.let(::decodeDayConfig) ?: DayConfig.default(type)
        val slots = config.slots.map { if (it.variationId == from) it.copy(variationId = to) else it }
        prefs[Keys.dayConfig(type)] = encodeDayConfig(config.copy(slots = slots))
    }

    private fun encodeDayConfig(config: DayConfig): String = JSONObject()
        .put("sets", config.sets)
        .put("restSets", config.restBetweenSetsSec)
        .put("restExercises", config.restBetweenExercisesSec)
        .put(
            "slots",
            JSONArray().apply {
                config.slots.forEach { put(JSONObject().put("ladder", it.ladderId).put("variation", it.variationId)) }
            },
        )
        .toString()

    private fun decodeDayConfig(json: String): DayConfig? = runCatching {
        val o = JSONObject(json)
        val slots = o.getJSONArray("slots")
        DayConfig(
            slots = List(slots.length()) { i ->
                val s = slots.getJSONObject(i)
                Slot(s.getString("ladder"), s.getString("variation"))
            },
            sets = o.getInt("sets"),
            restBetweenSetsSec = o.getInt("restSets"),
            restBetweenExercisesSec = o.getInt("restExercises"),
        )
    }.getOrNull()

    suspend fun saveProgram(program: IntervalProgram) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.programs]?.let(::decodePrograms) ?: IntervalProgram.defaults
        val updated = if (current.any { it.id == program.id }) {
            current.map { if (it.id == program.id) program else it }
        } else {
            current + program
        }
        prefs[Keys.programs] = encodePrograms(updated)
    }

    suspend fun deleteProgram(id: String) = context.dataStore.edit { prefs ->
        val current = prefs[Keys.programs]?.let(::decodePrograms) ?: IntervalProgram.defaults
        prefs[Keys.programs] = encodePrograms(current.filterNot { it.id == id })
        if (prefs[Keys.selectedProgram] == id) prefs.remove(Keys.selectedProgram)
    }

    suspend fun resetPrograms() = context.dataStore.edit {
        it.remove(Keys.programs)
        it.remove(Keys.selectedProgram)
    }

    private fun encodePrograms(programs: List<IntervalProgram>): String = JSONArray().apply {
        programs.forEach { p ->
            put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("run", p.runSec)
                    .put("walk", p.walkSec)
                    .put("repeats", p.repeats)
            )
        }
    }.toString()

    private fun decodePrograms(json: String): List<IntervalProgram>? = runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            IntervalProgram(o.getString("id"), o.getString("name"), o.getInt("run"), o.getInt("walk"), o.getInt("repeats"))
        }
    }.getOrNull()

    suspend fun setBatteryHintDismissed(value: Boolean) =
        context.dataStore.edit { it[Keys.batteryHintDismissed] = value }
}
