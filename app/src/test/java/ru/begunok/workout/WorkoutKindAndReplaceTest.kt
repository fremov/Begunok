package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.data.db.WorkoutEntity
import java.time.LocalDate
import java.time.ZoneId

class WorkoutKindAndReplaceTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 4)

    private fun workout(id: Long, daysAgo: Long, distance: Double, dayType: String?) = WorkoutEntity(
        id = id,
        startTime = today.minusDays(daysAgo).atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
        durationMs = 1_800_000,
        distanceM = distance,
        upperLimit = 145,
        dayType = dayType,
    )

    private val data = listOf(
        workout(1, 1, 5000.0, DayType.Run.name),
        workout(2, 2, 3000.0, DayType.Strength.name),
        workout(3, 3, 4000.0, null),
    )

    @Test
    fun allDoesNotCountStrengthDistance() {
        val a = Analytics.build(data, emptyList(), today, zone, Period.All, WorkoutKind.All)
        assertEquals(3, a.total)
        assertEquals(9000.0, a.totalDistanceM, 0.0)
        assertEquals(listOf(5000.0, 4000.0), a.runs.map { it.distanceM }.sortedDescending())
        assertEquals(9000.0, a.weeks.sumOf { it.distanceM }, 0.0)
    }

    @Test
    fun strengthKindHasNoRuns() {
        val a = Analytics.build(data, emptyList(), today, zone, Period.All, WorkoutKind.Strength)
        assertEquals(1, a.total)
        assertEquals(0.0, a.totalDistanceM, 0.0)
        assertTrue(a.runs.isEmpty())
    }

    @Test
    fun runKindSkipsStrengthDays() {
        val a = Analytics.build(data, emptyList(), today, zone, Period.All, WorkoutKind.Run)
        assertEquals(2, a.total)
        assertEquals(9000.0, a.totalDistanceM, 0.0)
    }

    @Test
    fun weekPlanIsThreeRunsAndTwoStrength() {
        assertEquals(5, WorkoutKind.All.weekPlan)
        assertEquals(3, WorkoutKind.Run.weekPlan)
        assertEquals(2, WorkoutKind.Strength.weekPlan)
        assertEquals(2, Analytics.build(data, emptyList(), today, zone, Period.All, WorkoutKind.Strength).weekPlan)
    }

    private fun session(): StrengthSession {
        val block = StrengthBlock(
            "Силовая",
            listOf(
                ExerciseCatalog.variation("pullups")!!.copy(id = "Strength:pullups", variationId = "pullups"),
                ExerciseCatalog.variation("dips")!!.copy(id = "Strength:dips", variationId = "dips"),
            ),
            sets = 3, circuit = false, restBetweenSetsSec = 0, restBetweenExercisesSec = 0,
        )
        return StrengthSession(block, mapOf("Strength:pullups" to 6, "Strength:dips" to 8))
    }

    @Test
    fun replaceKeepsDoneSetsAndSwitchesRest() {
        val s = session()
        s.begin(0)
        s.completeSet(6, 10)
        val stepsBefore = s.steps.size
        val events = s.replaceExercise(ExerciseCatalog.variation("australian")!!, 9, 20)
        assertTrue(events.isNotEmpty())
        val v = s.view(20)
        assertEquals("Strength:australian", v.exercise!!.id)
        assertEquals(9, v.target)
        assertEquals(listOf("Strength:pullups"), v.done.map { it.exerciseId })
        assertEquals(stepsBefore, s.steps.size)
        assertEquals("Strength:australian", v.exercises[0].id)
    }

    @Test
    fun replaceWithExerciseFromAnotherLadder() {
        // Дождь: отжимания от пола заменяем приседаниями из другой лесенки.
        val s = session()
        s.begin(0)
        s.replaceExercise(ExerciseCatalog.variation("squats")!!, 15, 5)
        val v = s.view(5)
        assertEquals("Strength:squats", v.exercise!!.id)
        assertEquals(15, v.target)
        assertEquals("Strength:dips", v.exercises[1].id)
    }

    @Test
    fun replaceRejectsSameOrPresentVariation() {
        val s = session()
        s.begin(0)
        assertTrue(s.replaceExercise(ExerciseCatalog.variation("pullups")!!, 5, 1).isEmpty())
        // «Брусья» — другая лесенка, но уже есть в блоке под тем же variationId: id совпал бы с чужим слотом.
        assertTrue(s.replaceExercise(ExerciseCatalog.variation("dips")!!, 5, 1).isEmpty())
        assertEquals("Strength:pullups", s.view(1).exercise!!.id)
    }
}
