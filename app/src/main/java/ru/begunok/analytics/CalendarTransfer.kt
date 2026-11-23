package ru.begunok.analytics

import android.util.JsonReader
import android.util.JsonToken
import okio.BufferedSink
import org.json.JSONObject
import ru.begunok.activity.DailyActivityRow
import ru.begunok.data.db.PcCalendarDay
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64

internal fun writePhoneCalendar(sink: BufferedSink, source: String, rows: List<DailyActivityRow>) {
    sink.writeUtf8("{\"source\":").writeUtf8(JSONObject.quote(source)).writeUtf8(",\"days\":{")
    for ((index, row) in rows.withIndex()) {
        if (index > 0) sink.writeByte(','.code)
        sink.writeUtf8(JSONObject.quote(row.day.toString())).writeByte(':'.code)
        sink.writeUtf8(JSONObject().apply {
            put("workouts", row.workouts)
            put("topics", row.study.topics)
            put("tasks", row.study.tasks)
            put("reviews", row.study.reviews)
            put("pages", row.pages)
            put("showerDurationSec", row.showerDurationSec)
            put("steps", row.steps ?: JSONObject.NULL)
        }.toString())
    }
    // No valid complete JSON document exists until every source and every row has been read.
    sink.writeUtf8("},\"complete\":true}\n")
}

internal class PcCalendarChunk(val days: List<PcCalendarDay>, val clears: List<String>, val digest: String)

private val dateFormat = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val groupKeys = listOf("ai", "study", "development", "games", "leisure", "other")
private const val MAX_DAY_SECONDS = 48 * 3600 // Includes long civil days across clock changes.

internal fun readPcCalendarChunk(encoded: String, expectedSource: String): PcCalendarChunk {
    require(encoded.length in 1..65_536 && '=' !in encoded) { "Invalid calendar chunk size or padding" }
    val bytes = Base64.getUrlDecoder().decode(encoded)
    val json = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
    val rows = mutableListOf<PcCalendarDay>()
    val clears = mutableListOf<String>()
    val dates = mutableSetOf<String>()
    var sourceSeen = false
    var daysSeen = false
    JsonReader(json.reader()).use { input ->
        input.beginObject()
        while (input.hasNext()) {
            when (input.nextName()) {
                "source" -> {
                    require(!sourceSeen && input.peek() == JsonToken.STRING && input.nextString() == expectedSource) { "Wrong calendar source" }
                    sourceSeen = true
                }
                "days" -> {
                    require(!daysSeen) { "Duplicate days field" }
                    daysSeen = true
                    input.beginObject()
                    while (input.hasNext()) {
                        val date = input.nextName()
                        require(dates.size < 366 && dateFormat.matches(date) && LocalDate.parse(date).year > 0 && dates.add(date)) { "Invalid calendar date" }
                        input.beginObject()
                        val seconds = IntArray(groupKeys.size)
                        var seen = 0
                        var total = 0
                        while (input.hasNext()) {
                            val index = groupKeys.indexOf(input.nextName())
                            require(index >= 0 && seen and (1 shl index) == 0 && input.peek() == JsonToken.NUMBER) { "Invalid PC group" }
                            val value = input.nextString().toIntOrNull()
                            require(value != null && value in 0..(MAX_DAY_SECONDS - total)) { "Invalid PC duration" }
                            seen = seen or (1 shl index)
                            seconds[index] = value
                            total += value
                        }
                        input.endObject()
                        if (seen == 0) clears.add(date) else rows.add(PcCalendarDay(expectedSource, date,
                            seconds[0], seconds[1], seconds[2], seconds[3], seconds[4], seconds[5]))
                    }
                    input.endObject()
                }
                else -> throw IllegalArgumentException("Unknown calendar field")
            }
        }
        input.endObject()
        require(daysSeen && sourceSeen && input.peek() == JsonToken.END_DOCUMENT) { "Incomplete calendar chunk" }
    }
    return PcCalendarChunk(rows, clears, MessageDigest.getInstance("SHA-256").digest(bytes).toHexString())
}
