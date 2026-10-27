package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Test
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
        val a = Analytics.build(workouts, sets, today, zone)
        assertEquals(2, a.thisWeek)
        assertEquals(1, a.weeks[a.weeks.size - 2].workouts)
        assertEquals(1, a.daysSinceLast)
        assertEquals(listOf(1_800.0, 2_000.0), a.runs.map { it.distanceM })
        assertEquals(2, a.programRuns)
        assertEquals(1, a.programCompleted)
        val pull = a.exercises.single()
        assertEquals(DayType.Run, pull.day)
        assertEquals(listOf(5, 6), pull.sessions.map { it.best })
        assertEquals(false, pull.last.success)
    }
}
