package ru.begunok.alerts

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Pcm(val samples: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

data class PackPhrase(val key: String, val text: String)

data class PackEntry(val key: String, val startMs: Long, val durationMs: Long)

object PcmTools {
    fun resample(pcm: Pcm, rate: Int): Pcm {
        if (pcm.sampleRate == rate) return pcm
        if (pcm.samples.isEmpty()) return Pcm(pcm.samples, rate)
        val src = pcm.samples
        val n = (src.size.toLong() * rate / pcm.sampleRate).toInt()
        val last = src.size - 1
        val out = ShortArray(n) { i ->
            val pos = i.toDouble() * pcm.sampleRate / rate
            val i0 = min(pos.toInt(), last)
            val i1 = min(i0 + 1, last)
            val f = pos - i0
            (src[i0] + (src[i1] - src[i0]) * f).roundToInt().toShort()
        }
        return Pcm(out, rate)
    }

    fun trimSilence(samples: ShortArray, sampleRate: Int, padMs: Int = 40): ShortArray {
        if (samples.isEmpty()) return samples
        val peak = samples.maxOf { abs(it.toInt()) }
        if (peak < MIN_PEAK) return ShortArray(0)
        val threshold = max(peak * 0.06, MIN_THRESHOLD.toDouble())
        val first = samples.indexOfFirst { abs(it.toInt()) > threshold }
        val last = samples.indexOfLast { abs(it.toInt()) > threshold }
        val pad = sampleRate * padMs / 1000
        return samples.copyOfRange(max(0, first - pad), min(samples.size, last + pad + 1))
    }

    fun fade(samples: ShortArray, sampleRate: Int, ms: Int = 10): ShortArray {
        val n = min(samples.size / 2, sampleRate * ms / 1000)
        val out = samples.copyOf()
        for (i in 0 until n) {
            val k = i.toDouble() / n
            out[i] = (out[i] * k).toInt().toShort()
            out[out.size - 1 - i] = (out[out.size - 1 - i] * k).toInt().toShort()
        }
        return out
    }

    fun normalize(samples: ShortArray, targetPeak: Double = 0.9): ShortArray {
        val peak = samples.maxOfOrNull { abs(it.toInt()) } ?: return samples
        if (peak == 0) return samples
        val gain = targetPeak * Short.MAX_VALUE / peak
        return ShortArray(samples.size) { (samples[it] * gain).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    private const val MIN_PEAK = 300
    private const val MIN_THRESHOLD = 200
}

object VoicePackLayout {
    const val SAMPLE_RATE = 24_000
    const val LEAD_MS = 800L
    const val GAP_MS = 1_500L
    private const val CUT_BEFORE_MS = 300L
    private const val CUT_AFTER_MS = 700L

    fun assemble(clips: List<Pair<PackPhrase, Pcm>>): Pair<Pcm, List<PackEntry>> {
        val prepared = clips.map { (phrase, pcm) ->
            val resampled = PcmTools.resample(pcm, SAMPLE_RATE)
            phrase to PcmTools.trimSilence(resampled.samples, SAMPLE_RATE)
        }.filter { it.second.isNotEmpty() }
        val lead = samples(LEAD_MS)
        val gap = samples(GAP_MS)
        val total = lead + prepared.sumOf { it.second.size + gap }
        val out = ShortArray(total)
        val entries = mutableListOf<PackEntry>()
        var offset = lead
        for ((phrase, s) in prepared) {
            s.copyInto(out, offset)
            entries += PackEntry(phrase.key, offset * 1000L / SAMPLE_RATE, s.size * 1000L / SAMPLE_RATE)
            offset += s.size + gap
        }
        return Pcm(out, SAMPLE_RATE) to entries
    }

    fun slice(converted: Pcm, entries: List<PackEntry>, sourceDurationMs: Long): Map<String, ShortArray> {
        val ratio = if (sourceDurationMs > 0) converted.durationMs.toDouble() / sourceDurationMs else 1.0
        val rate = converted.sampleRate
        val result = LinkedHashMap<String, ShortArray>()
        for (e in entries) {
            val fromMs = ((e.startMs - CUT_BEFORE_MS) * ratio).toLong()
            val toMs = ((e.startMs + e.durationMs + CUT_AFTER_MS) * ratio).toLong()
            val from = (fromMs * rate / 1000).toInt().coerceIn(0, converted.samples.size)
            val to = (toMs * rate / 1000).toInt().coerceIn(from, converted.samples.size)
            val trimmed = PcmTools.trimSilence(converted.samples.copyOfRange(from, to), rate)
            if (trimmed.isNotEmpty()) result[e.key] = PcmTools.fade(PcmTools.normalize(trimmed), rate)
        }
        return result
    }

    private fun samples(ms: Long) = (ms * SAMPLE_RATE / 1000).toInt()
}

object Wav {
    fun encode(pcm: Pcm): ByteArray {
        val dataSize = pcm.samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        pcm.samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }
}
