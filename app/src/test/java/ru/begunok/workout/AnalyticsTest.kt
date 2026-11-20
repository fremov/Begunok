package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ExerciseSetEntity
import ru.begunok.data.db.WorkoutEntity
import java.time.LocalDate
import java.time.ZoneOffset

class AnalyticsTest {
    private val zone = ZoneOffset.UTC
    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 8 * 3_600_000

    @Test
    fun weeksRunsAndExercises() {
        val today = LocalDate.of(2026, 10, 28)
        val workouts = listOf(
            WorkoutEntity(1, millis(LocalDate.of(2026, 10, 26)), 1, 600_000, 2_000.0, 140, 160, 145, null, "Уровень 1", "Run", true),
            WorkoutEntity(2, millis(LocalDate.of(2026, 10, 27)), 1, 900_000, 300.0, 120, 150, 145, null, "Трусца", "Strength", true),
            WorkoutEntity(3, millis(LocalDate.of(2026, 10, 20)), 1, 600_000, 1_800.0, 142, 158, 145, null, "Уровень 1", "Run", false),
        )
        val sets = listOf(
            ExerciseSetEntity(0, 3, 10, "Run:pullups", "Подтягивания", 1, 5, 5, false),
            ExerciseSetEntity(0, 1, 20, "Run:pullups", "Подтягивания", 1, 6, 6, false),
            ExerciseSetEntity(0, 1, 21, "Run:pullups", "Подтягивания", 2, 5, 6, false),
        )
        val a = Analytics.build(
            workouts,
            sets,
            today,
            zone,
            showers = listOf(
                ColdShowerEntity(1, millis(LocalDate.of(2026, 10, 27)), 90, 180, true),
                ColdShowerEntity(2, millis(LocalDate.of(2026, 9, 1)), 60, 60, true),
                ColdShowerEntity(3, millis(LocalDate.of(2026, 10, 27)) + 60_000, 35, 90, false),
            ),
            steps = 12_345,
        )
        assertEquals(2, a.thisWeek)
        assertEquals(1, a.weeks[a.weeks.size - 2].workouts)
        assertEquals(1, a.daysSinceLast)
        assertEquals(listOf(1_800.0, 2_000.0), a.runs.map { it.distanceM })
        assertEquals(2, a.programRuns)
        assertEquals(1, a.programCompleted)
        assertEquals(185L, a.coldShowerDurationSec)
        assertEquals(listOf(60f, 125f), a.showerSeries.map { it.value })
        assertEquals(12_345L, a.steps)
        val pull = a.exercises.single()
        assertEquals(DayType.Run, pull.day)
        assertEquals(listOf(5, 6), pull.sessions.map { it.best })
        assertEquals(false, pull.last.success)
    }

    @Test
    fun yearIncludesBoundaryAndAllKeepsWorkoutsOlderThanAYear() {
        val today = LocalDate.of(2026, 11, 10)
        val recent = WorkoutEntity(1, millis(today), 1, 600_000, 2_000.0, 140, 160, 145, null)
        val boundary = recent.copy(id = 2, startTime = millis(LocalDate.of(2025, 11, 11)))
        val before = recent.copy(id = 3, startTime = boundary.startTime - 24 * 3_600_000, distanceM = 5_000.0, avgHr = null)
        val future = recent.copy(id = 4, startTime = millis(today.plusDays(1)), distanceM = 7_000.0)
        val workouts = listOf(recent, boundary, before, future)
        val year = Analytics.build(workouts, emptyList(), today, zone, Period.Year)
        val all = Analytics.build(workouts, emptyList(), today, zone, Period.All)

        assertEquals(listOf(2L, 1L), year.runs.map { it.id })
        assertEquals(3_000.0, requireNotNull(year.runAverages.distanceM), 0.01)
        assertEquals(140.0, requireNotNull(year.runAverages.avgHr), 0.01)
        assertEquals(listOf(3L, 2L, 1L), all.runs.map { it.id })
        assertEquals(3, all.weeks.sumOf { it.workouts })
    }
}
