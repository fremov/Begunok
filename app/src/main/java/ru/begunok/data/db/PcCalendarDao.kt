package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calendar_pc", primaryKeys = ["source", "day"])
data class PcCalendarDay(
    val source: String,
    val day: String,
    val ai: Int,
    val study: Int,
    val development: Int,
    val games: Int,
    val leisure: Int,
    val other: Int,
)

@Dao
abstract class PcCalendarDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceDays(days: List<PcCalendarDay>)

    @Query("DELETE FROM calendar_pc WHERE source = :source AND day IN (:dates)")
    protected abstract suspend fun clearDays(source: String, dates: List<String>)

    @Transaction
    open suspend fun applyDays(source: String, days: List<PcCalendarDay>, clears: List<String>) {
        if (clears.isNotEmpty()) clearDays(source, clears)
        if (days.isNotEmpty()) replaceDays(days)
    }

    @Query("SELECT * FROM calendar_pc WHERE source = :source AND day BETWEEN :start AND :end")
    abstract fun observe(source: String, start: String, end: String): Flow<List<PcCalendarDay>>
}
