package dev.avijit.orbit.server.web

import dev.avijit.orbit.server.api.MutableClock
import dev.avijit.orbit.server.api.T0
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStoreTest {
    @Test fun aFloodOfAnonymousSessionsNeverEvictsTheAdmin() {
        val store = SessionStore(MutableClock(T0), maxSessions = 3)
        val (admin, _) = store.create(true, "tag")
        repeat(50) { store.create(false, null) }
        assertEquals(3, store.size())
        assertNotNull(store.get(admin, "tag"))
    }

    @Test fun onlyTheRawIdFindsASession() {
        val store = SessionStore(MutableClock(T0))
        val (id, s) = store.create(false, null)
        assertNotNull(store.get(id, null))
        assertNull(store.get(s.csrf, null))
        assertNull(store.get("not a token", null))
        store.remove(id)
        assertNull(store.get(id, null))
    }
}
