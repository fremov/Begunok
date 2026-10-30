package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WeightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeightEntryEntity)

    @Query("DELETE FROM weight_entries WHERE day = :day")
    suspend fun deleteDay(day: Long)

    @Query("SELECT * FROM weight_entries ORDER BY day ASC")
    fun observeEntries(): Flow<List<WeightEntryEntity>>
}
