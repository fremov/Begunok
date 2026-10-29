package ru.begunok.shower

import ru.begunok.habit.Streaks
import java.time.ZoneId

data class ShowerSession(val time: Long, val durationSec: Int, val targetSec: Int, val completed: Boolean)

data class ShowerStatus(val targetSec: Int, val streak: Int) {
    val atMax: Boolean get() = targetSec >= ShowerProgression.MAX_SEC
}

object ShowerProgression {
    const val START_SEC = 60
    const val MAX_SEC = 180
    const val STEP_SEC = 15
    const val LEVEL_UP_SESSIONS = 3
    const val WEEKLY_GOAL_SEC = 11 * 60

    fun status(recent: List<ShowerSession>): ShowerStatus {
        val last = recent.firstOrNull() ?: return ShowerStatus(START_SEC, 0)
        val streak = recent.takeWhile { it.completed && it.targetSec == last.targetSec }.size
        return if (streak >= LEVEL_UP_SESSIONS && last.targetSec < MAX_SEC) {
            ShowerStatus(minOf(last.targetSec + STEP_SEC, MAX_SEC), 0)
        } else {
            ShowerStatus(last.targetSec, minOf(streak, LEVEL_UP_SESSIONS))
        }
    }

    fun weekSec(recent: List<ShowerSession>, nowMs: Long): Int =
        recent.filter { nowMs - it.time < WEEK_MS }.sumOf { it.durationSec }

    fun dayStreak(recent: List<ShowerSession>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        Streaks.consecutiveDays(recent.map { Streaks.dayOf(it.time, zone) }.toSet(), Streaks.dayOf(nowMs, zone))

    private const val WEEK_MS = 7 * 24 * 60 * 60_000L
}
