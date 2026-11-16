package ru.begunok.health

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.net.toUri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import ru.begunok.data.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class HealthSyncState(
    val availability: HealthAvailability,
    val granted: Set<String> = emptySet(),
    val day: HealthDay? = null,
    val loading: Boolean = false,
    val message: String? = null,
    val updatedAt: Instant? = null,
)

/** One reader shared by the visible app and Android's persisted background job. */
class HealthAutoSync(private val context: Context, private val repository: HealthRepository, private val settings: SettingsRepository) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(HealthSyncState(repository.availability()))
    val state = mutableState.asStateFlow()
    private val historyStore by lazy { HealthHistoryStore(context) }
    private val mutableHistoryChanges = MutableStateFlow(0L)
    val historyChanges: StateFlow<Long> = mutableHistoryChanges.asStateFlow()
    private val initializationMutex = Mutex()
    private val backfillMutex = Mutex()
    private var initialized = false
    private var lastGadgetbridgeCheckAt = -1L
    private val extrasImporter by lazy { GadgetbridgeExtrasImporter(context) }
    private var lastExtrasReadAt = -1L
    private var lastExtrasUri: String? = null
    private var extrasMessage: String? = null

    /** Reads only the retained local database, including when Health Connect access is revoked. */
    suspend fun history(start: LocalDate?, end: LocalDate): HealthHistory = withContext(Dispatchers.IO) {
        initializeHistory(LocalDate.now(ZoneId.systemDefault()))
        historyStore.history(start, end, ZoneId.systemDefault())
    }

    /** Calendar projection: daily rows only, no raw measurement arrays or provider requests. */
    suspend fun dailySteps(start: LocalDate?, end: LocalDate): List<DailySteps> = withContext(Dispatchers.IO) {
        initializeHistory(LocalDate.now(ZoneId.systemDefault()))
        historyStore.dailySteps(start, end)
    }

    private suspend fun initializeHistory(today: LocalDate): String? = initializationMutex.withLock {
        if (initialized) return@withLock null
        try {
            withContext(Dispatchers.IO) { historyStore.initialize(today) }
            initialized = true
            historyCommitted()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Cannot initialize or migrate health history", error)
            "Не удалось перенести прежнюю историю здоровья: ${error.message}"
        }
    }

    private fun historyCommitted() {
        synchronized(mutableHistoryChanges) { mutableHistoryChanges.value += 1 }
    }

    val permissions: Set<String>
        get() = repository.permissions + if (backgroundSupported()) {
            setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        } else emptySet()

    private fun backgroundSupported(): Boolean =
        repository.availability() == HealthAvailability.Available &&
            HealthConnectClient.getOrCreate(context).features.getFeatureStatus(
                HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
            ) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) != null) return
        val result = scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, HealthSyncJobService::class.java))
                .setPeriodic(BACKGROUND_INTERVAL_MS)
                .setPersisted(true)
                .build(),
        )
        if (result == JobScheduler.RESULT_FAILURE) Log.e(TAG, "Background health job could not be scheduled")
    }

    suspend fun refresh(background: Boolean = false) {
        val backfillPermissions = mutex.withLock {
            val availability = repository.availability()
            val zone = ZoneId.systemDefault()
            val date = LocalDate.now(zone)
            val initializationError = initializeHistory(date)
            mutableState.value = mutableState.value.copy(availability = availability, loading = true, message = initializationError)
            try {
                // Restore today's retained values before attempting any provider access.
                val retained = withContext(Dispatchers.IO) { historyStore.day(date) }
                if (mutableState.value.day == null || mutableState.value.updatedAt?.atZone(zone)?.toLocalDate() != date) {
                    mutableState.value = mutableState.value.copy(day = retained?.data, updatedAt = retained?.readAt)
                }
                val granted = repository.granted()
                val readable = granted.intersect(repository.permissions) - HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
                mutableState.value = mutableState.value.copy(granted = granted)
                // Revocation prevents new reads, never erases already acquired local history.
                if (readable.isEmpty() || (background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted)) {
                    mutableState.value = mutableState.value.copy(loading = false)
                    return@withLock null
                }
                val braceletSyncError = requestGadgetbridgeSync()
                val now = Instant.now()
                val exportUri = settings.gadgetbridgeExportTreeUri.first()
                val elapsed = SystemClock.elapsedRealtime()
                if (exportUri != lastExtrasUri || lastExtrasReadAt < 0 || elapsed - lastExtrasReadAt >= GADGETBRIDGE_REQUEST_INTERVAL_MS) {
                    val extras = extrasImporter.read(exportUri?.toUri(), date.minusDays(30).atStartOfDay(zone).toInstant(), now)
                    withContext(Dispatchers.IO) { historyStore.saveExtras(extras.stress, extras.vitality, now, zone) }
                    extrasMessage = extras.message
                    lastExtrasUri = exportUri
                    lastExtrasReadAt = elapsed
                }
                val result = repository.readDay(date.atStartOfDay(zone).toInstant(), now, granted)
                val saved = withContext(Dispatchers.IO) {
                    // A partial current day must be finalized once more after midnight.
                    historyStore.save(date, now, result.data, emptySet())
                }
                historyCommitted()
                mutableState.value = HealthSyncState(
                    availability, granted, saved.data,
                    message = (listOfNotNull(initializationError, braceletSyncError, extrasMessage) + result.errors).takeIf { it.isNotEmpty() }?.joinToString("; "),
                    updatedAt = saved.readAt,
                )
                Log.i(TAG, "Automatic health read completed; background=$background; updatedAt=$now")
                granted
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(loading = false)
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Cannot refresh health history", error)
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    message = error.message ?: "Не удалось прочитать или сохранить показатели на телефоне.",
                )
                null
            }
        }
        // Today's read is committed/published and its lock released before the historical work.
        // Concurrent refreshes still read today while a single backfill proceeds.
        if (backfillPermissions != null && backfillMutex.tryLock()) {
            try {
                backfill(backfillPermissions)
            } finally {
                backfillMutex.unlock()
            }
        }
    }

    /** Health Connect only stores data; ask the configured bracelet companion to fetch new samples. */
    private suspend fun requestGadgetbridgeSync(): String? {
        val now = SystemClock.elapsedRealtime()
        if (lastGadgetbridgeCheckAt >= 0 && now - lastGadgetbridgeCheckAt < GADGETBRIDGE_REQUEST_INTERVAL_MS) return null
        lastGadgetbridgeCheckAt = now
        return withContext(Dispatchers.IO) {
            val application = try {
                context.packageManager.getApplicationInfo(GADGETBRIDGE_PACKAGE, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                return@withContext null
            }
            // Respect an explicit stop or disable; never restart a companion against the user's choice.
            if (!application.enabled || application.flags and ApplicationInfo.FLAG_STOPPED != 0) return@withContext null
            try {
                context.sendBroadcast(
                    Intent("nodomain.freeyourgadget.gadgetbridge.command.ACTIVITY_SYNC")
                        .setPackage(GADGETBRIDGE_PACKAGE)
                        // Daily activity, SpO2, stress, HR and Xiaomi vitality; no workouts or GPS tracks.
                        .putExtra("dataTypesHex", "0x000001E1")
                )
                Log.i(TAG, "Requested bracelet sync from Gadgetbridge")
                null
            } catch (error: SecurityException) {
                Log.e(TAG, "Cannot request bracelet sync from Gadgetbridge", error)
                "Не удалось запросить новые измерения у Gadgetbridge: ${error.message}"
            }
        }
    }

    private suspend fun backfill(granted: Set<String>) {
        try {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val readable = granted.intersect(repository.permissions) - HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
            val pending = withContext(Dispatchers.IO) {
                historyStore.pendingDates(today, readable, BACKFILL_DAYS_PER_REFRESH)
            }
            val failures = mutableListOf<String>()
            for (date in pending) {
                val result = repository.readDay(
                    date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant(), granted,
                )
                withContext(Dispatchers.IO) {
                    historyStore.save(date, Instant.now(), result.data, result.completedPermissions.intersect(readable))
                }
                historyCommitted()
                if (result.errors.isNotEmpty()) failures.add("$date: ${result.errors.joinToString("; ")}")
            }
            if (failures.isNotEmpty()) {
                mutableState.value = mutableState.value.copy(
                    message = "История прочитана частично (${failures.size} дн.); непрочитанные данные будут запрошены снова. ${failures.first()}",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Health history backfill failed", error)
            mutableState.value = mutableState.value.copy(
                message = "Не удалось дочитать историю; повторим при обновлении. ${error.message.orEmpty()}",
            )
        }
    }

    companion object {
        const val FOREGROUND_INTERVAL_MS = 15_000L
        const val JOB_ID = 7401
        private const val BACKGROUND_INTERVAL_MS = 15 * 60_000L
        private const val GADGETBRIDGE_PACKAGE = "nodomain.freeyourgadget.gadgetbridge"
        private const val GADGETBRIDGE_REQUEST_INTERVAL_MS = 60_000L
        private const val BACKFILL_DAYS_PER_REFRESH = 3
        private const val TAG = "MyLifeHealthSync"
    }
}
