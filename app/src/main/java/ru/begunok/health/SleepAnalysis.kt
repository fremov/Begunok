package ru.begunok.health

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

internal val comparedSleepPhases = listOf(SleepStageKind.LIGHT, SleepStageKind.DEEP, SleepStageKind.REM, SleepStageKind.AWAKE)

internal data class SleepScore(val value: Int, val asleep: Duration, val efficiency: Double, val internalAwakenings: Int)

internal data class SleepReport(
    val date: LocalDate,
    val recordedSleep: Duration,
    val intervals: List<SleepStageSample> = emptyList(),
    val phaseDurations: Map<SleepStageKind, Duration> = emptyMap(),
    val score: SleepScore? = null,
)

// The exporter also derives the wake date from the end instant in the phone's current zone.
internal fun sleepReports(nights: List<SleepNight>, durationOnly: Map<LocalDate, Duration>, zone: ZoneId): List<SleepReport> {
    val primary = mutableMapOf<LocalDate, SleepNight>()
    for (night in nights) {
        if (night.end <= night.start) continue
        val date = night.end.atZone(zone).toLocalDate()
        val prior = primary[date]
        if (prior == null || primaryOrder.compare(night, prior) < 0) primary[date] = night
    }
    val reports = primary.mapTo(ArrayList<SleepReport>()) { (date, night) -> analyzeNight(date, night) }
    for ((date, duration) in durationOnly) {
        if (date !in primary && !duration.isNegative) reports.add(SleepReport(date, duration))
    }
    reports.sortBy { it.date }
    return reports
}

private val primaryOrder = compareByDescending<SleepNight> { Duration.between(it.start, it.end) }
    .thenBy { it.start }
    .thenByDescending { night -> night.stages.count { it.kind.isDetailedSleep() && it.end > it.start && it.end > night.start && it.start < night.end } }
    .thenBy { it.source }

private data class Boundary(val at: Instant, val kind: SleepStageKind, val change: Int)

private fun normalizedIntervals(night: SleepNight): List<SleepStageSample> {
    val events = ArrayList<Boundary>(night.stages.size * 2 + 1)
    for (stage in night.stages) {
        val from = maxOf(night.start, stage.start)
        val to = minOf(night.end, stage.end)
        if (from >= to) continue
        val kind = if (stage.kind == SleepStageKind.OUT_OF_BED) SleepStageKind.AWAKE else stage.kind
        events.add(Boundary(from, kind, 1))
        events.add(Boundary(to, kind, -1))
    }
    events.add(Boundary(night.end, SleepStageKind.UNKNOWN, 0))
    events.sortBy { it.at }
    val intervals = mutableListOf<SleepStageSample>()
    val active = IntArray(SleepStageKind.entries.size)
    var from = night.start
    for (event in events) {
        if (event.at > from) {
            val kind = resolvePhase(active)
            val last = intervals.lastOrNull()
            if (last?.kind == kind) intervals[intervals.lastIndex] = last.copy(end = event.at)
            else intervals.add(SleepStageSample(from, event.at, kind))
            from = event.at
        }
        active[event.kind.ordinal] += event.change
    }
    return intervals
}

private fun resolvePhase(active: IntArray): SleepStageKind {
    if (active[SleepStageKind.UNKNOWN.ordinal] > 0) return SleepStageKind.UNKNOWN
    var detail: SleepStageKind? = null
    for (kind in comparedSleepPhases) {
        if (active[kind.ordinal] == 0) continue
        if (detail != null) return SleepStageKind.UNKNOWN
        detail = kind
    }
    // Generic sleep can refine to one specific sleep phase, but contradicts being awake.
    if (active[SleepStageKind.SLEEPING.ordinal] > 0) {
        return if (detail == SleepStageKind.AWAKE) SleepStageKind.UNKNOWN else detail ?: SleepStageKind.SLEEPING
    }
    return detail ?: SleepStageKind.UNKNOWN
}

private fun analyzeNight(date: LocalDate, night: SleepNight): SleepReport {
    val intervals = normalizedIntervals(night)
    val totals = mutableMapOf<SleepStageKind, Duration>()
    for (span in intervals) {
        totals[span.kind] = (totals[span.kind] ?: Duration.ZERO) + Duration.between(span.start, span.end)
    }
    val score = if (SleepStageKind.UNKNOWN in totals || totals.keys.none { it.isDetailedSleep() }) null else {
        val elapsed = Duration.between(night.start, night.end)
        val asleep = elapsed - (totals[SleepStageKind.AWAKE] ?: Duration.ZERO)
        val efficiency = asleep.fractionalSeconds() / elapsed.fractionalSeconds()
        val internal = intervals.count { it.kind == SleepStageKind.AWAKE && it.start > night.start && it.end < night.end }
        val points = 50 * minOf(asleep.fractionalSeconds() / (8 * 3600), 1.0) +
            30 * efficiency + 20 * maxOf(0.0, 1 - internal / 5.0)
        SleepScore(points.roundToInt(), asleep, efficiency, internal)
    }
    return SleepReport(date, night.sleepDuration, intervals, totals, score)
}

internal fun Duration.fractionalSeconds(): Double = seconds + nano / 1_000_000_000.0

internal data class SleepPhaseComparison(val current: Duration?, val previous: Duration?, val weeklyMean: Duration?)

/** Reports are sorted by waking date. Never substitute an older phase for a missing previous-night phase. */
internal fun compareSleepPhases(reports: List<SleepReport>, index: Int): Map<SleepStageKind, SleepPhaseComparison> {
    val current = reports[index]
    val previous = reports.getOrNull(index - 1)
    val since = current.date.minusDays(7)
    return comparedSleepPhases.associateWith { kind ->
        var sum = Duration.ZERO
        var count = 0L
        for (i in index - 1 downTo 0) {
            if (reports[i].date < since) break
            reports[i].phaseDurations[kind]?.let { sum += it; count++ }
        }
        SleepPhaseComparison(current.phaseDurations[kind], previous?.phaseDurations?.get(kind), if (count == 0L) null else sum.dividedBy(count))
    }
}
