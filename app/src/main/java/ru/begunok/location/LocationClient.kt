package ru.begunok.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import ru.begunok.system.Permissions
import ru.begunok.system.SystemChecks
import kotlin.math.roundToInt

sealed interface GpsStatus {
    val label: String

    data object NoPermission : GpsStatus {
        override val label = "нет доступа к геолокации"
    }

    data object Disabled : GpsStatus {
        override val label = "геолокация выключена"
    }

    data object Searching : GpsStatus {
        override val label = "ищу спутники"
    }

    data class Weak(val accuracyM: Int) : GpsStatus {
        override val label = "слабый сигнал, точность $accuracyM м"
    }

    data class Ready(val accuracyM: Int) : GpsStatus {
        override val label = "GPS готов, точность $accuracyM м"
    }

    companion object {
        const val GOOD_ACCURACY_M = 20f
        private const val STALE_MS = 10_000L

        fun of(context: Context, tracker: FixQuality): GpsStatus = when {
            !Permissions.hasFineLocation(context) -> NoPermission
            !SystemChecks.isLocationEnabled(context) -> Disabled
            else -> tracker.status()
        }

        internal fun of(last: Location?, lastGood: Location?): GpsStatus {
            val good = lastGood?.takeIf { it.ageMs() <= STALE_MS }
            if (good != null) {
                val accuracy = last?.takeIf { it.ageMs() <= STALE_MS && it.hasAccuracy() }?.accuracy ?: good.accuracy
                return Ready(minOf(accuracy, GOOD_ACCURACY_M).roundToInt().coerceAtLeast(1))
            }
            val recent = last?.takeIf { it.ageMs() <= STALE_MS && it.hasAccuracy() } ?: return Searching
            return Weak(recent.accuracy.roundToInt())
        }
    }
}

class FixQuality {
    var last: Location? = null
        private set
    var lastGood: Location? = null
        private set

    fun onLocation(location: Location) {
        last = location
        if (location.hasAccuracy() && location.accuracy <= GpsStatus.GOOD_ACCURACY_M) lastGood = location
    }

    fun reset() {
        last = null
        lastGood = null
    }

    fun status(): GpsStatus = GpsStatus.of(last, lastGood)
}

fun Location.ageMs(): Long = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000

fun Location.elapsedMs(): Long = elapsedRealtimeNanos / 1_000_000

class LocationClient(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    private fun updatesOnce(): Flow<Location> = callbackFlow {
        if (!Permissions.hasFineLocation(context)) {
            close()
            return@callbackFlow
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateIntervalMillis(INTERVAL_MS)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setWaitForAccurateLocation(false)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { trySend(it) }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { close(it) }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    fun updates(): Flow<Location> = flow {
        while (true) {
            updatesOnce().catch { }.collect { emit(it) }
            delay(RESTART_DELAY_MS)
        }
    }

    fun status(): Flow<GpsStatus> = channelFlow {
        val quality = FixQuality()
        launch { updates().collect { quality.onLocation(it) } }
        while (true) {
            send(GpsStatus.of(context, quality))
            delay(1_000)
        }
    }.distinctUntilChanged()

    private companion object {
        const val INTERVAL_MS = 1_000L
        const val RESTART_DELAY_MS = 2_000L
    }
}
