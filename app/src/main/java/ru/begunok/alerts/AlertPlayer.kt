package ru.begunok.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.net.toUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

data class Beep(val frequencyHz: Int, val durationMs: Int, val pauseMs: Int = 80)

enum class CueId(val label: String) {
    HighHr("Пульс высокий"),
    LowHr("Пульс низкий"),
    Run("Бег"),
    Walk("Шаг"),
    Finish("Программа завершена"),
}

data class Cue(
    val id: CueId? = null,
    val packKey: String? = null,
    val beeps: List<Beep> = emptyList(),
    val vibration: LongArray? = null,
    val text: String? = null,
)

data class VoiceSettings(
    val engine: String? = null,
    val voiceName: String? = null,
    val pitch: Float = 1f,
    val rate: Float = 1f,
    val clips: Map<CueId, String> = emptyMap(),
    val usePack: Boolean = true,
)

data class AlertOptions(
    val sound: Boolean,
    val voiceEnabled: Boolean,
    val vibration: Boolean,
    val voice: VoiceSettings = VoiceSettings(),
)

data class VoiceOption(val name: String, val label: String)

class AlertPlayer(
    context: Context,
    scope: CoroutineScope,
    engine: String? = null,
    private val voicePack: VoicePack? = null,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val queue = Channel<Pair<Cue, AlertOptions>>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val ttsReady = CompletableDeferred<Boolean>()
    private var speechDone: CompletableDeferred<Unit>? = null
    private val tts: TextToSpeech = TextToSpeech(appContext, { status ->
        if (status == TextToSpeech.SUCCESS) onTtsReady() else ttsReady.complete(false)
    }, engine)
    private val worker: Job = scope.launch(Dispatchers.Default) {
        for ((cue, options) in queue) perform(cue, options)
    }

    fun play(cue: Cue, options: AlertOptions) {
        queue.trySend(cue to options)
    }

    suspend fun russianVoices(): List<VoiceOption> {
        if (withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } != true) return emptyList()
        return runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == "ru" }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
            .map { v ->
                val online = if (v.isNetworkConnectionRequired) " (онлайн)" else ""
                VoiceOption(v.name, v.name + online)
            }
    }

    suspend fun engines(): List<VoiceOption> {
        withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() }
        return runCatching { tts.engines.map { VoiceOption(it.name, it.label) } }.getOrDefault(emptyList())
    }

    fun release() {
        worker.cancel()
        queue.close()
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    private fun onTtsReady() {
        val result = tts.setLanguage(Locale.forLanguageTag("ru-RU"))
        tts.setAudioAttributes(attributes)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                speechDone?.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speechDone?.complete(Unit)
            }
        })
        ttsReady.complete(result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
    }

    private suspend fun perform(cue: Cue, options: AlertOptions) {
        val sound = options.sound && cue.beeps.isNotEmpty()
        val clip = cue.id?.let { options.voice.clips[it] }
            ?: cue.packKey?.takeIf { options.voice.usePack }?.let { voicePack?.clip(it) }?.toUri()?.toString()
        val voice = options.voiceEnabled && (clip != null || cue.text != null)
        if (options.vibration && cue.vibration != null) vibrate(cue.vibration)
        if (!sound && !voice) return
        val focus = requestFocus()
        try {
            if (sound) playBeeps(cue.beeps)
            if (voice) {
                val clipPlayed = clip != null && playClip(clip)
                if (!clipPlayed && cue.text != null &&
                    withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } == true
                ) {
                    speak(cue.text, options.voice)
                }
            }
        } finally {
            focus?.let { audioManager?.abandonAudioFocusRequest(it) }
        }
    }

    suspend fun synthesizeToFile(text: String, voice: VoiceSettings, file: File): Boolean {
        if (withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { ttsReady.await() } != true) return false
        applyVoice(voice.copy(pitch = 1f))
        val done = CompletableDeferred<Unit>()
        speechDone = done
        val result = tts.synthesizeToFile(text, Bundle(), file, "export-${System.nanoTime()}")
        if (result != TextToSpeech.SUCCESS) return false
        withTimeoutOrNull(SYNTH_TIMEOUT_MS) { done.await() }
        return file.length() > WAV_HEADER_BYTES
    }

    private fun applyVoice(voice: VoiceSettings) {
        runCatching {
            voice.voiceName?.let { name -> tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it } }
            tts.setPitch(voice.pitch)
            tts.setSpeechRate(voice.rate)
        }
    }

    private suspend fun speak(text: String, voice: VoiceSettings) {
        applyVoice(voice)
        val done = CompletableDeferred<Unit>()
        speechDone = done
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue-${System.nanoTime()}")
        withTimeoutOrNull(SPEECH_TIMEOUT_MS) { done.await() }
    }

    private suspend fun playClip(uri: String): Boolean {
        val done = CompletableDeferred<Boolean>()
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(attributes)
            player.setDataSource(appContext, uri.toUri())
            player.setOnCompletionListener { done.complete(true) }
            player.setOnErrorListener { _, _, _ -> done.complete(false); true }
            player.prepare()
            player.start()
            withTimeoutOrNull(CLIP_TIMEOUT_MS) { done.await() } ?: true
        } catch (_: Exception) {
            false
        } finally {
            runCatching { player.release() }
        }
    }

    private suspend fun playBeeps(beeps: List<Beep>) {
        val pcm = synthesize(beeps)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
        }.getOrNull() ?: return
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            delay(pcm.size * 1000L / SAMPLE_RATE + 50)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun synthesize(beeps: List<Beep>): ShortArray {
        val total = beeps.sumOf { (it.durationMs + it.pauseMs) * SAMPLE_RATE / 1000 }
        val out = ShortArray(total)
        var offset = 0
        for (beep in beeps) {
            val n = beep.durationMs * SAMPLE_RATE / 1000
            val fade = min(n / 2, SAMPLE_RATE / 200)
            for (i in 0 until n) {
                val envelope = when {
                    i < fade -> i.toDouble() / fade
                    i > n - fade -> (n - i).toDouble() / fade
                    else -> 1.0
                }
                val value = sin(2 * PI * beep.frequencyHz * i / SAMPLE_RATE) * envelope * AMPLITUDE
                out[offset + i] = value.toInt().toShort()
            }
            offset += n + beep.pauseMs * SAMPLE_RATE / 1000
        }
        return out
    }

    private fun requestFocus(): AudioFocusRequest? {
        val manager = audioManager ?: return null
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        return if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request else null
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }
        if (vibrator?.hasVibrator() != true) return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val AMPLITUDE = 0.8 * Short.MAX_VALUE
        const val SPEECH_TIMEOUT_MS = 10_000L
        const val TTS_INIT_TIMEOUT_MS = 3_000L
        const val CLIP_TIMEOUT_MS = 15_000L
        const val SYNTH_TIMEOUT_MS = 20_000L
        const val WAV_HEADER_BYTES = 44L
    }
}
