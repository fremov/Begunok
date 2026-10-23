package ru.begunok.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    val bluetooth: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }

    val location: Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val notifications: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allGranted(context: Context, permissions: Array<String>): Boolean =
        permissions.all { granted(context, it) }

    fun canConnect(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun canScan(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasFineLocation(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_FINE_LOCATION)
}
