package ru.begunok.activity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import okio.BufferedSink
import ru.begunok.analytics.readPcCalendarChunk
import ru.begunok.analytics.writePhoneCalendar
import ru.begunok.data.MathRepository
import ru.begunok.data.ReadingRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.PcCalendarDao
import ru.begunok.data.db.ReadingEntryEntity
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.health.DailySteps
import ru.begunok.health.HealthAutoSync
import ru.begunok.math.MathActivityEvent
import ru.begunok.workout.Repetitions
import java.time.LocalDate
import java.time.ZoneId

internal class PhoneCalendarData(
    private val workouts: List<WorkoutEntity>,
    private val reading: List<ReadingEntryEntity>,
    private val learning: List<MathActivityEvent>,
    private val showers: List<ColdShowerEntity>,
    private val repetitions: Map<Long, Repetitions>,
) {
    fun rows(steps: List<DailySteps>, start: LocalDate?, end: LocalDate, zone: ZoneId) =
        DailyActivity.build(workouts, reading, learning, showers, steps, end, start, zone, repetitions)
}

internal class ActivityCalendar(
    workouts: WorkoutRepository,
    reading: ReadingRepository,
    math: MathRepository,
    showers: ShowerRepository,
    private val health: HealthAutoSync,
    private val pc: PcCalendarDao,
    private val source: () -> String,
) {
    val phone: Flow<PhoneCalendarData> = flow {
        val progress = math.observeProgress(math.catalog())
        val reps = workouts.observeAllSets().map { Repetitions.byWorkout(it) }
        emitAll(combine(workouts.observeFinished(), reading.observeEntries(), progress, showers.observeAll(), reps) {
                sessions, entries, study, cold, repetitions -> PhoneCalendarData(sessions, entries, study.events, cold, repetitions)
        })
    }

    fun observePc(start: LocalDate, end: LocalDate) = pc.observe(source(), start.toString(), end.toString())

    suspend fun export(sink: BufferedSink) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val rows = phone.first().rows(health.dailySteps(null, today), null, today, zone)
        writePhoneCalendar(sink, source(), rows)
    }

    suspend fun importPc(encoded: String): String {
        val id = source()
        val chunk = readPcCalendarChunk(encoded, id)
        pc.applyDays(id, chunk.days, chunk.clears)
        return chunk.digest
    }
}
