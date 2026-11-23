package ru.begunok

import android.content.Context
import android.provider.Settings
import ru.begunok.activity.ActivityCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ru.begunok.alerts.VoicePack
import ru.begunok.analytics.AnalyticsExport
import ru.begunok.analytics.LocalAnalyticsSnapshots
import ru.begunok.data.ReadingRepository
import ru.begunok.data.FinanceRepository
import ru.begunok.data.MathRepository
import ru.begunok.data.SettingsRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WeightRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.AppDatabase
import ru.begunok.health.HealthRepository
import ru.begunok.health.HealthAutoSync
import ru.begunok.location.LocationClient
import ru.begunok.lol.LolSettings
import ru.begunok.reminders.ReminderScheduler
import ru.begunok.sensor.BleScanner
import ru.begunok.sensor.HeartRateMonitor

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(appContext)
    private val reminderScheduler = ReminderScheduler(appContext)
    val bleScanner = BleScanner(appContext)
    val heartRateMonitor = HeartRateMonitor(appContext, settings, bleScanner, appScope)
    val locationClient = LocationClient(appContext)
    private val database = AppDatabase.create(appContext)
    val workouts = WorkoutRepository(database.workoutDao())
    val showers = ShowerRepository(database.showerDao())
    val reading = ReadingRepository(database.readingDao())
    val weight = WeightRepository(database.weightDao())
    val finance = FinanceRepository(database.financeDao())
    val math = MathRepository(appContext)
    val health = HealthRepository(appContext)
    val healthAutoSync = HealthAutoSync(appContext, health, settings)
    internal val analyticsExport = AnalyticsExport(healthAutoSync, LocalAnalyticsSnapshots(showers, weight))
    internal val activityCalendar = ActivityCalendar(workouts, reading, math, showers, healthAutoSync, database.pcCalendarDao()) {
        checkNotNull(Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID))
    }
    val voicePack = VoicePack(appContext)
    val lolSettings = LolSettings(appContext)

    init {
        healthAutoSync.schedule()
        appScope.launch {
            settings.settings.collect { reminderScheduler.schedule(it) }
        }
    }
}
