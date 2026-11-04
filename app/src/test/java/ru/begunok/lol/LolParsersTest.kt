package ru.begunok.lol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Образцы ответов собраны по документации Riot, это не записи с реального сервера. */
class LolParsersTest {
    @Test
    fun riotIdParse() {
        assertEquals(RiotId("Имя", "RU1"), RiotId.parse(" Имя # RU1 "))
        assertNull(RiotId.parse("безтега"))
        assertNull(RiotId.parse("#RU1"))
        assertNull(RiotId.parse("Имя#"))
    }

    @Test
    fun account() {
        val a = LolParsers.account("""{"puuid":"abc-1","gameName":"Имя","tagLine":"RU1"}""")
        assertEquals("abc-1", a.puuid)
        assertEquals("Имя#RU1", a.id.toString())
    }

    @Test
    fun ranksSoloFirst() {
        val r = LolParsers.ranks(
            """[{"queueType":"RANKED_FLEX_SR","tier":"GOLD","rank":"IV","leaguePoints":12,"wins":10,"losses":9},
                {"queueType":"RANKED_SOLO_5x5","tier":"MASTER","rank":"I","leaguePoints":120,"wins":60,"losses":40}]""",
        )
        assertEquals("RANKED_SOLO_5x5", r[0].queue)
        assertEquals("Мастер · 120 LP", r[0].label())
        assertEquals("Золото IV · 12 LP", r[1].label())
        assertEquals(60, r[0].winRate)
        assertNotNull(r.solo())
    }

    @Test
    fun emptyRanks() {
        assertTrue(LolParsers.ranks("[]").isEmpty())
    }

    @Test
    fun match() {
        val m = LolParsers.match(
            """{"metadata":{"matchId":"RU_1"},"info":{"queueId":2400,"gameDuration":1500,"gameEndTimestamp":1700000000000,
              "participants":[{"puuid":"me","riotIdGameName":"Я","riotIdTagline":"RU1","teamId":100,"championId":103,
              "championName":"Ahri","champLevel":15,"kills":5,"deaths":2,"assists":7,"totalMinionsKilled":150,
              "neutralMinionsKilled":10,"goldEarned":11000,"totalDamageDealtToChampions":20000,"visionScore":12,
              "win":true,"teamPosition":"MIDDLE","item0":3078,"item1":0,"item2":3158}]}}""",
            "me",
        )
        val me = m.me!!
        assertEquals(160, me.cs)
        assertEquals(listOf(3078, 3158), me.items)
        assertEquals("Я#RU1", me.riotId)
        assertEquals(1500, m.durationSec)
        assertEquals("ARAM: Хаос", queueName(m.queueId))
    }

    @Test
    fun liveGame() {
        val g = LolParsers.liveGame(
            """{"gameQueueConfigId":420,"gameMode":"CLASSIC","gameLength":300,
              "participants":[{"puuid":"p1","riotId":"А#1","teamId":100,"championId":86}],
              "bannedChampions":[{"championId":238,"teamId":100},{"championId":-1,"teamId":200}]}""",
        )
        assertEquals(1, g.players.size)
        assertEquals(listOf(238 to 100), g.bans)
    }

    @Test
    fun suggestions() {
        val s = LolParsers.suggestions("""{"players":[{"riotId":"Амп#1","games":7},{"riotId":"","games":1}]}""")
        assertEquals(listOf(Suggestion("Амп#1", 7)), s)
        assertTrue(LolParsers.suggestions("""{}""").isEmpty())
    }

    @Test
    fun favoritesRoundTrip() {
        val list = listOf(Favorite("А#1", "p1"), Favorite("Б#2", ""))
        assertEquals(list, LolSettings.decodeFavorites(LolSettings.encodeFavorites(list)))
        assertTrue(LolSettings.decodeFavorites("мусор").isEmpty())
    }
}
