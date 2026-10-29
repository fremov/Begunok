package ru.begunok.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insertBook(book: BookEntity): Long

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun book(id: Long): BookEntity?

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)

    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Insert
    suspend fun insertEntry(entry: ReadingEntryEntity)

    @Query("SELECT * FROM reading_entries ORDER BY time DESC")
    fun observeEntries(): Flow<List<ReadingEntryEntity>>
}
