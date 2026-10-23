package ru.begunok.sensor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import ru.begunok.system.Permissions
import java.util.UUID

@SuppressLint("MissingPermission")
class BleHeartRateClient(
    private val context: Context,
    private val scanner: BleScanner,
) {
    interface Listener {
        fun onStatus(status: SensorStatus)
        fun onDeviceName(name: String)
        fun onBattery(level: Int)
        fun onMeasurement(measurement: HeartRateMeasurement)
    }

    private sealed interface Event {
        data object Connected : Event
        data class Disconnected(val status: Int) : Event
        data class ServicesDiscovered(val success: Boolean) : Event
        class Read(val uuid: UUID, val value: ByteArray) : Event
        data class DescriptorWritten(val success: Boolean) : Event
        class Notify(val uuid: UUID, val value: ByteArray) : Event
    }

    private class LinkLost : Exception()

    private val manager = context.getSystemService(BluetoothManager::class.java)

    suspend fun run(address: String, listener: Listener) {
        var everConnected = false
        while (true) {
            val adapter = manager?.adapter
            when {
                adapter == null -> {
                    listener.onStatus(SensorStatus.Unavailable)
                    delay(5_000)
                    continue
                }
                !Permissions.canConnect(context) -> {
                    listener.onStatus(SensorStatus.NoPermission)
                    delay(2_000)
                    continue
                }
                !adapter.isEnabled -> {
                    listener.onStatus(SensorStatus.BluetoothOff)
                    delay(2_000)
                    continue
                }
            }
            listener.onStatus(if (everConnected) SensorStatus.Lost else SensorStatus.Searching)
            val device = findDevice(adapter, address)
            if (session(device, listener)) everConnected = true
            delay(1_000)
        }
    }

    private suspend fun findDevice(adapter: BluetoothAdapter, address: String): BluetoothDevice {
        val found = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
            scanner.scanAddress(address)
                .catch { }
                .firstOrNull()
                ?.device
        }
        return found ?: adapter.getRemoteDevice(address)
    }

    private suspend fun session(device: BluetoothDevice, listener: Listener): Boolean {
        val events = Channel<Event>(Channel.UNLIMITED)
        @Suppress("DEPRECATION")
        val gatt = device.connectGatt(context, false, GattCallback(events), BluetoothDevice.TRANSPORT_LE)
            ?: return false
        var connected = false
        try {
            events.await(CONNECT_TIMEOUT_MS, listener) { it is Event.Connected } ?: return false
            device.name?.let(listener::onDeviceName)
            delay(300)
            gatt.discoverServices()
            val discovered = events.await(DISCOVERY_TIMEOUT_MS, listener) { it is Event.ServicesDiscovered }
            if ((discovered as? Event.ServicesDiscovered)?.success != true) return false

            val hrCharacteristic = gatt.getService(GattUuids.HEART_RATE_SERVICE)
                ?.getCharacteristic(GattUuids.HEART_RATE_MEASUREMENT)
                ?: return false
            val batteryCharacteristic = gatt.getService(GattUuids.BATTERY_SERVICE)
                ?.getCharacteristic(GattUuids.BATTERY_LEVEL)

            if (batteryCharacteristic != null && gatt.readCharacteristic(batteryCharacteristic)) {
                events.await(OPERATION_TIMEOUT_MS, listener) { it is Event.Read }
            }

            gatt.setCharacteristicNotification(hrCharacteristic, true)
            val cccd = hrCharacteristic.getDescriptor(GattUuids.CCCD) ?: return false
            gatt.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            events.await(OPERATION_TIMEOUT_MS, listener) { it is Event.DescriptorWritten }

            connected = true
            listener.onStatus(SensorStatus.Connected)

            var nextBatteryRead = System.currentTimeMillis() + BATTERY_POLL_MS
            while (true) {
                events.await(BATTERY_POLL_MS, listener) { false }
                if (batteryCharacteristic != null && System.currentTimeMillis() >= nextBatteryRead) {
                    gatt.readCharacteristic(batteryCharacteristic)
                    nextBatteryRead = System.currentTimeMillis() + BATTERY_POLL_MS
                }
            }
        } catch (_: LinkLost) {
            return connected
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
    }

    private suspend fun Channel<Event>.await(
        timeoutMs: Long,
        listener: Listener,
        predicate: (Event) -> Boolean,
    ): Event? = withTimeoutOrNull(timeoutMs) {
        var result: Event
        do {
            result = receive()
            when (result) {
                is Event.Disconnected -> throw LinkLost()
                is Event.Notify -> if (result.uuid == GattUuids.HEART_RATE_MEASUREMENT) {
                    HeartRateParser.parse(result.value)?.let(listener::onMeasurement)
                }
                is Event.Read -> if (result.uuid == GattUuids.BATTERY_LEVEL && result.value.isNotEmpty()) {
                    listener.onBattery(result.value[0].toInt() and 0xFF)
                }
                else -> Unit
            }
        } while (!predicate(result))
        result
    }

    @Suppress("DEPRECATION")
    private fun BluetoothGatt.writeDescriptorCompat(descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeDescriptor(descriptor, value)
        } else {
            descriptor.value = value
            writeDescriptor(descriptor)
        }
    }

    private class GattCallback(private val events: Channel<Event>) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                events.trySend(Event.Connected)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                events.trySend(Event.Disconnected(status))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            events.trySend(Event.ServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS))
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            events.trySend(Event.Read(characteristic.uuid, value))
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                events.trySend(Event.Read(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0)))
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            events.trySend(Event.Notify(characteristic.uuid, value))
        }

        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                events.trySend(Event.Notify(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0)))
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            events.trySend(Event.DescriptorWritten(status == BluetoothGatt.GATT_SUCCESS))
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 12_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVERY_TIMEOUT_MS = 15_000L
        const val OPERATION_TIMEOUT_MS = 5_000L
        const val BATTERY_POLL_MS = 10 * 60_000L
    }
}
