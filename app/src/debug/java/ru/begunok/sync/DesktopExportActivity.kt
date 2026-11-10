package ru.begunok.sync

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.begunok.BegunokApp
import ru.begunok.workout.WorkoutService
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** USB-only desktop export. Android's DUMP permission restricts invocation to shell/system. */
class DesktopExportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra("requestId")?.takeIf { it.matches(Regex("[a-zA-Z0-9-]{1,80}")) }
        if (requestId == null) {
            finish()
            return
        }
        setContentView(TextView(this).apply {
            text = "MyLife · перенос показателей на компьютер…"
            textSize = 20f
            setPadding(32, 64, 32, 32)
        })
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val directory = File(filesDir, "desktop-export").apply { mkdirs() }
                val result = JSONObject().put("requestId", requestId)
                try {
                    check(!WorkoutService.state.value.isActive) { "Сначала завершите тренировку на телефоне." }
                    val container = (application as BegunokApp).container
                    // BuildConfig defaults are not stored until edited in Android.
                    // Materialize the effective Riot ID so the desktop receives it too.
                    val lolPrefs = container.lolSettings.current()
                    if (lolPrefs.myRiotId.isNotBlank()) container.lolSettings.setMe(lolPrefs.myRiotId)
                    val health = container.health
                    val granted = health.granted()
                    val now = Instant.now()
                    val zone = ZoneId.systemDefault()
                    val today = LocalDate.now(zone)
                    val days = JSONArray()
                    for (offset in 29 downTo 0) {
                        val date = today.minusDays(offset.toLong())
                        val start = date.atStartOfDay(zone).toInstant()
                        val end = minOf(date.plusDays(1).atStartOfDay(zone).toInstant(), now)
                        val day = health.day(start, end, granted)
                        days.put(JSONObject().apply {
                            put("day", date.toString())
                            put("steps", day.totals.steps ?: JSONObject.NULL)
                            put("activeKcal", day.totals.activeKcal ?: JSONObject.NULL)
                            put("avgHr", day.totals.avgHr ?: JSONObject.NULL)
                            put("minHr", day.totals.minHr ?: JSONObject.NULL)
                            put("maxHr", day.totals.maxHr ?: JSONObject.NULL)
                            put("lastHr", day.lastHr?.let { JSONObject().put("time", it.time.toEpochMilli()).put("bpm", it.bpm) } ?: JSONObject.NULL)
                            put("spo2", day.spo2?.let { JSONObject().put("time", it.time.toEpochMilli()).put("percent", it.percent) } ?: JSONObject.NULL)
                            put("sleepSeconds", day.sleep?.seconds ?: JSONObject.NULL)
                            put("sources", JSONArray(day.totals.sources.toList()))
                        })
                    }
                    atomicWrite(File(directory, "health.json"), JSONObject()
                        .put("exportedAt", now.toEpochMilli())
                        .put("availability", health.availability().name)
                        .put("permissions", JSONArray(granted.toList()))
                        .put("days", days).toString())
                    result.put("ok", true).put("exportedAt", now.toEpochMilli())
                } catch (error: Exception) {
                    result.put("ok", false).put("error", error.message ?: "Не удалось экспортировать показатели.")
                }
                atomicWrite(File(directory, "result.json"), result.toString())
            }
            finish()
        }
    }

    private fun atomicWrite(file: File, text: String) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "Не удалось сохранить экспорт." }
    }
}
