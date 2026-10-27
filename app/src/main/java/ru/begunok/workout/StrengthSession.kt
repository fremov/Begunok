package ru.begunok.workout

data class StrengthStep(
    val rest: Boolean,
    val exerciseIndex: Int,
    val set: Int,
    val restSec: Int = 0,
)

data class StrengthView(
    val title: String,
    val stepIndex: Int,
    val exercise: Exercise?,
    val exerciseNumber: Int,
    val exerciseCount: Int,
    val set: Int,
    val sets: Int,
    val circuit: Boolean,
    val target: Int,
    val resting: Boolean,
    val remainingSec: Int?,
    val timedRunning: Boolean,
    val nextExercise: Exercise?,
    val done: List<DoneSet>,
    val exercises: List<Exercise>,
) {
    val finished: Boolean get() = exercise == null
}

sealed interface StrengthEvent {
    data class SetDone(val set: DoneSet, val exercise: Exercise, val number: Int) : StrengthEvent
    data class Work(val exercise: Exercise, val target: Int, val set: Int, val sets: Int, val circuit: Boolean, val firstInRound: Boolean) : StrengthEvent
    data class Rest(val sec: Int, val next: Exercise, val sameExercise: Boolean) : StrengthEvent
    data object Finished : StrengthEvent
}

class StrengthSession(val block: StrengthBlock, private val targets: Map<String, Int>) {
    val steps: List<StrengthStep> = buildSteps(block)
    private val done = mutableListOf<DoneSet>()
    private var index = 0
    private var stepStartSec = 0L
    private var timedStartSec: Long? = null

    val finished: Boolean get() = index >= steps.size

    private val current: StrengthStep? get() = steps.getOrNull(index)

    fun target(exercise: Exercise): Int = targets[exercise.id] ?: exercise.startTarget

    fun begin(nowSec: Long): List<StrengthEvent> {
        index = 0
        stepStartSec = nowSec
        return listOfNotNull(workEvent())
    }

    fun completeSet(reps: Int, nowSec: Long): List<StrengthEvent> {
        val step = current?.takeIf { !it.rest } ?: return emptyList()
        val exercise = block.exercises[step.exerciseIndex]
        val set = DoneSet(exercise.id, reps.coerceAtLeast(0), target(exercise))
        done += set
        timedStartSec = null
        return listOf(StrengthEvent.SetDone(set, exercise, step.set)) + advance(nowSec)
    }

    fun startTimed(nowSec: Long) {
        val step = current?.takeIf { !it.rest } ?: return
        if (block.exercises[step.exerciseIndex].timed && timedStartSec == null) timedStartSec = nowSec
    }

    fun skipRest(nowSec: Long): List<StrengthEvent> =
        if (current?.rest == true) advance(nowSec) else emptyList()

    fun tick(nowSec: Long): List<StrengthEvent> {
        val step = current ?: return emptyList()
        if (step.rest) {
            return if (nowSec - stepStartSec >= step.restSec) advance(nowSec) else emptyList()
        }
        val started = timedStartSec ?: return emptyList()
        val exercise = block.exercises[step.exerciseIndex]
        return if (nowSec - started >= target(exercise)) completeSet(target(exercise), nowSec) else emptyList()
    }

    fun doneSets(): List<DoneSet> = done.toList()

    fun timedElapsed(nowSec: Long): Int? = timedStartSec?.let { (nowSec - it).toInt() }

    fun remainingSec(nowSec: Long): Int? {
        val step = current ?: return null
        if (step.rest) return (step.restSec - (nowSec - stepStartSec)).toInt().coerceAtLeast(0)
        val started = timedStartSec ?: return null
        return (target(block.exercises[step.exerciseIndex]) - (nowSec - started)).toInt().coerceAtLeast(0)
    }

    fun view(nowSec: Long): StrengthView {
        val step = current
        val exercise = step?.let { block.exercises[it.exerciseIndex] }
        val next = steps.drop(index + 1).firstOrNull { !it.rest }?.let { block.exercises[it.exerciseIndex] }
        return StrengthView(
            title = block.title,
            stepIndex = index,
            exercise = exercise,
            exerciseNumber = (step?.exerciseIndex ?: block.exercises.lastIndex) + 1,
            exerciseCount = block.exercises.size,
            set = step?.set ?: block.sets,
            sets = block.sets,
            circuit = block.circuit,
            target = exercise?.let(::target) ?: 0,
            resting = step?.rest == true,
            remainingSec = remainingSec(nowSec),
            timedRunning = timedStartSec != null,
            nextExercise = if (step?.rest == true) exercise else next,
            done = done.toList(),
            exercises = block.exercises,
        )
    }

    private fun advance(nowSec: Long): List<StrengthEvent> {
        index++
        stepStartSec = nowSec
        timedStartSec = null
        val step = current ?: return listOf(StrengthEvent.Finished)
        if (!step.rest) return listOfNotNull(workEvent())
        val previous = steps[index - 1]
        return listOf(StrengthEvent.Rest(step.restSec, block.exercises[step.exerciseIndex], previous.exerciseIndex == step.exerciseIndex))
    }

    private fun workEvent(): StrengthEvent.Work? {
        val step = current?.takeIf { !it.rest } ?: return null
        val exercise = block.exercises[step.exerciseIndex]
        return StrengthEvent.Work(exercise, target(exercise), step.set, block.sets, block.circuit, step.exerciseIndex == 0)
    }

    companion object {
        fun buildSteps(block: StrengthBlock): List<StrengthStep> {
            val works = if (block.circuit) {
                (1..block.sets).flatMap { set -> block.exercises.indices.map { StrengthStep(false, it, set) } }
            } else {
                block.exercises.indices.flatMap { e -> (1..block.sets).map { StrengthStep(false, e, it) } }
            }
            return buildList {
                works.forEachIndexed { i, work ->
                    if (i > 0) {
                        val previous = works[i - 1]
                        val sameGroup = if (block.circuit) previous.set == work.set else previous.exerciseIndex == work.exerciseIndex
                        val rest = if (sameGroup) block.restBetweenSetsSec else block.restBetweenExercisesSec
                        if (rest > 0) add(StrengthStep(true, work.exerciseIndex, work.set, rest))
                    }
                    add(work)
                }
            }
        }
    }
}
