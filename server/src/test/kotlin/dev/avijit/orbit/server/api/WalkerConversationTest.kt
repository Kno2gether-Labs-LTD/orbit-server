package dev.avijit.orbit.server.api

import dev.avijit.orbit.server.agent.ConversationStore
import dev.avijit.orbit.server.agent.ModelProvider
import dev.avijit.orbit.server.agent.ModelReply
import dev.avijit.orbit.server.agent.Msg
import dev.avijit.orbit.server.agent.ProviderFactory
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbit.server.agent.ToolCall
import dev.avijit.orbit.server.agent.ToolSpec
import dev.avijit.orbithealth.sync.ChatTurn
import dev.avijit.orbithealth.sync.WalkerAskRequest
import dev.avijit.orbithealth.sync.WalkerContinueRequest
import dev.avijit.orbithealth.sync.WalkerReply
import dev.avijit.orbithealth.sync.WalkerToolResult
import dev.avijit.orbithealth.sync.WalkerToolSpec
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers "answer N" to the Nth chat, or a client call when the user's text is "use the tool"; records what it saw. */
private class EchoProvider : ModelProvider {
    override val model = "echo"
    val seen = mutableListOf<List<Msg>>()
    override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply {
        seen += messages.toList()
        val last = messages.last()
        if (last is Msg.User && last.text == "use the tool") return ModelReply.ToolCalls(listOf(ToolCall("c1", "close_screen", JsonObject(emptyMap()))))
        return ModelReply.Text("answer ${seen.size}")
    }

    /** The history (user/assistant text) of the last chat, without the system prompt and the new question. */
    fun lastHistory(): List<String> = seen.last().drop(1).dropLast(1).map {
        when (it) { is Msg.User -> "u:${it.text}"; is Msg.Assistant -> "a:${it.text}"; else -> it.toString() }
    }
}

private val CLOSE = WalkerToolSpec("close_screen", "Close the focused screen.", buildJsonObject { put("type", "object"); putJsonObject("properties") {} })

class WalkerConversationTest {
    private fun withWalker(block: suspend ApiEnv.(EchoProvider) -> Unit) {
        val p = EchoProvider()
        withApi(providerFactory = ProviderFactory { p }) {
            deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-test")
            block(p)
        }
    }

    private suspend fun ApiEnv.ask(token: String, text: String, conversation: String? = null, history: List<ChatTurn> = emptyList()): HttpResponse =
        postJson("/v1/walker/ask", testJson.encodeToString(WalkerAskRequest(text, history = history, tools = listOf(CLOSE), conversation = conversation)), token)

    private suspend fun HttpResponse.reply(): WalkerReply {
        assertEquals(bodyAsText(), HttpStatusCode.OK, status)
        return testJson.decodeFromString(bodyAsText())
    }

    @Test fun serverKeepsTheConversation() = withWalker { p ->
        val dev = pairedDevice("Glasses")
        val first = ask(dev.deviceToken, "hello").reply()
        assertNotNull(first.conversation)
        val conv = first.conversation!!
        assertTrue(conv, ConversationStore.CONVERSATION_ID.matches(conv))
        assertEquals(emptyList<String>(), p.lastHistory())

        // The stored conversation is the history; whatever the client sends as history is ignored.
        val second = ask(dev.deviceToken, "and then?", conv, history = listOf(ChatTurn("user", "client-side"), ChatTurn("assistant", "ignored"))).reply()
        assertEquals(conv, second.conversation)
        assertEquals(listOf("u:hello", "a:answer 1"), p.lastHistory())
        ask(dev.deviceToken, "third", conv).reply()
        assertEquals(listOf("u:hello", "a:answer 1", "u:and then?", "a:answer 2"), p.lastHistory())
        assertEquals(6, deps.conversations.count())
    }

    @Test fun withoutAConversationTheClientHistoryIsUsedAsBefore() = withWalker { p ->
        val dev = pairedDevice("Glasses")
        val r = ask(dev.deviceToken, "now", history = listOf(ChatTurn("user", "before"), ChatTurn("assistant", "yes"))).reply()
        assertEquals(listOf("u:before", "a:yes"), p.lastHistory())
        // A new conversation started; it holds only this turn (not the client's history).
        val stored = deps.conversations.recent(dev.deviceId, r.conversation!!, 20).map { it.text }
        assertEquals(listOf("now", "answer 1"), stored)
    }

    @Test fun unknownForeignOrIdleConversationsStartANewOne() = withWalker { p ->
        val a = pairedDevice("A")
        val b = pairedDevice("B")
        val convA = ask(a.deviceToken, "mine").reply().conversation!!

        val unknown = ask(a.deviceToken, "q", "no-such-conversation", history = listOf(ChatTurn("user", "fallback"), ChatTurn("assistant", "ok"))).reply()
        assertNotEquals("no-such-conversation", unknown.conversation)
        assertEquals("an unknown id falls back to the client's history", listOf("u:fallback", "a:ok"), p.lastHistory())

        val foreign = ask(b.deviceToken, "q", convA).reply()
        assertNotEquals(convA, foreign.conversation)
        assertEquals(emptyList<String>(), p.lastHistory())
        assertEquals("A's conversation is untouched", 2, deps.conversations.recent(a.deviceId, convA, 20).size)

        clock.advance(ConversationStore.IDLE_MS - 1)
        assertEquals(convA, ask(a.deviceToken, "still here", convA).reply().conversation)
        clock.advance(ConversationStore.IDLE_MS)
        val idle = ask(a.deviceToken, "much later", convA).reply()
        assertNotEquals(convA, idle.conversation)
        assertEquals(emptyList<String>(), p.lastHistory())
    }

    @Test fun malformedConversationIsBadRequest() = withWalker { _ ->
        val dev = pairedDevice("Glasses")
        for (bad in listOf("", "has space", "x".repeat(65), "semi;colon")) {
            val r = ask(dev.deviceToken, "hi", bad)
            assertEquals(bad, HttpStatusCode.BadRequest, r.status)
            assertTrue(r.bodyAsText().contains("bad_request"))
        }
        assertEquals(0, deps.conversations.count())
    }

    @Test fun pausedTurnsCarryTheConversationAndStoreOnlyWhenDone() = withWalker { p ->
        val dev = pairedDevice("Glasses")
        val paused = ask(dev.deviceToken, "use the tool").reply()
        assertEquals(WalkerReply.TOOLS, paused.status)
        val conv = paused.conversation!!
        assertEquals("nothing stored while paused", 0, deps.conversations.count())
        val done = postJson(
            "/v1/walker/continue", testJson.encodeToString(WalkerContinueRequest(paused.turn!!, listOf(WalkerToolResult("c1", "closed")))), dev.deviceToken,
        ).reply()
        assertEquals(WalkerReply.DONE, done.status)
        assertEquals(conv, done.conversation)
        assertEquals(listOf("use the tool", "answer 2"), deps.conversations.recent(dev.deviceId, conv, 20).map { it.text })
        // Tool calls and results are not part of the stored history.
        ask(dev.deviceToken, "next", conv).reply()
        assertEquals(listOf("u:use the tool", "a:answer 2"), p.lastHistory())
    }

    @Test fun failedTurnsStoreNothing() {
        val failing = object : ModelProvider {
            override val model = "x"
            override suspend fun chat(messages: List<Msg>, tools: List<ToolSpec>): ModelReply = throw dev.avijit.orbit.server.agent.ProviderException("HTTP 500 from provider")
        }
        withApi(providerFactory = ProviderFactory { failing }) {
            deps.agentSettings.save(ProviderKind.ANTHROPIC, null, "", "sk-ant-test")
            val dev = pairedDevice("Glasses")
            assertEquals(HttpStatusCode.BadGateway, ask(dev.deviceToken, "hi").status)
            assertEquals(0, deps.conversations.count())
        }
    }

    @Test fun unpairingDeletesTheDevicesHistory() = withWalker { _ ->
        val dev = pairedDevice("Glasses")
        val other = pairedDevice("Other")
        ask(dev.deviceToken, "hi").reply()
        ask(other.deviceToken, "hi").reply()
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/devices/me") {
            header(io.ktor.http.HttpHeaders.Authorization, "Bearer ${dev.deviceToken}")
        }.status)
        assertEquals(0, deps.conversations.count(dev.deviceId))
        assertFalse(deps.conversations.count(other.deviceId) == 0)
    }
}
