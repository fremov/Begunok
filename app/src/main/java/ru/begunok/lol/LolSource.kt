package ru.begunok.lol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/** Источник данных вкладки: настоящий (через сервер Sintence) или демонстрационный. */
interface LolSource {
    suspend fun account(id: RiotId): Account
    suspend fun profile(account: Account, matchCount: Int): Profile
    suspend fun ranks(puuid: String): List<RankEntry>
    suspend fun liveGame(puuid: String): LiveGame?

    /** Подсказки ника по началу ввода (из базы Sintence). */
    suspend fun suggest(query: String): List<Suggestion>
}

/** Платформа игрока; для RU — `ru`. Для других регионов понадобится region-v1 (шлюз его разрешает). */
private const val PLATFORM = "ru"
private const val REGION = "europe"

class ServerLolSource(private val gateway: LolGateway) : LolSource {
    override suspend fun account(id: RiotId): Account {
        val path = "/riot/account/v1/accounts/by-riot-id/${LolGateway.segment(id.name)}/${LolGateway.segment(id.tag)}"
        return LolParsers.account(gateway.riot(REGION, path))
    }

    override suspend fun profile(account: Account, matchCount: Int): Profile = coroutineScope {
        val puuid = account.puuid
        val summoner = async { runCatching { LolParsers.summoner(gateway.riot(PLATFORM, "/lol/summoner/v4/summoners/by-puuid/$puuid")) } }
        val ranks = async { runCatching { ranks(puuid) } }
        val mastery = async {
            runCatching { LolParsers.mastery(gateway.riot(PLATFORM, "/lol/champion-mastery/v4/champion-masteries/by-puuid/$puuid/top?count=5")) }
        }
        val ids = async {
            runCatching { LolParsers.matchIds(gateway.riot(REGION, "/lol/match/v5/matches/by-puuid/$puuid/ids?start=0&count=$matchCount")) }
        }
        val missing = mutableListOf<String>()
        val matchList = ids.await().getOrElse { missing += "матчи"; emptyList() }
            .map { id -> async { runCatching { LolParsers.match(gateway.riot(REGION, "/lol/match/v5/matches/$id"), puuid) } } }
            .mapNotNull { it.await().getOrNull() }
        val (level, icon) = summoner.await().getOrElse { missing += "уровень"; null to null }
        Profile(
            account = account,
            level = level,
            iconId = icon,
            ranks = ranks.await().getOrElse { missing += "ранги"; emptyList() },
            mastery = mastery.await().getOrElse { missing += "мастери"; emptyList() },
            matches = matchList.sortedByDescending { it.endedAtMs },
            missing = missing,
        )
    }

    override suspend fun suggest(query: String): List<Suggestion> =
        LolParsers.suggestions(gateway.players(query, 8))

    override suspend fun ranks(puuid: String): List<RankEntry> =
        LolParsers.ranks(gateway.riot(PLATFORM, "/lol/league/v4/entries/by-puuid/$puuid"))

    override suspend fun liveGame(puuid: String): LiveGame? {
        val game = try {
            LolParsers.liveGame(gateway.riot(PLATFORM, "/lol/spectator/v5/active-games/by-summoner/$puuid"))
        } catch (e: GatewayException) {
            // 404 у spectator — игрок не в игре, это не ошибка.
            if (e.kind == GatewayError.NotFound) return null
            throw e
        }
        // Ранги всех игроков — по запросу на каждого (кеш на сервере 10 минут). Сбой одного не рушит остальных.
        return coroutineScope {
            val withRanks = game.players.map { p ->
                async { p.copy(rank = if (p.puuid.isEmpty()) null else runCatching { ranks(p.puuid).solo() }.getOrNull()) }
            }.map { it.await() }
            game.copy(players = withRanks)
        }
    }
}
