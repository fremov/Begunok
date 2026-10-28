package ru.begunok.alerts

import ru.begunok.workout.DayPlan
import ru.begunok.workout.DayType
import ru.begunok.workout.Exercise
import ru.begunok.workout.HrAlert
import ru.begunok.workout.IntervalKind
import ru.begunok.workout.IntervalPosition
import ru.begunok.workout.IntervalProgram

object Cues {
    val highHeartRate = Cue(
        id = CueId.HighHr,
        packKey = "hr_high",
        beeps = List(3) { Beep(1400, 150, 70) },
        vibration = longArrayOf(0, 500, 200, 500, 200, 500),
        text = "Пульс высокий, сбавь темп",
    )

    val lowHeartRate = Cue(
        id = CueId.LowHr,
        packKey = "hr_low",
        beeps = listOf(Beep(500, 300, 120), Beep(500, 300)),
        vibration = longArrayOf(0, 200, 150, 200),
        text = "Пульс низкий",
    )

    val countdown = Cue(beeps = listOf(Beep(880, 90, 0)))

    val programFinished = Cue(
        id = CueId.Finish,
        packKey = "finish",
        beeps = listOf(Beep(660, 150), Beep(880, 150), Beep(1100, 400)),
        vibration = longArrayOf(0, 300, 150, 300, 150, 600),
        text = "Программа завершена. Отличная работа!",
    )

    val runFinished = Cue(
        packKey = "break",
        beeps = listOf(Beep(660, 150), Beep(880, 150), Beep(1100, 400)),
        vibration = longArrayOf(0, 300, 150, 300, 150, 600),
        text = "Бег завершён. Перерыв, затем начинай тренировку на площадке",
    )

    fun showerStart(sec: Int) = Cue(
        beeps = listOf(Beep(880, 180), Beep(1320, 350)),
        vibration = longArrayOf(0, 250, 120, 250),
        text = "Холодная вода, ${spokenSpan(sec)}. Дыши ровно",
    )

    val showerHalfMinute = Cue(
        beeps = listOf(Beep(1100, 150)),
        text = "Осталось 30 секунд",
    )

    val showerDone = Cue(
        beeps = listOf(Beep(660, 150), Beep(880, 150), Beep(1100, 400)),
        vibration = longArrayOf(0, 300, 150, 300, 150, 600),
        text = "Готово! Можно выключать холодную воду",
    )

    fun exercise(exercise: Exercise, target: Int, set: Int, sets: Int, circuit: Boolean, firstInRound: Boolean): Cue {
        val amount = when {
            exercise.timed -> spokenSpan(target)
            exercise.perSide -> "$target ${plural(target, "раз", "раза", "раз")} на каждую сторону"
            else -> "$target ${plural(target, "повтор", "повтора", "повторов")}"
        }
        val text = when {
            circuit && firstInRound -> "Круг $set из $sets. ${exercise.name}, $amount"
            circuit -> "${exercise.name}, $amount"
            else -> "${exercise.name}, $amount. Подход $set из $sets"
        }
        return Cue(
            beeps = listOf(Beep(880, 180), Beep(1320, 350)),
            vibration = longArrayOf(0, 250, 120, 250),
            text = text,
        )
    }

    fun rest(sec: Int, next: Exercise, sameExercise: Boolean): Cue = Cue(
        packKey = if (sameExercise) "rest_$sec" else null,
        beeps = listOf(Beep(1100, 180), Beep(660, 450)),
        vibration = longArrayOf(0, 700),
        text = if (sameExercise) "Отдых ${spokenSpan(sec)}" else "Отдых ${spokenSpan(sec)}. Далее ${next.name.lowercase()}",
    )

    fun sample(id: CueId): Cue = when (id) {
        CueId.HighHr -> highHeartRate
        CueId.LowHr -> lowHeartRate
        CueId.Run -> interval(IntervalPosition(IntervalKind.Run, 1, 8, 60, 0), 60, false)
        CueId.Walk -> interval(IntervalPosition(IntervalKind.Walk, 1, 8, 120, 1), 120, false)
        CueId.Finish -> programFinished
    }

    fun hr(alert: HrAlert): Cue = when (alert) {
        HrAlert.High -> highHeartRate
        HrAlert.Low -> lowHeartRate
    }

    fun interval(position: IntervalPosition, segmentSec: Int, continuous: Boolean): Cue = when (position.kind) {
        IntervalKind.Warmup -> Cue(
            packKey = "warmup_$segmentSec",
            beeps = listOf(Beep(1100, 180), Beep(660, 450)),
            vibration = longArrayOf(0, 700),
            text = "Разминка, ${spokenSpan(segmentSec)}. Быстрый шаг",
        )
        IntervalKind.Run -> Cue(
            id = CueId.Run,
            packKey = if (continuous) "run_$segmentSec" else "run_${segmentSec}_${position.repeat}_of_${position.repeats}",
            beeps = listOf(Beep(880, 180), Beep(1320, 350)),
            vibration = longArrayOf(0, 250, 120, 250),
            text = if (continuous) {
                "Бег, ${spokenSpan(segmentSec)}"
            } else {
                "Бег, ${spokenSpan(segmentSec)}. Интервал ${position.repeat} из ${position.repeats}"
            },
        )
        IntervalKind.Walk -> Cue(
            id = CueId.Walk,
            packKey = "walk_$segmentSec",
            beeps = listOf(Beep(1100, 180), Beep(660, 450)),
            vibration = longArrayOf(0, 700),
            text = "Шаг, ${spokenSpan(segmentSec)}",
        )
        null -> programFinished
    }

    fun packPhrases(programs: List<IntervalProgram>): List<PackPhrase> {
        val cues = buildList {
            add(highHeartRate)
            add(lowHeartRate)
            add(programFinished)
            add(runFinished)
            DayType.entries.map { DayPlan.of(it) }.forEach { plan ->
                if (plan.warmupSec > 0) {
                    add(interval(IntervalPosition(IntervalKind.Warmup, 1, 1, plan.warmupSec, -1), plan.warmupSec, false))
                }
                plan.jog?.let { add(interval(IntervalPosition(IntervalKind.Run, 1, 1, it.runSec, 0), it.runSec, true)) }
                val first = plan.strength.exercises.first()
                if (plan.strength.restBetweenSetsSec > 0) add(rest(plan.strength.restBetweenSetsSec, first, true))
            }
            for (p in programs) {
                for (i in 1..p.repeats) {
                    add(interval(IntervalPosition(IntervalKind.Run, i, p.repeats, p.runSec, 0), p.runSec, p.isContinuous))
                    if (p.walkSec > 0) {
                        add(interval(IntervalPosition(IntervalKind.Walk, i, p.repeats, p.walkSec, 1), p.walkSec, false))
                    }
                }
            }
        }
        return cues.mapNotNull { cue -> cue.packKey?.let { key -> cue.text?.let { PackPhrase(key, it) } } }
            .distinctBy { it.key }
    }

    fun spokenSpan(sec: Int): String {
        val min = sec / 60
        val s = sec % 60
        val parts = buildList {
            if (min > 0) add("$min ${plural(min, "минута", "минуты", "минут")}")
            if (s > 0) add("$s ${plural(s, "секунда", "секунды", "секунд")}")
        }
        return parts.joinToString(" ")
    }

    private fun plural(n: Int, one: String, few: String, many: String): String = when {
        n % 10 == 1 && n % 100 != 11 -> one
        n % 10 in 2..4 && n % 100 !in 12..14 -> few
        else -> many
    }
}
