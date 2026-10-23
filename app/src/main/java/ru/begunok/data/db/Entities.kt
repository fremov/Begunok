package ru.begunok.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val durationMs: Long = 0,
    val distanceM: Double = 0.0,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val upperLimit: Int,
    val lowerLimit: Int? = null,
    val programName: String? = null,
)

@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val time: Long,
    val heartRate: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val speed: Double?,
    val paused: Boolean,
)

@Entity(
    tableName = "rr_intervals",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workoutId")],
)
data class RrIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workoutId: Long,
    val time: Long,
    val rrMs: Int,
)
