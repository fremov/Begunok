package ru.begunok.health

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal suspend fun readStepHistory(
    start: Instant,
    end: Instant,
    zone: ZoneId,
    historyGranted: Boolean,
    today: LocalDate,
    read: suspend (Instant, Instant) -> List<DailySteps>,
): StepHistory {
    if (!end.isAfter(start)) return StepHistory()
    val notice = if (historyGranted) null else
        "Шаги: без доступа к истории доступны данные не ранее 30 дней до первого разрешения. " +
            "Доступ к истории можно дать в разделе «Здоровье»."
    // This is a request boundary, NOT a history cutoff. Always read both sides:
    // the real access boundary depends on the first grant and is not exposed by the SDK.
    // Midnight keeps the daily buckets disjoint even across daylight-saving changes.
    val boundary = today.minusDays(29).atStartOfDay(zone).toInstant()
    if (historyGranted || !start.isBefore(boundary) || !end.isAfter(boundary)) {
        return StepHistory(read(start, end), message = notice)
    }
    val recent = read(boundary, end)
    return try {
        StepHistory(read(start, boundary) + recent, message = notice)
    } catch (error: HealthReadException) {
        StepHistory(
            days = recent,
            message = "Старая история шагов не прочитана: ${error.message}. " +
                "Показана только успешно прочитанная часть. Проверьте доступ к истории в разделе «Здоровье».",
            failed = true,
        )
    }
}
