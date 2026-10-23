package ru.begunok.system

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import androidx.core.net.toUri

object SystemChecks {
    fun isBluetoothEnabled(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun isLocationEnabled(context: Context): Boolean =
        context.getSystemService(LocationManager::class.java)
            ?.let(LocationManagerCompat::isLocationEnabled) == true

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    @SuppressLint("BatteryLife")
    fun openBatteryOptimizationSettings(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData("package:${context.packageName}".toUri())
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        context.startSafely(request, fallback)
    }

    fun openLocationSettings(context: Context) =
        context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    fun openAppSettings(context: Context) = context.startSafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:${context.packageName}".toUri())
    )

    fun openUrl(context: Context, url: String) =
        context.startSafely(Intent(Intent.ACTION_VIEW, url.toUri()))

    private fun Context.startSafely(vararg intents: Intent) {
        for (intent in intents) {
            if (this !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }
}
