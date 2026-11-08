package dev.avijit.orbithealth.ring

/**
 * One GATT characteristic, addressed by its service and its own UUID (the same characteristic UUID may exist in two
 * services). UUIDs are full 128-bit lower-case strings, e.g. "0000180f-0000-1000-8000-00805f9b34fb", so :ring-core
 * stays pure JVM; the app parses them. Use [sig] for 16-bit SIG UUIDs.
 */
data class GattChar(val service: String, val characteristic: String) {
    init {
        require(service == service.lowercase() && characteristic == characteristic.lowercase()) {
            "GattChar UUIDs must be lower case: $service / $characteristic"
        }
    }

    companion object {
        /** A characteristic from 16-bit SIG UUIDs, e.g. `sig("180f", "2a19")` for battery level. */
        fun sig(service16: String, characteristic16: String) =
            GattChar(RingAdvertisement.sigUuid(service16), RingAdvertisement.sigUuid(characteristic16))
    }
}

/**
 * How a family talks over GATT. A profile may span several services (e.g. a command service plus a separate
 * big-data service), and a ring may be write-less (notify/read only, e.g. standard Heart Rate + Battery services).
 */
data class GattProfile(
    /** Characteristics commands can be written to; the first is the default for [RingTransport.write]. Empty = no writes. */
    val writes: List<GattChar>,
    /** Characteristics to subscribe to after connecting, in order (indications when offered, else notifications). */
    val notifies: List<GattChar>,
    /** Write with response (true) or without response (false). */
    val writeWithResponse: Boolean,
    /** MTU to request after connecting (a hint; the link works with whatever the phone grants). */
    val mtu: Int = DEFAULT_MTU,
) {
    /** Where [RingTransport.write] without a characteristic goes; null for a write-less ring. */
    val defaultWrite: GattChar? get() = writes.firstOrNull()

    companion object {
        const val DEFAULT_MTU = 247
    }
}
