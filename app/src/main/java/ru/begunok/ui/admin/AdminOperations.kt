package ru.begunok.ui.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import java.util.Calendar
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private fun JSONObject.text(key: String): String = if (isNull(key)) "—" else optString(key, "—")
private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
private fun JSONObject.items(key: String): List<JSONObject> = optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
private fun JSONObject.entries(): List<Pair<String, String>> = keys().asSequence().map { it to text(it) }.toList()
private fun date(value: Long): String = if (value <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value * 1000))
private val privacyNames = mapOf("players_taken" to "Очередь: обойдённые", "players_pending" to "Очередь: ожидающие", "riot_cache" to "Кеш Riot", "profiles" to "Профили", "known_players" to "Подсказки ника", "ladder" to "Топ игроков", "app_users_riot_id" to "Riot ID пользователя", "app_users" to "Пользователи приложения", "api_requests" to "Статистика шлюза", "crawl_log" to "Журнал", "backups" to "Резервные копии", "participants" to "Участники матчей", "players" to "Очередь обхода")

@Composable
fun AdminOperations(request: suspend (String, String, JSONObject) -> JSONObject, confirm: (String, suspend () -> Unit) -> Unit) {
    var section by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var pg by remember { mutableStateOf<JSONObject?>(null) }
    var privacy by remember { mutableStateOf<JSONObject?>(null) }
    var users by remember { mutableStateOf<JSONObject?>(null) }
    var gateway by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        try {
            when (section) {
                0 -> { status = request("GET", "/api/status", JSONObject()); stats = request("GET", "/api/stats", JSONObject()) }
                1 -> pg = request("GET", "/api/postgres", JSONObject())
                2 -> privacy = request("GET", "/api/privacy", JSONObject())
                3 -> { users = request("GET", "/api/app-users", JSONObject()); gateway = request("GET", "/api/gateway", JSONObject()) }
            }
            error = ""
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка загрузки" }
    }
    fun action(title: String, path: String, body: JSONObject = JSONObject(), onResult: (JSONObject) -> Unit = {}) {
        confirm(title) {
            busy = true
            try {
                val result = request("POST", path, body)
                onResult(result)
                message = when (path) {
                    "/api/privacy/lookup" -> ""
                    "/api/privacy/erase" -> "Удалено игроков: ${result.items("erased").size}" + result.items("erased").joinToString(prefix = " ", separator = ", ") { "#${it.text("id")}" }
                    "/api/backup" -> "Копия запрошена — служба начнёт в течение 20 секунд"
                    "/api/restore" -> "Восстановление запрошено — ход в журнале"
                    else -> "Действие выполнено"
                }
                refresh()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка запроса" } finally { busy = false }
        }
    }
    LaunchedEffect(section) {
        while (true) { refresh(); delay(if (section == 0) 2000 else if (section == 1) 30000 else 10000) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScrollableTabRow(selectedTabIndex = section) {
            listOf("Обслуживание", "PostgreSQL и копии", "Приватность", "Приложение").forEachIndexed { i, title ->
                Tab(selected = section == i, onClick = { section = i; error = ""; message = "" }, text = { Text(title) })
            }
        }
        OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !busy) { Text("Обновить") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
        when (section) {
            0 -> if (status == null || stats == null) Text("Загрузка…") else Maintenance(status!!, stats!!, busy, ::action)
            1 -> if (pg == null) Text("Загрузка…") else Database(pg!!, busy, ::action)
            2 -> if (privacy == null) Text("Загрузка…") else Privacy(privacy!!, busy, ::action)
            3 -> if (users == null || gateway == null) Text("Загрузка…") else Application(users!!, gateway!!, busy, ::action)
        }
    }
}

private typealias Operation = (String, String, JSONObject, (JSONObject) -> Unit) -> Unit
private fun payload(vararg pairs: Pair<String, Any>): JSONObject = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
@Composable private fun Heading(value: String) { Text(value, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
@Composable private fun Line(label: String, value: Any?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${value ?: "—"}", style = MaterialTheme.typography.bodyLarge)
    }
}
@Composable private fun Metrics(vararg values: Pair<String, Any?>) {
    values.toList().chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { (label, value) ->
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text("${value ?: "—"}", style = MaterialTheme.typography.titleMedium)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
@Composable private fun Details(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
            Text("$title ${if (expanded) "▲" else "▼"}")
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun Trigger(label: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()) { Text(label) }
}
@Composable private fun Input(value: String, change: (String) -> Unit, label: String) { OutlinedTextField(value, change, label = { Text(label) }, modifier = Modifier.fillMaxWidth()) }

private data class GraphSeries(val name: String, val color: Color, val values: List<Float>)
private val graphColors = listOf(Color(0xFF4B9DFF), Color(0xFFC8AA6E), Color(0xFF4AD66D))

@Composable
private fun Graph(times: List<Long>, series: List<GraphSeries>, bars: Boolean = false, details: (Int) -> String = { "" }) {
    var selected by remember { mutableIntStateOf(-1) }
    if (times.isEmpty()) { Text("Нет данных для графика"); return }
    val max = (if (bars) times.indices.maxOfOrNull { i -> series.sumOf { it.values.getOrElse(i) { 0f }.toDouble() }.toFloat() } else series.flatMap { it.values }.maxOrNull())?.coerceAtLeast(1f) ?: 1f
    fun select(x: Float, width: Int) {
        if (width <= 0) return
        val fraction = (x / width).coerceIn(0f, 1f)
        selected = if (bars) (fraction * times.size).toInt().coerceIn(times.indices) else {
            val target = times.first() + ((times.last() - times.first()) * fraction).toLong()
            times.indices.minByOrNull { abs(times[it] - target) } ?: 0
        }
    }
    Canvas(Modifier.fillMaxWidth().height(150.dp)
        .pointerInput(times, bars) { detectTapGestures { select(it.x, size.width) } }
        .pointerInput(times, bars) { detectDragGestures(onDragStart = { select(it.x, size.width) }) { change, _ -> select(change.position.x, size.width); change.consume() } }) {
        val last = (times.size - 1).coerceAtLeast(1)
        val start = times.first()
        val span = (times.last() - start).coerceAtLeast(1)
        series.forEach { s ->
            s.values.forEachIndexed { i, value ->
                if (i < times.size) {
                    val x = if (bars) (i + 0.5f) * size.width / times.size else (times[i] - start).toFloat() / span * size.width
                    val y = size.height - value.coerceAtLeast(0f) / max * size.height
                    if (bars) {
                        val previous = series.takeWhile { it !== s }.sumOf { it.values.getOrElse(i) { 0f }.toDouble() }.toFloat()
                        val height = value.coerceAtLeast(0f) / max * size.height
                        drawRect(s.color, Offset(x - size.width / times.size * .4f, size.height - previous / max * size.height - height), Size(size.width / times.size * .8f, height))
                    } else if (i > 0) {
                        val px = (times[i - 1] - start).toFloat() / span * size.width
                        val py = size.height - s.values[i - 1].coerceAtLeast(0f) / max * size.height
                        drawLine(s.color, Offset(px, py), Offset(x, y), strokeWidth = 2.dp.toPx())
                    } else if (last == 1) drawCircle(s.color, 3.dp.toPx(), Offset(x, y))
                }
            }
        }
        if (selected in times.indices) {
            val x = if (bars) (selected + 0.5f) * size.width / times.size else (times[selected] - start).toFloat() / span * size.width
            drawLine(Color.Gray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
    Column { series.forEach { Text("● ${it.name}", color = it.color, style = MaterialTheme.typography.labelMedium) } }
    Text("${date(times.first())} — ${date(times.last())} · максимум $max")
    if (selected in times.indices) {
        val values = series.joinToString(" · ") { "${it.name}: ${it.values.getOrElse(selected) { 0f }}" }
        Text("${date(times[selected])} · $values${details(selected)}")
    } else Text("Нажмите на график или проведите по нему для просмотра значений")
}

@Composable
private fun Maintenance(status: JSONObject, stats: JSONObject, busy: Boolean, action: Operation) {
    val crawler = status.obj("crawler"); val packs = status.obj("packs"); val index = packs.obj("index")
    val jobs = status.items("jobs"); val running = jobs.any { it.optBoolean("running") }
    var seedPages by remember { mutableStateOf("1") }; var seedPlatform by remember { mutableStateOf("ru") }
    var resetPages by remember { mutableStateOf("1") }; var resetPlatform by remember { mutableStateOf("ru") }
    val patch = stats.items("patches").firstOrNull()?.optString("patch").orEmpty()
    Heading("Пак и обслуживание")
    Text("Пак собирается каждые 6 часов и при достижении порога нового патча. Сборка по запросу начнётся в течение минуты.")
    Line("Последняя сборка", date(crawler.optLong("packBuiltAt")))
    crawler.optJSONObject("packProgress")?.let {
        Line(if (it.optString("stage") == "meta") "Мета" else "Сборка пака", "${it.text("done")} из ${it.text("total")} · начало ${date(it.optLong("startedAt"))}")
        val total = it.optDouble("total", 0.0)
        if (total > 0) LinearProgressIndicator(progress = { (it.optDouble("done", 0.0) / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Trigger(if (crawler.optBoolean("packRequested")) "Сборка запрошена" else "Собрать пак сейчас", !busy && !crawler.optBoolean("packRequested") && crawler.isNull("packProgress")) { action("Запросить сборку пака?", "/api/pack", JSONObject()) {} }
    Line("Текущий пак", "${index.text("file")} · патч ${index.text("patch")} · ${date(index.optLong("generatedAt"))}")
    Details("Файлы пака (${packs.items("files").size})") {
        packs.items("files").forEach { f ->
            Text(f.text("name"), style = MaterialTheme.typography.titleSmall)
            Metrics("Сочетаний" to f.text("buckets"), "Чемпион и роль" to f.text("baseBuckets"), "Порог игр" to f.text("minGames"), "Размер, байт" to f.text("sizeBytes"), "Матчапы, байт" to f.text("matchupBytes"), "Файлов матчапов" to f.text("matchupChampions"))
            Line("Изменён", date(f.optLong("modifiedAt")))
        }
    }
    status.optJSONObject("ladder")?.let { l ->
        Heading("База игроков")
        Metrics("Заполнено" to l.text("filled"), "Просканировано" to l.text("scanned"), "Ждут обновления" to l.text("stale"))
        Line("Сканирование", date(l.optLong("scanAt")))
        if (l.optInt("scanned") > 0) LinearProgressIndicator(progress = { (l.optDouble("filled", 0.0) / l.optInt("scanned")).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        l.optJSONObject("progress")?.let { Text("${it.text("stage")}: ${it.text("done")} из ${it.text("total")}") }
        Text(if (l.optBoolean("matchPause")) "Пауза сбора действует" else "Пауза сбора не действует")
        Trigger(if (l.optBoolean("priority")) "Выключить приоритет базы игроков" else "Приостановить сбор при заполнении базы", !busy) { action("Изменить приоритет базы игроков?", "/api/ladder/priority", payload("on" to !l.optBoolean("priority"))) {} }
    }
    val releases = status.obj("releases"); val latest = releases.optJSONObject("latest")
    Heading("Релиз приложения")
    if (latest == null) Line("Релиз", "Нет; файлов: ${releases.items("files").size}") else {
        Metrics("Версия" to latest.text("version"), "Релизов" to releases.items("files").size, "Размер, байт" to latest.text("bytes"))
        Line("Опубликован", date(latest.optLong("publishedAt")))
        if (latest.optString("notes").isNotEmpty()) Details("Примечания к релизу") { Text(latest.optString("notes")) }
    }
    Heading("Посев игроков")
    Text("Пополняет очередь с ладдера; матчей не скачивает.")
    Metrics("В очереди" to stats.text("queue"))
    Details("Настроить посев") {
        Input(seedPages, { seedPages = it }, "Страниц на дивизион (1–10)"); Input(seedPlatform, { seedPlatform = it.take(5) }, "Платформа")
        Trigger("Посев игроков", !busy && !running && (seedPages.toIntOrNull() ?: 0) in 1..10 && seedPlatform.isNotBlank()) { action("Запустить посев игроков?", "/api/run/seed", payload("pages" to seedPages.toInt(), "platform" to seedPlatform)) {} }
    }
    Heading("Опасные действия")
    Text("Сброс удаляет игроков из очереди; матчи остаются.", color = MaterialTheme.colorScheme.error)
    Details("Сбросить очередь и засеять заново") {
        Input(resetPages, { resetPages = it }, "Страниц на дивизион (1–10)"); Input(resetPlatform, { resetPlatform = it.take(5) }, "Платформа")
        Trigger("Сбросить очередь и засеять", !busy && !running && (resetPages.toIntOrNull() ?: 0) in 1..10 && resetPlatform.isNotBlank(), danger = true) { action("Очистить очередь (${stats.text("queue")}) и засеять заново?", "/api/run/reset", payload("pages" to resetPages.toInt(), "platform" to resetPlatform)) {} }
    }
    Details("Удаление старых патчей") {
        Text("Удаление матчей других патчей необратимо.", color = MaterialTheme.colorScheme.error)
        Trigger("Оставить только патч $patch", !busy && !running && patch.isNotEmpty() && stats.items("patches").size >= 2, danger = true) { action("Удалить все матчи, кроме патча $patch? Это необратимо.", "/api/run/prune", payload("patch" to patch)) {} }
    }
    Heading("Задачи")
    jobs.forEach { j ->
        Text("${j.text("title")} · ${if (j.optBoolean("running")) "выполняется" else "код ${j.text("exitCode")}"}", style = MaterialTheme.typography.titleSmall)
        Metrics("Длительность, с" to j.text("elapsedS"), "Начало" to date(j.optLong("startedAt")))
        if (j.optBoolean("running")) Trigger(if (j.optBoolean("stopping")) "Завершить принудительно" else "Остановить задачу", !busy, danger = true) { action(if (j.optBoolean("stopping")) "Принудительно завершить задачу?" else "Остановить задачу?", "/api/jobs/${j.optInt("id")}/stop", JSONObject()) {} }
    }
}

@Composable
private fun Database(pg: JSONObject, busy: Boolean, action: Operation) {
    val backups = pg.obj("backups"); val forecast = pg.obj("forecast")
    var restoreFile by remember { mutableStateOf("") }
    var restoreConfirm by remember { mutableStateOf("") }
    val backupBusy = backups.optBoolean("requested") || (!backups.isNull("state") && backups.optString("state").isNotEmpty()) || (!backups.isNull("restoreRequested") && backups.optString("restoreRequested").isNotEmpty())
    Heading("PostgreSQL")
    Metrics("Версия" to pg.text("version"), "Размер, байт" to pg.text("sizeBytes"), "Чтений из памяти" to "${pg.optDouble("cacheHit") * 100}%", "Транзакции" to pg.text("commits"), "Откаты" to pg.text("rollbacks"), "Взаимоблокировки" to pg.text("deadlocks"), "Временные файлы, байт" to pg.text("tempBytes"))
    Line("Запущена", date(pg.optLong("startedAt")))
    Details("Соединения и настройки") {
        pg.obj("connections").entries().forEach { Line("Соединения ${it.first}", it.second) }
        pg.obj("settings").entries().forEach { Line(it.first, it.second) }
    }
    Heading("Прогноз места")
    val days = if (forecast.isNull("daysTo20")) "база не растёт" else "${forecast.text("daysTo20")} дней"
    Metrics("До 20% свободного места" to days, "Рост, байт/сутки" to forecast.text("growthPerDay"), "Занято на диске, байт" to forecast.text("diskUsed"), "Всего на диске, байт" to forecast.text("diskTotal"), "База, байт" to forecast.text("dbBytes"), "На матч, байт" to forecast.text("perMatch"), "Копии, байт" to forecast.text("backupBytes"))
    Line("Метод прогноза", forecast.text("method"))
    val history = forecast.items("history")
    if (history.size > 1) {
        Text("Размер базы по часам, байт")
        Graph(history.map { it.optLong("t") }, listOf(GraphSeries("база", graphColors[0], history.map { it.optDouble("bytes", 0.0).toFloat() })))
    } else Text("История размера копится раз в час.")
    Heading("Резервные копии")
    Text("Копии создаются ежедневно в 04:00 МСК, на сервере хранятся 7 последних.")
    Line("Состояние", backups.text("state"))
    Line("Последняя копия", date(backups.optLong("lastAt")))
    Line("Запрошенное восстановление", backups.text("restoreRequested"))
    Line("Последнее восстановление", date(backups.optLong("restoredAt")))
    Trigger("Сделать копию сейчас", !busy && !backupBusy) { action("Создать резервную копию?", "/api/backup", JSONObject()) {} }
    if (backups.items("files").isEmpty()) Text(if (backups.optBoolean("available")) "Копий пока нет" else "Каталог копий не подключён")
    Details("Список копий (${backups.items("files").size})") {
        backups.items("files").forEach { f ->
            val name = f.optString("name")
            key(name) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Metrics("Размер, байт" to f.text("bytes"), "Создана" to date(f.optLong("at")))
                if (restoreFile == name) {
                    Text("Все данные после копии пропадут.", color = MaterialTheme.colorScheme.error)
                    Input(restoreConfirm, { restoreConfirm = it }, "Введите «восстановить»")
                    Trigger("Подтвердить восстановление $name", !busy && !backupBusy && restoreConfirm.trim().equals("восстановить", ignoreCase = true), danger = true) {
                        action("Восстановить $name? Все данные после копии пропадут. Перед восстановлением будет создана копия текущей базы, сбор остановится.", "/api/restore", payload("file" to name)) { restoreFile = ""; restoreConfirm = "" }
                    }
                    TextButton(onClick = { restoreFile = ""; restoreConfirm = "" }) { Text("Отмена") }
                } else {
                    OutlinedButton(onClick = { restoreFile = name; restoreConfirm = "" }, enabled = !busy && !backupBusy, modifier = Modifier.fillMaxWidth()) { Text("Восстановить $name") }
                }
                HorizontalDivider()
            }
        }
    }
    Heading("Таблицы и секции")
    Text("Число строк — оценка PostgreSQL; мёртвые строки ещё не убраны VACUUM.")
    Details("Таблицы (${pg.items("tables").size})") { pg.items("tables").forEach { table -> key(table.optString("name")) { TableDetails(table) } } }
    Details("Кеш ответов Riot (${pg.items("cache").size})") {
        pg.items("cache").forEach { Line(it.text("kind"), "${it.text("rows")} записей · ${it.text("bytes")} байт") }
    }
}
@Composable private fun TableDetails(t: JSONObject) {
    Details("${t.text("name")} · ${t.text("rows")} строк") {
        if (t.optString("about").isNotEmpty()) Text(t.optString("about"))
        Metrics("Строк" to t.text("rows"), "Мёртвых строк" to t.text("dead"), "Данные, байт" to t.text("dataBytes"), "Индексы, байт" to t.text("indexBytes"), "Всего, байт" to t.text("totalBytes"))
        Line("VACUUM", date(t.optLong("vacuumedAt")))
        if (t.items("partitions").isNotEmpty()) {
            Details("Секции (${t.items("partitions").size})") { t.items("partitions").forEach { key(it.optString("name")) { TableDetails(it) } } }
        }
    }
}

@Composable
private fun Privacy(info: JSONObject, busy: Boolean, action: Operation) {
    var query by remember { mutableStateOf("") }; var searched by remember { mutableStateOf("") }; var preview by remember { mutableStateOf<JSONObject?>(null) }
    var source by remember { mutableStateOf("request") }; var note by remember { mutableStateOf("") }; var typed by remember { mutableStateOf("") }
    var batch by remember { mutableStateOf("") }; var batchSource by remember { mutableStateOf("riot") }; var batchNote by remember { mutableStateOf("") }; var batchTyped by remember { mutableStateOf("") }
    Heading("Сроки хранения")
    Text("Чистка раз в час. Матчи — текущий патч и патч пака; пак не содержит идентификаторов.")
    val pending = info.obj("pending")
    Details("Сроки и ожидающая очистка") {
        info.obj("retention").entries().forEach { (key, value) -> Line(privacyNames[key] ?: key, "$value дней · к удалению ${pending.text(key)}") }
    }
    Heading("Удалить игрока")
    Text("Заявка по электронной почте, срок — 30 дней. Укажите Riot ID или puuid.")
    Input(query, { query = it; preview = null }, "Имя#TAG или puuid")
    Trigger("Найти", !busy && query.isNotBlank()) {
        val value = query.trim(); val body = payload((if ('#' in value) "riotId" else "puuid") to value)
        action("Найти игрока?", "/api/privacy/lookup", body) { preview = it; searched = value; typed = "" }
    }
    preview?.let { p ->
        if (!p.optBoolean("found")) Text("Игрок не найден") else {
            Text("Игрок #${p.text("id")} · ${p.text("riotId")} ${if (p.optBoolean("alreadyErased")) "· уже удалён" else ""}")
            Details("Данные игрока") { p.obj("rows").entries().forEach { Line(privacyNames[it.first] ?: it.first, it.second) } }
            Text("Безвозвратное удаление", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
            SourceSelector(source) { source = it }; Input(note, { note = it }, "Номер обращения без ников")
            Input(typed, { typed = it }, "Повторите идентификатор")
            Trigger("Удалить игрока", !busy && typed.trim() == searched && searched.isNotEmpty(), danger = true) {
                val body = payload((if ('#' in searched) "riotId" else "puuid") to searched, "source" to source, "note" to note, "confirm" to typed)
                action("Безвозвратно удалить данные игрока?", "/api/privacy/erase", body) { preview = null; query = ""; typed = ""; note = "" }
            }
        }
    }
    Heading("Массовое удаление")
    Details("Удалить по списку puuid") {
        Text("Удаление данных игроков необратимо.", color = MaterialTheme.colorScheme.error)
        Input(batch, { batch = it }, "По одному puuid на строку")
        val ids = batch.lines().map { it.trim() }.filter { it.isNotEmpty() }; val phrase = "ERASE ${ids.size}"
        SourceSelector(batchSource) { batchSource = it }; Input(batchNote, { batchNote = it }, "Заметка")
        Input(batchTyped, { batchTyped = it }, "Введите $phrase")
        Trigger("Удалить ${ids.size}", !busy && ids.isNotEmpty() && batchTyped.trim() == phrase, danger = true) {
            action("Безвозвратно удалить ${ids.size} игроков?", "/api/privacy/erase", payload("puuids" to JSONArray(ids), "source" to batchSource, "note" to batchNote, "confirm" to batchTyped)) { batch = ""; batchTyped = ""; batchNote = "" }
        }
    }
    Heading("Журнал удалений")
    if (info.items("erased").isEmpty()) Text("Удалений не было")
    else Details("Записи (${info.items("erased").size})") {
        info.items("erased").forEach { e ->
            Text("#${e.text("id")} · ${date(e.optLong("erasedAt"))}", style = MaterialTheme.typography.titleSmall)
            Line("Источник", if (e.optString("source") == "riot") "Riot" else "заявка")
            Line("Заметка", e.text("note"))
            e.obj("removed").entries().forEach { Line(privacyNames[it.first] ?: it.first, it.second) }
            HorizontalDivider()
        }
    }
}
@Composable private fun SourceSelector(value: String, change: (String) -> Unit) {
    Row { FilterChip(selected = value == "request", onClick = { change("request") }, label = { Text("Заявка игрока") }); Spacer(Modifier.width(8.dp)); FilterChip(selected = value == "riot", onClick = { change("riot") }, label = { Text("Список Riot") }) }
}

@Composable
private fun Application(users: JSONObject, gateway: JSONObject, busy: Boolean, action: Operation) {
    var query by remember { mutableStateOf("") }; var onlineOnly by remember { mutableStateOf(false) }; var week by remember { mutableStateOf(false) }
    val online = users.obj("online"); val total = users.obj("users"); val active = users.obj("active"); val legacy = users.obj("legacy")
    Heading("Пользователи приложения")
    Metrics("Онлайн" to online.text("total"), "Выбор чемпиона" to online.text("champselect"), "В игре" to online.text("ingame"), "Всего пользователей" to total.text("total"), "Новых за сутки" to total.text("newDay"), "Новых за неделю" to total.text("newWeek"), "Активных за сутки" to active.text("day"), "Активных за неделю" to active.text("week"), "Активных за месяц" to active.text("month"))
    Details("Версии за 7 дней") {
        users.items("versions").forEach { Line(it.text("version"), it.text("users")) }
    }
    Details("Общий токен") {
        Text("Не входит в онлайн.")
        Metrics("Запросов за сутки" to legacy.text("requestsDay"))
        Line("Последний запрос", date(legacy.optLong("lastSeen")))
        Line("Heartbeat", date(legacy.optLong("lastHeartbeat")))
    }
    Heading("Онлайн")
    Row { FilterChip(selected = !week, onClick = { week = false }, label = { Text("24 часа") }); Spacer(Modifier.width(8.dp)); FilterChip(selected = week, onClick = { week = true }, label = { Text("7 дней") }) }
    Text("Онлайн: замеры каждые 5 минут, heartbeat за последние ${users.text("onlineS")} с")
    val since = users.optLong("now") - if (week) 604800 else 86400
    val samples = users.items("samples").filter { it.optLong("t") >= since }
    Graph(samples.map { it.optLong("t") }, listOf("online", "champselect", "ingame").mapIndexed { i, key ->
        GraphSeries(listOf("онлайн", "выбор чемпиона", "в игре")[i], graphColors[i], samples.map { it.optDouble(key, 0.0).toFloat() })
    })
    Heading("Регистрации за 30 дней")
    val today = Calendar.getInstance().apply { timeInMillis = users.optLong("now") * 1000; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val days = (29 downTo 0).map { n -> (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -n) }.timeInMillis / 1000 }
    val registrations = users.items("newHours")
    Graph(days, listOf(GraphSeries("новых", graphColors[1], days.mapIndexed { i, start ->
        val end = if (i + 1 < days.size) days[i + 1] else (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis / 1000
        registrations.filter { it.optLong("h") in start until end }.sumOf { it.optInt("users") }.toFloat()
    })), bars = true)
    val registration = users.optBoolean("registrationOpen")
    Heading("Управление регистрацией")
    Line("Регистрация", if (registration) "Открыта" else "Закрыта")
    Trigger(if (registration) "Закрыть регистрацию" else "Открыть регистрацию", !busy, danger = registration) { action(if (registration) "Закрыть регистрацию? Новые установки получат 503." else "Открыть регистрацию?", "/api/app-users/registration", payload("open" to !registration)) {} }
    Heading("Поиск пользователей")
    Input(query, { query = it }, "Riot ID, userId, подпись или версия")
    Row { Checkbox(checked = onlineOnly, onCheckedChange = { onlineOnly = it }); Text("Только онлайн") }
    val filtered = users.items("list").filter { u -> (!onlineOnly || u.optBoolean("online")) && listOf("id", "riotId", "note", "appVersion").any { u.optString(it).contains(query.trim(), ignoreCase = true) } }
    if (filtered.isEmpty()) Text("Пользователи не найдены")
    Text("Показаны последние ${users.text("listLimit")} по активности. Блокировка доходит до шлюза в течение минуты.")
    if (filtered.isNotEmpty()) Details("Пользователи (${filtered.size})") {
        filtered.forEach { u -> key(u.optString("id")) { UserRow(u, busy, action) } }
    }
    Heading("Шлюз приложения")
    val day = gateway.obj("day"); val count = day.optInt("requests"); val cache = day.optInt("cache")
    Metrics("Запросов за сутки" to count, "Из кеша" to "$cache (${if (count > 0) cache * 100 / count else 0}%)", "Лимит 429" to day.text("limited"), "Ошибки 5xx" to day.text("errors"), "Медиана, мс" to day.text("p50"), "95-й процентиль, мс" to day.text("p95"))
    Line("Последний запрос", date(gateway.optLong("lastAt")))
    Heading("Запросы по часам — 48 часов")
    val lastHour = gateway.optLong("now") / 3600 * 3600
    val hours = (47 downTo 0).map { lastHour - it * 3600L }
    val byHour = gateway.items("hours").associateBy { it.optLong("h") }
    Graph(hours, listOf(
        GraphSeries("из кеша", graphColors[2], hours.map { byHour[it]?.optDouble("cache", 0.0)?.toFloat() ?: 0f }),
        GraphSeries("к Riot", graphColors[0], hours.map { h -> byHour[h]?.let { (it.optDouble("requests", 0.0) - it.optDouble("cache", 0.0)).toFloat() } ?: 0f })
    ), bars = true, details = { i -> byHour[hours[i]]?.let { " · 429: ${it.optInt("limited")} · 5xx: ${it.optInt("errors")}" } ?: "" })
    val errors = gateway.items("hours").filter { it.optInt("limited") != 0 || it.optInt("errors") != 0 }
    if (errors.isNotEmpty()) Details("Ошибки по часам (${errors.size})") {
        errors.forEach { Line(date(it.optLong("h")), "429: ${it.text("limited")} · 5xx: ${it.text("errors")}") }
    }
    Details("Методы за сутки (${gateway.items("routes").size})") {
        gateway.items("routes").forEach {
            val requests = it.optInt("requests")
            val share = if (requests == 0) 0 else it.optInt("cache") * 100 / requests
            Text(it.text("route"), style = MaterialTheme.typography.titleSmall)
            Metrics("Запросы" to requests, "Из кеша" to "$share%", "Медиана, мс" to it.text("p50"), "95-й процентиль, мс" to it.text("p95"), "Без кеша, мс" to (if (it.optInt("missP50") == 0) "—" else it.text("missP50")), "429" to it.text("limited"), "5xx" to it.text("errors"))
            HorizontalDivider()
        }
    }
    Details("Ответы за сутки") {
        val statusNames = mapOf("200" to "ответ", "401" to "неверный токен", "403" to "метод не разрешён", "404" to "нет у Riot", "429" to "лимит ключа Riot", "500" to "сбой шлюза", "504" to "Riot не ответил")
        gateway.obj("statuses").entries().sortedByDescending { it.second.toLongOrNull() ?: 0 }.forEach { Line("${it.first} ${statusNames[it.first].orEmpty()}", it.second) }
    }
}
@Composable private fun UserRow(u: JSONObject, busy: Boolean, action: Operation) {
    val id = u.optString("id"); var note by remember(id, u.optString("note")) { mutableStateOf(u.optString("note")) }
    HorizontalDivider()
    val state = when (u.optString("state")) { "idle" -> "открыто"; "champselect" -> "выбор чемпиона"; "ingame" -> "в игре"; "closed" -> "закрыто"; else -> "—" }
    val blocked = u.optBoolean("blocked")
    Text(u.text("riotId"), style = MaterialTheme.typography.titleMedium)
    Text("${if (blocked) "Заблокирован · " else ""}${if (u.optBoolean("online")) "Онлайн" else "Не в сети"} · $state", color = if (blocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    Details("Сведения и управление") {
        Line("ID", id)
        Metrics("Версия" to u.text("appVersion"), "Значок S" to if (u.optBoolean("showBadge")) "да" else "нет", "Запросов за сутки" to u.text("requestsDay"))
        Line("Последний раз", date(u.optLong("lastSeen")))
        Line("Создан", date(u.optLong("createdAt")))
        Input(note, { if (it.length <= 200) note = it }, "Подпись")
        Trigger("Сохранить подпись", !busy && note != u.optString("note")) { action("Сохранить подпись пользователя $id?", "/api/app-users/note", payload("id" to id, "note" to note)) {} }
        Trigger(if (blocked) "Разблокировать" else "Заблокировать", !busy, danger = !blocked) { action(if (blocked) "Разблокировать $id?" else "Заблокировать $id? Доступ пропадёт в течение минуты.", "/api/app-users/block", payload("id" to id, "blocked" to !blocked)) {} }
    }
}
