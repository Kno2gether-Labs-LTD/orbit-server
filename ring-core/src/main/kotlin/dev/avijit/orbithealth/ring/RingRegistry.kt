package dev.avijit.orbithealth.ring

/** The outcome of [RingRegistry.identify]. */
sealed interface RingIdentity {
    /** A registered family claims the ring. */
    data class Supported(val family: RingFamily, val match: RingMatch) : RingIdentity

    /** No registered family claims it. Never connect to it. */
    data object Unsupported : RingIdentity
}

/**
 * Every ring family the app knows. Built once by the app (AppGraph); adding a ring adds one family to the list.
 */
class RingRegistry(val families: List<RingFamily>) {
    init {
        val dupes = families.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) { "duplicate ring family ids: $dupes" }
    }

    /** The family with the most confident match ([families] order breaks ties), or [RingIdentity.Unsupported]. */
    fun identify(advertisement: RingAdvertisement): RingIdentity {
        var best: RingIdentity.Supported? = null
        for (family in families) {
            val m = family.match(advertisement) ?: continue
            if (best == null || m.confidence > best.match.confidence) best = RingIdentity.Supported(family, m)
        }
        return best ?: RingIdentity.Unsupported
    }

    /** The family with [id], to re-open a paired ring; null if this build doesn't have it. */
    fun family(id: String): RingFamily? = families.firstOrNull { it.id == id }

    /**
     * Vendor app packages that put the paired ring in COEXISTENCE: those of the family [pairedFamilyId], or, when no
     * ring is paired yet or the family is unknown, those of every family (the cautious choice).
     */
    fun vendorAppPackagesFor(pairedFamilyId: String?): Set<String> =
        pairedFamilyId?.let(::family)?.vendorAppPackages ?: families.flatMapTo(mutableSetOf()) { it.vendorAppPackages }

    /** The [RingFamily.vendorAppName] of the family that owns vendor app [packageName]; null if unknown. */
    fun vendorAppName(packageName: String?): String? =
        packageName?.let { p -> families.firstOrNull { p in it.vendorAppPackages }?.vendorAppName }

    /**
     * Whether an advertisement no family claims still looks like a smart ring, so the scan can list it as
     * "not supported yet" instead of hiding it. Name-based heuristic; other BLE devices (phones, TVs, earbuds) are
     * ignored.
     */
    fun looksLikeRing(advertisement: RingAdvertisement): Boolean {
        val name = advertisement.name?.trim().orEmpty()
        return name.isNotEmpty() && RING_NAME.containsMatchIn(name)
    }

    companion object {
        /**
         * Minimum [RingMatch.confidence] for pairing to pick a ring by itself. Weaker matches (e.g. a shared service
         * UUID only) are still listed and can be chosen by tapping them.
         */
        const val AUTO_PICK_CONFIDENCE = 50

        /**
         * Words and model patterns rings commonly advertise ("Galaxy Ring", "Oura", "R02_1A2B", "Circular", "RingConn").
         * "ring" counts only as a whole word, so "Ringo's iPhone" or "Bluering speaker" stay hidden.
         */
        private val RING_NAME = Regex("""(?i)\bring\b|\bringconn|\boura|\bcircular|^R\d{2}([_ \-]|$)""")
    }
}
