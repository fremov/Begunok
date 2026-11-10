package ru.begunok.math

import java.time.LocalDate
import kotlin.math.ceil

/** Whole-topic, four-rating adaptation of SM-2; intervals use the previous ease. */
data class MathReview(
    val due: LocalDate,
    val interval: Int,
    val repetitions: Int,
    val ease: Double,
    val lastReviewed: LocalDate?,
) {
    companion object {
        fun initial(today: LocalDate) = MathReview(today.plusDays(1), 1, 1, 2.5, null)
    }
}

enum class MathRating(val quality: Int, val label: String) {
    Again(0, "Снова — не вспомнил"),
    Hard(3, "Трудно — с усилием"),
    Good(4, "Хорошо — вспомнил"),
    Easy(5, "Легко — без затруднений"),
}

object MathReviewScheduler {
    fun rate(old: MathReview, rating: MathRating, today: LocalDate): MathReview {
        require(old.due <= today && old.lastReviewed != today) { "Review is not due" }
        val quality = rating.quality
        val repetitions = if (quality < 3) 0 else old.repetitions + 1
        val interval = when {
            quality < 3 || repetitions == 1 -> 1
            repetitions == 2 -> 6
            else -> ceil(old.interval * old.ease).toInt().coerceAtLeast(1)
        }
        val distance = 5 - quality
        val ease = (old.ease + 0.1 - distance * (0.08 + distance * 0.02)).coerceAtLeast(1.3)
        return MathReview(today.plusDays(interval.toLong()), interval, repetitions, ease, today)
    }
}

enum class MathActivityKind(val key: String) { Topic("topic"), Task("task"), Review("review") }

data class MathActivityEvent(
    val day: LocalDate,
    val at: Long,
    val lessonId: String,
    val kind: MathActivityKind,
)

data class MathDayActivity(val topics: Int = 0, val tasks: Int = 0, val reviews: Int = 0) {
    val total: Int get() = topics + tasks + reviews
}

fun mathActivityByDay(events: List<MathActivityEvent>): Map<LocalDate, MathDayActivity> {
    val days = mutableMapOf<LocalDate, MathDayActivity>()
    val firstTopics = mutableMapOf<String, MathActivityEvent>()
    for (event in events) {
        if (event.kind == MathActivityKind.Topic) {
            val previous = firstTopics[event.lessonId]
            if (previous == null || event.at < previous.at) firstTopics[event.lessonId] = event
        } else {
            val day = days[event.day] ?: MathDayActivity()
            days[event.day] = when (event.kind) {
                MathActivityKind.Task -> day.copy(tasks = day.tasks + 1)
                MathActivityKind.Review -> day.copy(reviews = day.reviews + 1)
                MathActivityKind.Topic -> day
            }
        }
    }
    for (event in firstTopics.values) {
        val day = days[event.day] ?: MathDayActivity()
        days[event.day] = day.copy(topics = day.topics + 1)
    }
    return days
}
