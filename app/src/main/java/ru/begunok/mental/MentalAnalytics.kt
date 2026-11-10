package ru.begunok.mental

import ru.begunok.workout.Analytics
import ru.begunok.workout.Period
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Averages use observed calendar days, not the number of entries in a day. */
data class MentalAverages(val mood: Double, val tension: Double, val energy: Double) {
    operator fun minus(other: MentalAverages) = MentalAverages(
        mood - other.mood, tension - other.tension, energy - other.energy,
    )
}

data class MentalDailyAverage(val day: LocalDate, val entries: Int, val averages: MentalAverages)

data class MentalPeriodSummary(
    val entries: Int,
    val observedDays: Int,
    val averages: MentalAverages?,
)

/** Only days with both windows participate, with equal weight per paired day. */
data class MentalDaypartComparison(
    val pairedDays: Int,
    val morning: MentalPeriodSummary,
    val evening: MentalPeriodSummary,
)

data class MentalQuestionnaireSeries(
    val test: MentalTest,
    val results: List<MentalResult>,
    val precedingLatest: MentalResult?,
) {
    val latest: MentalResult? get() = results.lastOrNull()
    val change: Int? get() = latest?.let { current -> precedingLatest?.let { current.score - it.score } }
}

data class MentalAnalytics(
    val start: LocalDate?,
    val endExclusive: LocalDate,
    val previousStart: LocalDate?,
    val entries: List<QuickCheckIn>,
    val daily: List<MentalDailyAverage>,
    val summary: MentalPeriodSummary,
    val previous: MentalPeriodSummary?,
    val dayparts: MentalDaypartComparison?,
    val questionnaires: List<MentalQuestionnaireSeries>,
) {
    companion object {
        fun build(
            checkIns: List<QuickCheckIn>,
            results: List<MentalResult>,
            today: LocalDate,
            period: Period,
            zone: ZoneId = ZoneId.systemDefault(),
        ): MentalAnalytics {
            val start = if (period == Period.All) null else Analytics.periodStart(today, period)
            val end = today.plusDays(1)
            val previousStart = start?.minusDays(ChronoUnit.DAYS.between(start, end))
            val startTime = start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            val endTime = end.atStartOfDay(zone).toInstant().toEpochMilli()
            val previousTime = previousStart?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
            fun inPeriod(time: Long) = time < endTime && (startTime == null || time >= startTime)
            val current = checkIns.filter { inPeriod(it.completedAt) }
                .sortedWith(compareBy<QuickCheckIn> { it.completedAt }.thenBy { it.id })
            val daily = daily(current, zone)
            val previous = if (startTime != null && previousTime != null) {
                summarize(daily(checkIns.filter { it.completedAt >= previousTime && it.completedAt < startTime }, zone))
            } else null
            val morning = current.filter { Instant.ofEpochMilli(it.completedAt).atZone(zone).hour in 6..11 }
            val evening = current.filter { Instant.ofEpochMilli(it.completedAt).atZone(zone).hour in 18..23 }
            val morningDaily = daily(morning, zone)
            val eveningDaily = daily(evening, zone)
            val pairedDays = morningDaily.map { it.day }.toSet().intersect(eveningDaily.map { it.day }.toSet())
            val dayparts = if (pairedDays.size >= 2) MentalDaypartComparison(
                pairedDays.size,
                summarize(morningDaily.filter { it.day in pairedDays }),
                summarize(eveningDaily.filter { it.day in pairedDays }),
            ) else null
            val questionnaires = MentalTest.entries.map { test ->
                val history = results.filter { it.test == test && it.completedAt < endTime }
                    .sortedWith(compareBy<MentalResult> { it.completedAt }.thenBy { it.id })
                val visible = history.filter { inPeriod(it.completedAt) }
                val latestIndex = history.lastIndex
                MentalQuestionnaireSeries(
                    test,
                    visible,
                    if (visible.isNotEmpty()) history.getOrNull(latestIndex - 1) else null,
                )
            }
            return MentalAnalytics(start, end, previousStart, current, daily, summarize(daily), previous, dayparts, questionnaires)
        }

        private fun daily(entries: List<QuickCheckIn>, zone: ZoneId): List<MentalDailyAverage> = entries
            .groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
            .map { (day, items) ->
                MentalDailyAverage(day, items.size, MentalAverages(
                    items.sumOf { it.mood.toDouble() } / items.size,
                    items.sumOf { it.tension.toDouble() } / items.size,
                    items.sumOf { it.energy.toDouble() } / items.size,
                ))
            }.sortedBy { it.day }

        private fun summarize(days: List<MentalDailyAverage>) = MentalPeriodSummary(
            entries = days.sumOf { it.entries },
            observedDays = days.size,
            averages = days.takeIf { it.isNotEmpty() }?.let {
                MentalAverages(it.sumOf { day -> day.averages.mood } / it.size,
                    it.sumOf { day -> day.averages.tension } / it.size,
                    it.sumOf { day -> day.averages.energy } / it.size)
            },
        )
    }
}
