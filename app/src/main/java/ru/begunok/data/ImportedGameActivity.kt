package ru.begunok.data

import org.json.JSONObject
import ru.begunok.activity.DailyGameActivity

internal data class ImportedGameActivityPage(
    val games: List<DailyGameActivity>,
    val nextCursor: String?,
    val lastSyncAt: Long?,
)

internal fun parseImportedGameActivity(json: String): ImportedGameActivityPage {
    val root = JSONObject(json)
    val games = root.getJSONArray("games")
    val sources = root.getJSONArray("sources")
    return ImportedGameActivityPage(
        games = List(games.length()) { index ->
            val game = games.getJSONObject(index)
            DailyGameActivity(
                id = game.getString("id"),
                endedAt = game.getLong("endedAt"),
                durationSec = game.getInt("durationSec"),
                queueId = game.getInt("queueId"),
            )
        },
        nextCursor = if (root.isNull("nextCursor")) null else root.getString("nextCursor"),
        lastSyncAt = (0 until sources.length()).maxOfOrNull { sources.getJSONObject(it).getLong("lastSyncAt") },
    )
}
