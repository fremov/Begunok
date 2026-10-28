package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import ru.begunok.data.db.ColdShowerEntity
import ru.begunok.data.db.ShowerDao

class ShowerRepository(private val dao: ShowerDao) {
    fun observeRecent(limit: Int = 200): Flow<List<ColdShowerEntity>> = dao.observeRecent(limit)

    suspend fun add(time: Long, durationSec: Int, targetSec: Int, completed: Boolean) =
        dao.insert(ColdShowerEntity(time = time, durationSec = durationSec, targetSec = targetSec, completed = completed))

    suspend fun delete(id: Long) = dao.delete(id)
}
