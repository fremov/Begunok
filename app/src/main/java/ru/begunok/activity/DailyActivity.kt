package ru.begunok.activity

import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.DailySteps
import ru.begunok.math.MathActivityEvent
import ru.begunok.math.MathDayActivity
import ru.begunok.math.mathActivityByDay
import ru.begunok.workout.Analytics
import ru.begunok.workout.Period
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyGameActivity(val id: String, val endedAt: Long, val durationSec: Int, val queueId: Int = 0)

data class DailyActivityRow(
    val day: LocalDate,
    val workouts: Int = 0,
    val study: MathDayActivity = MathDayActivity(),
    val pages: Int = 0,
    val games: Int? = null,
    val gameDurationSec: Long? = null,
    val showerDurationSec: Long = 0,
    val steps: Long? = null,
)

object DailyActivity {
    fun build(
        workouts: List<WorkoutEntity>,
        reading: List<ReadingEntryEntity>,
        learning: List<MathActivityEvent>,
        showers: List<ColdShowerEntity>,
        games: List<DailyGameActivity>?,
        steps: List<DailySteps>,
        today: LocalDate,
        period: Period,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailyActivityRow> {
        val start = if (period == Period.All) null else Analytics.periodStart(today, period)
        val days = mutableMapOf<LocalDate, DayTotals>()
        fun dateOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        fun totals(day: LocalDate): DayTotals? {
            if (day > today || (start != null && day < start)) return null
            return days.getOrPut(day) { DayTotals() }
        }

        for (workout in workouts) {
            if (workout.endTime == null) continue
            totals(dateOf(workout.startTime))?.let { it.workouts++ }
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
        if (games != null) {
            val seen = mutableSetOf<String>()
            for (game in games) {
                if (!seen.add(game.id)) continue
                totals(dateOf(game.endedAt))?.let {
                    it.games++
                    it.gameDurationSec += game.durationSec.toLong()
                }
            }
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
                        workouts = totals?.workouts ?: 0,
                        study = totals?.study ?: MathDayActivity(),
                        pages = totals?.pages ?: 0,
                        games = if (games == null) null else totals?.games ?: 0,
                        gameDurationSec = if (games == null) null else totals?.gameDurationSec ?: 0L,
                        showerDurationSec = totals?.showerDurationSec ?: 0L,
                        steps = totals?.steps,
                    ),
                )
                if (day == first) break
                day = day.minusDays(1)
            }
        }
    }

    private class DayTotals {
        var workouts = 0
        var study = MathDayActivity()
        var pages = 0
        var games = 0
        var gameDurationSec = 0L
        var showerDurationSec = 0L
        var steps: Long? = null
    }
}
