package ru.begunok.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import ru.begunok.activity.DailyGameActivity
import ru.begunok.lol.LolGateway
import ru.begunok.lol.LolParsers
import ru.begunok.lol.LolSettings
import ru.begunok.lol.RiotId
import ru.begunok.lol.ServerLolSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

data class GameActivityResult(
    val games: List<DailyGameActivity>?,
    val message: String? = null,
)

internal fun mergeGameActivity(riot: GameActivityResult, imported: GameActivityResult): GameActivityResult {
    val games = if (riot.games == null && imported.games == null) null else {
        val byId = LinkedHashMap<String, DailyGameActivity>()
        for (game in riot.games.orEmpty()) byId[game.id] = game
        for (game in imported.games.orEmpty()) byId.putIfAbsent(game.id, game)
        byId.values.sortedByDescending { it.endedAt }
    }
    return GameActivityResult(games, listOfNotNull(riot.message, imported.message).joinToString("\n").ifEmpty { null })
}

/** Riot даёт обычные режимы; приватная история клиента League дополняет их ARAM «Хаос». */
class GameActivityRepository(private val settings: LolSettings) {
    suspend fun load(
        start: LocalDate?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): GameActivityResult = withContext(Dispatchers.IO) {
        val prefs = settings.current()
        check(!prefs.demo) { "История игр недоступна в деморежиме." }
        val riotId = requireNotNull(RiotId.parse(prefs.myRiotId)) {
            "Укажите свой Riot ID в формате Имя#TAG в настройках LoL."
        }
        val gateway = LolGateway(settings)
        val riot = async {
            try {
                GameActivityResult(loadRiot(gateway, riotId, start, today, zone))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GameActivityResult(null, "История Riot недоступна; общий итог игр может быть неполным.")
            }
        }
        val imported = async {
            try {
                loadImported(gateway, "${riotId.name}#${riotId.tag}", start, today, zone)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GameActivityResult(null, "Не удалось загрузить историю клиента League. ARAM «Хаос» может отсутствовать; обновите данные.")
            }
        }
        mergeGameActivity(riot.await(), imported.await())
    }

    private suspend fun loadImported(
        gateway: LolGateway,
        riotId: String,
        start: LocalDate?,
        today: LocalDate,
        zone: ZoneId,
    ): GameActivityResult {
        val from = start?.atStartOfDay(zone)?.toEpochSecond()
        val until = today.plusDays(1).atStartOfDay(zone).toEpochSecond()
        val games = mutableListOf<DailyGameActivity>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var lastSyncAt: Long? = null
        do {
            currentCoroutineContext().ensureActive()
            val page = parseImportedGameActivity(gateway.activityMatches(riotId, from, until, cursor))
            games += page.games
            page.lastSyncAt?.let { lastSyncAt = maxOf(lastSyncAt ?: it, it) }
            cursor = page.nextCursor
            check(cursor == null || seenCursors.add(cursor)) { "Повторный курсор истории клиента League." }
        } while (cursor != null)
        val sync = lastSyncAt
        val message = if (sync == null) {
            "ARAM «Хаос» не синхронизирован. Нужен запущенный настольный Sintence, связанный с этой установкой MyLife."
        } else {
            val time = Instant.ofEpochMilli(sync).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
            "История клиента League: $time. Новые игры «Хаоса» передаются при запущенном Sintence."
        }
        return GameActivityResult(games, message)
    }

    private suspend fun loadRiot(
        gateway: LolGateway,
        riotId: RiotId,
        start: LocalDate?,
        today: LocalDate,
        zone: ZoneId,
    ): List<DailyGameActivity> = kotlinx.coroutines.coroutineScope {
        val account = ServerLolSource(gateway).account(riotId)
        val puuid = LolGateway.segment(account.puuid)
        val from = start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val until = today.plusDays(1).atStartOfDay(zone).toInstant()
        // Match IDs are selected by start time, but daily activity belongs to the end day.
        val startQuery = start?.minusDays(1)?.atStartOfDay(zone)?.toEpochSecond()
            ?.let { "&startTime=$it" }.orEmpty()
        val endQuery = "&endTime=${until.epochSecond}"
        val untilMillis = until.toEpochMilli()
        val seen = mutableSetOf<String>()
        val games = mutableListOf<DailyGameActivity>()
        var offset = 0

        while (true) {
            ensureActive()
            val ids = LolParsers.matchIds(
                gateway.riot(
                    REGION,
                    "/lol/match/v5/matches/by-puuid/$puuid/ids?start=$offset&count=$PAGE_SIZE$startQuery$endQuery",
                ),
            )
            for (batch in ids.filter { seen.add(it) }.chunked(DETAIL_CONCURRENCY)) {
                val details = batch.map { id ->
                    async {
                        val match = LolParsers.match(
                            gateway.riot(REGION, "/lol/match/v5/matches/${LolGateway.segment(id)}"),
                            account.puuid,
                        )
                        DailyGameActivity(
                            id = id,
                            endedAt = match.endedAtMs,
                            durationSec = match.durationSec,
                            queueId = match.queueId,
                        )
                    }
                }.awaitAll()
                for (game in details) {
                    if (game.endedAt > 0 && (from == null || game.endedAt >= from) && game.endedAt < untilMillis) {
                        games += game
                    }
                }
            }
            if (ids.size < PAGE_SIZE) break
            offset += PAGE_SIZE
        }
        games.sortedByDescending { it.endedAt }
    }

    private companion object {
        const val REGION = "europe"
        const val PAGE_SIZE = 100
        const val DETAIL_CONCURRENCY = 4
    }
}
