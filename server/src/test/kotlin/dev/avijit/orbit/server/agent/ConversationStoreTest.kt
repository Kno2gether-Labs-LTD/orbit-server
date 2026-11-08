package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.DeviceRow
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.withTempDir
import dev.avijit.orbithealth.sync.SampleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class ConversationStoreTest {
    private fun <T> withStore(block: (Store) -> T): T = withTempDir { dir -> Store(ServerHome(dir).dbFile).use(block) }

    private fun Store.addDevice(id: String) = insertDevice(DeviceRow(id, "Glasses $id", "hash-$id", setOf("heart"), 1_000, null, false))

    private fun turn(u: String, a: String) = listOf(ConversationStore.USER to u, ConversationStore.ASSISTANT to a)

    @Test fun appendAndRecentInOrderPerDevice() = withStore { s ->
        s.addDevice("d1"); s.addDevice("d2")
        val c = ConversationStore(s)
        c.append("d1", "conv", turn("one", "uno"), 1_000)
        c.append("d1", "conv", turn("two", "dos"), 2_000)
        c.append("d1", "other", turn("x", "y"), 2_500)
        c.append("d2", "conv", turn("theirs", "secret"), 3_000)
        assertEquals(listOf("one", "uno", "two", "dos"), c.recent("d1", "conv", 20).map { it.text })
        assertEquals(listOf("two", "dos"), c.recent("d1", "conv", 2).map { it.text })
        assertEquals(listOf("user", "assistant"), c.recent("d1", "conv", 2).map { it.role })
        assertEquals(2_000L, c.lastMessageMs("d1", "conv"))
        assertEquals(listOf("theirs", "secret"), c.recent("d2", "conv", 20).map { it.text })
        assertNull(c.lastMessageMs("d2", "other"))
        assertEquals(6, c.count("d1"))
        assertEquals(8, c.count())
    }

    @Test fun textIsCappedAndRetentionKeepsTheNewest() = withStore { s ->
        s.addDevice("d1"); s.addDevice("d2")
        val c = ConversationStore(s, maxPerDevice = 5)
        c.append("d1", "a", listOf(ConversationStore.USER to "x".repeat(5_000)), 1)
        assertEquals(ConversationStore.MAX_TEXT_CHARS, c.recent("d1", "a", 1).single().text.length)
        c.append("d2", "a", turn("keep", "me"), 1)
        for (i in 1..4) c.append("d1", "b", turn("q$i", "a$i"), 10L + i)
        assertEquals(5, c.count("d1"))
        assertEquals(listOf("a2", "q3", "a3", "q4", "a4"), c.recent("d1", "b", 50).map { it.text })
        assertTrue(c.recent("d1", "a", 5).isEmpty())
        assertEquals("other devices are never trimmed", 2, c.count("d2"))
    }

    @Test fun deleteAllPerDeviceAndEverything() = withStore { s ->
        s.addDevice("d1"); s.addDevice("d2")
        val c = ConversationStore(s)
        c.append("d1", "a", turn("1", "2"), 1)
        c.append("d2", "a", turn("3", "4"), 1)
        assertEquals(2, c.deleteAll("d1"))
        assertEquals(0, c.count("d1"))
        assertEquals(2, c.count("d2"))
        assertEquals(2, c.deleteAll())
        assertEquals(0, c.count())
    }

    @Test fun revokingADeviceDeletesItsHistory() = withStore { s ->
        s.addDevice("d1"); s.addDevice("d2")
        val c = ConversationStore(s)
        c.append("d1", "a", turn("1", "2"), 1)
        c.append("d2", "a", turn("3", "4"), 1)
        assertTrue(s.revokeDevice("d1"))
        assertTrue(s.device("d1")!!.revoked)
        assertEquals(0, c.count("d1"))
        assertEquals(2, c.count("d2"))
    }

    @Test fun resolveContinuesOnlyLiveOwnConversations() = withStore { s ->
        s.addDevice("d1"); s.addDevice("d2")
        val c = ConversationStore(s)
        val now = 100_000_000L
        c.append("d1", "live", turn("hello", "hi"), now - 1_000)
        c.append("d1", "stale", turn("old", "news"), now - ConversationStore.IDLE_MS)
        val live = c.resolve("d1", "live", now)
        assertEquals("live", live.id)
        assertEquals(listOf("hello", "hi"), live.history!!.map { it.text })

        for (requested in listOf(null, "stale", "unknown", "bad id!")) {
            val r = c.resolve("d1", requested, now)
            assertNull(r.history)
            assertNotEquals(requested, r.id)
            assertTrue(r.id, ConversationStore.CONVERSATION_ID.matches(r.id))
        }
        val foreign = c.resolve("d2", "live", now)
        assertNull("another device's conversation is unknown", foreign.history)
        assertNotEquals("live", foreign.id)
    }

    @Test fun resolvedHistoryIsCappedByCountAndCharacters() = withStore { s ->
        s.addDevice("d1")
        val c = ConversationStore(s)
        for (i in 1..15) c.append("d1", "c", turn("q$i", "a$i"), 1_000L + i)
        val h = c.resolve("d1", "c", 2_000).history!!
        assertEquals(ConversationStore.HISTORY_MESSAGES, h.size)
        assertEquals("q6", h.first().text)
        assertEquals("a15", h.last().text)

        c.deleteAll()
        for (i in 1..5) c.append("d1", "c", turn("$i" + "u".repeat(3_999), "$i" + "a".repeat(3_999)), 1_000L + i)
        val capped = c.resolve("d1", "c", 2_000).history!!
        assertTrue(capped.sumOf { it.text.length } <= ConversationStore.HISTORY_CHARS)
        assertEquals(4, capped.size)
        assertTrue("newest kept", capped.last().text.startsWith("5"))
    }

    @Test fun migrationOnAnOlderDatabaseIsAdditiveAndIdempotent() = withTempDir { dir ->
        val file = ServerHome(dir).dbFile
        Store(file).use { s ->
            s.addDevice("d1")
            s.insertSamples(listOf(SampleDto("HEART_RATE", 1_000, 60.0, "HISTORY")), "d1")
        }
        // Make it look like a v2 database: no walker_messages table.
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { st ->
                st.execute("DROP TABLE walker_messages")
                st.execute("PRAGMA user_version = 2")
            }
        }
        repeat(2) {
            Store(file).use { s ->
                assertEquals(listOf(60.0), s.samples("HEART_RATE", 0, 10_000).map { it.value })
                val c = ConversationStore(s)
                c.append("d1", "c$it", turn("q", "a"), 1)
                assertEquals(2 * (it + 1), c.count())
            }
        }
        DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}").use { c ->
            c.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { rs -> rs.next(); assertEquals(Store.SCHEMA_VERSION, rs.getInt(1)) } }
        }
    }
}
