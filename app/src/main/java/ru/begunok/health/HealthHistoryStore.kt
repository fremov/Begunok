package ru.begunok.health

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Independent of the workout Room database and desktop synchronization. Never prunes acquired data. */
internal class HealthHistoryStore(context: Context) : SQLiteOpenHelper(
    context,
    File(File(context.filesDir, "health-history").apply { mkdirs() }, "history.db").absolutePath,
    null,
    2,
) {
    private val legacy = AtomicFile(File(context.noBackupFilesDir, "health-auto/latest.json"))

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE daily (date TEXT PRIMARY KEY, read_at TEXT NOT NULL, data TEXT NOT NULL, completed TEXT NOT NULL)")
        db.execSQL("CREATE TABLE heart_rate (seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, bpm INTEGER NOT NULL, PRIMARY KEY(seconds, nanos, bpm))")
        db.execSQL("CREATE TABLE oxygen (seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, percent REAL NOT NULL, PRIMARY KEY(seconds, nanos, percent))")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createExtraTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createExtraTables(db)
        if (oldVersion < 2) {
            db.query("daily", arrayOf("date", "completed"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val previous = JSONArray(cursor.getString(1))
                    val remaining = JSONArray()
                    for (index in 0 until previous.length()) {
                        val permission = previous.getString(index)
                        if (permission != "android.permission.health.READ_SLEEP") remaining.put(permission)
                    }
                    db.update("daily", ContentValues().apply { put("completed", remaining.toString()) },
                        "date = ?", arrayOf(cursor.getString(0)))
                }
            }
        }
    }

    /** A failed/corrupt migration is deliberately not marked done and is reported to the caller. */
    @Synchronized
    fun initialize(today: LocalDate) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (metadata(db, "legacy_imported") == null) {
                if (legacy.baseFile.exists() || File(legacy.baseFile.path + ".bak").exists()) {
                    val json = legacy.openRead().bufferedReader().use { JSONObject(it.readText()) }
                    val date = LocalDate.parse(json.getString("day"))
                    val readAt = Instant.ofEpochMilli(json.getLong("updatedAt"))
                    save(db, date, readAt, decode(json), emptySet())
                }
                putMetadata(db, "legacy_imported", "1")
            }
            if (metadata(db, "backfill_start") == null) {
                putMetadata(db, "backfill_start", today.minusDays(29).toString())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        // The original JSON is left untouched; it is never the source of truth after migration.
    }

    @Synchronized
    fun save(date: LocalDate, readAt: Instant, data: HealthDay, completed: Set<String>): HealthHistoryDay {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val result = save(db, date, readAt, data, completed)
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun save(db: SQLiteDatabase, date: LocalDate, readAt: Instant, data: HealthDay, completed: Set<String>): HealthHistoryDay {
        val old = day(db, date)
        val merged = if (old != null && readAt.isBefore(old.readAt)) mergeHealthDay(data, old.data)
            else mergeHealthDay(old?.data, data)
        val timestamp = maxOf(old?.readAt ?: readAt, readAt)
        val permissions = completed(db, date) + completed
        db.insertWithOnConflict("daily", null, ContentValues().apply {
            put("date", date.toString())
            put("read_at", timestamp.toString())
            put("data", encode(merged).toString())
            put("completed", JSONArray(permissions.sorted()).toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Cannot save daily health history" } }
        (data.hrSamples + listOfNotNull(data.lastHr)).distinct().filter { it.bpm > 0 }.forEach { sample ->
            db.execSQL("INSERT OR IGNORE INTO heart_rate(seconds,nanos,bpm) VALUES(?,?,?)",
                arrayOf<Any>(sample.time.epochSecond, sample.time.nano, sample.bpm))
        }
        (data.spo2Samples + listOfNotNull(data.spo2)).distinct()
            .filter { it.percent.isFinite() && it.percent > 0 }.forEach { sample ->
                db.execSQL("INSERT OR IGNORE INTO oxygen(seconds,nanos,percent) VALUES(?,?,?)",
                    arrayOf<Any>(sample.time.epochSecond, sample.time.nano, sample.percent))
            }
        data.stressSamples.filter { it.score in 1..100 }.forEach { sample ->
            db.execSQL("INSERT OR REPLACE INTO stress(seconds,nanos,score) VALUES(?,?,?)",
                arrayOf<Any>(sample.time.epochSecond, sample.time.nano, sample.score))
        }
        data.vitalitySamples.filter { it.score.isFinite() && it.score >= 0 }.forEach { sample ->
            db.execSQL("INSERT OR REPLACE INTO vitality(seconds,nanos,score,earned) VALUES(?,?,?,?)",
                arrayOf<Any?>(sample.time.epochSecond, sample.time.nano, sample.score, sample.earned))
        }
        return HealthHistoryDay(date, timestamp, merged)
    }

    @Synchronized
    fun saveExtras(stress: List<StressSample>, vitality: List<VitalitySample>, readAt: Instant, zone: ZoneId) {
        if (stress.isEmpty() && vitality.isEmpty()) return
        val stressDays = stress.groupBy { it.time.atZone(zone).toLocalDate() }
        val vitalityDays = vitality.groupBy { it.time.atZone(zone).toLocalDate() }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (date in stressDays.keys + vitalityDays.keys) {
                val stressSamples = stressDays[date].orEmpty()
                val vitalitySamples = vitalityDays[date].orEmpty()
                save(db, date, readAt, HealthDay(
                    totals = HealthTotals(sources = setOf("nodomain.freeyourgadget.gadgetbridge")),
                    lastHr = null, spo2 = null, sleep = null,
                    stressSamples = stressSamples, vitalitySamples = vitalitySamples,
                    lastStress = stressSamples.maxByOrNull { it.time },
                    lastVitality = vitalitySamples.maxByOrNull { it.time },
                ), emptySet())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun day(date: LocalDate): HealthHistoryDay? = day(readableDatabase, date)

    @Synchronized
    fun pendingDates(today: LocalDate, permissions: Set<String>, limit: Int): List<LocalDate> {
        val db = readableDatabase
        val start = metadata(db, "backfill_start")?.let(LocalDate::parse) ?: return emptyList()
        val completedDates = mutableSetOf<LocalDate>()
        val attempts = mutableMapOf<LocalDate, Instant>()
        db.query("daily", arrayOf("date", "read_at", "completed"), "date >= ? AND date < ?",
            arrayOf(start.toString(), today.toString()), null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                val date = LocalDate.parse(cursor.getString(0))
                if (strings(JSONArray(cursor.getString(2))).containsAll(permissions)) completedDates.add(date)
                else attempts[date] = Instant.parse(cursor.getString(1))
            }
        }
        val result = mutableListOf<LocalDate>()
        var date = today.minusDays(1)
        while (!date.isBefore(start)) {
            if (date !in completedDates) result.add(date)
            date = date.minusDays(1)
        }
        // Unattempted days first; persistent failures rotate instead of starving older dates.
        return result.sortedBy { attempts[it] ?: Instant.MIN }.take(limit)
    }

    @Synchronized
    fun dailySteps(start: LocalDate?, end: LocalDate): List<DailySteps> {
        if (start != null && start.isAfter(end)) return emptyList()
        val condition = if (start == null) "date <= ?" else "date >= ? AND date <= ?"
        val args = if (start == null) arrayOf(end.toString()) else arrayOf(start.toString(), end.toString())
        val result = mutableListOf<DailySteps>()
        readableDatabase.query("daily", arrayOf("date", "data"), condition, args, null, null, "date ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val data = JSONObject(cursor.getString(1))
                if (!data.isNull("steps")) result.add(DailySteps(LocalDate.parse(cursor.getString(0)), data.getLong("steps")))
            }
        }
        return result
    }

    @Synchronized
    fun history(start: LocalDate?, end: LocalDate, zone: ZoneId): HealthHistory {
        if (start != null && start.isAfter(end)) return HealthHistory()
        val db = readableDatabase
        val days = mutableListOf<HealthHistoryDay>()
        val condition = if (start == null) "date <= ?" else "date >= ? AND date <= ?"
        val args = if (start == null) arrayOf(end.toString()) else arrayOf(start.toString(), end.toString())
        db.query("daily", arrayOf("date", "read_at", "data"), condition, args, null, null, "date ASC").use { cursor ->
            while (cursor.moveToNext()) {
                days.add(HealthHistoryDay(LocalDate.parse(cursor.getString(0)), Instant.parse(cursor.getString(1)),
                    decode(JSONObject(cursor.getString(2)))))
            }
        }
        val until = end.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val from = start?.atStartOfDay(zone)?.toEpochSecond()
        val samplesCondition = if (from == null) "seconds < ?" else "seconds >= ? AND seconds < ?"
        val sampleArgs = if (from == null) arrayOf(until.toString()) else arrayOf(from.toString(), until.toString())
        val heartRates = mutableListOf<HrSample>()
        db.query("heart_rate", arrayOf("seconds", "nanos", "bpm"), samplesCondition, sampleArgs, null, null, "seconds ASC, nanos ASC, bpm ASC").use { cursor ->
            while (cursor.moveToNext()) heartRates.add(HrSample(Instant.ofEpochSecond(cursor.getLong(0), cursor.getLong(1)), cursor.getLong(2)))
        }
        val oxygen = mutableListOf<Spo2Sample>()
        db.query("oxygen", arrayOf("seconds", "nanos", "percent"), samplesCondition, sampleArgs, null, null, "seconds ASC, nanos ASC, percent ASC").use { cursor ->
            while (cursor.moveToNext()) oxygen.add(Spo2Sample(Instant.ofEpochSecond(cursor.getLong(0), cursor.getLong(1)), cursor.getDouble(2)))
        }
        val stress = mutableListOf<StressSample>()
        db.query("stress", arrayOf("seconds", "nanos", "score"), "($samplesCondition) AND score > 0", sampleArgs, null, null, "seconds ASC, nanos ASC").use { cursor ->
            while (cursor.moveToNext()) stress.add(StressSample(Instant.ofEpochSecond(cursor.getLong(0), cursor.getLong(1)), cursor.getInt(2)))
        }
        val vitality = mutableListOf<VitalitySample>()
        db.query("vitality", arrayOf("seconds", "nanos", "score", "earned"), samplesCondition, sampleArgs, null, null, "seconds ASC, nanos ASC").use { cursor ->
            while (cursor.moveToNext()) vitality.add(VitalitySample(Instant.ofEpochSecond(cursor.getLong(0), cursor.getLong(1)), cursor.getDouble(2),
                if (cursor.isNull(3)) null else cursor.getDouble(3)))
        }
        return HealthHistory(days, heartRates, oxygen, stress, vitality)
    }

    private fun day(db: SQLiteDatabase, date: LocalDate): HealthHistoryDay? =
        db.query("daily", arrayOf("read_at", "data"), "date = ?", arrayOf(date.toString()), null, null, null).use {
            if (it.moveToFirst()) HealthHistoryDay(date, Instant.parse(it.getString(0)), decode(JSONObject(it.getString(1)))) else null
        }

    private fun completed(db: SQLiteDatabase, date: LocalDate): Set<String> =
        db.query("daily", arrayOf("completed"), "date = ?", arrayOf(date.toString()), null, null, null).use {
            if (it.moveToFirst()) strings(JSONArray(it.getString(0))) else emptySet()
        }

    private fun metadata(db: SQLiteDatabase, key: String): String? =
        db.query("metadata", arrayOf("value"), "name = ?", arrayOf(key), null, null, null).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun putMetadata(db: SQLiteDatabase, key: String, value: String) {
        db.insertOrThrow("metadata", null, ContentValues().apply { put("name", key); put("value", value) })
    }

    private fun strings(array: JSONArray): Set<String> = buildSet {
        for (index in 0 until array.length()) add(array.getString(index))
    }

    private fun encode(day: HealthDay): JSONObject = JSONObject().apply {
        put("steps", day.totals.steps ?: JSONObject.NULL)
        put("activeKcal", day.totals.activeKcal ?: JSONObject.NULL)
        put("avgHr", day.totals.avgHr ?: JSONObject.NULL)
        put("minHr", day.totals.minHr ?: JSONObject.NULL)
        put("maxHr", day.totals.maxHr ?: JSONObject.NULL)
        put("lastHr", day.lastHr?.let { JSONObject().put("instant", it.time.toString()).put("bpm", it.bpm) } ?: JSONObject.NULL)
        put("spo2", day.spo2?.let { JSONObject().put("instant", it.time.toString()).put("percent", it.percent) } ?: JSONObject.NULL)
        put("sleepSeconds", day.sleep?.seconds ?: JSONObject.NULL)
        put("distanceMeters", day.distanceMeters ?: JSONObject.NULL)
        put("avgStepCadence", day.avgStepCadence ?: JSONObject.NULL)
        put("sources", JSONArray(day.totals.sources.sorted()))
        put("sleepNights", JSONArray().apply {
            day.sleepNights.forEach { night -> put(JSONObject().apply {
                put("start", night.start.toString())
                put("end", night.end.toString())
                put("source", night.source)
                put("stages", JSONArray().apply {
                    night.stages.forEach { stage -> put(JSONObject()
                        .put("start", stage.start.toString()).put("end", stage.end.toString()).put("kind", stage.kind.name)) }
                })
            }) }
        })
        put("lastStress", day.lastStress?.let { JSONObject().put("instant", it.time.toString()).put("score", it.score) } ?: JSONObject.NULL)
        put("lastVitality", day.lastVitality?.let { JSONObject().put("instant", it.time.toString()).put("score", it.score)
            .put("earned", it.earned ?: JSONObject.NULL) } ?: JSONObject.NULL)
    }

    private fun decode(json: JSONObject): HealthDay {
        fun long(key: String): Long? = if (json.isNull(key)) null else json.getLong(key)
        fun double(key: String): Double? = if (json.isNull(key)) null else json.getDouble(key)
        fun time(value: JSONObject): Instant = if (value.has("instant")) Instant.parse(value.getString("instant"))
            else Instant.ofEpochMilli(value.getLong("time"))
        return HealthDay(
            totals = HealthTotals(long("steps"), double("activeKcal"), long("avgHr"), long("minHr"), long("maxHr"),
                json.optJSONArray("sources")?.let(::strings).orEmpty()),
            lastHr = json.optJSONObject("lastHr")?.let { HrSample(time(it), it.getLong("bpm")) },
            spo2 = json.optJSONObject("spo2")?.let { Spo2Sample(time(it), it.getDouble("percent")) },
            sleep = long("sleepSeconds")?.let(Duration::ofSeconds),
            distanceMeters = double("distanceMeters"),
            avgStepCadence = double("avgStepCadence"),
            sleepNights = decodeNights(json.optJSONArray("sleepNights")),
            lastStress = json.optJSONObject("lastStress")?.let { StressSample(time(it), it.getInt("score")) }?.takeIf { it.score in 1..100 },
            lastVitality = json.optJSONObject("lastVitality")?.let {
                VitalitySample(time(it), it.getDouble("score"), if (it.isNull("earned")) null else it.getDouble("earned"))
            },
        )
    }

    private fun createExtraTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS stress (seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, score INTEGER NOT NULL, PRIMARY KEY(seconds,nanos))")
        db.execSQL("CREATE TABLE IF NOT EXISTS vitality (seconds INTEGER NOT NULL, nanos INTEGER NOT NULL, score REAL NOT NULL, earned REAL, PRIMARY KEY(seconds,nanos))")
    }

    private fun decodeNights(array: JSONArray?): List<SleepNight> {
        if (array == null) return emptyList()
        return List(array.length()) { index ->
            val night = array.getJSONObject(index)
            val stages = night.optJSONArray("stages")
            SleepNight(
                start = Instant.parse(night.getString("start")),
                end = Instant.parse(night.getString("end")),
                stages = if (stages == null) emptyList() else List(stages.length()) { stageIndex ->
                    val stage = stages.getJSONObject(stageIndex)
                    SleepStageSample(
                        Instant.parse(stage.getString("start")), Instant.parse(stage.getString("end")),
                        SleepStageKind.entries.firstOrNull { it.name == stage.optString("kind") } ?: SleepStageKind.UNKNOWN,
                    )
                },
                source = night.optString("source"),
            )
        }
    }
}
