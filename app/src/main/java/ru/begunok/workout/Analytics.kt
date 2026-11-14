package ru.begunok.workout

import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ExerciseSetEntity
import ru.begunok.data.db.WorkoutEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class Period(val label: String, val weeks: Int?) {
    Month("4 недели", 4),
    Quarter("3 месяца", 13),
    All("Всё время", null),
}

/** Какие тренировки показывать: все, беговые (и просто бег) или силовые. */
enum class WorkoutKind(val label: String) {
    All("Все"),
    Run("Бег"),
    Strength("Силовой"),
    ;

    /** План на неделю: 3 беговых и 2 силовых, всего 5. */
    val weekPlan: Int get() = when (this) {
        All -> Analytics.WEEK_PLAN
        Run -> Analytics.RUN_PLAN
        Strength -> Analytics.STRENGTH_PLAN
    }

    fun includes(w: WorkoutEntity): Boolean = when (this) {
        All -> true
        Run -> w.dayType != DayType.Strength.name
        Strength -> w.dayType == DayType.Strength.name
    }
}

/** Километры считаются только в беге: в силовой день GPS пишет шаги между снарядами. */
fun WorkoutEntity.countsDistance(): Boolean = dayType != DayType.Strength.name

data class WeekStat(val start: LocalDate, val workouts: Int, val distanceM: Double)
data class AnalyticsPoint(val time: Long, val value: Float)

data class RunPoint(
    val id: Long,
    val time: Long,
    val distanceM: Double,
    val durationMs: Long,
    val paceSecPerKm: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val upperLimit: Int,
    val title: String?,
)

data class WeekSummary(val runs: Int, val distanceM: Double, val durationMs: Long, val paceSecPerKm: Double?, val avgHr: Int?)

data class Records(val longest: RunPoint?, val fastest: RunPoint?, val longestTime: RunPoint?)

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
    val currentWeek: WeekSummary,
    val previousWeek: WeekSummary,
    val records: Records,
    val programRuns: Int,
    val programCompleted: Int,
    val exercises: List<ExerciseStat>,
    val coldShowerDurationSec: Long,
    val steps: Long?,
    val showerSeries: List<AnalyticsPoint>,
    val stepSeries: List<AnalyticsPoint>,
) {
    companion object {
        const val RUN_PLAN = 3
        const val STRENGTH_PLAN = 2
        const val WEEK_PLAN = RUN_PLAN + STRENGTH_PLAN
        const val MAX_WEEKS = 26
        const val MIN_WEEKS = 4
        const val MIN_RUN_M = 500.0
        const val MIN_RECORD_PACE_M = 1_000.0

        fun periodStart(today: LocalDate, period: Period): LocalDate {
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weeks = period.weeks ?: MAX_WEEKS
            return weekStart.minusWeeks((weeks - 1).toLong())
        }

        fun build(
            workouts: List<WorkoutEntity>,
            sets: List<ExerciseSetEntity>,
            today: LocalDate,
            zone: ZoneId = ZoneId.systemDefault(),
            period: Period = Period.All,
            kind: WorkoutKind = WorkoutKind.All,
            showers: List<ColdShowerEntity> = emptyList(),
            steps: Long? = null,
            stepSeries: List<AnalyticsPoint> = emptyList(),
        ): Analytics {
            fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val scoped = workouts.filter { kind.includes(it) }
            val firstDate = scoped.minOfOrNull { dateOf(it.startTime) }
            val weeksCount = period.weeks ?: firstDate
                ?.let { (ChronoUnit.WEEKS.between(it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), weekStart) + 1).toInt() }
                ?.coerceIn(MIN_WEEKS, MAX_WEEKS)
                ?: MIN_WEEKS
            val from = weekStart.minusWeeks((weeksCount - 1).toLong())
            val inPeriod = scoped.filter { !dateOf(it.startTime).isBefore(from) }
            val periodIds = inPeriod.map { it.id }.toSet()

            fun inWeek(start: LocalDate) = scoped.filter { w ->
                dateOf(w.startTime).let { !it.isBefore(start) && it.isBefore(start.plusWeeks(1)) }
            }
            val weeks = (weeksCount - 1 downTo 0).map { i ->
                val start = weekStart.minusWeeks(i.toLong())
                val list = inWeek(start)
                WeekStat(start, list.size, list.filter { it.countsDistance() }.sumOf { it.distanceM })
            }
            val allRuns = workouts
                .filter { it.distanceM >= MIN_RUN_M && it.countsDistance() }
                .sortedBy { it.startTime }
                .map {
                    RunPoint(
                        it.id, it.startTime, it.distanceM, it.durationMs, paceSecPerKm(it.durationMs, it.distanceM),
                        it.avgHr, it.maxHr, it.upperLimit, it.programName,
                    )
                }
            val runs = allRuns.filter { it.id in periodIds }
            val programs = inPeriod.filter { it.programName != null && it.dayType != DayType.Strength.name }
            val coldShowersInPeriod = showers.filter { !dateOf(it.time).isBefore(from) }
            val coldShowerDurationSec = coldShowersInPeriod.sumOf { it.durationSec.toLong() }
            val showerSeries = coldShowersInPeriod
                .groupBy { dateOf(it.time) }
                .toSortedMap()
                .map { (day, sessions) -> AnalyticsPoint(day.atStartOfDay(zone).toInstant().toEpochMilli(), sessions.sumOf { it.durationSec.toLong() }.toFloat()) }
            return Analytics(
                total = inPeriod.size,
                thisWeek = weeks.last().workouts,
                weekPlan = kind.weekPlan,
                totalDistanceM = inPeriod.filter { it.countsDistance() }.sumOf { it.distanceM },
                totalTimeMs = inPeriod.sumOf { it.durationMs },
                daysSinceLast = scoped.maxOfOrNull { it.startTime }?.let { ChronoUnit.DAYS.between(dateOf(it), today).toInt() },
                weeks = weeks,
                runs = runs,
                currentWeek = summary(allRuns.filter { r -> dateOf(r.time).let { !it.isBefore(weekStart) } }),
                previousWeek = summary(
                    allRuns.filter { r -> dateOf(r.time).let { !it.isBefore(weekStart.minusWeeks(1)) && it.isBefore(weekStart) } },
                ),
                records = Records(
                    longest = allRuns.maxByOrNull { it.distanceM },
                    fastest = allRuns.filter { it.distanceM >= MIN_RECORD_PACE_M && it.paceSecPerKm != null }
                        .minByOrNull { it.paceSecPerKm!! },
                    longestTime = allRuns.maxByOrNull { it.durationMs },
                ),
                programRuns = programs.size,
                programCompleted = programs.count { it.programCompleted },
                exercises = exerciseStats(sets.filter { it.workoutId in periodIds }),
                coldShowerDurationSec = coldShowerDurationSec,
                steps = steps,
                showerSeries = showerSeries,
                stepSeries = stepSeries,
            )
        }

        fun summary(runs: List<RunPoint>): WeekSummary {
            val distance = runs.sumOf { it.distanceM }
            val duration = runs.sumOf { it.durationMs }
            val hr = runs.filter { it.avgHr != null }
            return WeekSummary(
                runs = runs.size,
                distanceM = distance,
                durationMs = duration,
                paceSecPerKm = paceSecPerKm(duration, distance),
                avgHr = if (hr.isEmpty()) null else (hr.sumOf { it.avgHr!!.toLong() * it.durationMs } / hr.sumOf { it.durationMs }.coerceAtLeast(1)).toInt(),
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
