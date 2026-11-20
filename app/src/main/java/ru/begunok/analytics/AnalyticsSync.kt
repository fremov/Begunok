package ru.begunok.analytics

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.begunok.health.HealthAutoSync
import ru.begunok.health.HealthHistory
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal data class AnalyticsSyncState(
    val phase: Phase = Phase.Loading,
    val endpoint: String? = null,
    val usbFallback: Boolean = false,
    val lastSuccess: Instant? = null,
    val problem: String? = null,
) {
    enum class Phase { Loading, Disabled, Queued, Running, Synced, Retry, Failed }
}

internal class AnalyticsSync(
    private val context: Context,
    private val health: HealthAutoSync,
    private val local: LocalAnalyticsSnapshots,
) {
    private val files = AnalyticsSyncFiles(context)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, AnalyticsSyncJobService::class.java)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AnalyticsSyncState())
    val state = mutableState.asStateFlow()

    suspend fun start() = exclusive {
        val config = readConfiguration() ?: run {
            cancelJobs()
            return@exclusive
        }
        try {
            val progress = files.readProgress()
            val previous = scheduler.getPendingJob(PERIODIC_JOB)
            val alreadySynced = progress.destination == config.destination
            val lastSuccess = progress.lastSuccess.takeIf { alreadySynced }
            if (mutableState.value.phase == AnalyticsSyncState.Phase.Loading) {
                val phase = if (lastSuccess == null) AnalyticsSyncState.Phase.Queued else AnalyticsSyncState.Phase.Synced
                publish(config, phase, lastSuccess)
            }
            schedule(config, periodic = true)
            if (previous == null || !alreadySynced || progress.lastSuccess == null) schedule(config, periodic = false)
        } catch (_: Exception) {
            publish(config, AnalyticsSyncState.Phase.Failed, problem = "Не удалось запланировать синхронизацию.")
        }
    }

    suspend fun configure(endpoint: String, token: String, usbFallback: Boolean): String? {
        val config = try {
            AnalyticsConfiguration.parse(endpoint, token, ANALYTICS_USB_ENDPOINT.takeIf { usbFallback })
        } catch (_: IllegalArgumentException) {
            return "Проверьте адрес (HTTPS или USB) и токен отправки."
        }
        return changeConfiguration {
            try {
                files.writeConfig(config)
            } catch (_: Exception) {
                readConfiguration()?.let { queue(it) }
                return@changeConfiguration "Не удалось сохранить настройки на телефоне."
            }
            queue(config)
            null
        }
    }

    suspend fun disable() = changeConfiguration {
        files.removeConfig()
        cancelJobs()
        mutableState.value = AnalyticsSyncState(phase = AnalyticsSyncState.Phase.Disabled)
    }

    suspend fun force() = exclusive {
        val config = readConfiguration() ?: return@exclusive
        try {
            val progress = files.readProgress()
            progress.invalidate()
            files.writeProgress(progress)
            queue(config, replaceImmediate = true)
        } catch (_: Exception) {
            publish(config, AnalyticsSyncState.Phase.Failed, problem = "Не удалось подготовить принудительную отправку.")
        }
    }

    /** Returns true only for failures that need JobScheduler's exponential retry. */
    suspend fun run(): Boolean = exclusive {
        val config = readConfiguration() ?: return@exclusive false
        var lastSuccess = mutableState.value.lastSuccess
        publish(config, AnalyticsSyncState.Phase.Running, lastSuccess)
        try {
            val progress = files.readProgress()
            if (progress.destination != config.destination) {
                progress.destination = config.destination
                progress.invalidate()
                files.writeProgress(progress)
            }
            lastSuccess = progress.lastSuccess
            val rejected = export(config, progress)
            if (rejected != null) {
                publish(config, AnalyticsSyncState.Phase.Failed, lastSuccess, "Сервер отклонил отправку (HTTP $rejected).")
            } else {
                val now = Instant.now()
                progress.lastSuccess = now
                files.writeProgress(progress)
                publish(config, AnalyticsSyncState.Phase.Synced, now)
            }
            false
        } catch (cancelled: CancellationException) {
            publish(config, AnalyticsSyncState.Phase.Queued, lastSuccess)
            throw cancelled
        } catch (error: AnalyticsRejected) {
            publish(config, AnalyticsSyncState.Phase.Failed, lastSuccess, "Сервер отклонил отправку (HTTP ${error.status}).")
            false
        } catch (_: IOException) {
            publish(config, AnalyticsSyncState.Phase.Retry, lastSuccess, "Не удалось завершить отправку. Проверьте подключение к ПК.")
            true
        } catch (_: Exception) {
            publish(config, AnalyticsSyncState.Phase.Retry, lastSuccess, "Не удалось подготовить историю на телефоне.")
            true
        }
    }

    private suspend fun export(config: AnalyticsConfiguration, progress: AnalyticsProgress): Int? {
        val zone = ZoneId.systemDefault()
        val retained = health.analyticsDateBounds(zone)
        val personal = local.read(zone)
        val months = sortedSetOf<YearMonth>(reverseOrder())
        months.addAll(personal.keys)
        progress.partitions.keys.forEach { months.add(it.month) }
        // A wake date can cross a month after a timezone change, even if its daily row did not.
        val first = retained?.start?.minusDays(2)?.let(YearMonth::from)
        val last = retained?.endInclusive?.plusDays(2)?.let(YearMonth::from)
        if (first != null && last != null) {
            var month: YearMonth = first
            while (month <= last) {
                months.add(month)
                month = month.plusMonths(1)
            }
        }
        val delivery = AnalyticsDelivery(config)
        val empty = HealthHistory()
        var rejected: Int? = null
        for (month in months) {
            val history = if (first != null && last != null && month >= first && month <= last) {
                health.analyticsHistory(month.atDay(1).minusDays(2), month.atEndOfMonth().plusDays(2), zone)
            } else empty
            for (stream in AnalyticsStream.entries) {
                currentCoroutineContext().ensureActive()
                val partition = AnalyticsPartition(stream, month)
                val records = if (stream.health) healthRecords(history, stream, month, zone)
                    else personal[month]?.get(stream)?.asSequence() ?: emptySequence()
                val known = progress.partitions.containsKey(partition)
                val iterator = records.iterator()
                if (!known && !iterator.hasNext()) continue
                val batch = AnalyticsBatch(partition, iterator.asSequence())
                if (progress.partitions[partition] == batch.digest) continue
                if (!known) {
                    // Journal presence before POST: the server may commit before the phone receives the acknowledgement.
                    progress.partitions[partition] = null
                    files.writeProgress(progress)
                }
                try {
                    delivery.post(batch)
                    progress.partitions[partition] = batch.digest
                    files.writeProgress(progress)
                } catch (error: AnalyticsRejected) {
                    // A bad batch should not block other months; a bad token or endpoint would reject them all.
                    if (error.status !in setOf(400, 413, 422)) throw error
                    if (rejected == null) rejected = error.status
                }
            }
        }
        return rejected
    }

    private fun queue(config: AnalyticsConfiguration, replaceImmediate: Boolean = false) {
        publish(config, AnalyticsSyncState.Phase.Queued)
        try {
            schedule(config, periodic = true)
            schedule(config, periodic = false, replace = replaceImmediate)
        } catch (_: Exception) {
            publish(config, AnalyticsSyncState.Phase.Failed, problem = "Не удалось запланировать синхронизацию.")
        }
    }

    private fun readConfiguration(): AnalyticsConfiguration? {
        val config = try {
            files.readConfig()
        } catch (_: Exception) {
            mutableState.value = AnalyticsSyncState(phase = AnalyticsSyncState.Phase.Failed,
                problem = "Не удалось прочитать настройки синхронизации. Настройте подключение снова.")
            return null
        }
        if (config == null) mutableState.value = AnalyticsSyncState(phase = AnalyticsSyncState.Phase.Disabled)
        return config
    }

    private fun publish(config: AnalyticsConfiguration, phase: AnalyticsSyncState.Phase, lastSuccess: Instant? = null, problem: String? = null) {
        mutableState.value = AnalyticsSyncState(phase, config.endpoint.toString().trimEnd('/'), config.usbEndpoint != null, lastSuccess, problem)
    }

    private fun cancelJobs() {
        scheduler.cancel(IMMEDIATE_JOB)
        scheduler.cancel(PERIODIC_JOB)
    }

    private fun schedule(config: AnalyticsConfiguration, periodic: Boolean, replace: Boolean = false) {
        val id = if (periodic) PERIODIC_JOB else IMMEDIATE_JOB
        val existing = scheduler.getPendingJob(id)
        if (!replace && existing != null && existing.extras.getBoolean("usb") == config.supportsUsb) return
        val builder = JobInfo.Builder(id, service)
            .setPersisted(true)
            .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(PersistableBundle().apply { putBoolean("usb", config.supportsUsb) })
        if (!config.supportsUsb) builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        if (periodic) builder.setPeriodic(30 * 60_000L)
        else if (config.supportsUsb) builder.setMinimumLatency(1)
        check(scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS) { "Cannot schedule private analytics export" }
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private suspend fun <T> changeConfiguration(block: () -> T): T = withContext(NonCancellable + Dispatchers.IO) {
        // Stop pending/running transfers promptly, even if the Settings composition is leaving.
        cancelJobs()
        mutex.withLock { block() }
    }

    private companion object {
        const val PERIODIC_JOB = 7402
        const val IMMEDIATE_JOB = 7403
    }
}
