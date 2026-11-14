package ru.begunok.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.begunok.activity.DailyGameActivity

class GameActivityRepositoryTest {
    @Test
    fun riotAndClientCopiesCountOnceWhileMayhemIsPreserved() {
        val riotGame = DailyGameActivity("RU_1", 1794331246547, 900, 450)
        val mayhem = DailyGameActivity("RU_2", 1794332246547, 704, 2400)
        val result = mergeGameActivity(
            GameActivityResult(listOf(riotGame)),
            GameActivityResult(listOf(riotGame.copy(durationSec = 899), mayhem, mayhem)),
        )
        assertEquals(listOf(mayhem, riotGame), result.games)
        assertEquals(1_604, result.games!!.sumOf { it.durationSec })
    }

    @Test
    fun failedSourceDoesNotEraseGamesReadFromTheOther() {
        val mayhem = DailyGameActivity("RU_2", 1794332246547, 704, 2400)
        val unavailable = GameActivityResult(null, "Источник недоступен")
        val available = GameActivityResult(listOf(mayhem))
        for (result in listOf(mergeGameActivity(unavailable, available), mergeGameActivity(available, unavailable))) {
            assertEquals(listOf(mayhem), result.games)
            assertEquals("Источник недоступен", result.message)
        }
        assertNull(mergeGameActivity(unavailable, unavailable).games)
    }

    @Test
    fun privateHistoryKeepsMayhemTimesAndOpaquePagination() {
        val page = parseImportedGameActivity(
            """{"games":[{"id":"RU_573430294","queueId":2400,"endedAt":1794331246547,"durationSec":704}],
                "nextCursor":"next/+==", "sources":[{"lastSyncAt":1794331247000}]}""",
        )
        assertEquals(listOf(DailyGameActivity("RU_573430294", 1794331246547, 704, 2400)), page.games)
        assertEquals("next/+==", page.nextCursor)
        assertEquals(1794331247000L, page.lastSyncAt)
    }

    @Test
    fun emptyHistoryDistinguishesUnlinkedClientFromSyncedPeriodWithoutGames() {
        val unlinked = parseImportedGameActivity("""{"games":[],"nextCursor":null,"sources":[]}""")
        val synced = parseImportedGameActivity("""{"games":[],"nextCursor":null,"sources":[{"lastSyncAt":1794331247000}]}""")
        assertNull(unlinked.lastSyncAt)
        assertEquals(1794331247000L, synced.lastSyncAt)
        assertNull(unlinked.nextCursor)
        assertNull(synced.nextCursor)
    }
}
