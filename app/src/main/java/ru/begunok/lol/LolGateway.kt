package ru.begunok.lol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class GatewayError { NotConfigured, Unreachable, Blocked, NotFound, RateLimited, Server }

class GatewayException(val kind: GatewayError, message: String) : Exception(message)

/** Человеческое сообщение об ошибке шлюза. */
fun GatewayException.userMessage(): String = when (kind) {
    GatewayError.NotConfigured -> "Адрес сервера Sintence не задан — впишите его в «Подключении»."
    GatewayError.Unreachable -> "Сервер Sintence недоступен. Проверьте адрес и интернет."
    GatewayError.Blocked -> "Сервер заблокировал этот токен."
    GatewayError.NotFound -> "Не найдено."
    GatewayError.RateLimited -> "Слишком много запросов, попробуйте через минуту."
    GatewayError.Server -> message ?: "Ошибка сервера."
}

/**
 * Клиент шлюза Sintence (пути /v1/..., sintence-data/api/server.py): Riot API идёт через сервер,
 * ключ Riot в приложении не хранится. Токен свой у каждой установки — выдаёт `POST /v1/register`.
 */
class LolGateway(private val settings: LolSettings) {
    private class Reply(val code: Int, val body: String)

    suspend fun health(): Boolean {
        val server = settings.current().serverUrl
        if (server.isEmpty()) throw GatewayException(GatewayError.NotConfigured, "")
        return call("GET", "$server/v1/health", null, null).code == 200
    }

    /** Запрос к Riot через шлюз: `host` — «europe» или «ru», `path` — путь Riot, уже закодированный. */
    suspend fun riot(host: String, path: String): String = authed("/v1/riot/$host.api.riotgames.com$path")

    /** Подсказки ника из базы Sintence: без запросов к Riot, по части ника. */
    suspend fun players(query: String, limit: Int): String =
        authed("/v1/players?q=${segment(query)}&limit=$limit")

    /** Собственные игры, синхронизированные настольным Sintence, включая ARAM «Хаос». */
    suspend fun activityMatches(riotId: String, startTime: Long?, endTime: Long, cursor: String?): String {
        val query = buildString {
            append("riotId=${segment(riotId)}&endTime=$endTime")
            if (startTime != null) append("&startTime=$startTime")
            if (cursor != null) append("&cursor=${segment(cursor)}")
        }
        return authed("/v1/activity/matches?$query")
    }

    private suspend fun authed(path: String): String {
        val server = settings.current().serverUrl
        if (server.isEmpty()) throw GatewayException(GatewayError.NotConfigured, "")
        val url = server + path
        var reply = call("GET", url, null, ensureToken(server))
        if (reply.code == 401) {
            // Сервер не знает токен (новая база или сброс) — регистрируемся заново, один раз.
            settings.setToken("")
            reply = call("GET", url, null, ensureToken(server))
        }
        return when (reply.code) {
            200 -> reply.body
            403 -> throw GatewayException(GatewayError.Blocked, "")
            404 -> throw GatewayException(GatewayError.NotFound, "")
            429 -> throw GatewayException(GatewayError.RateLimited, "")
            else -> throw GatewayException(GatewayError.Server, "Ошибка сервера (${reply.code}).")
        }
    }

    private suspend fun ensureToken(server: String): String {
        val saved = settings.current().token
        if (saved.isNotEmpty()) return saved
        val reply = call("POST", "$server/v1/register", """{"appVersion":"mylife"}""", null)
        if (reply.code != 201) {
            throw GatewayException(
                GatewayError.Server,
                if (reply.code == 503) "Регистрация на сервере закрыта." else "Регистрация не удалась (${reply.code}).",
            )
        }
        val token = JSONObject(reply.body).getString("token")
        settings.setToken(token)
        return token
    }

    private suspend fun call(method: String, url: String, body: String?, token: String?): Reply =
        withContext(Dispatchers.IO) {
            val connection = try {
                (URL(url).openConnection() as HttpURLConnection)
            } catch (e: Exception) {
                throw GatewayException(GatewayError.Unreachable, e.message.orEmpty())
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = 10_000
                connection.readTimeout = 25_000
                connection.setRequestProperty("Accept", "application/json")
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                Reply(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
            } catch (e: GatewayException) {
                throw e
            } catch (e: Exception) {
                throw GatewayException(GatewayError.Unreachable, e.message.orEmpty())
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        /** Сегмент пути: пробелы и кириллица в нике кодируются, «+» не годится. */
        fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
