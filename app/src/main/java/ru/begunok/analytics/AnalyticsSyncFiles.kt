package ru.begunok.analytics

import android.content.Context
import android.util.AtomicFile
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.YearMonth

internal const val ANALYTICS_USB_ENDPOINT = "http://127.0.0.1:8786"

internal class AnalyticsConfiguration private constructor(
    val endpoint: HttpUrl,
    val token: String,
    val usbEndpoint: HttpUrl?,
) {
    val supportsUsb: Boolean get() = usbEndpoint != null || endpoint.toString().trimEnd('/') == ANALYTICS_USB_ENDPOINT

    // Endpoint changes or token rotation may identify a fresh server; keep tombstones and resend.
    val destination: String = "$endpoint\n$usbEndpoint\n$token".encodeUtf8().sha256().hex()

    fun json() = JSONObject().put("endpoint", endpoint.toString().trimEnd('/')).put("token", token).apply {
        if (usbEndpoint != null) put("usbEndpoint", usbEndpoint.toString().trimEnd('/'))
    }

    companion object {
        fun parse(endpoint: String, token: String, usbEndpoint: String?): AnalyticsConfiguration {
            val url = endpoint.trim().toHttpUrlOrNull()
            require(url != null && url.encodedPath == "/" && url.query == null && url.fragment == null &&
                url.username.isEmpty() && url.password.isEmpty() &&
                (url.isHttps || url.toString().trimEnd('/') == ANALYTICS_USB_ENDPOINT))
            val secret = token.trim()
            require(secret.isNotEmpty() && secret.length <= 4096 && secret.all { it in '!'..'~' })
            val usb = usbEndpoint?.trim()?.takeIf(String::isNotEmpty)?.let {
                val parsed = it.toHttpUrlOrNull()
                require(parsed != null && parsed.toString().trimEnd('/') == ANALYTICS_USB_ENDPOINT)
                parsed.takeUnless { it == url }
            }
            return AnalyticsConfiguration(url, secret, usb)
        }
    }
}

internal class AnalyticsSyncFiles(context: Context) {
    private val config = AtomicFile(File(context.noBackupFilesDir, "analytics-sync.json"))
    private val progress = AtomicFile(File(context.noBackupFilesDir, "analytics-sync-state.json"))

    fun readConfig(): AnalyticsConfiguration? = config.readJson()?.let {
        AnalyticsConfiguration.parse(it.getString("endpoint"), it.getString("token"),
            if (it.isNull("usbEndpoint")) null else it.getString("usbEndpoint"))
    }

    fun writeConfig(value: AnalyticsConfiguration) = config.writeJson(value.json())
    fun removeConfig() = config.delete()

    fun readProgress(): AnalyticsProgress {
        val json = progress.readJson() ?: return AnalyticsProgress()
        val partitions = json.getJSONObject("partitions")
        return AnalyticsProgress(
            destination = json.optString("destination"),
            lastSuccess = if (json.isNull("lastSuccess")) null else Instant.parse(json.getString("lastSuccess")),
            partitions = partitions.keys().asSequence().associateTo(mutableMapOf()) { key ->
                AnalyticsPartition(AnalyticsStream.valueOf(key.substringBefore('/')), YearMonth.parse(key.substringAfter('/'))) to
                    if (partitions.isNull(key)) null else partitions.getString(key)
            },
        )
    }

    fun writeProgress(state: AnalyticsProgress) = progress.writeJson(JSONObject().apply {
        put("destination", state.destination)
        put("lastSuccess", state.lastSuccess?.toString() ?: JSONObject.NULL)
        put("partitions", JSONObject().apply {
            for ((key, value) in state.partitions) {
                put("${key.stream}/${key.month}", value ?: JSONObject.NULL)
            }
        })
    })

    private fun AtomicFile.readJson(): JSONObject? = try {
        openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    } catch (error: FileNotFoundException) {
        if (baseFile.exists()) throw error
        null
    }

    private fun AtomicFile.writeJson(value: JSONObject) {
        val output = startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.UTF_8))
            finishWrite(output)
        } catch (error: Exception) {
            failWrite(output)
            throw error
        }
    }
}

internal class AnalyticsProgress(
    var destination: String = "",
    var lastSuccess: Instant? = null,
    val partitions: MutableMap<AnalyticsPartition, String?> = mutableMapOf(),
) {
    fun invalidate() {
        partitions.replaceAll { _, _ -> null }
        lastSuccess = null
    }
}
