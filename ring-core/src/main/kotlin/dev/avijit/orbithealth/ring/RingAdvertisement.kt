package dev.avijit.orbithealth.ring

/**
 * One BLE advertisement, as plain data so families can be matched and unit-tested without Android.
 *
 * [serviceUuids] are full 128-bit UUID strings; compare them with [hasService] (case-insensitive).
 */
data class RingAdvertisement(
    /** The advertised local name, or null when the advertisement carries none. */
    val name: String?,
    val address: String,
    val serviceUuids: List<String> = emptyList(),
    /** Manufacturer-specific data keyed by Bluetooth SIG company id. */
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val rssi: Int,
) {
    fun hasService(uuid: String): Boolean = serviceUuids.any { it.equals(uuid, ignoreCase = true) }

    // ByteArray values: compare by content so adverts are usable as test values.
    override fun equals(other: Any?): Boolean = other is RingAdvertisement && name == other.name &&
        address == other.address && serviceUuids == other.serviceUuids && rssi == other.rssi &&
        manufacturerData.keys == other.manufacturerData.keys &&
        manufacturerData.all { (k, v) -> other.manufacturerData[k]?.contentEquals(v) == true }

    override fun hashCode(): Int = listOf(name, address, serviceUuids, rssi, manufacturerData.keys).hashCode()

    companion object {
        /** Expands a 16-bit SIG UUID ("fee7") to its full 128-bit form. */
        fun sigUuid(short16: String): String = "0000${short16.lowercase()}-0000-1000-8000-00805f9b34fb"
    }
}
