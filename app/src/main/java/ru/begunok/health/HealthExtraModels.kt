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
    // This is the retained/exported total, even for providers with no detailed staging.
    val sleepDuration: Duration = if (end <= start) Duration.ZERO else {
        var until = start
        var awake = Duration.ZERO
        for (stage in stages.asSequence()
            .filter { it.kind == SleepStageKind.AWAKE || it.kind == SleepStageKind.OUT_OF_BED }
            .sortedBy { it.start }) {
            val from = maxOf(until, stage.start)
            val to = minOf(end, stage.end)
            if (to > from) {
                awake += Duration.between(from, to)
                until = to
            }
        }
        Duration.between(start, end) - awake
    }
}

data class StressSample(val time: Instant, val score: Int)

data class VitalitySample(val time: Instant, val score: Double, val earned: Double? = null)

internal fun SleepStageKind.isDetailedSleep(): Boolean =
    this == SleepStageKind.LIGHT || this == SleepStageKind.DEEP || this == SleepStageKind.REM


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
