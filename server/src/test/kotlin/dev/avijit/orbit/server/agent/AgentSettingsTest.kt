package dev.avijit.orbit.server.agent

import dev.avijit.orbit.server.MasterKey
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.withTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class AgentSettingsTest {
    private val key = "sk-ant-api03-PLAINTEXT-KEY-0123456789"

    private fun env(scripted: Boolean = false, block: (Store, AgentSettings, ServerHome) -> Unit) = withTempDir { dir ->
        val home = ServerHome(dir)
        Store(home.dbFile).use { store ->
            val env = if (scripted) mapOf(AgentSettings.SCRIPTED_ENV to "1") else emptyMap()
            block(store, AgentSettings(store, MasterKey(home), env), home)
        }
    }

    @Test fun apiKeyIsEncryptedAtRestAndNeverPrinted() = env { store, settings, home ->
        assertNull(settings.load())
        settings.save(ProviderKind.ANTHROPIC, baseUrl = null, model = "", apiKey = key)
        val cfg = settings.load()!!
        assertEquals(ProviderKind.ANTHROPIC, cfg.kind)
        assertEquals(AnthropicProvider.DEFAULT_MODEL, cfg.model)
        assertEquals(key, cfg.apiKey)
        assertFalse(cfg.toString(), cfg.toString().contains("PLAINTEXT"))
        assertTrue(settings.hasApiKey())

        val stored = store.getSetting(AgentSettings.API_KEY)!!
        assertNotEquals(key, stored)
        assertFalse(stored.contains("PLAINTEXT"))
        for (f in listOf(home.dbFile, Paths.get(home.dbFile.toString() + "-wal"))) {
            if (Files.exists(f)) assertFalse("$f holds the key in plaintext", String(Files.readAllBytes(f), Charsets.ISO_8859_1).contains("PLAINTEXT"))
        }
    }

    @Test fun nullKeyKeepsTheStoredOneAndEmptyRemovesIt() = env { _, settings, _ ->
        settings.save(ProviderKind.ANTHROPIC, null, "claude-opus-5-5", key)
        settings.save(ProviderKind.ANTHROPIC, null, "claude-sonnet-5-5", apiKey = null)
        assertEquals(key, settings.load()!!.apiKey)
        assertEquals("claude-sonnet-5-5", settings.load()!!.model)
        try { settings.save(ProviderKind.ANTHROPIC, null, "claude-sonnet-5-5", apiKey = ""); fail("Anthropic needs a key") } catch (e: IllegalArgumentException) { /* ok */ }

        settings.save(ProviderKind.OPENAI_COMPAT, "http://localhost:11434/v1", "llama3.1", apiKey = "")
        val cfg = settings.load()!!
        assertNull(cfg.apiKey)
        assertEquals("http://localhost:11434/v1", cfg.baseUrl)
        assertFalse(settings.hasApiKey())
    }

    @Test fun keyIsNeverCarriedAcrossProviders() = env { _, settings, _ ->
        settings.save(ProviderKind.ANTHROPIC, null, "", key)
        settings.save(ProviderKind.ANTHROPIC, null, "claude-opus-5-5", apiKey = null)
        assertEquals("same kind keeps the key", key, settings.load()!!.apiKey)

        settings.save(ProviderKind.OPENAI_COMPAT, "https://evil.example:8080/v1", "m", apiKey = null)
        assertNull("an Anthropic key must not be sent to another provider", settings.load()!!.apiKey)
        assertFalse(settings.hasApiKey())

        settings.save(ProviderKind.OPENAI_COMPAT, "https://api.one.example/v1", "m", "sk-one")
        settings.save(ProviderKind.OPENAI_COMPAT, "https://api.one.example/v2/", "m2", apiKey = null)
        assertEquals("same host keeps the key", "sk-one", settings.load()!!.apiKey)
        settings.save(ProviderKind.OPENAI_COMPAT, "https://api.two.example/v1", "m2", apiKey = null)
        assertNull("a new host drops the key", settings.load()!!.apiKey)

        settings.save(ProviderKind.OPENAI_COMPAT, "https://api.one.example/v1", "m", "sk-one")
        try { settings.save(ProviderKind.ANTHROPIC, null, "", apiKey = null); fail("switching to Anthropic needs a fresh key") } catch (e: IllegalArgumentException) { /* ok */ }
    }

    @Test fun openAiCompatNeedsAnHttpBaseUrlAndModel() = env { _, settings, _ ->
        for ((url, model) in listOf("ftp://x/v1" to "m", "" to "m", "http://localhost:11434/v1" to " ")) {
            try { settings.save(ProviderKind.OPENAI_COMPAT, url, model, null); fail("$url $model") } catch (e: IllegalArgumentException) { /* ok */ }
        }
        assertNull(settings.load())
    }

    @Test fun plainHttpOnlyForLocalHosts() = env { _, settings, _ ->
        for (ok in listOf(
            "http://localhost:11434/v1", "http://127.0.0.1:1234/v1", "http://[::1]:8080/v1", "http://10.0.0.5/v1",
            "http://172.16.1.2/v1", "http://172.31.255.1/v1", "http://192.168.1.20:11434/v1", "http://[fd12:3456::1]/v1",
            "http://nas.local:11434/v1", "http://ollama.localhost/v1", "https://api.example.com/v1", "https://8.8.8.8/v1",
        )) {
            assertTrue(ok, AgentSettings.cleartextAllowed(ok))
            settings.save(ProviderKind.OPENAI_COMPAT, ok, "m", null)
            assertEquals(ok.trimEnd('/'), settings.load()!!.baseUrl)
        }
        for (bad in listOf(
            "http://api.example.com/v1", "http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://[2001:db8::1]/v1",
            "http://other-box:11434/v1", "http://local/v1", "http://evil.local.example.com/v1", "http://192.168.1.1.nip.io/v1",
        )) {
            assertFalse(bad, AgentSettings.cleartextAllowed(bad))
            try {
                settings.save(ProviderKind.OPENAI_COMPAT, bad, "m", "sk-1"); fail(bad)
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!, e.message!!.contains("use https"))
            }
        }
    }

    @Test fun scriptedIsOnlyAvailableWithTheEnvFlag() {
        env(scripted = false) { store, settings, _ ->
            try { settings.save(ProviderKind.SCRIPTED, null, "scripted", null); fail("scripted must be refused") } catch (e: IllegalArgumentException) { /* ok */ }
            store.putSetting(AgentSettings.PROVIDER, "scripted") // planted directly
            assertNull(settings.load())
            assertFalse(ProviderKind.SCRIPTED in settings.selectableKinds())
        }
        env(scripted = true) { _, settings, _ ->
            assertEquals(ProviderKind.SCRIPTED, settings.load()!!.kind) // default when nothing else is configured
            assertTrue(ProviderKind.SCRIPTED in settings.selectableKinds())
            settings.save(ProviderKind.OPENAI_COMPAT, "http://localhost:1234/v1", "local", null)
            assertEquals(ProviderKind.OPENAI_COMPAT, settings.load()!!.kind)
        }
    }

    @Test fun clearRemovesEverything() = env { store, settings, _ ->
        settings.save(ProviderKind.ANTHROPIC, null, "", key)
        settings.clear()
        assertNull(settings.load())
        assertNull(store.getSetting(AgentSettings.API_KEY))
    }

    @Test fun aRefusedStoredUrlIsWarnedAboutOncePerUrl() = env { store, settings, _ ->
        store.putSetting(AgentSettings.PROVIDER, ProviderKind.OPENAI_COMPAT.wire)
        store.putSetting(AgentSettings.MODEL, "llama3.2")
        val unique = "warn-once-${System.nanoTime()}.example.com"
        val buf = java.io.ByteArrayOutputStream()
        val saved = System.err
        System.setErr(java.io.PrintStream(buf, true))
        try {
            store.putSetting(AgentSettings.BASE_URL, "http://a.$unique/v1")
            repeat(5) { assertNull(settings.load()) }
            store.putSetting(AgentSettings.BASE_URL, "http://b.$unique/v1")
            repeat(5) { assertNull(settings.load()) }
        } finally {
            System.setErr(saved)
        }
        val lines = buf.toString().lines().filter { "base URL is refused" in it }
        assertEquals(buf.toString(), 2, lines.size)
        assertTrue("the URL is not logged", lines.none { unique in it })
    }

    @Test fun storedPlainHttpToARemoteHostIsRefusedOnLoad() = env { store, settings, _ ->
        // A database written before https was required for remote hosts (or edited by hand).
        store.putSetting(AgentSettings.PROVIDER, ProviderKind.OPENAI_COMPAT.wire)
        store.putSetting(AgentSettings.BASE_URL, "http://models.example.com/v1")
        store.putSetting(AgentSettings.MODEL, "llama3.2")
        assertNull(settings.load())
        val why = settings.loadRefusal()!!
        assertTrue(why, why.contains("https"))
        assertTrue(why, why.contains("models.example.com"))

        for (bad in listOf("ftp://models.example.com/v1", "not a url", "https://")) {
            store.putSetting(AgentSettings.BASE_URL, bad)
            assertNull(bad, settings.load())
            assertTrue(bad, settings.loadRefusal()!!.contains("unsupported or invalid agent URL"))
        }

        store.putSetting(AgentSettings.BASE_URL, "http://192.168.1.20:11434/v1")
        assertEquals("http://192.168.1.20:11434/v1", settings.load()!!.baseUrl)
        assertNull(settings.loadRefusal())
        store.putSetting(AgentSettings.BASE_URL, "https://models.example.com/v1")
        assertEquals(ProviderKind.OPENAI_COMPAT, settings.load()!!.kind)
        assertNull(settings.loadRefusal())
    }
}
