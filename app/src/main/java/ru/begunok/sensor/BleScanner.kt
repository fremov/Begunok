package ru.begunok.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import ru.begunok.system.Permissions

class ScanFailedException(val code: Int) : Exception("BLE scan failed: $code")

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)

    fun scanHeartRateSensors(): Flow<ScanResult> =
        scan(ScanFilter.Builder().setServiceUuid(ParcelUuid(GattUuids.HEART_RATE_SERVICE)).build())

    fun scanAddress(address: String): Flow<ScanResult> =
        scan(ScanFilter.Builder().setDeviceAddress(address).build())

    private fun scan(filter: ScanFilter): Flow<ScanResult> = callbackFlow {
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || !Permissions.canScan(context)) {
            close(ScanFailedException(-1))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { trySend(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                close(ScanFailedException(errorCode))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
        } catch (e: SecurityException) {
            close(e)
        }
        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }
}
