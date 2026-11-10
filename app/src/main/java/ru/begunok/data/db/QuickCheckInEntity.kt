package ru.begunok.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mental_check_ins")
data class QuickCheckInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val completedAt: Long,
    val mood: Int,
    val tension: Int,
    val energy: Int,
    val context: String? = null,
)
