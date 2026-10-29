package ru.begunok

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.begunok.alerts.VoicePack
import ru.begunok.data.ReadingRepository
import ru.begunok.data.SettingsRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.AppDatabase
import ru.begunok.health.HealthRepository
import ru.begunok.location.LocationClient
import ru.begunok.sensor.BleScanner
import ru.begunok.sensor.HeartRateMonitor

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(appContext)
    val bleScanner = BleScanner(appContext)
    val heartRateMonitor = HeartRateMonitor(appContext, settings, bleScanner, appScope)
    val locationClient = LocationClient(appContext)
    private val database = AppDatabase.create(appContext)
    val workouts = WorkoutRepository(database.workoutDao())
    val showers = ShowerRepository(database.showerDao())
    val reading = ReadingRepository(database.readingDao())
    val health = HealthRepository(appContext)
    val voicePack = VoicePack(appContext)
}
