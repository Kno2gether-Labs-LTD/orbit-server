package dev.avijit.orbit.server

import dev.avijit.orbit.server.api.MutableClock
import dev.avijit.orbit.server.api.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class SetupCodeTest {
    private fun env(block: (Store, SetupCode, MutableClock) -> Unit) = withTempDir { dir ->
        Store(ServerHome(dir).dbFile).use { store ->
            val clock = MutableClock(T0)
            block(store, SetupCode(store, clock), clock)
        }
    }

    @Test fun codeIsHumanFriendlyAndAtLeast128Bits() = env { _, setup, _ ->
        val code = setup.issue().code
        assertTrue(code, Regex("[0-9A-HJKMNP-TV-Z]{5}(-[0-9A-HJKMNP-TV-Z]{5}){5}").matches(code))
        assertTrue("entropy ${SetupCode.BITS}", SetupCode.BITS >= 128)
        val codes = (1..50).map { setup.issue().code }.toSet()
        assertEquals("random", 50, codes.size)
    }

    @Test fun onlyAHashAndExpiryAreStored() = env { store, setup, _ ->
        val issued = setup.issue()
        assertEquals(T0 + 24 * 3600_000L, issued.expiresMs)
        val stored = store.getSetting(Settings.SETUP_CODE_HASH)!!
        val bare = issued.code.replace("-", "")
        assertFalse(stored.contains(bare))
        assertNotEquals(issued.code, stored)
        assertEquals(Secrets.sha256Hex(bare), stored)
        assertEquals((T0 + SetupCode.TTL_MS).toString(), store.getSetting(Settings.SETUP_CODE_EXPIRES))
    }

    @Test fun inputIsNormalised() = env { _, setup, _ ->
        val code = setup.issue().code
        assertTrue(setup.matches(code))
        assertTrue(setup.matches(code.lowercase()))
        assertTrue(setup.matches(" " + code.replace("-", " ") + " "))
        assertTrue(setup.matches(code.replace("-", "")))
        // Crockford look-alikes: O→0, I/L→1.
        assertTrue(setup.matches(code.replace('0', 'O').replace('1', 'I')))
        assertFalse(setup.matches(code.dropLast(1)))
        assertFalse(setup.matches(code.dropLast(1) + if (code.last() == 'A') "B" else "A"))
        assertFalse(setup.matches(""))
        assertFalse(setup.matches("x".repeat(10_000)))
        assertNull(SetupCode.normalize("ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z!!!!"))
    }

    @Test fun setupCodeIsSingleUse() = env { store, setup, _ ->
        val code = setup.issue().code
        assertEquals(SetupCode.Outcome.BAD_CODE, setup.complete("WRONG-CODE0-00000-00000-00000-00000", "\$2a\$04\$hash"))
        assertNull(store.getSetting(Settings.ADMIN_HASH))
        assertEquals(SetupCode.Outcome.DONE, setup.complete(code, "\$2a\$04\$hash"))
        assertEquals("\$2a\$04\$hash", store.getSetting(Settings.ADMIN_HASH))
        assertNull("burned", store.getSetting(Settings.SETUP_CODE_HASH))
        assertNull(store.getSetting(Settings.SETUP_CODE_EXPIRES))
        assertFalse(setup.matches(code))
        assertEquals(SetupCode.Outcome.ALREADY_SET_UP, setup.complete(code, "\$2a\$04\$other"))
        assertEquals("\$2a\$04\$hash", store.getSetting(Settings.ADMIN_HASH))
    }

    @Test fun setupCodeExpires() = env { store, setup, clock ->
        val code = setup.issue().code
        clock.advance(SetupCode.TTL_MS - 1)
        assertTrue(setup.matches(code))
        clock.advance(1)
        assertFalse(setup.matches(code))
        assertEquals(SetupCode.Outcome.BAD_CODE, setup.complete(code, "\$2a\$04\$hash"))
        assertNull(store.getSetting(Settings.ADMIN_HASH))
    }

    @Test fun regeneratingInvalidatesTheOldCode() = env { _, setup, _ ->
        val old = setup.issue().code
        val new = setup.issue().code
        assertFalse(setup.matches(old))
        assertTrue(setup.matches(new))
    }

    @Test fun concurrentCompletionsWithTheRightCodeHaveExactlyOneWinner() = env { store, setup, _ ->
        repeat(20) { round ->
            store.deleteSetting(Settings.ADMIN_HASH)
            val code = setup.issue().code
            val start = java.util.concurrent.CountDownLatch(1)
            val results = java.util.concurrent.ConcurrentLinkedQueue<SetupCode.Outcome>()
            val threads = (1..2).map { i ->
                Thread {
                    start.await()
                    results += setup.complete(code, "\$2a\$04\$thread$i")
                }.apply { start() }
            }
            start.countDown()
            threads.forEach { it.join(10_000) }
            assertEquals("round $round: $results", 1, results.count { it == SetupCode.Outcome.DONE })
            assertEquals(2, results.size)
            assertEquals(1, results.count { it == SetupCode.Outcome.ALREADY_SET_UP })
            assertTrue(store.getSetting(Settings.ADMIN_HASH)!!.startsWith("\$2a\$04\$thread"))
        }
    }

    @Test fun noCodeMeansNothingMatches() = env { _, setup, _ ->
        assertFalse(setup.matches("00000-00000-00000-00000-00000-00000"))
        assertEquals(SetupCode.Outcome.BAD_CODE, setup.complete("00000-00000-00000-00000-00000-00000", "h"))
    }

    @Test fun bannerShowsCodeUrlAndExpiry() = env { _, setup, _ ->
        val issued = setup.issue()
        val banner = SetupCode.banner(issued, "https://orbit.example.com/setup", ZoneOffset.UTC)
        assertTrue(banner, banner.contains(issued.code))
        assertTrue(banner, banner.contains("https://orbit.example.com/setup"))
        assertTrue(banner, banner.contains("orbit-server setup-code"))
        assertTrue(banner, banner.contains("24 hours"))
    }
}
