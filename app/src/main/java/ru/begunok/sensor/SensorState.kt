package ru.begunok.sensor

import java.util.UUID

enum class SensorStatus(val label: String) {
    NotSelected("датчик не выбран"),
    Unavailable("Bluetooth недоступен"),
    NoPermission("нет разрешения Bluetooth"),
    BluetoothOff("Bluetooth выключен"),
    Searching("поиск"),
    Connected("подключён"),
    Lost("потеряна связь"),
    Simulation("симуляция"),
}

data class SensorState(
    val status: SensorStatus = SensorStatus.NotSelected,
    val name: String? = null,
    val address: String? = null,
    val battery: Int? = null,
    val heartRate: Int? = null,
) {
    val isReceiving: Boolean
        get() = (status == SensorStatus.Connected || status == SensorStatus.Simulation) && heartRate != null
}

object GattUuids {
    val HEART_RATE_SERVICE: UUID = uuid16(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = uuid16(0x2A37)
    val BATTERY_SERVICE: UUID = uuid16(0x180F)
    val BATTERY_LEVEL: UUID = uuid16(0x2A19)
    val CCCD: UUID = uuid16(0x2902)

    private fun uuid16(value: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", value))
}
