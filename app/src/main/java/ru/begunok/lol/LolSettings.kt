package ru.begunok.lol

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import ru.begunok.BuildConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.lolStore by preferencesDataStore("lol")

data class Favorite(val riotId: String, val puuid: String)

data class LolPrefs(
    /** Адрес шлюза Sintence, например https://203.0.113.5 (пусто — не настроено). */
    val serverUrl: String = "",
    /** Токен установки на сервере; пусто — зарегистрируемся при первом запросе. */
    val token: String = "",
    val myRiotId: String = "",
    val favorites: List<Favorite> = emptyList(),
    /** Показывать выдуманные данные, пока сервер недоступен. */
    val demo: Boolean = false,
)

class LolSettings(private val context: Context) {
    private val serverKey = stringPreferencesKey("server")
    private val tokenKey = stringPreferencesKey("token")
    private val meKey = stringPreferencesKey("me")
    private val favKey = stringPreferencesKey("favorites")
    private val demoKey = booleanPreferencesKey("demo")

    val prefs: Flow<LolPrefs> = context.lolStore.data.map { p ->
        LolPrefs(
            serverUrl = p[serverKey] ?: BuildConfig.LOL_SERVER_DEFAULT,
            token = p[tokenKey].orEmpty(),
            myRiotId = p[meKey] ?: BuildConfig.LOL_ME_DEFAULT,
            favorites = decodeFavorites(p[favKey].orEmpty()),
            demo = p[demoKey] ?: false,
        )
    }

    suspend fun current(): LolPrefs = prefs.first()

    suspend fun setServer(url: String) = context.lolStore.edit {
        val clean = url.trim().trimEnd('/')
        // Новый адрес — новый сервер, прежний токен ему не подойдёт.
        if (clean != (it[serverKey] ?: BuildConfig.LOL_SERVER_DEFAULT)) it[tokenKey] = ""
        it[serverKey] = clean
    }

    suspend fun setToken(token: String) = context.lolStore.edit { it[tokenKey] = token }
    suspend fun setMe(riotId: String) = context.lolStore.edit { it[meKey] = riotId.trim() }
    suspend fun setDemo(value: Boolean) = context.lolStore.edit { it[demoKey] = value }

    suspend fun addFavorite(favorite: Favorite) = context.lolStore.edit {
        val list = decodeFavorites(it[favKey].orEmpty()).filterNot { f -> f.riotId.equals(favorite.riotId, true) }
        it[favKey] = encodeFavorites(list + favorite)
    }

    suspend fun removeFavorite(riotId: String) = context.lolStore.edit {
        it[favKey] = encodeFavorites(decodeFavorites(it[favKey].orEmpty()).filterNot { f -> f.riotId.equals(riotId, true) })
    }

    companion object {
        fun encodeFavorites(list: List<Favorite>): String =
            JSONArray(list.map { JSONObject().put("riotId", it.riotId).put("puuid", it.puuid) }).toString()

        fun decodeFavorites(raw: String): List<Favorite> = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Favorite(it.getString("riotId"), it.optString("puuid")) }
        }.getOrDefault(emptyList())
    }
}
