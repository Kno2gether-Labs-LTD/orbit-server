package dev.avijit.orbit.server.agent.voice

import dev.avijit.orbit.server.MasterKey
import dev.avijit.orbit.server.ServerHome
import dev.avijit.orbit.server.Store
import dev.avijit.orbit.server.withTempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceSettingsTest {
    private fun <T> withSettings(block: (VoiceSettings, Store) -> T): T = withTempDir { dir ->
        val home = ServerHome(dir)
        Store(home.dbFile).use { block(VoiceSettings(it, MasterKey(home)), it) }
    }

    @Test fun keysAreStoredEncryptedAndKeptRemovedOrReplaced() = withSettings { vs, store ->
        assertTrue(vs.configured().isEmpty())
        vs.save(VoiceKind.OPENAI, "sk-openai-1", null)
        vs.save(VoiceKind.ASSEMBLYAI, "aai-1", " ")
        val raw = store.getSetting(VoiceSettings.keyName(VoiceKind.OPENAI))!!
        assertFalse(raw.contains("sk-openai-1"))
        assertEquals(listOf(VoiceKind.OPENAI, VoiceKind.ASSEMBLYAI), vs.configured().map { it.kind })
        assertEquals("sk-openai-1", vs.load(VoiceKind.OPENAI)!!.apiKey)
        assertEquals("gpt-4o-mini-tts", vs.load(VoiceKind.OPENAI)!!.model)
        assertFalse(vs.load(VoiceKind.OPENAI).toString().contains("sk-openai-1"))

        vs.save(VoiceKind.OPENAI, null, "tts-1-hd") // null keeps the key
        assertEquals("sk-openai-1", vs.load(VoiceKind.OPENAI)!!.apiKey)
        assertEquals("tts-1-hd", vs.load(VoiceKind.OPENAI)!!.model)
        vs.save(VoiceKind.OPENAI, "sk-openai-2", "")
        assertEquals("sk-openai-2", vs.load(VoiceKind.OPENAI)!!.apiKey)
        assertNull(vs.storedModel(VoiceKind.OPENAI))
        vs.save(VoiceKind.OPENAI, "", null)
        assertFalse(vs.hasKey(VoiceKind.OPENAI))
        assertEquals(listOf(VoiceKind.ASSEMBLYAI), vs.configured().map { it.kind })
    }

    @Test fun invalidInputIsRefused() = withSettings { vs, _ ->
        for (bad in listOf("has space", "x".repeat(101), "a\nb")) {
            try { vs.save(VoiceKind.OPENAI, "k", bad); fail(bad) } catch (e: IllegalArgumentException) { /* expected */ }
        }
        try { vs.save(VoiceKind.OPENAI, "k", null, "https://example.com"); fail() } catch (e: IllegalArgumentException) { /* no base URL for OpenAI */ }
        for (bad in listOf("http://inworld.example.com", "ftp://x", "https://user:pw@h.example.com", "https://h.example.com?x=1", "not a url")) {
            try { vs.save(VoiceKind.INWORLD, "k", null, bad); fail(bad) } catch (e: IllegalArgumentException) { /* expected */ }
        }
        assertFalse(vs.hasKey(VoiceKind.OPENAI))
    }

    @Test fun inworldBaseUrl() = withSettings { vs, _ ->
        vs.save(VoiceKind.INWORLD, "k", null, "")
        assertEquals(InworldVoice.BASE, vs.load(VoiceKind.INWORLD)!!.baseUrl)
        vs.save(VoiceKind.INWORLD, null, null, "https://inworld.example.com/tts/v1/voice")
        assertEquals("https://inworld.example.com", vs.load(VoiceKind.INWORLD)!!.baseUrl)
        vs.save(VoiceKind.INWORLD, null, null, "https://inworld.example.com/")
        assertEquals("https://inworld.example.com", vs.storedBaseUrl(VoiceKind.INWORLD))
        vs.save(VoiceKind.INWORLD, null, null, null)
        assertNull(vs.storedBaseUrl(VoiceKind.INWORLD))
        assertNull(vs.load(VoiceKind.OPENAI))
    }

    @Test fun defaultVoice() = withSettings { vs, _ ->
        assertNull(vs.defaultVoice())
        vs.setDefaultVoice("openai:coral")
        assertEquals("openai:coral", vs.defaultVoice())
        for (bad in listOf("coral", "nope:coral", "openai:", "openai:has space")) {
            try { vs.setDefaultVoice(bad); fail(bad) } catch (e: IllegalArgumentException) { /* expected */ }
        }
        vs.setDefaultVoice("")
        assertNull(vs.defaultVoice())
        assertEquals(VoiceKind.INWORLD to "ws__a/b", VoiceSettings.parseVoiceId("inworld:ws__a/b"))
    }
}
