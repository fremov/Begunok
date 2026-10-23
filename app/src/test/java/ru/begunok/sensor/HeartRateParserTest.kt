package ru.begunok.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateParserTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun uint8WithoutRr() {
        val m = HeartRateParser.parse(bytes(0x00, 72))!!
        assertEquals(72, m.bpm)
        assertEquals(emptyList<Int>(), m.rrIntervalsMs)
    }

    @Test
    fun uint16Value() {
        val m = HeartRateParser.parse(bytes(0x01, 0x2C, 0x01))!!
        assertEquals(300, m.bpm)
    }

    @Test
    fun rrIntervalsAfterEnergyExpended() {
        val m = HeartRateParser.parse(bytes(0x18, 150, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(150, m.bpm)
        assertEquals(listOf(1000, 500), m.rrIntervalsMs)
    }

    @Test
    fun uint8WithRr() {
        val m = HeartRateParser.parse(bytes(0x16, 120, 0x00, 0x02))!!
        assertEquals(120, m.bpm)
        assertEquals(listOf(500), m.rrIntervalsMs)
    }

    @Test
    fun emptyAndTruncated() {
        assertNull(HeartRateParser.parse(ByteArray(0)))
        assertNull(HeartRateParser.parse(bytes(0x01, 0x10)))
    }
}
