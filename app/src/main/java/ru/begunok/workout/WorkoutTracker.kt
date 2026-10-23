package ru.begunok.workout

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
)

data class WorkoutSummary(
    val workoutId: Long,
    val startTime: Long,
    val durationMs: Long,
    val distanceM: Double,
    val avgHr: Int?,
    val maxHr: Int?,
) {
    val avgPaceSecPerKm: Double? get() = paceSecPerKm(durationMs, distanceM)
}

fun paceSecPerKm(durationMs: Long, distanceM: Double): Double? =
    if (distanceM < 10.0) null else durationMs / 1000.0 / (distanceM / 1000.0)

fun distanceM(a: LatLon, b: LatLon): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

class WorkoutTracker {
    var activeMs = 0L
        private set
    var distanceM = 0.0
        private set
    var paused = false
        private set
    var currentSpeedMps: Double? = null
        private set
    var lastFix: Fix? = null
        private set
    var maxHr: Int? = null
        private set
    val route = ArrayList<LatLon>()

    val avgHr: Int?
        get() = if (hrCount == 0) null else (hrSum.toDouble() / hrCount).let { Math.round(it).toInt() }

    val avgPaceSecPerKm: Double?
        get() = paceSecPerKm(activeMs, distanceM)

    private var anchor: Fix? = null
    private var lastTickMs: Long? = null
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var hrSum = 0L
    private var hrCount = 0

    fun onFix(fix: Fix): Boolean {
        if (fix.accuracy > MAX_ACCURACY_M) return false
        val prev = anchor
        if (prev != null) {
            val dt = (fix.timeMs - prev.timeMs) / 1000.0
            if (dt <= 0) return false
            val d = distanceM(prev.latLon, fix.latLon)
            if (d / dt > MAX_SPEED_MPS) return false
            lastFix = fix
            if (paused || d < MIN_STEP_M) return true
            distanceM += d
        } else {
            lastFix = fix
            if (paused) return true
        }
        anchor = fix
        route += fix.latLon
        return true
    }

    fun tick(nowMs: Long, heartRate: Int?) {
        val last = lastTickMs ?: nowMs
        lastTickMs = nowMs
        if (paused) return
        activeMs += (nowMs - last).coerceAtLeast(0)
        if (heartRate != null) {
            hrSum += heartRate
            hrCount++
            maxHr = maxOf(maxHr ?: heartRate, heartRate)
        }
        updateSpeed(nowMs)
    }

    fun pause(nowMs: Long) {
        if (paused) return
        tick(nowMs, null)
        paused = true
        anchor = null
        window.clear()
        currentSpeedMps = null
    }

    fun resume(nowMs: Long) {
        if (!paused) return
        paused = false
        anchor = null
        lastTickMs = nowMs
    }

    private fun updateSpeed(nowMs: Long) {
        window.addLast(nowMs to distanceM)
        while (window.size >= 2 && window[1].first <= nowMs - SPEED_WINDOW_MS) window.removeFirst()
        val (startMs, startDistance) = window.first()
        val span = nowMs - startMs
        val fixFresh = lastFix?.let { nowMs - it.timeMs <= FIX_STALE_MS } == true
        currentSpeedMps = if (fixFresh && span >= MIN_SPEED_SPAN_MS) {
            (distanceM - startDistance) / (span / 1000.0)
        } else {
            null
        }
    }

    private val Fix.latLon get() = LatLon(lat, lon)

    companion object {
        const val MAX_ACCURACY_M = 20f
        const val MAX_SPEED_MPS = 10.0
        const val MIN_STEP_M = 3.0
        const val SPEED_WINDOW_MS = 10_000L
        const val MIN_SPEED_SPAN_MS = 3_000L
        const val FIX_STALE_MS = 10_000L
    }
}
