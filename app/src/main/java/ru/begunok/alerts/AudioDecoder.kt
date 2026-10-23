package ru.begunok.alerts

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.roundToInt

object AudioDecoder {
    fun decode(context: Context, uri: Uri): Pcm? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var float = false
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val out = ShortBuilder()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = decoder.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        float = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    index >= 0 -> {
                        val buffer = decoder.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (float) {
                            val floats = buffer.asFloatBuffer()
                            while (floats.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += floats.get() }
                                out.add(((sum / channels) * Short.MAX_VALUE).roundToInt().coerceIn(-32768, 32767).toShort())
                            }
                        } else {
                            val shorts = buffer.asShortBuffer()
                            while (shorts.remaining() >= channels) {
                                var sum = 0
                                repeat(channels) { sum += shorts.get() }
                                out.add((sum / channels).toShort())
                            }
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return Pcm(out.toArray(), sampleRate)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private class ShortBuilder {
        private var data = ShortArray(1 shl 16)
        private var size = 0

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private const val TIMEOUT_US = 10_000L
}
