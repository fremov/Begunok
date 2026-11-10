package ru.begunok.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.begunok.data.db.MentalDao
import ru.begunok.data.db.MentalResultEntity
import ru.begunok.data.db.QuickCheckInEntity
import ru.begunok.mental.MentalResult
import ru.begunok.mental.MentalTest
import ru.begunok.mental.QuickCheckIn

class MentalRepository(private val dao: MentalDao) {
    fun observeResults(): Flow<List<MentalResult>> = dao.observeResults().map { results ->
        results.map { result ->
            val test = MentalTest.valueOf(result.test)
            val answers = result.answers.split(',').map { it.toInt() }
            test.score(answers)
            MentalResult(result.id, test, result.completedAt, answers)
        }
    }

    suspend fun record(test: MentalTest, answers: List<Int>, completedAt: Long = System.currentTimeMillis()) {
        test.score(answers)
        dao.insert(
            MentalResultEntity(
                test = test.name,
                completedAt = completedAt,
                answers = answers.joinToString(","),
            )
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)

    fun observeCheckIns(): Flow<List<QuickCheckIn>> = dao.observeCheckIns().map { entries ->
        entries.map { entry ->
            QuickCheckIn(entry.id, entry.completedAt, entry.mood, entry.tension, entry.energy, entry.context)
        }
    }

    suspend fun recordCheckIn(
        mood: Int,
        tension: Int,
        energy: Int,
        context: String? = null,
        completedAt: Long = System.currentTimeMillis(),
    ) {
        // Validate before touching storage; no same-day replacement or score aggregation.
        val entry = QuickCheckIn(
            completedAt = completedAt,
            mood = mood,
            tension = tension,
            energy = energy,
            context = context?.trim()?.takeIf { it.isNotEmpty() },
        )
        dao.insertCheckIn(
            QuickCheckInEntity(
                completedAt = entry.completedAt,
                mood = entry.mood,
                tension = entry.tension,
                energy = entry.energy,
                context = entry.context,
            )
        )
    }

    suspend fun deleteCheckIn(id: Long) = dao.deleteCheckIn(id)
}
