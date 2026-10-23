package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class HrAlarmTest {
    private fun run(alarm: HrAlarm, hrBySecond: List<Int?>, upper: Int = 145, lower: Int? = null): List<Pair<Int, HrAlert>> =
        hrBySecond.mapIndexedNotNull { s, hr -> alarm.update(s * 1000L, hr, upper, lower)?.let { s to it } }

    @Test
    fun highAfterFiveSecondsThenEveryThirty() {
        val alerts = run(HrAlarm(), List(70) { 150 })
        assertEquals(listOf(5 to HrAlert.High, 35 to HrAlert.High, 65 to HrAlert.High), alerts)
    }

    @Test
    fun shortSpikeIgnored() {
        assertEquals(emptyList<Pair<Int, HrAlert>>(), run(HrAlarm(), List(4) { 150 } + List(10) { 140 }))
    }

    @Test
    fun hysteresisPreventsQuickRetrigger() {
        val hr = List(6) { 150 } + List(5) { 144 } + List(40) { 150 }
        val alerts = run(HrAlarm(), hr)
        assertEquals(listOf(5 to HrAlert.High, 35 to HrAlert.High), alerts)
    }

    @Test
    fun rearmsAfterDroppingBelowHysteresis() {
        val hr = List(6) { 150 } + List(3) { 140 } + List(6) { 150 }
        val alerts = run(HrAlarm(), hr)
        assertEquals(listOf(5 to HrAlert.High, 14 to HrAlert.High), alerts)
    }

    @Test
    fun lowLimit() {
        val alerts = run(HrAlarm(), List(7) { 100 }, lower = 110)
        assertEquals(listOf(5 to HrAlert.Low), alerts)
    }
}
