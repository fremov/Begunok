package ru.begunok.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthSessionTest {
    @Test
    fun splitCircuitsPrescribeSixMovementsAndBothRestIntervals() {
        val upper = DayPlan.of(DayType.StrengthUpper)
        val lower = DayPlan.of(DayType.StrengthLower)
        assertEquals(listOf(6..12, 8..15, 12..20, 10..15, 10..15, 10..15),
            upper.strength.exercises.map { it.startTarget..it.maxTarget })
        assertEquals(listOf(8..12, 10..12, 12..15, 15..20, 8..12, 15..20),
            lower.strength.exercises.map { it.startTarget..it.maxTarget })
        assertEquals(listOf(true, true, false, false, false, true), lower.strength.exercises.map { it.perSide })
        for ((plan, shortRest) in listOf(upper to 30, lower to 45)) {
            assertEquals(Stage.Strength, plan.initialStage)
            assertEquals(null, plan.runProgram(IntervalProgram.defaults.first()))
            val steps = StrengthSession.buildSteps(plan.strength)
            assertEquals(18, steps.count { !it.rest })
            assertEquals(15, steps.count { it.restSec == shortRest })
            assertEquals(2, steps.count { it.restSec == 120 })
            assertEquals(StrengthStep(true, 0, 2, 120), steps[11])
        }
    }

    @Test
    fun lowerCanDoFourStraightSetsWithOneMinuteRest() {
        val type = DayType.StrengthLower
        val config = DayConfig.default(type).copy(sets = 4, lowerCircuit = false, restBetweenSetsSec = 60)
        val steps = StrengthSession.buildSteps(DayPlan.of(type, config).strength)
        assertEquals(listOf(1, 2, 3, 4), steps.filter { it.exerciseIndex == 0 && !it.rest }.map { it.set })
        assertEquals(23, steps.count { it.restSec == 60 })
        assertEquals(StrengthStep(false, 1, 1), steps[8])
    }

    @Test
    fun chosenUpperVariantsKeepSeparateTargetsWithinTheirPrescribedRange() {
        val type = DayType.StrengthUpper
        val config = DayConfig.default(type)
        val slots = config.slots.toMutableList()
        slots[0] = slots[0].copy(variationId = "upper_assisted_rows")
        slots[2] = slots[2].copy(variationId = "upper_bench_pushups")
        val block = DayPlan.of(type, config.copy(slots = slots)).strength
        val first = block.exercises.first()
        assertEquals("StrengthUpper:upper_assisted_rows", first.id)
        assertEquals("StrengthUpper:upper_bench_pushups", block.exercises[2].id)
        assertEquals("StrengthUpper:upper_rows", block.exercises[3].id)
        val done = List(3) { DoneSet(first.id, 13, 12) }
        assertEquals(12, Progression.nextTarget(first, done, 3))
        assertTrue(Progression.levelUps(block, done).isEmpty())
    }

    @Test
    fun onlyNewStrengthProgramsAndRunningCanBeStarted() {
        assertEquals(listOf(DayType.Run, DayType.StrengthUpper, DayType.StrengthLower), DayType.selectable)
        assertEquals(DayType.Strength, DayType.fromStorage("Strength"))
        assertEquals(DayType.StrengthUpper, DayType.fromStorage("strength_upper"))
        assertEquals(DayType.StrengthLower, DayType.fromStorage("strength_lower"))
        assertEquals(null, DayType.fromStorage(STRETCHING_KEY))
    }

    @Test
    fun strengthDayStartsWithExerciseWithoutJogOrSelectedRunProgram() {
        val plan = DayPlan.of(DayType.Strength)
        assertEquals(Stage.Strength, plan.initialStage)
        assertEquals(null, plan.runProgram(IntervalProgram.defaults.first()))
        assertEquals(null, plan.runProgram(null))
        val session = StrengthSession(plan.strength, emptyMap())
        val first = session.begin(0).single() as StrengthEvent.Work
        assertEquals(plan.strength.exercises.first(), first.exercise)
        assertEquals(1, first.set)
        assertEquals(0, session.view(0).stepIndex)
    }

    @Test
    fun runningDayKeepsWarmupAndSelectedRunBeforeStrength() {
        val plan = DayPlan.of(DayType.Run)
        val selected = IntervalProgram.defaults.first()
        assertEquals(Stage.Run, plan.initialStage)
        val program = requireNotNull(plan.runProgram(selected))
        assertEquals(selected.copy(warmupSec = 300), program)
        assertEquals(IntervalKind.Warmup, program.positionAt(0).kind)
        assertEquals(IntervalKind.Run, program.positionAt(300).kind)
        assertEquals(null, plan.runProgram(null))
    }

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
