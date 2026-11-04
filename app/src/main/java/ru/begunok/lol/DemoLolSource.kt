package ru.begunok.lol

import kotlinx.coroutines.delay

/** Выдуманные данные, чтобы смотреть экран, пока сервер Sintence недоступен. Это не настоящие игроки. */
class DemoLolSource : LolSource {
    override suspend fun account(id: RiotId): Account {
        delay(300)
        return Account("demo-${id.name.lowercase()}", id)
    }

    override suspend fun suggest(query: String): List<Suggestion> {
        delay(150)
        val all = listOf("Амплификатор#3138" to 40, "Ампер#RU1" to 12, "Тестовый#RU1" to 9, "Игрок1#RU1" to 3)
        return all.filter { it.first.contains(query, ignoreCase = true) }.map { Suggestion(it.first, it.second) }
    }

    override suspend fun ranks(puuid: String): List<RankEntry> {
        delay(150)
        val n = puuid.hashCode().let { if (it < 0) -it else it }
        val tiers = listOf("SILVER", "GOLD", "PLATINUM", "EMERALD", "DIAMOND")
        val wins = 40 + n % 60
        val losses = 35 + n % 50
        return listOf(
            RankEntry("RANKED_SOLO_5x5", tiers[n % tiers.size], listOf("I", "II", "III", "IV")[n % 4], n % 100, wins, losses),
            RankEntry("RANKED_FLEX_SR", "GOLD", "IV", 12, 10, 9),
        )
    }

    override suspend fun profile(account: Account, matchCount: Int): Profile {
        delay(500)
        val champs = listOf(103, 86, 222, 238, 157, 64, 51, 412, 245, 99)
        val players = { me: Int, win: Boolean, team: Int ->
            (0 until 10).map { i ->
                MatchPlayer(
                    puuid = if (i == me) account.puuid else "demo-p$i",
                    riotId = if (i == me) account.id.toString() else "Игрок$i#RU1",
                    teamId = if (i < 5) 100 else 200,
                    championId = champs[(i + team) % champs.size],
                    championName = "",
                    level = 14 + i % 4,
                    kills = 3 + i, deaths = 2 + i % 5, assists = 5 + i % 7,
                    cs = 120 + i * 11, gold = 9000 + i * 600, damage = 15000 + i * 2100, vision = 10 + i * 3,
                    win = if (i < 5) win else !win,
                    position = listOf("TOP", "JUNGLE", "MIDDLE", "BOTTOM", "UTILITY")[i % 5],
                    items = listOf(3078, 3158, 6653, 3089, 3157, 3020),
                )
            }
        }
        val now = System.currentTimeMillis()
        val matches = (0 until matchCount.coerceAtMost(10)).map {
            MatchSummary(
                id = "DEMO_$it",
                queueId = listOf(420, 450, 2400, 440, 420)[it % 5],
                durationSec = 1500 + it * 130,
                endedAtMs = now - it * 5_400_000L,
                players = players(it % 5, it % 3 != 0, it),
                mePuuid = account.puuid,
            )
        }
        return Profile(
            account = account, level = 312, iconId = 4568,
            ranks = ranks(account.puuid),
            mastery = listOf(Mastery(103, 7, 412_000), Mastery(222, 7, 301_500), Mastery(238, 6, 188_200)),
            matches = matches,
            missing = emptyList(),
        )
    }

    override suspend fun liveGame(puuid: String): LiveGame? {
        delay(300)
        // Игрок «в игре» у каждого второго демо-аккаунта — чтобы видеть оба состояния.
        if (puuid.length % 2 == 0) return null
        val champs = listOf(103, 86, 222, 238, 157, 64, 51, 412, 245, 99)
        return LiveGame(
            queueId = 420, mode = "CLASSIC", lengthSec = 734,
            players = (0 until 10).map { i ->
                LivePlayer(if (i == 0) puuid else "demo-p$i", if (i == 0) "Вы#RU1" else "Игрок$i#RU1",
                    if (i < 5) 100 else 200, champs[i], ranks("demo-p$i").first())
            },
            bans = listOf(238 to 100, 157 to 100, 64 to 200),
        )
    }
}
