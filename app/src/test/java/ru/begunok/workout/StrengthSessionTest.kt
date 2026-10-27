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
}
