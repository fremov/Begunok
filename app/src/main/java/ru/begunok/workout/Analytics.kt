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

enum class Period(val label: String, private val months: Long?) {
    Month("Месяц", 1),
    Quarter("3 мес.", 3),
    Year("Год", 12),
    All("Всё время", null);

    fun start(today: LocalDate): LocalDate? = months?.let { today.plusDays(1).minusMonths(it) }
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
    val paceSecPerKm: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
)

data class RunAverages(val distanceM: Double?, val paceSecPerKm: Double?, val avgHr: Double?)

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
    val runAverages: RunAverages,
    val weeklyDistanceAverageM: Double?,
    val programRuns: Int,
    val programCompleted: Int,
    val exercises: List<ExerciseStat>,
    val coldShowerDurationSec: Long,
    val steps: Long?,
    val showerSeries: List<AnalyticsPoint>,
    val stepSeries: List<AnalyticsPoint>,
    val showerAverageSec: Double?,
    val stepAverage: Double? = null,
) {
    companion object {
        const val RUN_PLAN = 3
        const val STRENGTH_PLAN = 2
        const val WEEK_PLAN = RUN_PLAN + STRENGTH_PLAN
        const val MIN_RUN_M = 500.0

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
            val scoped = workouts.filter { kind.includes(it) && dateOf(it.startTime) <= today }
            val from = period.start(today)
            val inPeriod = if (from == null) scoped else scoped.filter { dateOf(it.startTime) >= from }
            val firstWeek = (from ?: inPeriod.minOfOrNull { dateOf(it.startTime) } ?: today)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val workoutsByWeek = inPeriod.groupBy {
                dateOf(it.startTime).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            }
            val periodIds = inPeriod.map { it.id }.toSet()

            val weeks = generateSequence(firstWeek) { start ->
                if (start < weekStart) start.plusWeeks(1) else null
            }.map { start ->
                val sessions = workoutsByWeek[start].orEmpty()
                WeekStat(start, sessions.size, sessions.filter { it.countsDistance() }.sumOf { it.distanceM })
            }.toList()
            val allRuns = scoped.filter { it.distanceM >= MIN_RUN_M && it.countsDistance() }
                .sortedBy { it.startTime }
                .map {
                    RunPoint(
                        it.id, it.startTime, it.distanceM, paceSecPerKm(it.durationMs, it.distanceM),
                        it.avgHr, it.maxHr,
                    )
                }
            val runs = allRuns.filter { it.id in periodIds }
            val programs = inPeriod.filter { it.programName != null && it.dayType != DayType.Strength.name }
            val showersByDay = showers.groupBy { dateOf(it.time) }.filterKeys { it <= today }
                .mapValues { (_, sessions) -> sessions.sumOf { it.durationSec.toLong() } }
            val selectedShowers = if (from == null) showersByDay else showersByDay.filterKeys { it >= from }
            val coldShowerDurationSec = selectedShowers.values.sum()
            val showerSeries = selectedShowers.toSortedMap().map { (day, seconds) ->
                AnalyticsPoint(day.atStartOfDay(zone).toInstant().toEpochMilli(), seconds.toFloat())
            }
            return Analytics(
                total = inPeriod.size,
                thisWeek = weeks.last().workouts,
                weekPlan = kind.weekPlan,
                totalDistanceM = inPeriod.filter { it.countsDistance() }.sumOf { it.distanceM },
                totalTimeMs = inPeriod.sumOf { it.durationMs },
                daysSinceLast = scoped.maxOfOrNull { it.startTime }?.let { ChronoUnit.DAYS.between(dateOf(it), today).toInt() },
                weeks = weeks,
                runs = runs,
                runAverages = RunAverages(
                    allRuns.asSequence().map { it.distanceM }.average().takeIf { it.isFinite() },
                    allRuns.asSequence().mapNotNull { it.paceSecPerKm }.average().takeIf { it.isFinite() },
                    allRuns.asSequence().mapNotNull { it.avgHr }.average().takeIf { it.isFinite() },
                ),
                weeklyDistanceAverageM = scoped.asSequence().filter { it.countsDistance() }
                    .groupingBy { dateOf(it.startTime).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                    .fold(0.0) { total, workout -> total + workout.distanceM }
                    .values.average().takeIf { it.isFinite() },
                programRuns = programs.size,
                programCompleted = programs.count { it.programCompleted },
                exercises = exerciseStats(sets.filter { it.workoutId in periodIds }),
                coldShowerDurationSec = coldShowerDurationSec,
                steps = steps,
                showerSeries = showerSeries,
                stepSeries = stepSeries,
                showerAverageSec = showersByDay.values.average().takeIf { it.isFinite() },
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
