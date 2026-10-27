package ru.begunok.workout

import ru.begunok.data.db.ExerciseSetEntity
import ru.begunok.data.db.WorkoutEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class WeekStat(val start: LocalDate, val workouts: Int, val distanceM: Double)

data class RunPoint(val time: Long, val distanceM: Double, val paceSecPerKm: Double?, val avgHr: Int?)

data class ExerciseSession(val time: Long, val target: Int, val best: Int, val total: Int, val reps: List<Int>) {
    val success: Boolean get() = reps.all { it >= target }
}

data class ExerciseStat(
    val key: String,
    val name: String,
    val day: DayType?,
    val timed: Boolean,
    val sessions: List<ExerciseSession>,
) {
    val last: ExerciseSession get() = sessions.last()
    val best: Int get() = sessions.maxOf { it.best }
}

data class Analytics(
    val total: Int,
    val thisWeek: Int,
    val weekPlan: Int,
    val totalDistanceM: Double,
    val totalTimeMs: Long,
    val daysSinceLast: Int?,
    val weeks: List<WeekStat>,
    val runs: List<RunPoint>,
    val programRuns: Int,
    val programCompleted: Int,
    val exercises: List<ExerciseStat>,
) {
    companion object {
        const val WEEK_PLAN = 5
        const val WEEKS = 8
        const val MAX_POINTS = 20
        const val MIN_RUN_M = 500.0

        fun build(
            workouts: List<WorkoutEntity>,
            sets: List<ExerciseSetEntity>,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Analytics {
            fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeks = (WEEKS - 1 downTo 0).map { i ->
                val start = weekStart.minusWeeks(i.toLong())
                val inWeek = workouts.filter { w -> dateOf(w.startTime).let { !it.isBefore(start) && it.isBefore(start.plusWeeks(1)) } }
                WeekStat(start, inWeek.size, inWeek.sumOf { it.distanceM })
            }
            val runs = workouts
                .filter { it.distanceM >= MIN_RUN_M }
                .sortedBy { it.startTime }
                .takeLast(MAX_POINTS)
                .map { RunPoint(it.startTime, it.distanceM, paceSecPerKm(it.durationMs, it.distanceM), it.avgHr) }
            val programs = workouts.filter { it.programName != null && it.dayType != DayType.Strength.name }
            return Analytics(
                total = workouts.size,
                thisWeek = weeks.last().workouts,
                weekPlan = WEEK_PLAN,
                totalDistanceM = workouts.sumOf { it.distanceM },
                totalTimeMs = workouts.sumOf { it.durationMs },
                daysSinceLast = workouts.maxOfOrNull { it.startTime }?.let { ChronoUnit.DAYS.between(dateOf(it), today).toInt() },
                weeks = weeks,
                runs = runs,
                programRuns = programs.size,
                programCompleted = programs.count { it.programCompleted },
                exercises = exerciseStats(sets),
            )
        }

        fun exerciseStats(sets: List<ExerciseSetEntity>): List<ExerciseStat> =
            sets.groupBy { it.exerciseId }.map { (key, all) ->
                val sessions = all.groupBy { it.workoutId }.values
                    .map { s ->
                        val reps = s.sortedBy { it.time }.map { it.reps }
                        ExerciseSession(s.minOf { it.time }, s.maxOf { it.target }, reps.max(), reps.sum(), reps)
                    }
                    .sortedBy { it.time }
                ExerciseStat(key, all.last().exerciseName, dayOf(key), all.first().timed, sessions)
            }.sortedByDescending { it.last.time }

        private fun dayOf(key: String): DayType? = when {
            key.startsWith("c_") -> DayType.Run
            key.startsWith("s_") -> DayType.Strength
            else -> DayType.entries.firstOrNull { key.startsWith("${it.name}:") }
        }
    }
}
