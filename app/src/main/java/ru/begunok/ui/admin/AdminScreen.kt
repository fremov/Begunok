package ru.begunok.ui.admin

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
private fun adminNetwork(context: Context): android.net.Network {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: error("Нет подключения. Включите WireGuard.")
    check(manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
        "Подключите WireGuard перед открытием пульта"
    }
    val destination = InetAddress.getByAddress(byteArrayOf(10, 77, 0, 1))
    check(manager.getLinkProperties(network)?.routes?.any { it.destination.contains(destination) } == true) {
        "Адрес сервера не направлен через VPN. Проверьте конфигурацию WireGuard."
    }
    return network
}


private suspend fun adminRequest(context: Context, method: String, path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
    require(method == "GET" || method == "POST")
    require((path.startsWith("/api/") || path.startsWith("/admin/")) && !path.startsWith("/api/badges") && !path.startsWith("/api/skins") && !path.contains("..") && !path.contains('#'))
    val network = adminNetwork(context)
    val connection = network.openConnection(URL("http://10.77.0.1:8791$path")) as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000
        connection.readTimeout = 30000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (method == "POST") {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Admin-Action", "1")
            connection.setRequestProperty("X-Dashboard-Action", "1")
            if (path == "/admin/reboot" || path == "/admin/restart") {
                connection.setRequestProperty("X-Admin-Request-Id", UUID.randomUUID().toString())
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val data = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (e: org.json.JSONException) {
            if (code !in 200..299) error("Ошибка HTTP $code")
            throw e
        }
        if (code !in 200..299) error(data.optString("error").ifBlank { "Ошибка HTTP $code" })
        data
    } finally {
        connection.disconnect()
    }
}

@Composable
fun AdminScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableIntStateOf(0) }
    var question by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var offline by remember { mutableStateOf(false) }
    var unread by remember { mutableIntStateOf(0) }
    var logSignal by remember { mutableIntStateOf(0) }
    val credential = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && action != null) {
            scope.launch { try { action() } catch (e: Exception) { error = e.message ?: "Ошибка запроса" } }
        } else if (action != null) error = "Подтверждение устройства отменено"
    }
    val request: suspend (String, String, JSONObject) -> JSONObject = remember(context) {
        { method, path, body -> adminRequest(context, method, path, body) }
    }
    LaunchedEffect(Unit) {
        var cursor = 0L
        var logInitialized = false
        var tick = 0
        while (true) {
            if (tick % 2 == 0) {
                try { status = request("GET", "/api/status", JSONObject()); offline = false }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { offline = true }
            }
            try {
                val log = request("GET", "/api/log?since=$cursor", JSONObject())
                val lines = log.optJSONArray("lines")
                if (lines != null) for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val text = line.optString("text")
                    if (logInitialized && (line.optString("level") == "error" ||
                        (line.optString("level").isBlank() && Regex("traceback|error|ошибк", RegexOption.IGNORE_CASE).containsMatchIn(text)))) unread++
                }
                cursor = log.optLong("cursor", cursor)
                logInitialized = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
            tick++
            delay(1000)
        }
    }
    val confirm: (String, suspend () -> Unit) -> Unit = { title, action ->
        pending = action
        question = title
    }
    if (question != null) {
        AlertDialog(
            onDismissRequest = { question = null; pending = null },
            title = { Text("Подтвердите действие") },
            text = { Text("${question}\n\nПроверьте, не идёт ли сборка пака: перезапуск и обслуживание могут её прервать.") },
            confirmButton = { TextButton(onClick = {
                question = null
                val manager = context.getSystemService(KeyguardManager::class.java)
                val intent = if (manager.isDeviceSecure) manager.createConfirmDeviceCredentialIntent("Подтверждение действия", "Подтвердите доступ к устройству") else null
                if (intent == null) { pending = null; error = "Настройте PIN, пароль или графический ключ устройства" } else credential.launch(intent)
            }) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { question = null; pending = null }) { Text("Отмена") } },
        )
    }
    Column(Modifier.fillMaxSize()) {
        if (offline) {
            Text("Сервер пульта не отвечает. Проверьте WireGuard.", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
        } else status?.let { current ->
            val crawler = current.optJSONObject("crawler")
            val problems = current.optJSONObject("problems")
            val key = current.optJSONObject("key")
            val jobs = current.optJSONArray("jobs")
            val active = if (jobs == null) emptyList() else (0 until jobs.length()).mapNotNull { jobs.optJSONObject(it) }.filter { it.optBoolean("running") }
            val crawlText = if (crawler?.optString("state") == "running") "Сбор идёт · +${crawler.optJSONObject("progress")?.optInt("matches") ?: 0}" else "Сбор не идёт · ${crawler?.optString("state").orEmpty()}"
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(crawlText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(if (key?.optBoolean("found") == true) "Ключ Riot найден" else "Ключ Riot не найден",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (active.isNotEmpty()) Text(active.joinToString(" · ") { it.optString("title") },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            val hour = problems?.optInt("errorsHour") ?: 0
            val day = problems?.optInt("errorsDay") ?: 0
            if (hour > 0 || day > 0 || unread > 0) TextButton(onClick = { section = 0; unread = 0; logSignal++ }) {
                Text("${if (hour > 0) "Ошибок за час: $hour" else "Ошибок за сутки: $day"} · новых: $unread")
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            listOf(listOf("Мониторинг", "Управление"), listOf("Сервер", "Файлы пака")).forEachIndexed { row, titles ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    titles.forEachIndexed { column, title ->
                        val index = row * 2 + column
                        FilterChip(
                            selected = section == index,
                            onClick = { section = index },
                            label = { Text(title) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        error?.let { message ->
            TextButton(onClick = { error = null }) { Text("$message · Закрыть", color = MaterialTheme.colorScheme.error) }
        }
        when (section) {
            0 -> AdminMonitoring(request, confirm, logSignal)
            1 -> AdminOperations(request, confirm)
            2 -> HostPanel(request, confirm)
            else -> PackDownloads(request)
        }
    }
}

@Composable
private fun HostPanel(request: suspend (String, String, JSONObject) -> JSONObject, confirm: (String, suspend () -> Unit) -> Unit) {
    val scope = rememberCoroutineScope()
    var disk by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("db") }
    suspend fun refresh() {
        loading = true
        try { disk = request("GET", "/admin/disk", JSONObject()); error = null }
        catch (e: Exception) { error = e.message ?: "Не удалось получить состояние диска" }
        finally { loading = false }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Сервер", style = MaterialTheme.typography.titleLarge)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Диск", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                disk?.let { value ->
                    val free = value.optLong("freeBytes", -1)
                    val total = value.optLong("totalBytes", -1)
                    if (free >= 0 && total > 0) {
                        Text("%.1f ГиБ свободно".format(free / 1073741824.0), style = MaterialTheme.typography.headlineSmall)
                        Text("из %.1f ГиБ · %.0f%%".format(total / 1073741824.0, 100.0 * free / total),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(progress = { (free.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    val smart = value.optJSONObject("smart")
                    val state = if (smart == null || smart.isNull("passed")) "недоступен" else if (smart.optBoolean("passed")) "исправен" else "обнаружена проблема"
                    Text("SMART: $state", fontWeight = FontWeight.Medium)
                    smart?.optString("message")?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                OutlinedButton(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("Обновить") }
            }
        }
        Text("Управление службами", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Выберите службу для перезапуска", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val services = listOf(
            "db" to "База PostgreSQL", "crawler" to "Сбор матчей", "ladder" to "База игроков",
            "maintain" to "Обслуживание и пак", "api" to "Шлюз приложения", "dashboard" to "Пульт",
            "proxy" to "HTTPS", "backup" to "Резервные копии",
        )
        services.forEach { (service, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { selected = service },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == service, onClick = null)
                Text(label, Modifier.weight(1f))
                Text(service, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = {
            val service = selected
            confirm("Перезапустить службу $service?") { request("POST", "/admin/restart", JSONObject().put("service", service)); refresh() }
        }) { Text("Перезапустить выбранную службу") }
        OutlinedButton(onClick = {
            confirm("Перезагрузить сервер?") { request("POST", "/admin/reboot", JSONObject()) }
        }) { Text("Перезагрузить сервер") }
    }
}

@Composable
private fun PackDownloads(request: suspend (String, String, JSONObject) -> JSONObject) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    suspend fun refresh() {
        loading = true
        try {
            val array = request("GET", "/api/status", JSONObject()).optJSONObject("packs")?.optJSONArray("files")
            files = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            message = null
        } catch (e: Exception) { message = e.message ?: "Не удалось получить список файлов" }
        finally { loading = false }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val name = selected
        selected = null
        if (uri != null && name != null) scope.launch {
            loading = true
            try {
                withContext(Dispatchers.IO) {
                    require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains(".."))
                    val connection = adminNetwork(context).openConnection(URL("http://10.77.0.1:8791/api/packs/${Uri.encode(name)}")) as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 10000
                        connection.readTimeout = 30000
                        val code = connection.responseCode
                        check(code in 200..299) { "Ошибка HTTP $code" }
                        val output = context.contentResolver.openOutputStream(uri) ?: error("Не удалось открыть файл")
                        output.use { target -> connection.inputStream.use { source -> source.copyTo(target) } }
                    } finally { connection.disconnect() }
                }
                message = "Файл сохранён"
            } catch (e: Exception) { message = e.message ?: "Не удалось скачать файл" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Файлы пака", style = MaterialTheme.typography.titleLarge)
        if (loading) CircularProgressIndicator()
        message?.let { Text(it) }
        if (!loading && files.isEmpty()) Text("Файлов нет")
        files.forEach { name ->
            OutlinedButton(onClick = { selected = name; save.launch(name) }, enabled = !loading) { Text("Скачать $name") }
        }
        TextButton(onClick = { scope.launch { refresh() } }, enabled = !loading) { Text("Обновить") }
    }
}
