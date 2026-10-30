package ru.begunok.weight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeightStatsTest {
    private val today = LocalDate.of(2026, 10, 30)

    private fun daysAgo(n: Long, kg: Double) = WeightPoint(today.minusDays(n), kg)

    @Test
    fun smoothedAveragesLastWeek() {
        val points = listOf(daysAgo(10, 90.0), daysAgo(6, 82.0), daysAgo(3, 81.0), daysAgo(0, 80.0))
        assertEquals(81.0, WeightStats.smoothedAt(points, today)!!, 1e-9)
        assertEquals(90.0, WeightStats.smoothedAt(points, today.minusDays(10))!!, 1e-9)
        assertNull(WeightStats.smoothedAt(points, today.minusDays(20)))
        assertEquals(listOf(90.0, 86.0, 81.5, 81.0), WeightStats.smoothed(points))
    }

    @Test
    fun weekChangeComparesSmoothedWeeks() {
        val points = listOf(daysAgo(9, 82.0), daysAgo(7, 81.0), daysAgo(2, 80.5), daysAgo(0, 79.5))
        assertEquals(80.0 - 81.5, WeightStats.weekChange(points)!!, 1e-9)
        assertNull(WeightStats.weekChange(listOf(daysAgo(2, 80.0), daysAgo(0, 79.0))))
        assertNull(WeightStats.weekChange(emptyList()))
    }

    @Test
    fun trendIsRegressionSlope() {
        val points = (0L..14L).map { daysAgo(14 - it, 90.0 - it * 0.1) }
        assertEquals(-0.1, WeightStats.trendPerDay(points)!!, 1e-9)
    }

    @Test
    fun trendNeedsEnoughHistory() {
        assertNull(WeightStats.trendPerDay(listOf(daysAgo(10, 90.0), daysAgo(0, 89.0))))
        assertNull(WeightStats.trendPerDay(listOf(daysAgo(3, 90.0), daysAgo(2, 89.5), daysAgo(0, 89.0))))
    }

    @Test
    fun trendIgnoresOldPoints() {
        val points = listOf(daysAgo(100, 120.0)) + (0L..10L).map { daysAgo(10 - it, 80.0) }
        assertEquals(0.0, WeightStats.trendPerDay(points)!!, 1e-9)
    }

    @Test
    fun losingGoalProgressAndForecast() {
        val goal = WeightGoal(targetKg = 80.0, startKg = 90.0)
        val status = WeightStats.status(goal, current = 86.0, trendPerDay = -0.1, today = today)
        assertEquals(0.4f, status.progress, 1e-6f)
        assertEquals(6.0, status.remainingKg, 1e-9)
        assertFalse(status.reached)
        assertEquals(today.plusDays(60), status.forecast)
        assertNull(status.neededPerWeek)
    }

    @Test
    fun noForecastWhenTrendGoesTheWrongWay() {
        val goal = WeightGoal(targetKg = 80.0, startKg = 90.0)
        assertNull(WeightStats.status(goal, 86.0, 0.05, today).forecast)
        assertNull(WeightStats.status(goal, 86.0, null, today).forecast)
    }

    @Test
    fun gainingGoal() {
        val goal = WeightGoal(targetKg = 70.0, startKg = 60.0)
        assertFalse(goal.losing)
        val status = WeightStats.status(goal, 65.0, 0.25, today)
        assertEquals(0.5f, status.progress, 1e-6f)
        assertEquals(today.plusDays(20), status.forecast)
        assertTrue(WeightStats.status(goal, 71.0, 0.25, today).reached)
    }

    @Test
    fun reachedGoalIsComplete() {
        val status = WeightStats.status(WeightGoal(80.0, 90.0, today.plusDays(10)), 79.5, -0.1, today)
        assertTrue(status.reached)
        assertEquals(1f, status.progress)
        assertEquals(0.0, status.remainingKg, 1e-9)
        assertNull(status.forecast)
        assertNull(status.neededPerWeek)
        assertFalse(status.overdue)
    }

    @Test
    fun deadlineGivesWeeklyPace() {
        val status = WeightStats.status(WeightGoal(80.0, 90.0, today.plusDays(28)), 84.0, null, today)
        assertEquals(1.0, status.neededPerWeek!!, 1e-9)
        assertFalse(status.overdue)
        val late = WeightStats.status(WeightGoal(80.0, 90.0, today.minusDays(1)), 84.0, null, today)
        assertNull(late.neededPerWeek)
        assertTrue(late.overdue)
    }

    @Test
    fun weightAboveStartGivesZeroProgress() {
        assertEquals(0f, WeightStats.status(WeightGoal(80.0, 90.0), 92.0, null, today).progress)
    }

    @Test
    fun newGoalKeepsStartInSameDirection() {
        val previous = WeightGoal(80.0, 90.0)
        assertEquals(WeightGoal(78.0, 90.0, today), WeightStats.newGoal(previous, 78.0, today, current = 85.0))
        assertEquals(WeightGoal(95.0, 85.0), WeightStats.newGoal(previous, 95.0, null, current = 85.0))
        assertEquals(WeightGoal(80.0, 85.0), WeightStats.newGoal(null, 80.0, null, current = 85.0))
    }

    @Test
    fun parseAcceptsCommaAndDot() {
        assertEquals(82.5, WeightStats.parseKg(" 82,5 ")!!, 1e-9)
        assertEquals(82.5, WeightStats.parseKg("82.5")!!, 1e-9)
        assertNull(WeightStats.parseKg("5"))
        assertNull(WeightStats.parseKg("абв"))
    }
}
