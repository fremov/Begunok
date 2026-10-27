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

    @Query("UPDATE workouts SET programCompleted = :completed WHERE id = :id")
    suspend fun setProgramCompleted(id: Long, completed: Boolean)

    @Query(
        "SELECT s.* FROM exercise_sets s INNER JOIN workouts w ON w.id = s.workoutId " +
            "WHERE w.endTime IS NOT NULL ORDER BY s.time"
    )
    fun observeAllSets(): Flow<List<ExerciseSetEntity>>

    @Query(
        "SELECT programName, programCompleted FROM workouts WHERE endTime IS NOT NULL AND programName IS NOT NULL " +
            "AND (dayType IS NULL OR dayType != 'Strength') ORDER BY startTime DESC LIMIT :limit"
    )
    fun observeRecentPrograms(limit: Int): Flow<List<ProgramRun>>

    @Insert
    suspend fun insertSet(set: ExerciseSetEntity)

    @Query("SELECT * FROM exercise_sets WHERE workoutId = :workoutId ORDER BY time")
    fun observeSets(workoutId: Long): Flow<List<ExerciseSetEntity>>

    @Query(
        "SELECT s.* FROM exercise_sets s INNER JOIN workouts w ON w.id = s.workoutId " +
            "WHERE w.endTime IS NOT NULL AND s.reps > 0 AND s.exerciseId IN (:ids) ORDER BY s.time DESC LIMIT 500"
    )
    fun observeRecentSets(ids: List<String>): Flow<List<ExerciseSetEntity>>
}
