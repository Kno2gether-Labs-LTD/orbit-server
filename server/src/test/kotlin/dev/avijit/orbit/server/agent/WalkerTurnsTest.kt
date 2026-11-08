package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.api.MutableClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkerTurnsTest {
    private fun state() = WalkerState(mutableListOf(Msg.User("hi")))

    @Test fun fullStoreEvictsExpiredOldestFirstElseRefuses() {
        val clock = MutableClock(1_000)
        val dropped = mutableListOf<String>()
        val turns = WalkerTurns(clock, maxPending = 2, ttlMs = 100) { dropped += it.id }
        val a = turns.put("d", "D", state(), emptyList(), "UTC")!!
        clock.advance(10)
        val b = turns.put("d", "D", state(), emptyList(), "UTC")!!
        assertNull("full of live turns", turns.put("d", "D", state(), emptyList(), "UTC"))
        clock.advance(95) // a expired, b live
        val c = turns.put("d", "D", state(), emptyList(), "UTC")
        assertNotNull(c)
        assertEquals(listOf(a), dropped)
        assertEquals(2, turns.size)
        assertTrue(turns.claim(b, "d") { true } is WalkerTurns.Claim.Ok)
        assertTrue(turns.claim(b, "d") { true } is WalkerTurns.Claim.Unknown)
    }

    @Test fun claimChecksDeviceExpiryAndAcceptance() {
        val clock = MutableClock(0)
        val turns = WalkerTurns(clock, ttlMs = 100)
        val id = turns.put("d1", "D1", state(), emptyList(), "UTC")!!
        assertTrue(Regex("[A-Za-z0-9_-]{22}").matches(id))
        assertTrue(turns.claim(id, "d2") { true } is WalkerTurns.Claim.Unknown)
        assertTrue(turns.claim(id, "d1") { false } is WalkerTurns.Claim.Rejected)
        clock.advance(100)
        assertTrue(turns.claim(id, "d1") { true } is WalkerTurns.Claim.Expired)
        assertEquals(0, turns.size)
    }

    @Test fun oneDeviceCannotCrowdOutOthers() {
        val clock = MutableClock(0)
        val dropped = mutableListOf<String>()
        val turns = WalkerTurns(clock, maxPending = 6, maxPerDevice = 2) { dropped += it.id }
        val other = turns.put("good", "G", state(), emptyList(), "UTC")!!
        val ids = (1..5).map { clock.advance(1); turns.put("noisy", "N", state(), emptyList(), "UTC")!! }
        assertEquals(3, turns.size) // the other device's turn + the noisy device's newest two
        assertEquals(ids.take(3), dropped)
        assertTrue(turns.claim(other, "good") { true } is WalkerTurns.Claim.Ok)
        assertTrue(turns.claim(ids.last(), "noisy") { true } is WalkerTurns.Claim.Ok)
    }
}
