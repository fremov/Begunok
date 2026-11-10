package ru.begunok.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.begunok.math.MathActivityEvent
import ru.begunok.math.MathActivityKind
import ru.begunok.math.MathRating
import ru.begunok.math.MathReview
import ru.begunok.math.MathReviewScheduler
import java.time.LocalDate
import java.util.UUID

private val Context.mathDataStore by preferencesDataStore(name = "math")

data class MathLesson(val id: String, val order: Int, val title: String, val url: String)
data class MathCatalog(val title: String, val source: String, val lessons: List<MathLesson>)
data class MathProgress(
    val lastLessonId: String? = null,
    val completedIds: Set<String> = emptySet(),
    val reviews: Map<String, MathReview> = emptyMap(),
    val events: List<MathActivityEvent> = emptyList(),
)

class MathRepository(context: Context) {
    private val context = context.applicationContext
    private val catalogMutex = Mutex()
    private var cachedCatalog: MathCatalog? = null
    private val eventKeyPattern = Regex("event_[0-9a-f]{32}")
    private val lastLessonKey = stringPreferencesKey("last_lesson")

    suspend fun catalog(): MathCatalog = catalogMutex.withLock {
        cachedCatalog ?: withContext(Dispatchers.IO) {
            val json = context.assets.open("math/spacemath.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            val entries = json.getJSONArray("lessons")
            val lessons = List(entries.length()) { index ->
                val lesson = entries.getJSONObject(index)
                MathLesson(
                    id = lesson.getString("id"),
                    order = lesson.getInt("order"),
                    title = lesson.getString("title"),
                    url = lesson.getString("url"),
                )
            }.sortedBy { it.order }
            require(lessons.isNotEmpty() && lessons.map { it.id }.toSet().size == lessons.size)
            require(lessons.all {
                val uri = Uri.parse(it.url)
                uri.scheme == "https" && uri.host == "spacemath.xyz" &&
                    uri.userInfo == null && (uri.port == -1 || uri.port == 443)
            })
            MathCatalog(json.getString("title"), json.getString("source"), lessons)
        }.also { cachedCatalog = it }
    }

    fun observeProgress(catalog: MathCatalog): Flow<MathProgress> = context.mathDataStore.data
        .map { decodeProgress(it, catalog) }.distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun saveProgress(lessonId: String, completed: Boolean? = null): Unit = withContext(Dispatchers.IO) {
        val catalog = checkedCatalog(lessonId)
        context.mathDataStore.edit { prefs ->
            val progress = decodeProgress(prefs, catalog)
            prefs[lastLessonKey] = lessonId
            if (completed != null) {
                prefs[booleanPreferencesKey("completed_$lessonId")] = completed
                if (!completed) {
                    prefs.remove(stringPreferencesKey("review_$lessonId"))
                } else if (lessonId !in progress.completedIds) {
                    val today = LocalDate.now()
                    if (lessonId !in progress.reviews) writeReview(prefs, lessonId, MathReview.initial(today))
                    if (progress.events.none { it.lessonId == lessonId && it.kind == MathActivityKind.Topic }) {
                        addEvent(prefs, lessonId, MathActivityKind.Topic, today)
                    }
                }
            }
        }
    }

    suspend fun recordTask(lessonId: String): Unit = withContext(Dispatchers.IO) {
        checkedCatalog(lessonId)
        context.mathDataStore.edit { prefs ->
            prefs[lastLessonKey] = lessonId
            addEvent(prefs, lessonId, MathActivityKind.Task, LocalDate.now())
        }
    }

    suspend fun rateReview(lessonId: String, rating: MathRating): Unit = withContext(Dispatchers.IO) {
        val catalog = checkedCatalog(lessonId)
        context.mathDataStore.edit { prefs ->
            val progress = decodeProgress(prefs, catalog)
            require(lessonId in progress.completedIds) { "Lesson is not completed" }
            val old = requireNotNull(progress.reviews[lessonId]) { "Review is not scheduled" }
            val today = LocalDate.now()
            val updated = MathReviewScheduler.rate(old, rating, today)
            prefs[lastLessonKey] = lessonId
            writeReview(prefs, lessonId, updated)
            addEvent(prefs, lessonId, MathActivityKind.Review, today)
        }
    }

    private suspend fun checkedCatalog(id: String): MathCatalog = catalog().also { catalog ->
        require(catalog.lessons.any { it.id == id })
    }

    private fun decodeProgress(prefs: Preferences, catalog: MathCatalog): MathProgress {
        val raw = prefs.asMap()
        val ids = catalog.lessons.mapTo(mutableSetOf()) { it.id }
        val completed = ids.filterTo(mutableSetOf()) { raw[booleanPreferencesKey("completed_$it")] == true }
        val reviews = ids.mapNotNull { id ->
            (raw[stringPreferencesKey("review_$id")] as? String)?.let(::parseReview)?.let { id to it }
        }.toMap()
        // Merged preferences can contain unknown IDs or malformed JSON; never discard the raw values.
        val events = raw.entries.mapNotNull { (key, value) ->
            if (!eventKeyPattern.matches(key.name) || value !is String) null else parseEvent(value, ids)
        }
        return MathProgress(
            lastLessonId = (raw[lastLessonKey] as? String)?.takeIf { it in ids },
            completedIds = completed,
            reviews = reviews,
            events = events,
        )
    }

    private fun parseReview(raw: String): MathReview? = try {
        val json = JSONObject(raw)
        val interval = json.getInt("interval")
        val repetitions = json.getInt("repetitions")
        val ease = json.getDouble("ease")
        require(interval >= 1 && repetitions >= 0 && ease.isFinite() && ease >= 1.3)
        MathReview(
            due = LocalDate.parse(json.getString("due")),
            interval = interval,
            repetitions = repetitions,
            ease = ease,
            lastReviewed = if (json.isNull("lastReviewed")) null else LocalDate.parse(json.getString("lastReviewed")),
        )
    } catch (_: Exception) { null }

    private fun parseEvent(raw: String, ids: Set<String>): MathActivityEvent? = try {
        val json = JSONObject(raw)
        val id = json.getString("lessonId")
        require(id in ids)
        val kind = MathActivityKind.entries.first { it.key == json.getString("kind") }
        val at = json.getLong("at")
        require(at >= 0)
        MathActivityEvent(LocalDate.parse(json.getString("day")), at, id, kind)
    } catch (_: Exception) { null }

    private fun writeReview(prefs: MutablePreferences, id: String, review: MathReview) {
        prefs[stringPreferencesKey("review_$id")] = JSONObject()
            .put("due", review.due.toString()).put("interval", review.interval)
            .put("repetitions", review.repetitions).put("ease", review.ease)
            .put("lastReviewed", review.lastReviewed?.toString() ?: JSONObject.NULL).toString()
    }

    private fun addEvent(prefs: MutablePreferences, id: String, kind: MathActivityKind, day: LocalDate) {
        val eventId = UUID.randomUUID().toString().replace("-", "")
        prefs[stringPreferencesKey("event_$eventId")] = JSONObject()
            .put("day", day.toString()).put("at", System.currentTimeMillis())
            .put("lessonId", id).put("kind", kind.key).toString()
    }
}
