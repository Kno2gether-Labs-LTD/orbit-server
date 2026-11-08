package dev.avijit.orbithealth.ring

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/** One notification/indication payload and the characteristic it came from. */
class RingPacket(val char: GattChar, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is RingPacket && char == other.char && bytes.contentEquals(other.bytes)
    override fun hashCode() = 31 * char.hashCode() + bytes.contentHashCode()
    override fun toString() = "RingPacket(${char.characteristic}, ${bytes.joinToString("") { "%02x".format(it) }})"
}

interface RingTransport {
    /**
     * Every notification/indication, tagged with its characteristic. Must be hot (e.g. a SharedFlow): replies
     * produced while [write] runs must reach collectors that subscribed before the write. Drivers subscribe
     * (undispatched) right before writing, so a cold flow or a callbackFlow that registers lazily would lose replies.
     */
    val packets: Flow<RingPacket>

    /** [packets] without the tag, for single-channel protocols (e.g. YCBT). Hot whenever [packets] is. */
    val incoming: Flow<ByteArray> get() = packets.map { it.bytes }

    /** Writes to the profile's default write characteristic. Throws if the profile has none. */
    suspend fun write(bytes: ByteArray)

    /** Writes to [char] (one of the profile's writes) with the profile's write type. */
    suspend fun write(char: GattChar, bytes: ByteArray)

    /** Reads [char] once (e.g. battery level 0x180F/0x2A19). Transports that can't read throw UnsupportedOperationException. */
    suspend fun read(char: GattChar): ByteArray

    val connected: StateFlow<Boolean>
}
