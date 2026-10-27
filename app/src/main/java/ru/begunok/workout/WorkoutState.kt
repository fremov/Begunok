package ru.begunok.workout

import ru.begunok.location.GpsStatus

enum class WorkoutPhase { Idle, Running, Paused }

enum class Stage { Run, Break, Strength }

data class WorkoutState(
    val phase: WorkoutPhase = WorkoutPhase.Idle,
    val workoutId: Long? = null,
    val startTime: Long = 0,
    val activeMs: Long = 0,
    val distanceM: Double = 0.0,
    val speedMps: Double? = null,
    val avgPaceSecPerKm: Double? = null,
    val heartRate: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val gps: GpsStatus = GpsStatus.Searching,
    val route: List<LatLon> = emptyList(),
    val programName: String? = null,
    val interval: IntervalPosition? = null,
    val day: DayType? = null,
    val stage: Stage = Stage.Run,
    val stageStartMs: Long = 0,
    val strength: StrengthView? = null,
    val runCompleted: Boolean? = null,
    val levelUps: List<LevelUp> = emptyList(),
    val lastFinished: WorkoutSummary? = null,
) {
    val isActive: Boolean get() = phase != WorkoutPhase.Idle

    val currentPaceSecPerKm: Double?
        get() = speedMps?.takeIf { it >= MIN_PACE_SPEED_MPS }?.let { 1000.0 / it }

    private companion object {
        const val MIN_PACE_SPEED_MPS = 0.5
    }
}
