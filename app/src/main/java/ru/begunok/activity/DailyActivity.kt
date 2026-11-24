package ru.begunok.activity

import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.DailySteps
import ru.begunok.math.MathActivityEvent
import ru.begunok.math.MathDayActivity
import ru.begunok.math.mathActivityByDay
import ru.begunok.workout.Repetitions
import ru.begunok.workout.WorkoutKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyActivityRow(
    val day: LocalDate,
    val workouts: Int = 0,
    val study: MathDayActivity = MathDayActivity(),
    val pages: Int = 0,
    val showerDurationSec: Long = 0,
    val steps: Long? = null,
    val workoutKinds: Map<String, Int> = emptyMap(),
    val upperReps: Long = 0,
    val lowerReps: Long = 0,
)

object DailyActivity {
    internal fun build(
        workouts: List<WorkoutEntity>,
        reading: List<ReadingEntryEntity>,
        learning: List<MathActivityEvent>,
        showers: List<ColdShowerEntity>,
        steps: List<DailySteps>,
        today: LocalDate,
        start: LocalDate?,
        zone: ZoneId = ZoneId.systemDefault(),
        repetitions: Map<Long, Repetitions> = emptyMap(),
    ): List<DailyActivityRow> {
        val days = mutableMapOf<LocalDate, DayTotals>()
        fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        fun totals(day: LocalDate): DayTotals? {
            if (day > today || (start != null && day < start)) return null
            return days.getOrPut(day) { DayTotals() }
        }

        for (workout in workouts) {
            if (workout.endTime == null) continue
            val day = totals(dateOf(workout.startTime)) ?: continue
            val key = requireNotNull(WorkoutKind.recorded(workout.dayType).key)
            day.workoutKinds[key] = (day.workoutKinds[key] ?: 0) + 1
            repetitions[workout.id]?.let {
                day.upperReps += it.upper
                day.lowerReps += it.lower
            }
        }
        for (entry in reading) {
            val pages = entry.toPage - entry.fromPage
            if (pages > 0) totals(dateOf(entry.time))?.let { it.pages += pages }
        }
        // Topic completions must be deduplicated over the full history, not the selected period.
        for ((day, study) in mathActivityByDay(learning)) {
            totals(day)?.study = study
        }
        for (shower in showers) {
            totals(dateOf(shower.time))?.let { it.showerDurationSec += shower.durationSec.toLong() }
        }
        for (step in steps) {
            totals(step.day)?.let { it.steps = (it.steps ?: 0L) + step.count }
        }

        val first = start ?: days.keys.minOrNull() ?: today
        return buildList {
            var day = today
            while (day >= first) {
                val totals = days[day]
                add(
                    DailyActivityRow(
                        day = day,
                        workouts = totals?.workoutKinds?.values?.sum() ?: 0,
                        workoutKinds = totals?.workoutKinds.orEmpty(),
                        study = totals?.study ?: MathDayActivity(),
                        pages = totals?.pages ?: 0,
                        showerDurationSec = totals?.showerDurationSec ?: 0L,
                        steps = totals?.steps,
                        upperReps = totals?.upperReps ?: 0L,
                        lowerReps = totals?.lowerReps ?: 0L,
                    ),
                )
                if (day == first) break
                day = day.minusDays(1)
            }
        }
    }

    private class DayTotals {
        val workoutKinds = mutableMapOf<String, Int>()
        var study = MathDayActivity()
        var pages = 0
        var showerDurationSec = 0L
        var steps: Long? = null
        var upperReps = 0L
        var lowerReps = 0L
    }
}
