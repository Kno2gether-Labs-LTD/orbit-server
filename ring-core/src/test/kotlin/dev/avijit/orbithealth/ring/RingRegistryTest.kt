package dev.avijit.orbithealth.ring

import dev.avijit.orbithealth.ring.fake.FakeFamily
import dev.avijit.orbithealth.ring.fake.FakeRingTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RingRegistryTest {
    private fun adv(name: String?, vararg services: String) = RingAdvertisement(name, "AA:BB", services.toList(), rssi = -60)

    @Test
    fun registryReportsUnsupportedForUnknownRing() {
        val registry = RingRegistry(listOf(FakeFamily()))
        val colmi = adv("R02_1A2B")
        assertEquals(RingIdentity.Unsupported, registry.identify(colmi))
        assertTrue("listed as not supported yet", registry.looksLikeRing(colmi))
        assertTrue(registry.looksLikeRing(adv("Galaxy Ring (3F)")))
        assertTrue(registry.looksLikeRing(adv("Oura")))
        // Other BLE devices are neither supported nor listed.
        assertEquals(RingIdentity.Unsupported, registry.identify(adv("Living Room TV")))
        assertFalse(registry.looksLikeRing(adv("Living Room TV")))
        assertFalse(registry.looksLikeRing(adv("Pixel Buds Pro")))
        assertFalse(registry.looksLikeRing(adv(null)))
    }

    @Test
    fun ringCountsOnlyAsAWholeWord() {
        val registry = RingRegistry(listOf(FakeFamily()))
        assertTrue(registry.looksLikeRing(adv("Smart Ring")))
        assertTrue(registry.looksLikeRing(adv("RingConn Gen 2")))
        assertTrue(registry.looksLikeRing(adv("R06 C0FF")))
        assertFalse("hidden", registry.looksLikeRing(adv("Ringo's iPhone")))
        assertFalse(registry.looksLikeRing(adv("Bluering speaker")))
        assertFalse(registry.looksLikeRing(adv("Earring box")))
    }

    @Test
    fun vendorAppNameComesFromTheFamilyThatOwnsThePackage() {
        val a = FakeFamily(id = "a", vendorAppPackages = setOf("com.vendor.a"), vendorAppName = "Vendor A")
        val b = FakeFamily(id = "b", vendorAppPackages = setOf("com.vendor.b"))
        val registry = RingRegistry(listOf(a, b))
        assertEquals("Vendor A", registry.vendorAppName("com.vendor.a"))
        assertEquals(null, registry.vendorAppName("com.vendor.b")) // the family names no app: generic copy
        assertEquals(null, registry.vendorAppName("com.unknown"))
        assertEquals(null, registry.vendorAppName(null))
    }

    @Test
    fun registryPrefersHigherConfidence() {
        val low = FakeFamily(id = "low", namePrefix = "RING", confidence = 30)
        val high = FakeFamily(id = "high", namePrefix = "RING", confidence = 80)
        val id = RingRegistry(listOf(low, high)).identify(adv("RING 01")) as RingIdentity.Supported
        assertSame(high, id.family)
        assertEquals(80, id.match.confidence)
        // Equal confidence: registration order wins.
        val a = FakeFamily(id = "a", namePrefix = "RING", confidence = 50)
        val b = FakeFamily(id = "b", namePrefix = "RING", confidence = 50)
        assertSame(a, (RingRegistry(listOf(a, b)).identify(adv("RING 01")) as RingIdentity.Supported).family)
    }

    @Test
    fun familyLookupReopensPairedRing() {
        val fake = FakeFamily()
        val registry = RingRegistry(listOf(fake))
        assertSame(fake, registry.family("fake"))
        assertNull(registry.family("ycbt"))
        assertEquals(1, fake.createDriver(FakeRingTransport()).let { fake.drivers.size })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateFamilyIdsRejected() {
        RingRegistry(listOf(FakeFamily(id = "x"), FakeFamily(id = "x")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun confidenceMustBeInRange() {
        RingMatch("x", 101)
    }

    @Test
    fun advertisementServiceMatchIgnoresCase() {
        val a = adv("x", "0000FEE7-0000-1000-8000-00805F9B34FB")
        assertTrue(a.hasService(RingAdvertisement.sigUuid("fee7")))
    }

    @Test
    fun vendorPackagesComeFromThePairedFamily() {
        val a = FakeFamily(id = "a", vendorAppPackages = setOf("com.vendor.a"))
        val b = FakeFamily(id = "b", vendorAppPackages = setOf("com.vendor.b"))
        val registry = RingRegistry(listOf(a, b))
        assertEquals(setOf("com.vendor.b"), registry.vendorAppPackagesFor("b"))
        // Nothing paired (or a family this build lacks): every family's apps count, the cautious choice.
        assertEquals(setOf("com.vendor.a", "com.vendor.b"), registry.vendorAppPackagesFor(null))
        assertEquals(setOf("com.vendor.a", "com.vendor.b"), registry.vendorAppPackagesFor("gone"))
        assertEquals(emptySet<String>(), RingRegistry(listOf(FakeFamily())).vendorAppPackagesFor("fake"))
    }

    @Test
    fun gattCharsAreLowerCaseAndProfilesMayBeWriteLess() {
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", GattChar.sig("180F", "2A19").service)
        try {
            GattChar("0000FEE7-0000-1000-8000-00805F9B34FB", "x")
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
        val notifyOnly = GattProfile(writes = emptyList(), notifies = listOf(GattChar.sig("180d", "2a37")), writeWithResponse = false)
        assertNull(notifyOnly.defaultWrite)
    }
}
