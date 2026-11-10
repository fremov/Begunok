package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MentalDao {
    @Query("SELECT * FROM mental_results ORDER BY completedAt DESC, id DESC")
    fun observeResults(): Flow<List<MentalResultEntity>>

    @Insert
    suspend fun insert(result: MentalResultEntity)

    @Query("DELETE FROM mental_results WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM mental_check_ins ORDER BY completedAt DESC, id DESC")
    fun observeCheckIns(): Flow<List<QuickCheckInEntity>>

    @Insert
    suspend fun insertCheckIn(entry: QuickCheckInEntity)

    @Query("DELETE FROM mental_check_ins WHERE id = :id")
    suspend fun deleteCheckIn(id: Long)
}
