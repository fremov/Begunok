package ru.begunok.ui.admin

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

private fun JSONObject.obj(key: String) = optJSONObject(key) ?: JSONObject()
private fun JSONObject.arr(key: String) = optJSONArray(key) ?: JSONArray()
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONObject.value(key: String): String = if (isNull(key)) "—" else optString(key, "—")
private fun time(value: Long): String = if (value <= 0) "—" else SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(value * 1000))
private fun size(value: Double): String {
    val units = arrayOf("Б", "КБ", "МБ", "ГБ", "ТБ")
    var n = value
    var i = 0
    while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }
    return String.format(Locale("ru"), "%.1f %s", n, units[i])
}
private fun percent(value: Double) = String.format(Locale("ru"), "%.1f%%", value * 100)
private val tiers = listOf("BRONZE" to "Железо–Бронза", "GOLD" to "Серебро–Золото", "EMERALD" to "Платина–Изумруд", "DIAMOND" to "Алмаз+")
private val roles = listOf("TOP" to "Верх", "JUNGLE" to "Лес", "MIDDLE" to "Центр", "BOTTOM" to "Низ", "UTILITY" to "Поддержка")
private val rankNames = mapOf("IRON" to "Железо", "BRONZE" to "Бронза", "SILVER" to "Серебро", "GOLD" to "Золото", "PLATINUM" to "Платина", "EMERALD" to "Изумруд", "DIAMOND" to "Алмаз", "MASTER" to "Мастер", "GRANDMASTER" to "Грандмастер", "CHALLENGER" to "Претендент", "?" to "неизвестно")
private fun rank(id: String) = rankNames[id] ?: id

private data class Champion(val id: String, val name: String, val image: String)
private data class ChartSeries(val name: String, val color: Color, val values: List<Double>)
private val chartColors = listOf(Color(0xffc8aa6e), Color(0xff4b9dff), Color(0xffff5f56), Color(0xff4ad66d), Color(0xffb07cff), Color(0xfff0b44c), Color(0xff7d8899))

private suspend fun loadChampions(): List<Champion> = withContext(Dispatchers.IO) {
    fun fetch(url: String): String {
        val connection = URL(url).openConnection()
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        return connection.getInputStream().bufferedReader().use { it.readText() }
    }
    val version = JSONArray(fetch("https://ddragon.leagueoflegends.com/api/versions.json")).getString(0)
    val data = JSONObject(fetch("https://ddragon.leagueoflegends.com/cdn/$version/data/ru_RU/champion.json")).getJSONObject("data")
    data.keys().asSequence().map { data.getJSONObject(it) }.map {
        Champion(it.getString("id"), it.getString("name"), "https://ddragon.leagueoflegends.com/cdn/$version/img/champion/${it.getString("id")}.png")
    }.toList()
}

@Composable private fun Chart(title: String, labels: List<String>, series: List<ChartSeries>, bars: Boolean = false, fixedMax: Double = 0.0, unit: (Double) -> String = { String.format(Locale("ru"), "%.1f", it) }) {
    Subheading(title)
    var selected by remember(title, labels) { mutableIntStateOf(-1) }
    val count = labels.size
    val max = (if (bars) (0 until count).maxOfOrNull { i -> series.sumOf { it.values.getOrElse(i) { 0.0 }.coerceAtLeast(0.0) } } else series.flatMap { it.values }.maxOrNull())?.coerceAtLeast(fixedMax)?.coerceAtLeast(1.0) ?: 1.0
    if (count == 0) { Text("История пока пуста"); return }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        series.forEach {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("● ", color = it.color, style = MaterialTheme.typography.labelSmall)
                Text(it.name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (selected in labels.indices) {
        Text(labels[selected], style = MaterialTheme.typography.labelMedium)
        Metrics(*series.map { it.name to unit(it.values.getOrElse(selected) { 0.0 }) }.toTypedArray())
    } else {
        Text("Максимум: ${unit(max)} · коснитесь графика", style = MaterialTheme.typography.labelSmall)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.height(140.dp).width(60.dp), verticalArrangement = Arrangement.SpaceBetween) {
        listOf(1.0, 0.75, 0.5, 0.25, 0.0).forEach { Text(unit(max * it), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
    val chartBackground = Color(0xff0b0f14)
    val chartGrid = Color(0xff59616b)
    Canvas(Modifier.weight(1f).height(140.dp).background(chartBackground).pointerInput(count) {
        detectTapGestures { selected = ((it.x / size.width) * count).toInt().coerceIn(0, count - 1) }
    }) {
        val step = size.width / count.coerceAtLeast(1)
        for (i in 0..4) {
            val y = size.height * i / 4
            drawLine(chartGrid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        if (bars) {
            for (i in 0 until count) {
                var bottom = size.height
                series.forEach { s ->
                    val h = (s.values.getOrElse(i) { 0.0 }.coerceAtLeast(0.0) / max * size.height).toFloat()
                    drawRect(s.color, Offset(i * step + step * .1f, bottom - h), Size(step * .8f, h))
                    bottom -= h
                }
            }
        } else {
            series.firstOrNull()?.let { first ->
                if (count > 1) {
                    val path = Path().apply {
                        moveTo(0f, size.height)
                        first.values.take(count).forEachIndexed { i, value ->
                            lineTo(i * size.width / (count - 1), size.height * (1f - (value / max).toFloat().coerceIn(0f, 1f)))
                        }
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(path, first.color.copy(alpha = .15f))
                }
            }
            series.forEach { s ->
                for (i in 1 until count) {
                    val x1 = (i - 1) * size.width / (count - 1)
                    val x2 = i * size.width / (count - 1)
                    val y1 = size.height * (1f - (s.values.getOrElse(i - 1) { 0.0 } / max).toFloat())
                    val y2 = size.height * (1f - (s.values.getOrElse(i) { 0.0 } / max).toFloat())
                    drawLine(s.color, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2.dp.toPx())
                }
            }
        }
        if (selected in 0 until count) {
            val x = if (bars) (selected + .5f) * step else if (count > 1) selected * size.width / (count - 1) else 0f
            drawLine(Color.LightGray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
    }
    }
    Row(Modifier.fillMaxWidth().padding(start = 60.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(0, (count - 1) / 2, count - 1).distinct().forEach { Text(labels[it], style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun MarkedProgress(value: Double, max: Double, mark: Double, color: Color) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRect(track)
        val scale = max.coerceAtLeast(1.0)
        drawRect(color, size = Size(size.width * (value / scale).toFloat().coerceIn(0f, 1f), size.height))
        if (mark in 0.0..scale) {
            val x = size.width * (mark / scale).toFloat()
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}

private fun tierColor(games: Int): Color = when {
    games < 20 -> Color(0xffff5f56)
    games < 50 -> Color(0xfff0b44c)
    games < 150 -> Color(0xffc8aa6e)
    else -> Color(0xff4ad66d)
}

@Composable
fun AdminMonitoring(request: suspend (String, String, JSONObject) -> JSONObject, confirm: (String, suspend () -> Unit) -> Unit, logSignal: Int = 0) {
    var tab by remember { mutableStateOf("Сбор") }
    var logLevel by remember { mutableStateOf("all") }
    LaunchedEffect(logSignal) { if (logSignal != 0) { logLevel = "error"; tab = "Журнал" } }
    var patch by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var stats by remember { mutableStateOf<JSONObject?>(null) }
    var system by remember { mutableStateOf<JSONObject?>(null) }
    var analytics by remember { mutableStateOf<JSONObject?>(null) }
    var logs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var problems by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val pageScroll = rememberScrollState()
    var followLog by remember { mutableStateOf(true) }
    LaunchedEffect(tab, logs, problems, followLog) {
        if (tab == "Журнал" && followLog) {
            delay(50)
            pageScroll.animateScrollTo(pageScroll.maxValue)
        }
    }
    LaunchedEffect(tab, patch, refresh, request) {
        loading = true
        var cursor = 0L
        while (true) {
            try {
                when (tab) {
                    "Сбор" -> {
                        status = request("GET", "/api/status", JSONObject())
                        system = request("GET", "/api/system", JSONObject())
                        analytics = request("GET", "/api/analytics", JSONObject())
                        stats = request("GET", "/api/stats", JSONObject())
                    }
                    "Аналитика" -> analytics = request("GET", "/api/analytics", JSONObject())
                    "База", "Недостающее" -> stats = request("GET", "/api/stats" + if (patch.isEmpty()) "" else "?patch=${URLEncoder.encode(patch, "UTF-8")}", JSONObject())
                    "Журнал" -> {
                        val result = request("GET", "/api/log?since=$cursor", JSONObject())
                        val incoming = result.arr("lines").objects()
                        if (cursor == 0L) logs = incoming else logs = (logs + incoming).takeLast(3000)
                        cursor = result.optLong("cursor", cursor)
                        problems = request("GET", "/api/log?level=problems", JSONObject()).arr("lines").objects()
                    }
                }
                error = ""
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Не удалось загрузить данные" }
            loading = false
            delay(if (tab == "Журнал") 1000 else if (tab == "Сбор") 5000 else 10000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Сбор", "Аналитика", "База", "Недостающее", "Журнал").forEach { name ->
                FilterChip(selected = tab == name, onClick = { tab = name }, label = { Text(name) })
            }
            TextButton(onClick = { refresh++ }) { Text("Обновить") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Загрузка данных…", style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotEmpty()) Text("Ошибка: $error", color = MaterialTheme.colorScheme.error)
            when (tab) {
                "Сбор" -> {
                    Heading("Сбор матчей")
                    val crawler = status?.obj("crawler")
                    if (crawler == null) Text("Нет состояния службы") else {
                        val state = crawler.optString("state")
                        val stale = state == "running" && crawler.optLong("updatedAt") > 0 && (status?.optLong("now") ?: 0) - crawler.optLong("updatedAt") > 600
                        Text(if (stale) "Служба не отмечалась больше 10 минут" else when (state) {
                            "running" -> "Сбор идёт"; "paused" -> "Пауза"; "disk" -> "Пауза: мало места"; "queue-empty" -> "Очередь пуста — нужен посев"; "key-error" -> "Ключ Riot не принят"; "stopped" -> "Служба остановлена"; else -> "Служба ещё не отмечалась"
                        }, fontWeight = FontWeight.Bold, color = if (stale || state in listOf("disk", "queue-empty", "key-error", "stopped")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (crawler.optLong("startedAt") > 0) Text("Служба работает ${(status?.optLong("now")?.minus(crawler.optLong("startedAt")) ?: 0).coerceAtLeast(0) / 60} мин")
                        when (state) {
                            "paused" -> Text("Сбор продолжится с того же места.")
                            "disk" -> Text("Свободно меньше 20%, удалять больше нечего. Сбор возобновится при 25% свободного места.")
                            "key-error" -> Text("Служба повторит попытку через 10 минут; замените ключ на сервере и перезапустите crawler.")
                        }
                        val p = crawler.obj("progress")
                        Metrics("Матчей" to p.value("matches"), "Темп" to "${p.value("rate")}/ч", "Повторов" to p.value("duplicates"), "Пропущено старых" to p.value("oldSkipped"), "Игроков" to p.value("players"), "Запросов" to p.value("requests"), "Последняя отметка" to time(crawler.optLong("updatedAt")))
                        listOf("target", "window", "error").forEach { if (p.optString(it).isNotBlank()) Text(p.optString(it)) }
                        val paused = crawler.optBoolean("paused")
                        Button(enabled = !busy, onClick = {
                            val action = if (paused) "продолжить" else "приостановить"
                            confirm("Подтвердить: $action сбор матчей?") {
                                busy = true
                                try { request("POST", if (paused) "/api/crawl/resume" else "/api/crawl/pause", JSONObject()); refresh++ }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Ошибка действия" }
                                finally { busy = false }
                            }
                        }) { Text(if (paused) "Продолжить" else "Пауза") }
                        Text("Пауза действует после текущего матча. Приложение имеет приоритет запросов Riot.", style = MaterialTheme.typography.bodySmall)
                    }
                    stats?.let {
                        Subheading("База сейчас")
                        Metrics("Матчей" to it.value("matches"), "За час" to it.value("lastHour"), "Темп" to "${it.obj("rate").value("perHour")}/ч", "Очередь" to it.value("queue"))
                    }
                    SystemSection(system, analytics)
                }
                "Аналитика" -> analytics?.let { AnalyticsSection(it) } ?: Text("Нет данных аналитики")
                "База" -> stats?.let { DatabaseSection(it) { selected -> patch = selected } } ?: Text("Нет данных базы")
                "Недостающее" -> stats?.optJSONObject("coverage")?.let { MissingSection(it) } ?: Text("Нет данных по патчу")
                "Журнал" -> LogSection(logs, problems, followLog, logLevel, { logLevel = it }) { followLog = it }
            }
        }
    }
}

@Composable private fun Heading(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
@Composable private fun Subheading(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }

@Composable private fun Metrics(vararg items: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.toList().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun DetailGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text("$title ${if (expanded) "↑" else "↓"}") }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
@Composable private fun Options(title: String, options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (id, label) -> FilterChip(selected = id == selected, onClick = { select(id) }, label = { Text(label) }) }
    }
}

@Composable private fun SystemSection(system: JSONObject?, analytics: JSONObject?) {
    Heading("Сервер")
    if (system == null) { Text("Нет состояния сервера"); return }
    val mem = system.obj("memory"); val disk = system.obj("disk"); val current = system.obj("current")
    Subheading("Процессор")
    Metrics("Загрузка" to "${current.value("cpu")}%", "Ядер" to system.value("cores"), "Нагрузка" to system.arr("load").let { a -> (0 until a.length()).joinToString(" / ") { a.optString(it) } }, "Ожидание диска" to "${current.value("iowait")}%")
    LinearProgressIndicator(progress = { (current.optDouble("cpu", 0.0) / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Subheading("Температура")
    val temperatures = system.arr("temperatures").objects().mapNotNull { sensor ->
        val celsius = sensor.optDouble("celsius", Double.NaN)
        if (!celsius.isFinite()) null else {
            sensor.optString("displayName").ifBlank { "${sensor.optString("chip")} · ${sensor.optString("label")}" } to String.format(Locale("ru"), "%.1f °C", celsius)
        }
    }
    if (temperatures.isEmpty()) Text("Датчики температуры недоступны") else Metrics(*temperatures.toTypedArray())
    Subheading("Память")
    Metrics("Использовано" to "${size(mem.optDouble("used"))} из ${size(mem.optDouble("total"))}", "Кеш" to size(mem.optDouble("cache")), "Swap" to "${size(mem.optDouble("swapUsed"))} из ${size(mem.optDouble("swapTotal"))}")
    LinearProgressIndicator(progress = { (mem.optDouble("used", 0.0) / mem.optDouble("total", 0.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Subheading("Диск")
    Metrics("Использовано" to "${size(disk.optDouble("used"))} из ${size(disk.optDouble("total"))}", "Свободно" to size(disk.optDouble("free")), "Чтение" to "${size(current.optDouble("read"))}/с", "Запись" to "${size(current.optDouble("write"))}/с")
    LinearProgressIndicator(progress = { (disk.optDouble("used", 0.0) / disk.optDouble("total", 0.0).coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    Text("Сервер работает: ${system.optLong("uptimeS") / 3600} ч")
    var span by remember { mutableStateOf("hour") }
    Options("История сервера", listOf("hour" to "Час", "day" to "Сутки"), span) { span = it }
    val points = system.arr(span).objects()
    val pointLabels = points.map { time(it.optLong("t")) }
    Chart("Процессор, память и ожидание диска", pointLabels, listOf("cpu" to "Процессор", "mem" to "Память", "iowait" to "Ожидание диска").mapIndexed { i, (key, label) -> ChartSeries(label, chartColors[i], points.map { it.optDouble(key, 0.0) }) }, fixedMax = 100.0, unit = { "${it.toInt()}%" })
    Chart("Диск: чтение и запись", pointLabels, listOf("write" to "Запись", "read" to "Чтение").mapIndexed { i, (key, label) -> ChartSeries(label, chartColors[i], points.map { it.optDouble(key, 0.0) }) }, unit = { "${size(it)}/с" })
    Subheading("Службы")
    val services = system.obj("services")
    val names = services.keys().asSequence().toList().sortedByDescending { services.obj(it).optDouble("memory") }
    if (names.isEmpty()) Text("Нет данных служб: нужен доступ к cgroup хоста")
    val stopped = names.filter { !services.obj(it).optBoolean("running") }
    if (stopped.isNotEmpty()) Text("Не работают: ${stopped.joinToString()}. Проверьте журнал и docker compose ps.", color = MaterialTheme.colorScheme.error)
    names.forEach { name -> val s = services.obj(name)
        HorizontalDivider()
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(if (s.optBoolean("running")) "Работает" else "Остановлена", color = if (s.optBoolean("running")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (s.optString("about").isNotBlank()) Text(s.optString("about"), style = MaterialTheme.typography.bodySmall)
        DetailGroup("Показатели службы") {
            Metrics("Процессор" to "${s.value("cpu")}%", "Память" to size(s.optDouble("memory")), "Чтение" to "${size(s.optDouble("read"))}/с", "Запись" to "${size(s.optDouble("write"))}/с", "Процессов" to s.value("pids"), "OOM" to s.value("oomKills"))
        }
    }
    Subheading("История служб за час")
    val history = system.arr("serviceHistory").objects()
    val historyLabels = history.map { time(it.optLong("t")) }
    Chart("Процессор служб", historyLabels, names.mapIndexed { i, name -> ChartSeries(name, chartColors[i % chartColors.size], history.map { it.optJSONArray(name)?.optDouble(0, 0.0) ?: 0.0 }) }, unit = { "${String.format(Locale("ru"), "%.1f", it)}%" })
    Chart("Память служб", historyLabels, names.mapIndexed { i, name -> ChartSeries(name, chartColors[i % chartColors.size], history.map { it.optJSONArray(name)?.optDouble(1, 0.0) ?: 0.0 }) }, unit = ::size)
    analytics?.let { a ->
        val key = a.obj("key"); val windows = key.obj("windows"); val app = windows.obj("app"); val crawl = windows.obj("crawl")
        Subheading("Лимит ключа Riot")
        Metrics("За 2 минуты" to "${app.optInt("twoMinutes") + crawl.optInt("twoMinutes")} из 95", "За секунду" to "${app.optInt("second") + crawl.optInt("second")} из 18", "Сбор за 2 минуты" to crawl.optInt("twoMinutes").toString(), "Приложение за 2 минуты" to app.optInt("twoMinutes").toString())
        val now = a.optLong("now")
        val buckets = key.arr("buckets").objects()
        val times = generateSequence((now - 300) / 10 * 10) { it + 10 }.takeWhile { it <= now }.toList()
        Chart("Запросы Riot по 10 секунд за 5 минут", times.map { time(it) }, listOf("crawl" to "Сбор", "app" to "Приложение").mapIndexed { i, (role, label) -> ChartSeries(label, chartColors[i], times.map { t -> buckets.filter { it.optLong("t") == t && (if (role == "app") it.optString("role") == "app" else it.optString("role") != "app") }.sumOf { it.optDouble("calls", 0.0) } }) }, bars = true, unit = { it.toLong().toString() })
    }
}

@Composable private fun AnalyticsSection(a: JSONObject) {
    Heading("Аналитика сбора")
    val now = a.optLong("now")
    val hours = a.arr("hours").objects(); val players = a.arr("players").objects()
    fun dayStart(offset: Int): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = now * 1000
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        add(java.util.Calendar.DAY_OF_YEAR, -offset)
    }.timeInMillis / 1000
    fun sum(from: Long, to: Long, key: String, data: List<JSONObject>) = data.filter { it.optLong("h") in (from / 3600 * 3600) until to }.sumOf { it.optLong(key) }
    val today = sum(dayStart(0), now + 1, "matches", hours)
    val yesterday = sum(dayStart(1), dayStart(0), "matches", hours)
    val yesterdayByNow = sum(dayStart(1), now - 86400 + 1, "matches", hours)
    val week = sum(now - 7 * 86400, now + 1, "matches", hours)
    val best = (0 until 30).map { offset -> offset to sum(dayStart(offset), if (offset == 0) now + 1 else dayStart(offset - 1), "matches", hours) }.maxByOrNull { it.second }
    Metrics(
        "Матчей сегодня" to today.toString(),
        "Вчера к этому часу" to "$yesterdayByNow${if (yesterdayByNow > 0) " (${percent(today.toDouble() / yesterdayByNow - 1)})" else ""}",
        "Матчей вчера" to yesterday.toString(),
        "Игроков вчера" to sum(dayStart(1), dayStart(0), "players", players).toString(),
        "За последний час" to sum(now - 3600, now + 1, "matches", hours).toString(),
        "С timeline за час" to sum(now - 3600, now + 1, "timeline", hours).toString(),
        "За 7 дней" to week.toString(),
        "В среднем за сутки" to (week / 7).toString(),
        "Лучшие сутки" to "${best?.second ?: 0} (${best?.let { time(dayStart(it.first)) } ?: "—"})",
        "Игроков сегодня" to sum(dayStart(0), now + 1, "players", players).toString()
    )
    val byHour = hours.associateBy { it.optLong("h") }
    val last = now / 3600 * 3600
    val hourTimes = (47 downTo 0).map { last - it * 3600 }
    val timeline = hourTimes.map { byHour[it]?.optDouble("timeline", 0.0) ?: 0.0 }
    Chart("Матчи по часам — 48 часов", hourTimes.map(::time), listOf(
        ChartSeries("С timeline", chartColors[0], timeline),
        ChartSeries("Без timeline", chartColors[1], hourTimes.mapIndexed { i, h -> (byHour[h]?.optDouble("matches", 0.0) ?: 0.0) - timeline[i] })
    ), bars = true, unit = { it.toLong().toString() })
    val days = (29 downTo 0).map { dayStart(it) to if (it == 0) now + 1 else dayStart(it - 1) }
    Chart("Матчи по дням — 30 дней", days.map { time(it.first) }, listOf(ChartSeries("Матчи", chartColors[0], days.map { sum(it.first, it.second, "matches", hours).toDouble() })), bars = true, unit = { it.toLong().toString() })
    Chart("Игроков обойдено по дням", days.map { time(it.first) }, listOf(ChartSeries("Игроки", chartColors[1], days.map { sum(it.first, it.second, "players", players).toDouble() })), bars = true, unit = { it.toLong().toString() })
    Subheading("Ранги собранных матчей")
    val ranks = a.arr("tiers").objects(); val total = ranks.sumOf { it.optLong("day") }
    val rankMax = ranks.maxOfOrNull { it.optDouble("week", 0.0) }?.coerceAtLeast(1.0) ?: 1.0
    ranks.forEach {
        Text(rank(it.value("tier")), style = MaterialTheme.typography.titleSmall)
        Metrics("За сутки" to "${it.value("day")} (${percent(if (total == 0L) 0.0 else it.optDouble("day") / total)})", "За неделю" to it.value("week"))
        LinearProgressIndicator(progress = { (it.optDouble("week", 0.0) / rankMax).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun DatabaseSection(s: JSONObject, pick: (String) -> Unit) {
    Heading("База и покрытие")
    if (s.optBoolean("empty")) { Text("База пуста — сделайте посев в обслуживании"); return }
    if (s.optString("error").isNotBlank()) Text(s.optString("error"), color = MaterialTheme.colorScheme.error)
    Metrics("Матчей" to s.value("matches"), "С timeline" to s.value("withTimeline"), "Наблюдений" to s.value("participants"), "Игроков" to s.value("players"), "За час" to s.value("lastHour"), "За сутки" to s.value("lastDay"), "Темп" to "${s.obj("rate").value("perHour")}/ч", "Очередь" to s.value("queue"), "Размер базы" to size(s.optDouble("sizeBytes")), "Последний матч" to time(s.optLong("lastFetchedAt")))
    Subheading("Патчи")
    val patches = s.arr("patches").objects()
    val packMin = s.obj("coverage").optDouble("packMinMatches", 1000.0)
    val patchMax = maxOf(packMin, patches.maxOfOrNull { it.optDouble("matches", 0.0) } ?: 0.0)
    patches.forEach { p ->
        TextButton(onClick = { pick(p.optString("patch")) }) { Text("Патч ${p.value("patch")}${if (p.optString("patch") == s.optString("packPatch")) " · пак" else ""}") }
        Metrics("Матчей" to p.value("matches"), "С timeline" to p.value("timeline"))
        MarkedProgress(p.optDouble("matches", 0.0), patchMax, packMin, if (p.optDouble("timeline", 0.0) >= packMin) Color(0xff4ad66d) else Color(0xffc8aa6e))
    }
    Subheading("Ранг посева")
    val seedTiers = s.arr("tiers").objects()
    val seedMax = seedTiers.maxOfOrNull { it.optDouble("matches", 0.0) }?.coerceAtLeast(1.0) ?: 1.0
    seedTiers.forEach {
        Text("${rank(it.value("tier"))}: ${it.value("matches")}")
        LinearProgressIndicator(progress = { (it.optDouble("matches", 0.0) / seedMax).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
    Subheading("Смена патча")
    val history = s.arr("patchHistory").objects().sortedByDescending { it.optLong("releasedAt") }
    if (history.isEmpty()) Text("История пока пуста")
    history.forEach { p ->
        fun elapsed(start: String, end: String): String = if (p.optLong(start) <= 0 || p.optLong(end) <= 0) "—" else String.format(Locale("ru"), "%.1f ч", (p.optLong(end) - p.optLong(start)).coerceAtLeast(0) / 3600.0)
        DetailGroup("Патч ${p.value("patch")}") {
            Metrics("Вышел" to time(p.optLong("releasedAt")), "Начало сбора" to time(p.optLong("firstFetchedAt")), "До начала сбора" to elapsed("releasedAt", "firstFetchedAt"), "До 1000 матчей" to elapsed("firstFetchedAt", "reached1000At"), "Первый пак" to time(p.optLong("firstPackAt")), "До первого пака" to elapsed("releasedAt", "firstPackAt"), "Матчей" to p.value("matches"), "Хранение" to if (p.optLong("droppedAt") > 0) "Удалён ${time(p.optLong("droppedAt"))}" else "На диске")
        }
    }
    s.optJSONObject("coverage")?.let { c ->
        Subheading("Покрытие патча ${c.value("patch")}")
        Metrics("Матчей" to c.value("matches"), "С timeline" to c.value("withTimeline"), "Наблюдений" to c.value("observations"), "Чемпионов" to c.value("champions"), "Порог пака" to c.value("packMinMatches"))
        listOf("base" to "Чемпион + роль в паке", "baseReliable" to "Чемпион + роль надёжно", "tier" to "По ранговым корзинам", "matchup" to "Матчапы в паке", "matchupReliable" to "Матчапы надёжно").forEach { (key, title) ->
            val f = c.obj(key)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Metrics("Покрытие" to percent(f.optDouble("share")), "Корзин готово" to "${f.value("bucketsOk")} из ${f.value("buckets")}", "Порог" to f.value("threshold"))
            f.arr("targets").objects().forEach { t ->
                val needed = t.optLong("matches", -1); val more = (needed - c.optLong("matches")).coerceAtLeast(0); val rate = s.obj("rate").optDouble("perHour")
                Text("${percent(t.optDouble("share"))}: ${if (needed < 0) "оценить нельзя" else if (more == 0L) "уже есть" else "нужно ≈ $needed матчей, ещё $more${if (rate > 0) String.format(Locale("ru"), ", ≈ %.1f ч", more / rate) else ""}"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        c.arr("tierGames").objects().forEach { Text("${it.value("tier")}: ${it.value("observations")} наблюдений") }
    }
}

@Composable private fun MissingSection(c: JSONObject) {
    var status by remember { mutableStateOf("all") }; var role by remember { mutableStateOf("") }; var tier by remember { mutableStateOf("") }; var below by remember { mutableStateOf("0") }; var query by remember { mutableStateOf("") }; var sort by remember { mutableStateOf("weakest") }; var desc by remember { mutableStateOf(false) }
    val thresholds = c.obj("thresholds"); val pack = thresholds.optInt("pack"); val reliable = thresholds.optInt("reliable")
    val rows = c.arr("mains").objects()
    var champions by remember { mutableStateOf<List<Champion>?>(null) }
    var championError by remember { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(retry) {
        try { champions = loadChampions(); championError = "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { championError = e.message ?: "Ошибка загрузки" }
    }
    val championMap = remember(champions) { champions.orEmpty().associateBy { it.id.lowercase(Locale.ROOT) } }
    fun championName(r: JSONObject): String = championMap[r.optString("champion").lowercase(Locale.ROOT)]?.name ?: r.optString("champion")
    fun rowStatus(r: JSONObject) = if (r.optInt("games") < pack) "bad" else if (r.optInt("games") < reliable) "warn" else "ok"
    fun weakest(r: JSONObject) = tiers.minOf { r.obj("tiers").optInt(it.first) }
    Heading("Чего не хватает · ${c.value("patch")}")
    Metrics("Всего ролей" to rows.size.toString(), "Не в паке" to rows.count { rowStatus(it) == "bad" }.toString(), "Недостаточно надёжно" to rows.count { rowStatus(it) == "warn" }.toString(), "Надёжно" to rows.count { rowStatus(it) == "ok" }.toString(), "Корзина меньше 20" to rows.count { weakest(it) < 20 }.toString())
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf("bad" to "Не в паке", "warn" to "В паке", "ok" to "Надёжно").forEach { (id, label) ->
            TextButton(onClick = { status = if (status == id) "all" else id }) { Text("$label: ${rows.count { rowStatus(it) == id }}") }
        }
    }
    if (championError.isNotEmpty()) {
        Text("Не удалось загрузить Data Dragon: $championError")
        TextButton(onClick = { retry++ }) { Text("Повторить загрузку справочника") }
    } else if (champions == null) Text("Загрузка справочника чемпионов…") else {
        val seen = rows.map { it.optString("champion").lowercase(Locale.ROOT) }.toSet()
        val unseen = champions.orEmpty().filter { it.id.lowercase(Locale.ROOT) !in seen }.sortedBy { it.name }
        if (unseen.isNotEmpty()) DetailGroup("Без игр на патче: ${unseen.size}") {
            unseen.forEach { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    TextButton(onClick = { below = if (below == "20" && tier.isEmpty()) "0" else "20"; tier = "" }) { Text("Показать роли с почти пустой корзиной") }
    Options("Статус", listOf("all" to "Все", "bad" to "Не в паке", "warn" to "В паке", "ok" to "Надёжно"), status) { status = it }
    Options("Роль", listOf("" to "Все") + roles, role) { role = if (role == it) "" else it }
    Options("Корзина", listOf("" to "Любая") + tiers, tier) { tier = it }
    Options("Игры в корзине", listOf("0" to "Любое число", "20" to "Меньше 20", "50" to "Меньше 50", "150" to "Меньше 150"), below) { below = it }
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Чемпион") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { status = "all"; role = ""; tier = ""; below = "0"; query = "" }) { Text("Сбросить фильтры") }
    Options("Сортировка", listOf("weakest" to "Слабее всего", "champion" to "Чемпион", "role" to "Роль", "games" to "Игры", "share" to "Доля", "matchups" to "Матчапы", "need" to "Нужно матчей") + tiers, sort) { if (sort == it) desc = !desc else { sort = it; desc = false } }
    TextButton(onClick = { desc = !desc }) { Text(if (desc) "По убыванию ↓" else "По возрастанию ↑") }
    val filtered = rows.filter { r ->
        (status == "all" || rowStatus(r) == status) && (role.isEmpty() || r.optString("role") == role) && (below == "0" || (if (tier.isEmpty()) tiers.map { it.first } else listOf(tier)).any { r.obj("tiers").optInt(it) < below.toInt() }) && (r.optString("champion").contains(query.trim(), ignoreCase = true) || championName(r).contains(query.trim(), ignoreCase = true))
    }.sortedWith { a, b ->
        fun number(r: JSONObject): Double = when (sort) { "weakest" -> weakest(r).toDouble(); "need" -> if (r.optInt("games") >= reliable) Double.MAX_VALUE else ceil(c.optDouble("matches") * (if (r.optInt("games") < pack) pack else reliable) / r.optInt("games").coerceAtLeast(1)); in tiers.map { it.first } -> r.obj("tiers").optDouble(sort); else -> r.optDouble(sort) }
        val comparison = when (sort) { "champion" -> java.text.Collator.getInstance(Locale("ru")).compare(championName(a), championName(b)); "role" -> roles.indexOfFirst { it.first == a.optString("role") }.compareTo(roles.indexOfFirst { it.first == b.optString("role") }); else -> number(a).compareTo(number(b)) }
        (if (desc) -comparison else comparison).let { if (it == 0) a.optInt("games").compareTo(b.optInt("games")) else it }
    }
    Text("Показано ${filtered.size} из ${rows.size}. Основная роль — от 15% игр чемпиона; ступени сбора: 20, 50 и 150 игр в корзине.")
    if (filtered.isEmpty()) Text("Под фильтр ничего не подходит")
    filtered.forEach { r ->
        val games = r.optInt("games"); val target = if (games < pack) pack else reliable
        val need = if (games >= target) "—" else "≈ ${ceil(c.optDouble("matches") * target / games.coerceAtLeast(1)).toLong()}"
        val weakestTier = tiers.minByOrNull { r.obj("tiers").optInt(it.first) }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            championMap[r.optString("champion").lowercase(Locale.ROOT)]?.let { champion ->
                AsyncImage(model = champion.image, contentDescription = champion.name, modifier = Modifier.size(32.dp))
            }
            Text("${championName(r)} · ${roles.find { it.first == r.optString("role") }?.second ?: r.value("role")}", fontWeight = FontWeight.SemiBold)
        }
        Text(when (rowStatus(r)) { "bad" -> "Не в паке"; "warn" -> "В паке, недостаточно надёжно"; else -> "Надёжно" }, color = if (rowStatus(r) == "bad") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Metrics("Игр" to games.toString(), "Доля" to percent(r.optDouble("share")), "Матчапов" to r.value("matchups"), "Нужно матчей" to need)
        MarkedProgress(games.toDouble(), reliable.toDouble(), pack.toDouble(), tierColor(if (games < pack) 0 else if (games < reliable) 20 else 150))
        DetailGroup("Игры по корзинам") {
            Metrics(*tiers.map { (id, label) -> label to r.obj("tiers").optInt(id).toString() }.toTypedArray())
        }
        Text("Слабее всего: ${weakestTier?.second ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun LogSection(lines: List<JSONObject>, problems: List<JSONObject>, follow: Boolean, level: String, setLevel: (String) -> Unit, setFollow: (Boolean) -> Unit) {
    var source by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    Heading("Журнал")
    Options("Уровень", listOf("all" to "Всё", "problems" to "Предупреждения и ошибки", "error" to "Только ошибки"), level, setLevel)
    val sources = (lines + problems).map { it.optString("source") }.filter { it.isNotEmpty() }.distinct().sorted()
    Options("Источник", listOf("" to "Все") + sources.map { it to it }, source) { source = it }
    OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск в журнале") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row { Checkbox(checked = follow, onCheckedChange = setFollow); Text("К новым строкам", modifier = Modifier.padding(top = 12.dp)) }
    val merged = if (level == "all") lines else (problems + lines).associateBy { it.optLong("n") }.values.sortedBy { it.optLong("n") }
    val shown = merged.filter { line ->
        val raw = line.optString("level"); val text = line.optString("text")
        val l = if (raw.isNotEmpty() && raw != "info") raw else if (Regex("traceback|error|ошибк|не удал|сбой", RegexOption.IGNORE_CASE).containsMatchIn(text)) "error" else "info"
        (level == "all" || level == "problems" && l != "info" || level == "error" && l == "error") && (source.isEmpty() || line.optString("source") == source) && text.contains(query.trim(), ignoreCase = true)
    }
    Text("Записей: ${shown.size}")
    if (shown.isEmpty()) Text("Записей нет")
    shown.forEach { line ->
        val raw = line.optString("level")
        val isError = raw == "error" || (raw.isEmpty() || raw == "info") && Regex("traceback|error|ошибк|не удал|сбой", RegexOption.IGNORE_CASE).containsMatchIn(line.optString("text"))
        val color = if (isError) MaterialTheme.colorScheme.error else if (raw == "warn" || line.optBoolean("system")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        HorizontalDivider()
        Text("${time(line.optLong("t"))} · ${line.value("source")} · ${line.value("level")}", color = color, style = MaterialTheme.typography.labelMedium)
        Text(line.optString("text"), style = MaterialTheme.typography.bodyMedium)
    }
}
