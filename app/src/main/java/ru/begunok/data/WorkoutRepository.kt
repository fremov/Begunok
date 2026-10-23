package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import ru.begunok.data.db.RrIntervalEntity
import ru.begunok.data.db.SampleEntity
import ru.begunok.data.db.WorkoutDao
import ru.begunok.data.db.WorkoutEntity
import ru.begunok.workout.Fix
import ru.begunok.workout.WorkoutSummary
import ru.begunok.workout.WorkoutTracker

class WorkoutRepository(private val dao: WorkoutDao) {
    fun observeFinished(): Flow<List<WorkoutEntity>> = dao.observeFinished()

    fun observeWorkout(id: Long): Flow<WorkoutEntity?> = dao.observeWorkout(id)

    suspend fun samples(id: Long): List<SampleEntity> = dao.samples(id)

    suspend fun create(startTime: Long, upperLimit: Int, lowerLimit: Int?, programName: String?): Long =
        dao.insertWorkout(
            WorkoutEntity(startTime = startTime, upperLimit = upperLimit, lowerLimit = lowerLimit, programName = programName)
        )

    suspend fun addSample(sample: SampleEntity) = dao.insertSample(sample)

    suspend fun addRrIntervals(intervals: List<RrIntervalEntity>) {
        if (intervals.isNotEmpty()) dao.insertRrIntervals(intervals)
    }

    suspend fun finish(summary: WorkoutSummary, endTime: Long) {
        val workout = dao.workout(summary.workoutId) ?: return
        dao.updateWorkout(
            workout.copy(
                endTime = endTime,
                durationMs = summary.durationMs,
                distanceM = summary.distanceM,
                avgHr = summary.avgHr,
                maxHr = summary.maxHr,
            )
        )
    }

    suspend fun unfinished(): WorkoutEntity? = dao.unfinished().firstOrNull()

    suspend fun recover(workout: WorkoutEntity): WorkoutSummary? {
        val samples = dao.samples(workout.id)
        if (samples.size < 2) {
            dao.deleteWorkout(workout.id)
            return null
        }
        val summary = summarize(workout, samples)
        finish(summary, samples.last().time)
        return summary
    }

    suspend fun delete(id: Long) = dao.deleteWorkout(id)

    companion object {
        fun summarize(workout: WorkoutEntity, samples: List<SampleEntity>): WorkoutSummary {
            val tracker = WorkoutTracker()
            for (s in samples) {
                if (s.paused) tracker.pause(s.time) else tracker.resume(s.time)
                if (s.latitude != null && s.longitude != null) {
                    tracker.onFix(Fix(s.time, s.latitude, s.longitude, s.accuracy ?: 0f))
                }
                tracker.tick(s.time, if (s.paused) null else s.heartRate)
            }
            return WorkoutSummary(
                workoutId = workout.id,
                startTime = workout.startTime,
                durationMs = tracker.activeMs,
                distanceM = tracker.distanceM,
                avgHr = tracker.avgHr,
                maxHr = tracker.maxHr,
            )
        }
    }
}
