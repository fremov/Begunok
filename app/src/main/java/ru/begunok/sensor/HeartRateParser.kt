package ru.begunok.sensor

import kotlin.math.roundToInt

data class HeartRateMeasurement(
    val bpm: Int,
    val rrIntervalsMs: List<Int>,
    val timestampMs: Long = System.currentTimeMillis(),
)

object HeartRateParser {
    private const val FLAG_HR_UINT16 = 0x01
    private const val FLAG_ENERGY_EXPENDED = 0x08
    private const val FLAG_RR_INTERVALS = 0x10

    fun parse(data: ByteArray): HeartRateMeasurement? {
        if (data.isEmpty()) return null
        val flags = data[0].toInt() and 0xFF
        var offset = 1

        val bpm = if ((flags and FLAG_HR_UINT16) != 0) {
            if (data.size < offset + 2) return null
            uint16(data, offset).also { offset += 2 }
        } else {
            if (data.size < offset + 1) return null
            (data[offset].toInt() and 0xFF).also { offset += 1 }
        }

        if ((flags and FLAG_ENERGY_EXPENDED) != 0) offset += 2

        val rr = mutableListOf<Int>()
        if ((flags and FLAG_RR_INTERVALS) != 0) {
            while (offset + 1 < data.size) {
                rr += (uint16(data, offset) * 1000.0 / 1024.0).roundToInt()
                offset += 2
            }
        }
        return HeartRateMeasurement(bpm, rr)
    }

    private fun uint16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
