package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthSessionTest {
    @Test
    fun straightSetsRestBetweenSetsAndExercises() {
        val steps = StrengthSession.buildSteps(DayPlan.of(DayType.Strength).strength)
        assertEquals(5 * 3 + 5 * 2 + 4, steps.size)
        assertEquals(StrengthStep(true, 0, 2, 90), steps[1])
        assertEquals(StrengthStep(true, 1, 1, 90), steps[5])
        assertTrue(!steps.last().rest)
    }

    @Test
    fun circuitRestsOnlyBetweenRounds() {
        val steps = StrengthSession.buildSteps(DayPlan.of(DayType.Run).strength)
        assertEquals(4 + 1 + 4, steps.size)
        assertEquals(StrengthStep(true, 0, 2, 90), steps[4])
    }

    @Test
    fun flowThroughRestAndTimedExercise() {
        val block = StrengthBlock(
            "t",
            listOf(Exercise("a", "A", "", 5, 10), Exercise("p", "P", "", 30, 60, timed = true)),
            sets = 1,
            circuit = false,
            restBetweenSetsSec = 0,
            restBetweenExercisesSec = 60,
        )
        val session = StrengthSession(block, mapOf("a" to 7))
        val begin = session.begin(100)
        assertEquals(StrengthEvent.Work(block.exercises[0], 7, 1, 1, false, true), begin.single())

        val done = session.completeSet(6, 120)
        assertEquals(DoneSet("a", 6, 7), (done[0] as StrengthEvent.SetDone).set)
        assertTrue(done[1] is StrengthEvent.Rest)
        assertEquals(60, session.remainingSec(120))
        assertTrue(session.tick(179).isEmpty())
        assertTrue(session.tick(180).single() is StrengthEvent.Work)

        session.startTimed(200)
        assertEquals(10, session.remainingSec(220))
        val finished = session.tick(230)
        assertEquals(DoneSet("p", 30, 30), (finished[0] as StrengthEvent.SetDone).set)
        assertEquals(StrengthEvent.Finished, finished[1])
        assertTrue(session.finished)
        assertTrue(session.view(230).finished)
    }

    @Test
    fun skipRest() {
        val session = StrengthSession(DayPlan.of(DayType.Strength).strength, emptyMap())
        session.begin(0)
        session.completeSet(5, 10)
        assertTrue(session.view(10).resting)
        assertTrue(session.skipRest(11).single() is StrengthEvent.Work)
        assertEquals(2, session.view(11).set)
    }

    private val a = Exercise("a", "A", "", 5, 10)
    private val b = Exercise("b", "B", "", 5, 10)
    private val c = Exercise("c", "C", "", 5, 10)

    @Test
    fun skipExerciseInStraightSetsGoesToNextWithoutRest() {
        val block = StrengthBlock("t", listOf(a, b, c), sets = 3, circuit = false, restBetweenSetsSec = 60, restBetweenExercisesSec = 90)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        repeat(3) { session.completeSet(5, 0); session.skipRest(0) }
        session.completeSet(5, 0)
        session.skipRest(0)
        assertEquals("b", session.view(0).exercise?.id)

        val events = session.skipExercise(0)
        assertEquals(StrengthEvent.Work(c, 5, 1, 3, false, true), events.single())
        assertEquals(setOf("b"), session.view(0).skipped)
        assertEquals(listOf("a", "a", "a", "b"), session.doneSets().map { it.exerciseId })
        session.completeSet(5, 0)
        assertTrue(session.view(0).resting)
    }

    @Test
    fun skipExerciseInCircuitRemovesItFromAllRounds() {
        val block = StrengthBlock("t", listOf(a, b, c), sets = 2, circuit = true, restBetweenSetsSec = 0, restBetweenExercisesSec = 90)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        session.completeSet(5, 0)
        assertEquals(StrengthEvent.Work(c, 5, 1, 2, true, false), session.skipExercise(0).single())

        val roundEnd = session.completeSet(5, 0)
        assertEquals(90, (roundEnd[1] as StrengthEvent.Rest).sec)
        assertEquals(StrengthEvent.Work(a, 5, 2, 2, true, true), session.skipRest(0).single())
        session.completeSet(5, 0)
        assertEquals("c", session.view(0).exercise?.id)
        assertEquals(StrengthEvent.Finished, session.completeSet(5, 0).last())
    }

    @Test
    fun skipLastExerciseOfRoundKeepsRoundRestAndAnnouncesRound() {
        val block = StrengthBlock("t", listOf(a, b), sets = 2, circuit = true, restBetweenSetsSec = 0, restBetweenExercisesSec = 90)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        session.completeSet(5, 0)
        val rest = session.skipExercise(0).single() as StrengthEvent.Rest
        assertEquals(90, rest.sec)
        assertEquals(StrengthEvent.Work(a, 5, 2, 2, true, true), session.skipRest(0).single())
        assertEquals(StrengthEvent.Finished, session.completeSet(5, 0).last())
    }

    @Test
    fun skipFirstExerciseStillAnnouncesNextRound() {
        val block = StrengthBlock("t", listOf(a, b), sets = 2, circuit = true, restBetweenSetsSec = 0, restBetweenExercisesSec = 0)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        assertEquals(StrengthEvent.Work(b, 5, 1, 2, true, false), session.skipExercise(0).single())
        assertEquals(StrengthEvent.Work(b, 5, 2, 2, true, true), session.completeSet(5, 0)[1])
    }

    @Test
    fun skippingTwoExercisesInARowDoesNotAddRest() {
        val block = StrengthBlock("t", listOf(a, b, c), sets = 2, circuit = false, restBetweenSetsSec = 60, restBetweenExercisesSec = 90)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        assertEquals(StrengthEvent.Work(b, 5, 1, 2, false, false), session.skipExercise(0).single())
        assertEquals(StrengthEvent.Work(c, 5, 1, 2, false, false), session.skipExercise(0).single())
        assertEquals(setOf("a", "b"), session.view(0).skipped)
    }

    @Test
    fun skippingEverythingFinishes() {
        val block = StrengthBlock("t", listOf(a), sets = 3, circuit = false, restBetweenSetsSec = 60, restBetweenExercisesSec = 90)
        val session = StrengthSession(block, emptyMap())
        session.begin(0)
        assertEquals(StrengthEvent.Finished, session.skipExercise(0).single())
        assertTrue(session.finished)
        assertTrue(session.skipExercise(0).isEmpty())
    }
}
