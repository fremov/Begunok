package ru.begunok.ui.lol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.begunok.lol.DemoLolSource
import ru.begunok.lol.Favorite
import ru.begunok.lol.GatewayException
import ru.begunok.lol.LiveGame
import ru.begunok.lol.LolGateway
import ru.begunok.lol.LolPrefs
import ru.begunok.lol.LolSettings
import ru.begunok.lol.LolSource
import ru.begunok.lol.Names
import ru.begunok.lol.Profile
import ru.begunok.lol.RankEntry
import ru.begunok.lol.RiotId
import ru.begunok.lol.ServerLolSource
import ru.begunok.lol.Suggestion
import ru.begunok.lol.solo
import ru.begunok.lol.userMessage

sealed interface LiveState {
    data object Unknown : LiveState
    data object Loading : LiveState
    data object NotInGame : LiveState
    data class InGame(val game: LiveGame) : LiveState
    data class Failed(val message: String) : LiveState
}

sealed interface ProfileState {
    data class Loading(val riotId: String) : ProfileState
    data class Failed(val riotId: String, val message: String) : ProfileState
    data class Ready(val profile: Profile, val live: LiveState) : ProfileState
}

/** Строка избранного: ранг соло и «в игре» — по кнопке обновления. */
data class FavoriteRow(val favorite: Favorite, val rank: RankEntry? = null, val live: LiveState = LiveState.Unknown, val loading: Boolean = false)

data class Connection(val checking: Boolean = false, val message: String? = null, val ok: Boolean? = null)

class LolViewModel(private val settings: LolSettings) : ViewModel() {
    private val gateway = LolGateway(settings)
    private val server = ServerLolSource(gateway)
    private val demo = DemoLolSource()
    val names = Names()

    val prefs: StateFlow<LolPrefs> = settings.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, LolPrefs())

    private val extras = MutableStateFlow<Map<String, FavoriteRow>>(emptyMap())
    val favorites: StateFlow<List<FavoriteRow>> = combine(prefs, extras) { p, e ->
        p.favorites.map { f -> e[f.riotId.lowercase()]?.copy(favorite = f) ?: FavoriteRow(f) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _profile = MutableStateFlow<ProfileState?>(null)
    val profile: StateFlow<ProfileState?> = _profile

    private val _connection = MutableStateFlow(Connection())
    val connection: StateFlow<Connection> = _connection

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var profileJob: Job? = null
    private var suggestJob: Job? = null

    private val _suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    val suggestions: StateFlow<List<Suggestion>> = _suggestions

    /** Ввод в строке поиска: через 250 мс тишины спрашиваем сервер; меньше двух символов — подсказок нет. */
    fun onQueryChange(text: String) {
        suggestJob?.cancel()
        val q = text.trim()
        if (q.length < 2 || q.contains('#')) {
            _suggestions.value = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            // Сбой подсказок не мешает поиску: просто без списка.
            _suggestions.value = runCatching { source().suggest(q) }.getOrDefault(emptyList())
        }
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
    }

    init {
        viewModelScope.launch { names.load() }
    }

    private fun source(): LolSource = if (prefs.value.demo) demo else server

    private fun messageOf(e: Throwable): String = (e as? GatewayException)?.userMessage() ?: "Ошибка: ${e.message ?: e::class.simpleName}"

    // --- Подключение ---
    fun saveServer(url: String) = viewModelScope.launch { settings.setServer(url) }
    fun saveMe(riotId: String) = viewModelScope.launch { settings.setMe(riotId) }
    fun setDemo(on: Boolean) = viewModelScope.launch { settings.setDemo(on) }

    fun checkConnection() {
        viewModelScope.launch {
            _connection.value = Connection(checking = true)
            _connection.value = try {
                if (prefs.value.demo) Connection(message = "Включён демо-режим: сервер не используется.", ok = true)
                else if (gateway.health()) Connection(message = "Сервер Sintence отвечает.", ok = true)
                else Connection(message = "Сервер ответил ошибкой.", ok = false)
            } catch (e: Exception) {
                Connection(message = messageOf(e), ok = false)
            }
        }
    }

    // --- Профиль ---
    fun openProfile(raw: String) {
        val id = RiotId.parse(raw)
        if (id == null) {
            _message.value = "Введите Riot ID в виде Имя#TAG."
            return
        }
        profileJob?.cancel()
        clearSuggestions()
        _profile.value = ProfileState.Loading(id.toString())
        profileJob = viewModelScope.launch {
            try {
                val src = source()
                val account = src.account(id)
                val profile = src.profile(account, MATCHES)
                _profile.value = ProfileState.Ready(profile, LiveState.Unknown)
                refreshLive()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _profile.value = ProfileState.Failed(id.toString(), messageOf(e))
            }
        }
    }

    fun closeProfile() {
        profileJob?.cancel()
        _profile.value = null
    }

    fun refreshLive() {
        val state = _profile.value as? ProfileState.Ready ?: return
        _profile.value = state.copy(live = LiveState.Loading)
        viewModelScope.launch {
            val live = try {
                source().liveGame(state.profile.account.puuid)?.let { LiveState.InGame(it) } ?: LiveState.NotInGame
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LiveState.Failed(messageOf(e))
            }
            _profile.update { (it as? ProfileState.Ready)?.copy(live = live) ?: it }
        }
    }

    // --- Избранное ---
    fun isFavorite(riotId: String) = prefs.value.favorites.any { it.riotId.equals(riotId, true) }

    fun toggleFavorite(profile: Profile) {
        val id = profile.account.id.toString()
        viewModelScope.launch {
            if (isFavorite(id)) settings.removeFavorite(id) else settings.addFavorite(Favorite(id, profile.account.puuid))
        }
    }

    fun removeFavorite(riotId: String) = viewModelScope.launch { settings.removeFavorite(riotId) }

    /** Обновить ранги и «в игре» у всех избранных: 2 запроса на игрока. */
    fun refreshFavorites() {
        val list = prefs.value.favorites
        viewModelScope.launch {
            coroutineScope {
                list.map { f ->
                    async {
                        val key = f.riotId.lowercase()
                        extras.update { it + (key to FavoriteRow(f, loading = true)) }
                        val src = source()
                        val row = try {
                            val puuid = f.puuid.ifEmpty {
                                RiotId.parse(f.riotId)?.let { src.account(it).puuid }.orEmpty()
                            }
                            val rank = src.ranks(puuid).solo()
                            val live = src.liveGame(puuid)?.let { LiveState.InGame(it) } ?: LiveState.NotInGame
                            FavoriteRow(f, rank, live)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            FavoriteRow(f, live = LiveState.Failed(messageOf(e)))
                        }
                        extras.update { it + (key to row) }
                    }
                }.forEach { it.await() }
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        const val MATCHES = 10
    }
}
