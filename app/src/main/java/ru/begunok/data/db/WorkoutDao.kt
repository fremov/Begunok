package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {
    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Update
    suspend fun updateWorkout(workout: WorkoutEntity)

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun workout(id: Long): WorkoutEntity?

    @Query("SELECT * FROM workouts WHERE id = :id")
    fun observeWorkout(id: Long): Flow<WorkoutEntity?>

    @Query("SELECT * FROM workouts WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun unfinished(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE endTime IS NOT NULL ORDER BY startTime DESC")
    fun observeFinished(): Flow<List<WorkoutEntity>>

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Insert
    suspend fun insertSample(sample: SampleEntity)

    @Query("SELECT * FROM samples WHERE workoutId = :workoutId ORDER BY time")
    suspend fun samples(workoutId: Long): List<SampleEntity>

    @Insert
    suspend fun insertRrIntervals(intervals: List<RrIntervalEntity>)

    @Query("SELECT * FROM rr_intervals WHERE workoutId = :workoutId ORDER BY time")
    suspend fun rrIntervals(workoutId: Long): List<RrIntervalEntity>
}
