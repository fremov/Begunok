package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import ru.begunok.data.db.WeightDao
import ru.begunok.data.db.WeightEntryEntity
import java.time.LocalDate

class WeightRepository(private val dao: WeightDao) {
    fun observeEntries(): Flow<List<WeightEntryEntity>> = dao.observeEntries()

    suspend fun record(day: LocalDate, kg: Double) =
        dao.upsert(WeightEntryEntity(day = day.toEpochDay(), time = System.currentTimeMillis(), kg = kg))

    suspend fun delete(day: LocalDate) = dao.deleteDay(day.toEpochDay())
}
