package ru.begunok.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.workout.IntervalProgram
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoicePackLayoutTest {
    private fun tone(ms: Int, rate: Int, silenceMs: Int = 200): Pcm {
        val silence = rate * silenceMs / 1000
        val n = rate * ms / 1000
        val samples = ShortArray(silence + n + silence) { i ->
            if (i < silence || i >= silence + n) 0 else (sin(2 * PI * 440 * i / rate) * 10_000).toInt().toShort()
        }
        return Pcm(samples, rate)
    }

    @Test
    fun assembleThenSliceRecoversPhrases() {
        val clips = listOf(
            PackPhrase("a", "А") to tone(500, 22_050),
            PackPhrase("b", "Б") to tone(1200, 16_000),
            PackPhrase("c", "В") to tone(800, 24_000),
        )
        val (pcm, entries) = VoicePackLayout.assemble(clips)
        assertEquals(listOf("a", "b", "c"), entries.map { it.key })
        assertEquals(580.0, entries[0].durationMs.toDouble(), 20.0)
        assertEquals(1280.0, entries[1].durationMs.toDouble(), 20.0)

        val converted = PcmTools.resample(pcm, 40_000)
        val sliced = VoicePackLayout.slice(converted, entries, pcm.durationMs)
        assertEquals(setOf("a", "b", "c"), sliced.keys)
        val bMs = sliced.getValue("b").size * 1000.0 / 40_000
        assertEquals(1280.0, bMs, 80.0)
        assertTrue(sliced.getValue("a").maxOf { abs(it.toInt()) } > 25_000)
    }

    @Test
    fun phrasesForDefaultPrograms() {
        val phrases = Cues.packPhrases(IntervalProgram.defaults)
        assertEquals(28, phrases.size)
        assertTrue(phrases.any { it.key == "run_60_3_of_8" && it.text == "Бег, 1 минута. Интервал 3 из 8" })
        assertTrue(phrases.any { it.key == "walk_120" && it.text == "Шаг, 2 минуты" })
        assertTrue(phrases.any { it.key == "run_1200" && it.text == "Бег, 20 минут" })
    }

    @Test
    fun wavHeader() {
        val bytes = Wav.encode(Pcm(ShortArray(10), 24_000))
        assertEquals(64, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WAVE", String(bytes, 8, 4))
    }
}
