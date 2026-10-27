package ru.begunok.workout

import ru.begunok.data.db.WorkoutEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Grouping(val label: String) {
    Week("Недели"),
    Month("Месяцы"),
    Year("Годы"),
}

data class HistoryGroup(val key: String, val title: String, val subtitle: String?, val workouts: List<WorkoutEntity>) {
    val distanceM: Double get() = workouts.sumOf { it.distanceM }
    val durationMs: Long get() = workouts.sumOf { it.durationMs }
    val strengthDays: Int get() = workouts.count { it.dayType == DayType.Strength.name }
}

private val ru = Locale.forLanguageTag("ru")
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)

fun groupWorkouts(
    workouts: List<WorkoutEntity>,
    grouping: Grouping,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): List<HistoryGroup> {
    val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return workouts
        .sortedByDescending { it.startTime }
        .groupBy { w ->
            val date = Instant.ofEpochMilli(w.startTime).atZone(zone).toLocalDate()
            when (grouping) {
                Grouping.Week -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Grouping.Month -> date.withDayOfMonth(1)
                Grouping.Year -> date.withDayOfYear(1)
            }
        }
        .map { (start, list) ->
            val (title, subtitle) = when (grouping) {
                Grouping.Week -> {
                    val range = "${start.format(dayMonth)} – ${start.plusDays(6).format(dayMonth)}"
                    when (start) {
                        thisWeek -> "Эта неделя" to range
                        thisWeek.minusWeeks(1) -> "Прошлая неделя" to range
                        else -> range to null
                    }
                }
                Grouping.Month -> {
                    val month = start.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.titlecase(ru) }
                    (if (start.year == today.year) month else "$month ${start.year}") to null
                }
                Grouping.Year -> "${start.year}" to null
            }
            HistoryGroup("${grouping.name}-$start", title, subtitle, list)
        }
}
