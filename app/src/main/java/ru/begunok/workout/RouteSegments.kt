package ru.begunok.workout

import ru.begunok.data.db.SampleEntity

enum class RouteColor { Green, Yellow, Red, Gray }

data class RouteSegment(val points: List<LatLon>, val color: RouteColor)

fun routeColor(heartRate: Int?, upperLimit: Int): RouteColor = when {
    heartRate == null -> RouteColor.Gray
    heartRate > upperLimit -> RouteColor.Red
    heartRate >= upperLimit - NEAR_LIMIT_BPM -> RouteColor.Yellow
    else -> RouteColor.Green
}

fun buildRouteSegments(samples: List<SampleEntity>, upperLimit: Int): List<RouteSegment> {
    val segments = mutableListOf<RouteSegment>()
    var current: MutableList<LatLon>? = null
    var currentColor: RouteColor? = null
    var prev: SampleEntity? = null

    fun flush() {
        val points = current
        val color = currentColor
        if (points != null && color != null && points.size >= 2) segments += RouteSegment(points, color)
        current = null
        currentColor = null
    }

    for (s in samples) {
        if (s.paused || s.latitude == null || s.longitude == null) {
            if (s.paused) {
                flush()
                prev = null
            }
            continue
        }
        val point = LatLon(s.latitude, s.longitude)
        val p = prev
        if (p == null || s.time - p.time > MAX_GAP_MS) {
            flush()
            prev = s
            continue
        }
        val previousPoint = LatLon(p.latitude!!, p.longitude!!)
        if (previousPoint == point) {
            prev = s
            continue
        }
        val color = routeColor(s.heartRate, upperLimit)
        if (color != currentColor) {
            flush()
            current = mutableListOf(previousPoint)
            currentColor = color
        }
        current!!.add(point)
        prev = s
    }
    flush()
    return segments
}

fun timeAboveFraction(samples: List<SampleEntity>, upperLimit: Int): Double? {
    val active = samples.filter { !it.paused && it.heartRate != null }
    if (active.isEmpty()) return null
    return active.count { it.heartRate!! > upperLimit }.toDouble() / active.size
}

fun gpsCoverage(samples: List<SampleEntity>): Double? {
    val active = samples.filter { !it.paused }
    if (active.isEmpty()) return null
    return active.count { it.latitude != null }.toDouble() / active.size
}

private const val NEAR_LIMIT_BPM = 5
private const val MAX_GAP_MS = 15_000L
