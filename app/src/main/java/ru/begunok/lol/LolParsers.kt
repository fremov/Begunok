package ru.begunok.lol

import org.json.JSONArray
import org.json.JSONObject

/** Разбор ответов Riot (через шлюз Sintence) в наши модели. Без сети и Android — проверяется юнит-тестами. */
object LolParsers {
    fun account(json: String): Account {
        val o = JSONObject(json)
        return Account(
            puuid = o.getString("puuid"),
            id = RiotId(o.optString("gameName"), o.optString("tagLine")),
        )
    }

    /** (уровень, номер иконки). */
    fun summoner(json: String): Pair<Int?, Int?> {
        val o = JSONObject(json)
        val level = if (o.has("summonerLevel")) o.getInt("summonerLevel") else null
        val icon = if (o.has("profileIconId")) o.getInt("profileIconId") else null
        return level to icon
    }

    fun ranks(json: String): List<RankEntry> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            RankEntry(
                queue = it.optString("queueType"),
                tier = it.optString("tier"),
                division = it.optString("rank"),
                lp = it.optInt("leaguePoints"),
                wins = it.optInt("wins"),
                losses = it.optInt("losses"),
            )
        }.sortedBy { if (it.queue == "RANKED_SOLO_5x5") 0 else 1 }
    }

    fun mastery(json: String): List<Mastery> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Mastery(it.getInt("championId"), it.optInt("championLevel"), it.optLong("championPoints"))
        }
    }

    fun suggestions(json: String): List<Suggestion> {
        val a = JSONObject(json).optJSONArray("players") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .map { Suggestion(it.optString("riotId"), it.optInt("games")) }
            .filter { it.riotId.isNotEmpty() }
    }

    fun matchIds(json: String): List<String> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getString(it) }
    }

    fun match(json: String, mePuuid: String): MatchSummary {
        val root = JSONObject(json)
        val info = root.getJSONObject("info")
        val parts = info.getJSONArray("participants")
        val players = (0 until parts.length()).map { parts.getJSONObject(it) }.map { p ->
            val name = p.optString("riotIdGameName").ifEmpty { p.optString("summonerName") }
            val tag = p.optString("riotIdTagline")
            MatchPlayer(
                puuid = p.optString("puuid"),
                riotId = if (tag.isEmpty()) name else "$name#$tag",
                teamId = p.optInt("teamId"),
                championId = p.optInt("championId"),
                championName = p.optString("championName"),
                level = p.optInt("champLevel"),
                kills = p.optInt("kills"),
                deaths = p.optInt("deaths"),
                assists = p.optInt("assists"),
                cs = p.optInt("totalMinionsKilled") + p.optInt("neutralMinionsKilled"),
                gold = p.optInt("goldEarned"),
                damage = p.optInt("totalDamageDealtToChampions"),
                vision = p.optInt("visionScore"),
                win = p.optBoolean("win"),
                position = p.optString("teamPosition"),
                items = (0..6).map { i -> p.optInt("item$i") }.filter { it > 0 },
            )
        }
        val ended = info.optLong("gameEndTimestamp", 0L).takeIf { it > 0 }
            ?: (info.optLong("gameStartTimestamp") + info.optLong("gameDuration") * 1000)
        return MatchSummary(
            id = root.optJSONObject("metadata")?.optString("matchId").orEmpty(),
            queueId = info.optInt("queueId"),
            // Старые матчи отдают длительность в миллисекундах — их в 2026 уже нет, но страхуемся.
            durationSec = info.optLong("gameDuration").let { if (it > 100_000) (it / 1000).toInt() else it.toInt() },
            endedAtMs = ended,
            players = players,
            mePuuid = mePuuid,
        )
    }

    /** Игра сейчас (spectator-v5); ранги игроков подставляются отдельно. */
    fun liveGame(json: String): LiveGame {
        val o = JSONObject(json)
        val parts = o.optJSONArray("participants") ?: JSONArray()
        val players = (0 until parts.length()).map { parts.getJSONObject(it) }.map {
            LivePlayer(
                puuid = it.optString("puuid"),
                riotId = it.optString("riotId"),
                teamId = it.optInt("teamId"),
                championId = it.optInt("championId"),
                rank = null,
            )
        }
        val banned = o.optJSONArray("bannedChampions") ?: JSONArray()
        val bans = (0 until banned.length()).map { banned.getJSONObject(it) }
            .filter { it.optInt("championId") > 0 }
            .map { it.optInt("championId") to it.optInt("teamId") }
        return LiveGame(
            queueId = o.optInt("gameQueueConfigId"),
            mode = o.optString("gameMode"),
            lengthSec = o.optInt("gameLength"),
            players = players,
            bans = bans,
        )
    }
}
