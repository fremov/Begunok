package ru.begunok

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import ru.begunok.alerts.VoicePack
import ru.begunok.data.ReadingRepository
import ru.begunok.data.FinanceRepository
import ru.begunok.data.MentalRepository
import ru.begunok.data.MathRepository
import ru.begunok.data.SettingsRepository
import ru.begunok.data.ShowerRepository
import ru.begunok.data.WeightRepository
import ru.begunok.data.WorkoutRepository
import ru.begunok.data.db.AppDatabase
import ru.begunok.health.HealthRepository
import ru.begunok.location.LocationClient
import ru.begunok.lol.LolSettings
import ru.begunok.reminders.ReminderScheduler
import ru.begunok.reminders.MentalReminderScheduler
import ru.begunok.sensor.BleScanner
import ru.begunok.sensor.HeartRateMonitor

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(appContext)
    private val reminderScheduler = ReminderScheduler(appContext)
    val bleScanner = BleScanner(appContext)
    val mentalReminderScheduler = MentalReminderScheduler(appContext)
    val heartRateMonitor = HeartRateMonitor(appContext, settings, bleScanner, appScope)
    val locationClient = LocationClient(appContext)
    private val database = AppDatabase.create(appContext)
    val workouts = WorkoutRepository(database.workoutDao())
    val showers = ShowerRepository(database.showerDao())
    val reading = ReadingRepository(database.readingDao())
    val weight = WeightRepository(database.weightDao())
    val mental = MentalRepository(database.mentalDao())
    val finance = FinanceRepository(database.financeDao())
    val math = MathRepository(appContext)
    val health = HealthRepository(appContext)
    val voicePack = VoicePack(appContext)
    val lolSettings = LolSettings(appContext)

    init {
        appScope.launch {
            settings.settings.collect { reminderScheduler.schedule(it) }
        }
        appScope.launch {
            combine(mental.observeResults(), settings.settings) { results, prefs ->
                results to prefs.mentalReminderEnabled
            }.collect { (results, enabled) ->
                mentalReminderScheduler.schedule(results, enabled)
            }
        }
    }
}
