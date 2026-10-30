package ru.begunok.weight

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

data class WeightPoint(val day: LocalDate, val kg: Double)

data class WeightGoal(val targetKg: Double, val startKg: Double, val deadline: LocalDate? = null) {
    val losing: Boolean get() = targetKg <= startKg
}

data class GoalStatus(
    val progress: Float,
    val remainingKg: Double,
    val reached: Boolean,
    val forecast: LocalDate?,
    val neededPerWeek: Double?,
    val overdue: Boolean,
)

object WeightStats {
    const val MIN_KG = 20.0
    const val MAX_KG = 400.0
    const val SMOOTH_DAYS = 7
    private const val TREND_WINDOW_DAYS = 30
    private const val TREND_MIN_POINTS = 3
    private const val TREND_MIN_SPAN_DAYS = 7
    private const val MAX_FORECAST_DAYS = 3650L

    fun smoothedAt(points: List<WeightPoint>, day: LocalDate): Double? {
        val from = day.minusDays(SMOOTH_DAYS - 1L)
        val window = points.filter { it.day in from..day }
        return if (window.isEmpty()) null else window.sumOf { it.kg } / window.size
    }

    fun smoothed(points: List<WeightPoint>): List<Double> = points.map { smoothedAt(points, it.day)!! }

    fun weekChange(points: List<WeightPoint>): Double? {
        val last = points.maxByOrNull { it.day } ?: return null
        val now = smoothedAt(points, last.day) ?: return null
        val before = smoothedAt(points, last.day.minusDays(SMOOTH_DAYS.toLong())) ?: return null
        return now - before
    }

    fun trendPerDay(points: List<WeightPoint>): Double? {
        val last = points.maxByOrNull { it.day } ?: return null
        val window = points.filter { it.day > last.day.minusDays(TREND_WINDOW_DAYS.toLong()) }
        if (window.size < TREND_MIN_POINTS) return null
        val first = window.minOf { it.day }
        if (ChronoUnit.DAYS.between(first, last.day) < TREND_MIN_SPAN_DAYS) return null
        val xs = window.map { ChronoUnit.DAYS.between(first, it.day).toDouble() }
        val ys = window.map { it.kg }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return if (sxx == 0.0) null else sxy / sxx
    }

    fun reached(goal: WeightGoal, current: Double): Boolean =
        if (goal.losing) current <= goal.targetKg else current >= goal.targetKg

    fun status(goal: WeightGoal, current: Double, trendPerDay: Double?, today: LocalDate): GoalStatus {
        val reached = reached(goal, current)
        val total = goal.startKg - goal.targetKg
        val progress = when {
            reached -> 1f
            total == 0.0 -> 0f
            else -> ((goal.startKg - current) / total).toFloat().coerceIn(0f, 1f)
        }
        val left = goal.targetKg - current
        val forecast = trendPerDay
            ?.takeIf { !reached && abs(it) > 1e-4 && it * left > 0 }
            ?.let { ceil(left / it).toLong() }
            ?.takeIf { it <= MAX_FORECAST_DAYS }
            ?.let { today.plusDays(it) }
        val daysToDeadline = goal.deadline?.let { ChronoUnit.DAYS.between(today, it) }
        return GoalStatus(
            progress = progress,
            remainingKg = if (reached) 0.0 else abs(left),
            reached = reached,
            forecast = forecast,
            neededPerWeek = daysToDeadline?.takeIf { !reached && it > 0 }?.let { abs(left) / it * 7 },
            overdue = !reached && daysToDeadline != null && daysToDeadline <= 0,
        )
    }

    fun newGoal(previous: WeightGoal?, targetKg: Double, deadline: LocalDate?, current: Double): WeightGoal {
        val keepStart = previous != null && (targetKg <= previous.startKg) == previous.losing
        return WeightGoal(targetKg, if (keepStart) previous!!.startKg else current, deadline)
    }

    fun parseKg(text: String): Double? =
        text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in MIN_KG..MAX_KG }
}
