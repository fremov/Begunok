package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ShowerDao {
    @Insert
    suspend fun insert(shower: ColdShowerEntity): Long

    @Query("SELECT * FROM cold_showers ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ColdShowerEntity>>

    @Query("DELETE FROM cold_showers WHERE id = :id")
    suspend fun delete(id: Long)
}
