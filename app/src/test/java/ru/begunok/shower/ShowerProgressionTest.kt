package ru.begunok.shower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ShowerProgressionTest {
    private val day = 24 * 60 * 60_000L

    private fun ok(target: Int, time: Long = 0) = ShowerSession(time, target, target, completed = true)
    private fun fail(target: Int, time: Long = 0) = ShowerSession(time, target / 2, target, completed = false)

    @Test
    fun startsFromOneMinute() {
        assertEquals(ShowerStatus(60, 0), ShowerProgression.status(emptyList()))
    }

    @Test
    fun countsSuccessesAtCurrentTarget() {
        assertEquals(ShowerStatus(60, 2), ShowerProgression.status(listOf(ok(60), ok(60))))
        assertEquals(ShowerStatus(75, 1), ShowerProgression.status(listOf(ok(75), ok(60), ok(60), ok(60))))
    }

    @Test
    fun threeInARowAddsStep() {
        assertEquals(ShowerStatus(75, 0), ShowerProgression.status(listOf(ok(60), ok(60), ok(60))))
    }

    @Test
    fun failureResetsStreakButKeepsTarget() {
        assertEquals(ShowerStatus(75, 0), ShowerProgression.status(listOf(fail(75), ok(75), ok(75))))
        assertEquals(ShowerStatus(75, 1), ShowerProgression.status(listOf(ok(75), fail(75), ok(75), ok(75))))
    }

    @Test
    fun stopsAtThreeMinutes() {
        val status = ShowerProgression.status(List(5) { ok(180) })
        assertEquals(ShowerStatus(180, 3), status)
        assertTrue(status.atMax)
    }

    @Test
    fun weekSumsLastSevenDays() {
        val now = 10 * day
        val recent = listOf(ok(60, now - day), ok(90, now - 6 * day), ok(120, now - 8 * day))
        assertEquals(150, ShowerProgression.weekSec(recent, now))
    }

    @Test
    fun dayStreakAllowsTodayNotDoneYet() {
        val now = 10 * day + 12 * 60 * 60_000L
        val utc = ZoneOffset.UTC
        val yesterdayAndBefore = listOf(ok(60, now - day), ok(60, now - 2 * day), ok(60, now - 4 * day))
        assertEquals(2, ShowerProgression.dayStreak(yesterdayAndBefore, now, utc))
        assertEquals(3, ShowerProgression.dayStreak(listOf(ok(60, now)) + yesterdayAndBefore, now, utc))
        assertEquals(0, ShowerProgression.dayStreak(listOf(ok(60, now - 3 * day)), now, utc))
    }
}
