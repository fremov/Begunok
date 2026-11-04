package ru.begunok.ui.lol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.begunok.lol.LiveGame
import ru.begunok.lol.MatchPlayer
import ru.begunok.lol.MatchSummary
import ru.begunok.lol.Names
import ru.begunok.lol.Profile
import ru.begunok.lol.RankEntry
import ru.begunok.lol.label
import ru.begunok.lol.queueName
import ru.begunok.lol.rankQueueName
import ru.begunok.lol.roleName
import ru.begunok.lol.tierName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LolScreen(viewModel: LolViewModel) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    // Читаем «справочники загружены», чтобы экран перерисовался, когда появятся названия и картинки.
    val ready by viewModel.names.loaded.collectAsStateWithLifecycle()
    BackHandler(enabled = profile != null) { viewModel.closeProfile() }
    val state = profile
    LolTheme {
        Box(Modifier.fillMaxSize().background(Lol.Panel)) {
            if (state == null) HomePane(viewModel, ready) else ProfilePane(viewModel, state, ready)
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Lol.Gold, unfocusedBorderColor = Lol.Edge,
    focusedLabelColor = Lol.Gold, unfocusedLabelColor = Lol.Muted, cursorColor = Lol.Gold,
    focusedTextColor = Lol.Text, unfocusedTextColor = Lol.Text,
)

@Composable
private fun HomePane(vm: LolViewModel, @Suppress("UNUSED_PARAMETER") ready: Boolean) {
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    Page {
        LolCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Поиск игрока")
                OutlinedTextField(
                    query, { query = it; vm.onQueryChange(it) },
                    label = { Text("Начните вводить ник или Имя#TAG") },
                    singleLine = true, shape = RoundedCornerShape(10.dp), colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                suggestions.forEachIndexed { i, hint ->
                    if (i > 0) HorizontalDivider(color = Lol.Edge)
                    Row(
                        Modifier.fillMaxWidth().clickable { query = hint.riotId; vm.openProfile(hint.riotId) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(hint.riotId, Modifier.weight(1f), color = Lol.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${hint.games} игр", color = Lol.Muted, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.openProfile(query) }, enabled = query.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Lol.Gold, contentColor = Lol.Panel),
                        shape = RoundedCornerShape(10.dp),
                    ) { Text("Найти", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { vm.openProfile(prefs.myRiotId) }, enabled = prefs.myRiotId.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Lol.Gold),
                    ) { Text("Мой профиль") }
                }
                message?.let { Text(it, color = Lol.Danger) }
            }
        }

        LolCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Избранные", Modifier.weight(1f))
                    IconButton(onClick = { vm.refreshFavorites() }, enabled = favorites.isNotEmpty()) {
                        androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = Lol.Gold)
                    }
                }
                if (favorites.isEmpty()) {
                    Text("Пусто. Откройте профиль и нажмите звёздочку.", color = Lol.Muted)
                }
                favorites.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(color = Lol.Edge)
                    FavoriteItem(vm, row)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Демо-данные", color = Lol.Text, fontSize = 14.sp)
                Text("Выдуманные игроки — пока сервер недоступен", color = Lol.Muted, fontSize = 12.sp)
            }
            Switch(
                prefs.demo, { vm.setDemo(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Lol.Gold, checkedThumbColor = Lol.Panel),
            )
        }
    }
}

@Composable
private fun FavoriteItem(vm: LolViewModel, row: FavoriteRow) {
    val live = row.live
    Row(
        Modifier.fillMaxWidth().clickable { vm.openProfile(row.favorite.riotId) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Names.rankEmblem(row.rank?.tier), 44.dp, shape = CircleShape, border = Lol.Edge)
        Column(Modifier.weight(1f)) {
            Text(row.favorite.riotId, color = Lol.Text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (live) {
                    is LiveState.InGame -> "${queueName(live.game.queueId)} · ${minutes(live.game.lengthSec)}"
                    is LiveState.NotInGame -> row.rank?.label() ?: "Без ранга"
                    is LiveState.Failed -> live.message
                    else -> "Нажмите «обновить»"
                },
                color = if (live is LiveState.Failed) Lol.Danger else Lol.Muted, fontSize = 12.sp,
            )
        }
        if (row.loading) CircularProgressIndicator(Modifier.size(20.dp), color = Lol.Gold, strokeWidth = 2.dp)
        else if (live is LiveState.InGame) Chip("В ИГРЕ", Lol.Ok, filled = true)
        IconButton(onClick = { vm.removeFavorite(row.favorite.riotId) }) {
            androidx.compose.material3.Icon(Icons.Filled.Star, contentDescription = "Убрать из избранного", tint = Lol.Gold)
        }
    }
}

private fun minutes(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun ProfilePane(vm: LolViewModel, state: ProfileState, @Suppress("UNUSED_PARAMETER") ready: Boolean) {
    Page {
        TextButton(onClick = { vm.closeProfile() }, colors = ButtonDefaults.textButtonColors(contentColor = Lol.Gold)) {
            androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  К списку")
        }
        when (state) {
            is ProfileState.Loading -> LolCard {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(color = Lol.Gold, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Column {
                        Text(state.riotId, color = Lol.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("Загружаю профиль…", color = Lol.Muted)
                    }
                }
            }
            is ProfileState.Failed -> LolCard(accent = Lol.Danger) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.riotId, color = Lol.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(state.message, color = Lol.Danger)
                    Button(
                        onClick = { vm.openProfile(state.riotId) },
                        colors = ButtonDefaults.buttonColors(containerColor = Lol.Gold, contentColor = Lol.Panel),
                    ) { Text("Повторить") }
                }
            }
            is ProfileState.Ready -> ProfileBody(vm, state)
        }
    }
}

@Composable
private fun ProfileBody(vm: LolViewModel, state: ProfileState.Ready) {
    val profile = state.profile
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val id = profile.account.id.toString()
    val favorite = prefs.favorites.any { it.riotId.equals(id, true) }
    LaunchedEffect(Unit) { vm.names.load() }

    LolCard {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundIcon(vm.names.profileIcon(profile.iconId), 72.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Ник и тег отдельно: длинный ник не рвёт тег посреди.
                Text(profile.account.id.name, color = Lol.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("#${profile.account.id.tag}", color = Lol.Muted, fontSize = 14.sp)
                profile.level?.let { Chip("Уровень $it") }
            }
            IconButton(onClick = { vm.toggleFavorite(profile) }) {
                androidx.compose.material3.Icon(
                    if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Избранное", tint = Lol.Gold, modifier = Modifier.size(28.dp),
                )
            }
        }
    }
    if (profile.missing.isNotEmpty()) {
        Text("Не удалось получить: ${profile.missing.joinToString()}.", color = Lol.Danger, fontSize = 13.sp)
    }

    LiveCard(vm, state.live)
    RanksCard(profile.ranks)
    MasteryCard(profile, vm.names)
    MatchesCard(profile, vm.names)
}

@Composable
private fun LiveCard(vm: LolViewModel, live: LiveState) {
    LolCard(accent = if (live is LiveState.InGame) Lol.Ok else null) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Сейчас", Modifier.weight(1f))
                IconButton(onClick = { vm.refreshLive() }) {
                    androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Обновить", tint = Lol.Gold)
                }
            }
            when (live) {
                LiveState.Unknown, LiveState.Loading ->
                    CircularProgressIndicator(Modifier.size(24.dp), color = Lol.Gold, strokeWidth = 2.dp)
                LiveState.NotInGame -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Lol.Muted))
                    Text("Не в игре", color = Lol.Muted)
                }
                is LiveState.Failed -> Text(live.message, color = Lol.Danger)
                is LiveState.InGame -> LiveGameBody(live.game, vm.names)
            }
        }
    }
}

@Composable
private fun LiveGameBody(game: LiveGame, names: Names) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("В ИГРЕ", Lol.Ok, filled = true)
        Text("${queueName(game.queueId)} · ${minutes(game.lengthSec)}", color = Lol.Text, fontWeight = FontWeight.Medium)
    }
    listOf(100, 200).forEach { team ->
        TeamHeader(team)
        game.players.filter { it.teamId == team }.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(names.championImage(p.championId), 36.dp, border = teamColor(team))
                Column(Modifier.weight(1f)) {
                    Text(names.champion(p.championId), color = Lol.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(p.riotId.ifEmpty { "скрыт" }, color = Lol.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Names.rankEmblem(p.rank?.tier), 24.dp, shape = CircleShape)
                        Text(p.rank?.let { tierName(it.tier) + if (it.tier.uppercase() in APEX) "" else " ${it.division}" } ?: "Без ранга",
                            color = Lol.Text, fontSize = 12.sp)
                    }
                    p.rank?.let { Text("${it.lp} LP · ${it.winRate}%", color = Lol.Muted, fontSize = 11.sp) }
                }
            }
        }
        val bans = game.bans.filter { it.second == team }
        if (bans.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Баны", color = Lol.Muted, fontSize = 12.sp)
                bans.forEach { Icon(names.championImage(it.first), 26.dp, shape = RoundedCornerShape(6.dp)) }
            }
        }
    }
}

private val APEX = setOf("MASTER", "GRANDMASTER", "CHALLENGER")

@Composable
private fun RanksCard(ranks: List<RankEntry>) {
    LolCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Ранг")
            if (ranks.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Names.rankEmblem(null), 56.dp, shape = CircleShape)
                    Text("Без ранга в этом сезоне", color = Lol.Muted)
                }
            }
            ranks.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(Names.rankEmblem(r.tier), 72.dp, shape = CircleShape)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(rankQueueName(r.queue), color = Lol.Muted, fontSize = 12.sp)
                        Text(r.label(), color = Lol.Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        WinLossBar(r.wins, r.losses)
                        Row {
                            Text("${r.wins} П · ${r.losses} Пр", color = Lol.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text("${r.winRate}%", color = if (r.winRate >= 50) Lol.Ok else Lol.Danger, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MasteryCard(profile: Profile, names: Names) {
    if (profile.mastery.isEmpty()) return
    LolCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Лучшие чемпионы")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                profile.mastery.take(5).forEach { m ->
                    Column(Modifier.width(58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(names.championImage(m.championId), 54.dp, border = Lol.Gold)
                        Text("М${m.level}", color = Lol.Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(points(m.points), color = Lol.Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun points(p: Long) = if (p >= 1_000_000) "%.1fM".format(Locale.US, p / 1_000_000.0) else if (p >= 1000) "${p / 1000}k" else "$p"

@Composable
private fun MatchesCard(profile: Profile, names: Names) {
    LolCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val wins = profile.matches.count { it.me?.win == true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Последние игры", Modifier.weight(1f))
                if (profile.matches.isNotEmpty()) Text("$wins из ${profile.matches.size}", color = Lol.Muted, fontSize = 12.sp)
            }
            if (profile.matches.isNotEmpty()) {
                // Полоса формы: по точке на игру, старые слева.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    profile.matches.reversed().forEach {
                        Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(if (it.me?.win == true) Lol.Ok else Lol.Chaos))
                    }
                }
            } else {
                Text("Игр не найдено.", color = Lol.Muted)
            }
            profile.matches.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = Lol.Edge)
                MatchRow(m, names)
            }
        }
    }
}

@Composable
private fun MatchRow(match: MatchSummary, names: Names) {
    var open by rememberSaveable(match.id) { mutableStateOf(false) }
    val me = match.me ?: return
    val tone = if (me.win) Lol.Ok else Lol.Chaos
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(tone))
            Icon(names.championImage(me.championId), 48.dp, border = tone)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (me.win) "Победа" else "Поражение", color = tone, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("${me.kills} / ${me.deaths} / ${me.assists}", color = Lol.Text, fontWeight = FontWeight.SemiBold)
                }
                Text("${queueName(match.queueId)} · ${minutes(match.durationSec)}", color = Lol.Muted, fontSize = 12.sp)
            }
            Text(date(match.endedAtMs), color = Lol.Muted, fontSize = 11.sp)
        }
        if (me.items.isNotEmpty()) {
            Row(Modifier.padding(start = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                me.items.forEach { Icon(names.itemImage(it), 26.dp, shape = RoundedCornerShape(5.dp)) }
            }
        }
        if (open) {
            Text(statsLine(me), color = Lol.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp))
            listOf(100, 200).forEach { team ->
                TeamHeader(team)
                match.players.filter { it.teamId == team }.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(names.championImage(p.championId), 30.dp, border = teamColor(team).copy(alpha = if (p.puuid == me.puuid) 1f else 0.35f))
                        Text(p.riotId.ifEmpty { names.champion(p.championId) },
                            Modifier.weight(1f), color = if (p.puuid == me.puuid) Lol.Gold else Lol.Text,
                            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.kills}/${p.deaths}/${p.assists}", color = Lol.Text, fontSize = 13.sp)
                        Text("${p.damage / 1000}k", color = Lol.Muted, fontSize = 12.sp, modifier = Modifier.width(36.dp))
                    }
                }
            }
        }
    }
}

private fun statsLine(p: MatchPlayer): String {
    val role = roleName(p.position).let { if (it.isEmpty()) "" else "$it · " }
    return role + "ур. ${p.level} · ${p.cs} CS · ${p.gold} золота · ${p.damage} урона · обзор ${p.vision}"
}

private fun date(ms: Long): String = SimpleDateFormat("d MMM", Locale("ru")).format(Date(ms))

