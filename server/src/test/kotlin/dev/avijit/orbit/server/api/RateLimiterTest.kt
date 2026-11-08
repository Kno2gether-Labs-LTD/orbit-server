package dev.avijit.orbit.server.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {
    @Test fun keysAreLimitedSeparately() {
        val clock = MutableClock(T0)
        val l = RateLimiter(2, 60_000, clock)
        assertTrue(l.tryAcquire("a")); assertTrue(l.tryAcquire("a"))
        assertFalse(l.tryAcquire("a"))
        assertTrue("another key has its own budget", l.tryAcquire("b"))
        clock.advance(60_001)
        assertTrue("the window slides", l.tryAcquire("a"))
    }

    @Test fun overflowEvictsOnlyTheLeastRecentlyUsedKey() {
        val clock = MutableClock(T0)
        val l = RateLimiter(1, 60_000, clock, maxKeys = 3)
        assertTrue(l.tryAcquire("victim"))
        assertTrue(l.tryAcquire("a"))
        assertTrue(l.tryAcquire("b"))
        assertFalse(l.tryAcquire("victim")) // limited, and now the most recently used
        assertTrue(l.tryAcquire("c")) // full: evicts "a", not everything
        assertFalse("an overflow must never reset other keys' limits", l.tryAcquire("victim"))
        assertFalse(l.tryAcquire("b"))
        assertFalse(l.tryAcquire("c"))
    }

    @Test fun overflowDropsStaleKeysFirst() {
        val clock = MutableClock(T0)
        val l = RateLimiter(1, 60_000, clock, maxKeys = 2)
        assertTrue(l.tryAcquire("old"))
        clock.advance(30_000)
        assertTrue(l.tryAcquire("limited"))
        clock.advance(30_001) // "old" is now outside the window
        assertTrue(l.tryAcquire("new"))
        assertFalse("the live bucket survives; the stale one was swept", l.tryAcquire("limited"))
    }

    @Test fun refundReturnsTheLastAttempt() {
        val l = RateLimiter(1, 60_000, MutableClock(T0))
        assertTrue(l.tryAcquire("ip"))
        l.refund("ip")
        assertTrue(l.tryAcquire("ip"))
        assertFalse(l.tryAcquire("ip"))
    }

    @Test fun ipv6PeersAreKeyedBySlash64() {
        assertEquals(RateLimiter.clientKey("2001:db8:1:2::1"), RateLimiter.clientKey("2001:db8:1:2:ffff:eeee:dddd:9"))
        assertEquals(RateLimiter.clientKey("2001:db8:1:2::1"), RateLimiter.clientKey("[2001:db8:1:2::77]"))
        assertNotEquals(RateLimiter.clientKey("2001:db8:1:2::1"), RateLimiter.clientKey("2001:db8:1:3::1"))
        assertEquals("192.168.1.5", RateLimiter.clientKey("192.168.1.5"))
        assertEquals("IPv4-mapped IPv6 is the IPv4 peer", "192.168.1.5", RateLimiter.clientKey("::ffff:192.168.1.5"))
        assertNotEquals(RateLimiter.clientKey("192.168.1.5"), RateLimiter.clientKey("192.168.1.6"))
        assertEquals("non-literals pass through", "localhost", RateLimiter.clientKey("localhost"))
    }
}
