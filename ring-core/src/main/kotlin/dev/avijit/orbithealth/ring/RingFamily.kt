package dev.avijit.orbithealth.ring

/**
 * One ring family (one protocol), e.g. YCBT. Each family lives in its own module and is registered once in the app's
 * [RingRegistry]. Adding a ring = a new module implementing this + one line in the registry.
 */
interface RingFamily {
    /** Stable id stored with the paired ring (e.g. "ycbt"). Never change it once shipped. */
    val id: String
    /** Human name of the family, e.g. "Yucheng (YCBT) rings". */
    val displayName: String
    /** How to talk to this family over GATT. */
    val gattProfile: GattProfile

    /**
     * Package names of the vendor's own phone app(s). While one is installed the app runs in COEXISTENCE: it never
     * clears ring history and lets go of the ring after each sync. Each package also needs a
     * `<queries><package android:name="…"/></queries>` line in app/src/main/AndroidManifest.xml, or Android 11+
     * hides it and it always looks uninstalled. Empty when the ring has no vendor app.
     */
    val vendorAppPackages: Set<String>

    /**
     * The vendor app's name as people know it (e.g. "SmartHealth"), for the coexistence banner and captions; null when
     * unknown, and the app then says "your ring's own app".
     */
    val vendorAppName: String? get() = null

    /** Whether [advertisement] is one of this family's rings, with the model name and confidence; null = not ours. */
    fun match(advertisement: RingAdvertisement): RingMatch?

    /** A driver for a connected ring of this family. */
    fun createDriver(transport: RingTransport): RingDriver
}

/** A family's claim on an advertisement. Higher [confidence] wins when several families match. */
data class RingMatch(val modelName: String, val confidence: Int) {
    init {
        require(confidence in 0..100) { "confidence must be 0..100, was $confidence" }
    }
}
