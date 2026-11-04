package ru.begunok.lol

/** Riot ID вида «Имя#TAG». */
data class RiotId(val name: String, val tag: String) {
    override fun toString() = "$name#$tag"

    companion object {
        fun parse(raw: String): RiotId? {
            val text = raw.trim()
            val hash = text.lastIndexOf('#')
            if (hash <= 0 || hash == text.lastIndex) return null
            val name = text.substring(0, hash).trim()
            val tag = text.substring(hash + 1).trim()
            return if (name.isEmpty() || tag.isEmpty()) null else RiotId(name, tag)
        }
    }
}

data class Account(val puuid: String, val id: RiotId)

data class RankEntry(
    val queue: String,
    val tier: String,
    val division: String,
    val lp: Int,
    val wins: Int,
    val losses: Int,
) {
    val games: Int get() = wins + losses
    val winRate: Int get() = if (games == 0) 0 else wins * 100 / games
}

/** Подсказка ника: игрок из просмотренных матчей Sintence и сколько раз он встречался. */
data class Suggestion(val riotId: String, val games: Int)

data class Mastery(val championId: Int, val level: Int, val points: Long)

data class MatchPlayer(
    val puuid: String,
    val riotId: String,
    val teamId: Int,
    val championId: Int,
    val championName: String,
    val level: Int,
    val kills: Int,
    val deaths: Int,
    val assists: Int,
    val cs: Int,
    val gold: Int,
    val damage: Int,
    val vision: Int,
    val win: Boolean,
    val position: String,
    val items: List<Int>,
)

data class MatchSummary(
    val id: String,
    val queueId: Int,
    val durationSec: Int,
    val endedAtMs: Long,
    val players: List<MatchPlayer>,
    val mePuuid: String,
) {
    val me: MatchPlayer? get() = players.firstOrNull { it.puuid == mePuuid }
}

data class LivePlayer(
    val puuid: String,
    val riotId: String,
    val teamId: Int,
    val championId: Int,
    val rank: RankEntry?,
)

data class LiveGame(
    val queueId: Int,
    val mode: String,
    val lengthSec: Int,
    val players: List<LivePlayer>,
    /** Пары (чемпион, команда). */
    val bans: List<Pair<Int, Int>>,
)

data class Profile(
    val account: Account,
    val level: Int?,
    val iconId: Int?,
    val ranks: List<RankEntry>,
    val mastery: List<Mastery>,
    val matches: List<MatchSummary>,
    /** Что не удалось получить (ранги, мастери, матчи), — для подсказки в профиле. */
    val missing: List<String>,
)

/** Название очереди по её номеру; неизвестную показываем номером. */
fun queueName(queueId: Int): String = when (queueId) {
    400 -> "Обычная (драфт)"
    420 -> "Ранговая: одиночная"
    430 -> "Обычная (слепой выбор)"
    440 -> "Ранговая: гибкая"
    450 -> "ARAM"
    490 -> "Быстрая игра"
    700 -> "Столкновение"
    1700 -> "Арена"
    2400 -> "ARAM: Хаос"
    0 -> "Своя игра"
    else -> "Очередь $queueId"
}

fun tierName(tier: String): String = when (tier.uppercase()) {
    "IRON" -> "Железо"
    "BRONZE" -> "Бронза"
    "SILVER" -> "Серебро"
    "GOLD" -> "Золото"
    "PLATINUM" -> "Платина"
    "EMERALD" -> "Изумруд"
    "DIAMOND" -> "Алмаз"
    "MASTER" -> "Мастер"
    "GRANDMASTER" -> "Грандмастер"
    "CHALLENGER" -> "Претендент"
    else -> tier
}

fun rankQueueName(queue: String): String = when (queue) {
    "RANKED_SOLO_5x5" -> "Одиночная"
    "RANKED_FLEX_SR" -> "Гибкая"
    else -> queue
}

private val APEX = setOf("MASTER", "GRANDMASTER", "CHALLENGER")

/** «Золото II · 45 LP»; у Мастера и выше дивизиона нет. */
fun RankEntry.label(): String {
    val name = tierName(tier)
    return if (tier.uppercase() in APEX) "$name · $lp LP" else "$name $division · $lp LP"
}

fun List<RankEntry>.solo(): RankEntry? = firstOrNull { it.queue == "RANKED_SOLO_5x5" }

fun roleName(position: String): String = when (position) {
    "TOP" -> "Топ"
    "JUNGLE" -> "Лес"
    "MIDDLE" -> "Мид"
    "BOTTOM" -> "АДК"
    "UTILITY" -> "Саппорт"
    else -> ""
}
