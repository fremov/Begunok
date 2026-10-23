package ru.begunok.alerts

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class VoicePackException(message: String) : Exception(message)

class VoicePack(private val context: Context) {
    private val dir = File(context.filesDir, "voicepack")
    private val clipsDir = File(dir, "clips")
    private val manifestFile = File(dir, "export.json")

    private val _keys = MutableStateFlow(loadKeys())
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    fun clip(key: String): File? = File(clipsDir, "$key.wav").takeIf { it.isFile }

    val hasExport: Boolean get() = manifestFile.isFile

    suspend fun export(
        player: AlertPlayer,
        phrases: List<PackPhrase>,
        voice: VoiceSettings,
        onProgress: (Int, Int) -> Unit,
    ): File {
        val tmp = File(context.cacheDir, "tts_export").apply {
            deleteRecursively()
            mkdirs()
        }
        val clips = mutableListOf<Pair<PackPhrase, Pcm>>()
        phrases.forEachIndexed { i, phrase ->
            onProgress(i + 1, phrases.size)
            val file = File(tmp, "$i.wav")
            if (player.synthesizeToFile(phrase.text, voice, file)) {
                withContext(Dispatchers.IO) { AudioDecoder.decode(context, Uri.fromFile(file)) }
                    ?.let { clips += phrase to it }
            }
        }
        if (clips.isEmpty()) throw VoicePackException("Синтез речи не сработал. Проверьте, что выбран русский голос.")

        return withContext(Dispatchers.IO) {
            val (pcm, entries) = VoicePackLayout.assemble(clips)
            dir.mkdirs()
            manifestFile.writeText(
                JSONObject()
                    .put("durationMs", pcm.durationMs)
                    .put("entries", JSONArray().apply {
                        entries.forEach { e ->
                            put(JSONObject().put("key", e.key).put("start", e.startMs).put("duration", e.durationMs))
                        }
                    })
                    .toString()
            )
            tmp.deleteRecursively()
            val out = File(context.cacheDir, "voice_export/begunok_voice_source.wav")
            out.parentFile?.mkdirs()
            out.writeBytes(Wav.encode(pcm))
            out
        }
    }

    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        if (!manifestFile.isFile) throw VoicePackException("Сначала сделайте экспорт фраз.")
        val json = JSONObject(manifestFile.readText())
        val array = json.getJSONArray("entries")
        val entries = List(array.length()) { i ->
            val o = array.getJSONObject(i)
            PackEntry(o.getString("key"), o.getLong("start"), o.getLong("duration"))
        }
        val pcm = AudioDecoder.decode(context, uri)
            ?: throw VoicePackException("Не удалось прочитать аудиофайл.")
        val sourceMs = json.getLong("durationMs")
        val ratio = pcm.durationMs.toDouble() / sourceMs
        if (ratio !in 0.9..1.1) {
            throw VoicePackException(
                "Длительность файла (${pcm.durationMs / 1000} с) не совпадает с экспортом (${sourceMs / 1000} с). " +
                    "Загрузите в сервис именно последний экспортированный файл и не обрезайте его."
            )
        }
        val clips = VoicePackLayout.slice(pcm, entries, sourceMs)
        if (clips.isEmpty()) throw VoicePackException("В файле не найдено ни одной фразы.")
        clipsDir.deleteRecursively()
        clipsDir.mkdirs()
        clips.forEach { (key, samples) -> File(clipsDir, "$key.wav").writeBytes(Wav.encode(Pcm(samples, pcm.sampleRate))) }
        _keys.value = loadKeys()
        clips.size
    }

    fun clear() {
        clipsDir.deleteRecursively()
        _keys.value = emptySet()
    }

    private fun loadKeys(): Set<String> =
        clipsDir.listFiles()?.filter { it.extension == "wav" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
}
