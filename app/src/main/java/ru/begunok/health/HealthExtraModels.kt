package ru.begunok.health

import java.time.Duration
import java.time.Instant

enum class SleepStageKind { UNKNOWN, AWAKE, SLEEPING, OUT_OF_BED, LIGHT, DEEP, REM }

data class SleepStageSample(val start: Instant, val end: Instant, val kind: SleepStageKind)

data class SleepNight(
    val start: Instant,
    val end: Instant,
    val stages: List<SleepStageSample> = emptyList(),
    val source: String = "",
) {
    // Keep source samples intact; clipping is only for interval-derived metrics.
    private val clippedStages = stages.mapNotNull { stage ->
        val from = maxOf(start, stage.start)
        val until = minOf(end, stage.end)
        if (until.isAfter(from)) stage.copy(start = from, end = until) else null
    }
    private val sleepingIntervals = mergeSleepIntervals(clippedStages.filter { it.kind.isSleep() }.map { it.start to it.end })
    private val awakeIntervals = mergeSleepIntervals(clippedStages.filter {
        it.kind == SleepStageKind.AWAKE || it.kind == SleepStageKind.OUT_OF_BED
    }.map { it.start to it.end })

    // An unstaged session still provides its recorded duration, not estimated sleep stages.
    val sleepDuration: Duration = if (!end.isAfter(start)) Duration.ZERO
        else Duration.between(start, end) - sleepIntervalDuration(awakeIntervals)

    private val fullyStaged = end.isAfter(start) && sleepIntervalDuration(mergeSleepIntervals(
        clippedStages.filter { it.kind != SleepStageKind.UNKNOWN }.map { it.start to it.end }
    )) == Duration.between(start, end)
    val awakeDuration: Duration? = if (awakeIntervals.isNotEmpty() || fullyStaged)
        sleepIntervalDuration(awakeIntervals) else null
    // Only internal wake episodes count: bedtime latency and the final waking are excluded.
    val awakeningCount: Int? = if (awakeIntervals.isEmpty() && fullyStaged) 0
    else if (sleepingIntervals.isEmpty() || awakeIntervals.isEmpty()) null else awakeIntervals.count { awake ->
        sleepingIntervals.any { it.first.isBefore(awake.first) } &&
            sleepingIntervals.any { it.second.isAfter(awake.second) }
    }

    fun stageDuration(kind: SleepStageKind): Duration? {
        if (clippedStages.none { it.kind == kind }) return null
        return sleepIntervalDuration(mergeSleepIntervals(clippedStages.filter { it.kind == kind }.map { it.start to it.end }))
    }
}

data class StressSample(val time: Instant, val score: Int)

data class VitalitySample(val time: Instant, val score: Double, val earned: Double? = null)

private fun SleepStageKind.isDetailedSleep(): Boolean =
    this == SleepStageKind.LIGHT || this == SleepStageKind.DEEP || this == SleepStageKind.REM

private fun SleepStageKind.isSleep(): Boolean = this == SleepStageKind.SLEEPING || isDetailedSleep()

private fun mergeSleepIntervals(intervals: List<Pair<Instant, Instant>>): List<Pair<Instant, Instant>> {
    if (intervals.isEmpty()) return emptyList()
    val sorted = intervals.sortedBy { it.first }
    val merged = ArrayList<Pair<Instant, Instant>>()
    var start = sorted.first().first
    var end = sorted.first().second
    for (index in 1 until sorted.size) {
        val interval = sorted[index]
        if (interval.first.isAfter(end)) {
            merged.add(start to end)
            start = interval.first
            end = interval.second
        } else if (interval.second.isAfter(end)) {
            end = interval.second
        }
    }
    merged.add(start to end)
    return merged
}

private fun sleepIntervalDuration(intervals: List<Pair<Instant, Instant>>): Duration =
    intervals.fold(Duration.ZERO) { total, interval -> total + Duration.between(interval.first, interval.second) }

fun normalizeSleepNights(nights: List<SleepNight>): List<SleepNight> {
    val ranked = nights.filter { it.end.isAfter(it.start) }.sortedWith(
        compareByDescending<SleepNight> { night ->
            night.stages.count { stage ->
                stage.kind.isDetailedSleep() && stage.end.isAfter(stage.start) &&
                    stage.end.isAfter(night.start) && stage.start.isBefore(night.end)
            }
        }.thenByDescending { night ->
            night.stages.count { stage ->
                stage.kind != SleepStageKind.UNKNOWN && stage.end.isAfter(stage.start) &&
                    stage.end.isAfter(night.start) && stage.start.isBefore(night.end)
            }
        }.thenByDescending { Duration.between(it.start, it.end) }
            .thenBy { it.source }
            .thenBy { it.start },
    )
    val selected = mutableListOf<SleepNight>()
    for (night in ranked) {
        // Keep one complete source record instead of stitching conflicting stages.
        if (selected.none { it.start.isBefore(night.end) && night.start.isBefore(it.end) }) selected.add(night)
    }
    return selected.sortedBy { it.start }
}
