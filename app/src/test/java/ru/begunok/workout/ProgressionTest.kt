package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.ExerciseSetEntity

class ProgressionTest {
    private val pullups = Exercise("p", "Подтягивания", "", 5, 12, variationId = "pullups")
    private val plank = Exercise("k", "Планка", "", 30, 90, timed = true)

    private fun sets(target: Int, vararg reps: Int) = reps.map { DoneSet("p", it, target) }

    @Test
    fun startsFromDefault() {
        assertEquals(5, Progression.nextTarget(pullups, emptyList()))
    }

    @Test
    fun allSetsDoneAddsStep() {
        assertEquals(7, Progression.nextTarget(pullups, sets(6, 6, 6, 7)))
        assertEquals(35, Progression.nextTarget(plank, listOf(DoneSet("k", 30, 30), DoneSet("k", 30, 30))))
    }

    @Test
    fun doingMoreJumpsToAchieved() {
        assertEquals(9, Progression.nextTarget(pullups, sets(6, 9, 10, 9)))
    }

    @Test
    fun missedSetsKeepTargetOrDrop() {
        assertEquals(6, Progression.nextTarget(pullups, sets(6, 6, 5, 4)))
        assertEquals(3, Progression.nextTarget(pullups, sets(5, 3, 2, 2)))
    }

    @Test
    fun nextVariationAfterReachingTop() {
        val block = StrengthBlock("t", listOf(pullups), 3, false, 90, 120)
        assertTrue(Progression.levelUps(block, sets(11, 11, 11, 11)).isEmpty())
        val up = Progression.levelUps(block, sets(12, 12, 12, 12)).single()
        assertEquals("wide_pullups", up.to.id)
        assertTrue(Progression.levelUps(block, sets(12, 12, 12, 10)).isEmpty())
    }

    @Test
    fun planUsesConfiguredVariationsWithDayKeys() {
        val config = DayConfig(listOf(Slot("pull", "negative_pullups")), 3, 90, 120)
        val exercise = DayPlan.of(DayType.Strength, config).strength.exercises.single()
        assertEquals("Strength:negative_pullups", exercise.id)
        assertEquals("negative_pullups", exercise.variationId)
        assertEquals(3, exercise.startTarget)
    }

    @Test
    fun runStreakCountsOnlyCurrentLevel() {
        val recent = listOf("Уровень 1" to true, "Уровень 1" to false, "Уровень 1" to true)
        assertEquals(1, Progression.successStreak(recent, "Уровень 1"))
    }

    @Test
    fun levelUpAfterThreeCompletedSessions() {
        val done = List(3) { "Уровень 1" to true }
        assertTrue(Progression.shouldSuggestNextLevel(done, "Уровень 1"))
        assertFalse(Progression.shouldSuggestNextLevel(done.take(2), "Уровень 1"))
        assertFalse(Progression.shouldSuggestNextLevel(listOf("Уровень 1" to false) + done.take(2), "Уровень 1"))
        assertFalse(Progression.shouldSuggestNextLevel(done, "Уровень 2"))
    }

    @Test
    fun lastSessionsTakeMostRecentWorkout() {
        fun set(workout: Long, time: Long, reps: Int) =
            ExerciseSetEntity(0, workout, time, "p", "P", 1, reps, 5, false)
        val recent = listOf(set(2, 300, 6), set(2, 290, 5), set(1, 100, 4))
        assertEquals(listOf(DoneSet("p", 6, 5), DoneSet("p", 5, 5)), WorkoutRepository.lastSessions(recent)["p"])
    }

    @Test
    fun warmupBeforeIntervals() {
        val p = IntervalProgram("1", "1", 60, 120, 8, warmupSec = 300)
        assertEquals(IntervalPosition(IntervalKind.Warmup, 1, 1, 300, -1), p.positionAt(0))
        assertEquals(IntervalPosition(IntervalKind.Run, 1, 8, 60, 0), p.positionAt(300))
        assertTrue(p.positionAt(300 + 8 * 180L).finished)
    }
}
