package ru.begunok.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mental_results")
data class MentalResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val test: String,
    val completedAt: Long,
    // Comma-separated raw integer option positions, in questionnaire order.
    val answers: String,
)
