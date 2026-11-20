package ru.begunok.analytics

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class AnalyticsRejected(val status: Int) : Exception()

internal class AnalyticsDelivery(private val config: AnalyticsConfiguration) {
    private val endpoints = listOfNotNull(config.endpoint, config.usbEndpoint)
    private var preferred = 0

    suspend fun post(batch: AnalyticsBatch) {
        var lastError: IOException? = null
        for (offset in endpoints.indices) {
            val index = (preferred + offset) % endpoints.size
            val endpoint = endpoints[index].newBuilder().addPathSegments("v1/sync/batches").build()
            val request = Request.Builder().url(endpoint).post(batch)
                .header("Authorization", "Bearer ${config.token}")
                .header("Accept", "application/json")
                .build()
            val status = try {
                client.await(request, batch)
            } catch (error: IOException) {
                lastError = error
                continue
            }
            if (status == 200) {
                preferred = index
                return
            }
            if (status == 408 || status in 500..599) {
                lastError = IOException("Analytics server unavailable: HTTP $status")
                continue
            }
            if (status == 425 || status == 429) throw IOException("Analytics retry required: HTTP $status")
            throw AnalyticsRejected(status)
        }
        throw checkNotNull(lastError)
    }


    private suspend fun OkHttpClient.await(request: Request, batch: AnalyticsBatch): Int = suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    val status = response.use {
                        if (it.code == 200) {
                            val result = JSONObject(it.peekBody(1024).string())
                            if (result.optString("stream") != batch.partition.stream.name ||
                                result.optString("month") != batch.partition.month.toString() ||
                                result.optInt("count", -1) != batch.count) throw IOException("Invalid analytics acknowledgement")
                        }
                        it.code
                    }
                    continuation.resume(status)
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                } catch (_: JSONException) {
                    continuation.resumeWithException(IOException("Invalid analytics acknowledgement"))
                }
            }
        })
    }

    private companion object {
        val client by lazy {
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
