package ru.begunok.lol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Названия и картинки из Data Dragon и CommunityDragon (статика на CDN, без ключа и лимитов) —
 * так же, как в интерфейсе Sintence.
 */
class Names {
    @Volatile private var version: String = ""
    @Volatile private var champions: Map<Int, String> = emptyMap()
    @Volatile private var championFiles: Map<Int, String> = emptyMap()
    @Volatile private var items: Map<Int, String> = emptyMap()

    private val _loaded = MutableStateFlow(false)

    /** Станет true, когда справочники загружены: экран перерисуется с названиями и картинками. */
    val loaded: StateFlow<Boolean> = _loaded

    fun champion(id: Int): String = champions[id] ?: "Чемпион $id"
    fun item(id: Int): String = items[id] ?: "#$id"

    fun championImage(id: Int): String? =
        championFiles[id]?.let { "$CDN/$version/img/champion/$it.png" }

    fun itemImage(id: Int): String? = if (version.isEmpty() || id <= 0) null else "$CDN/$version/img/item/$id.png"

    fun profileIcon(id: Int?): String? = if (version.isEmpty() || id == null) null else "$CDN/$version/img/profileicon/$id.png"

    suspend fun load() {
        if (_loaded.value) return
        withContext(Dispatchers.IO) {
            runCatching {
                val v = JSONArray(fetch("https://ddragon.leagueoflegends.com/api/versions.json")).getString(0)
                val base = "$CDN/$v/data/ru_RU"
                val champs = JSONObject(fetch("$base/champion.json")).getJSONObject("data")
                val keys = champs.keys().asSequence().map { champs.getJSONObject(it) }.toList()
                champions = keys.associate { it.getString("key").toInt() to it.getString("name") }
                championFiles = keys.associate { it.getString("key").toInt() to it.getString("id") }
                val all = JSONObject(fetch("$base/item.json")).getJSONObject("data")
                items = all.keys().asSequence().associate { it.toInt() to all.getJSONObject(it).optString("name") }
                version = v
                _loaded.value = true
            }
        }
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection()
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        return c.getInputStream().bufferedReader().use { it.readText() }
    }

    companion object {
        private const val CDN = "https://ddragon.leagueoflegends.com/cdn"

        /** Герб ранга 500×500 из клиента League (в Data Dragon его нет). Без ранга — серый «unranked». */
        fun rankEmblem(tier: String?): String =
            "https://raw.communitydragon.org/latest/plugins/rcp-fe-lol-shared-components/global/default/" +
                "${(tier?.takeIf { it.isNotEmpty() } ?: "unranked").lowercase()}.png"
    }
}
