package ru.begunok.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

private val ru = Locale.forLanguageTag("ru")

object Format {
    fun duration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    fun distanceKm(meters: Double): String = String.format(ru, "%.2f", meters / 1000.0)

    fun speedKmh(mps: Double?): String = mps?.let { String.format(ru, "%.1f", it * 3.6) } ?: "—"

    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm > 59 * 60) return "—"
        val total = secPerKm.roundToLong()
        return "%d:%02d".format(total / 60, total % 60)
    }

    fun heartRate(bpm: Int?): String = bpm?.toString() ?: "—"

    fun dateTime(millis: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(millis))
}
