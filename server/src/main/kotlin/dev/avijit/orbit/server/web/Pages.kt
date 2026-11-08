package dev.avijit.orbit.server.web

import dev.avijit.orbit.server.AgentTokenRow
import dev.avijit.orbit.server.agent.ProviderKind
import dev.avijit.orbithealth.sync.AccessLogEntry
import dev.avijit.orbithealth.sync.Scope
import dev.avijit.orbithealth.sync.ToolUse
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HtmlBlockTag
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.checkBoxInput
import kotlinx.html.code
import kotlinx.html.div
import kotlinx.html.em
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h3
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.audio
import kotlinx.html.optGroup
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.passwordInput
import kotlinx.html.section
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.textInput
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Times for people: relative for recent moments, local date and time otherwise. */
class Fmt(private val zone: ZoneId, private val nowMs: () -> Long) {
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK).withZone(zone)
    private val dayLine = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK).withZone(zone)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK).withZone(zone)
    private val shortDay = DateTimeFormatter.ofPattern("d MMM", Locale.UK).withZone(zone)

    fun abs(ms: Long): String = dateTime.format(Instant.ofEpochMilli(ms))
    fun day(ms: Long): String = shortDay.format(Instant.ofEpochMilli(ms))
    fun today(): String = dayLine.format(Instant.ofEpochMilli(nowMs()))
    fun clock(ms: Long): String = time.format(Instant.ofEpochMilli(ms))
    fun hour(): Int = Instant.ofEpochMilli(nowMs()).atZone(zone).hour

    fun rel(ms: Long?): String {
        if (ms == null) return "never"
        val d = Duration.ofMillis(nowMs() - ms)
        return when {
            d.isNegative -> abs(ms)
            d.toMinutes() < 1 -> "just now"
            d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
            d.toHours() < 24 -> "${d.toHours()} h ago"
            d.toDays() < 7 -> "${d.toDays()} d ago"
            else -> abs(ms)
        }
    }
}

// ---------------------------------------------------------------- view models

data class MetricCard(
    val label: String, val glyph: String, val tint: String, val value: String?, val unit: String,
    val caption: String, val estimate: Boolean, val spark: List<Double>,
)

data class DeviceVm(
    val id: String, val name: String, val pairedMs: Long, val lastSeenMs: Long?, val revoked: Boolean,
    val scopes: Set<String>, val battery: Int?, val firmware: String?,
)

class OverviewVm(
    val greeting: String, val heroInsight: String, val heroMeta: String, val heroLink: Pair<String, String>?,
    val cards: List<MetricCard>, val devices: List<DeviceVm>, val recent: List<AccessLogEntry>,
    val agentLine: String, val tokenCount: Int,
)

class PairVm(
    val uri: String?, val qrSvg: String?, val expiresAtMs: Long, val nowMs: Long, val serverUrl: String,
    val fingerprint: String?, val refreshCount: Int,
    /** Behind a reverse proxy: the phone checks the public certificate against the host (trust mode `ca`). */
    val publicCa: Boolean = false,
    /** The same one-time code as an `orbitwalker://pair` link for the OrbitWalker glasses app, with its QR. */
    val walkerUri: String? = null,
    val walkerQrSvg: String? = null,
)

data class AgentForm(val kind: String, val baseUrl: String, val model: String)

class TestResult(val answer: String?, val model: String?, val provenance: List<ToolUse>, val error: String?)

class AgentVm(
    val kinds: List<ProviderKind>, val form: AgentForm, val configured: Boolean, val hasKey: Boolean,
    val error: String? = null, val saved: Boolean = false, val test: TestResult? = null, val keyRemoved: Boolean = false,
    /** Why a saved provider is not used (see [dev.avijit.orbit.server.agent.AgentSettings.loadRefusal]). */
    val refusal: String? = null,
    /** Stored OrbitWalker conversation messages, all devices. */
    val conversationMessages: Int = 0,
    val conversationsCleared: Boolean = false,
)

/** One provider on the Voices page. [error] is why its voice list failed (a short code), if it did. */
class VoiceProviderVm(
    val wire: String, val name: String, val hasKey: Boolean, val model: String, val defaultModel: String,
    val voiceCount: Int?, val error: String?, val keyHint: String,
    /** Null: this provider has no configurable base URL. Otherwise the stored override ("" for the official endpoint). */
    val baseUrl: String? = null, val defaultBaseUrl: String? = null,
)

class VoiceOption(val id: String, val name: String, val providerName: String)

/** [testError] / [testBytes]: the outcome of the last "Test" (an audio player is shown for a success). */
class VoicesVm(
    val providers: List<VoiceProviderVm>, val options: List<VoiceOption>, val storedDefault: String?, val effectiveDefault: String?,
    val error: String? = null, val saved: Boolean = false, val testError: String? = null, val testBytes: Int? = null, val testVoice: String? = null,
)

class CreatedToken(val name: String, val scopes: Set<String>, val token: String, val mcpUrl: String, val base: String)

class TokensVm(
    val tokens: List<AgentTokenRow>, val created: CreatedToken?, val error: String?, val formName: String,
    val formScopes: Set<String>, val fingerprint: String?, val certAvailable: Boolean,
    /** Behind a reverse proxy with a public certificate: clients need no extra trust setup. */
    val publicCa: Boolean = false,
)

class AccessVm(val rows: List<AccessLogEntry>, val page: Int, val pages: Int, val total: Int)

class ExportVm(val samples: Int, val nights: Int, val devices: Int, val accessRows: Int)

// ---------------------------------------------------------------- pages

const val TEST_QUESTION = "What was my resting heart rate yesterday?"
const val VOICE_TEST_TEXT = "Hello! This is your Orbit voice."

object Pages {

    fun login(h: HTML, csrf: String, error: String?, limited: Boolean, serverName: String) = h.bare("Sign in") {
        section(classes = "login-card") {
            div(classes = "login-logo") { raw(logoSvg(92, "login")) }
            h1(classes = "title brand-title") { +"Orbit "; em { +"Server" } }
            p(classes = "lead center") { +serverName }
            if (limited) notice("error", "Too many failed sign-ins from this address. Try again in 15 minutes.")
            error?.let { notice("error", it) }
            form(action = "/login", method = FormMethod.post, classes = "stack") {
                csrfField(csrf)
                // Single-user: a hidden username (the server name, as on the setup page) lets password managers
                // file the credential under this server. It is not checked; there is no "admin" account name.
                textInput(name = "username") {
                    value = serverName; attributes["readonly"] = "readonly"; attributes["hidden"] = "hidden"
                    attributes["autocomplete"] = "username"; attributes["tabindex"] = "-1"
                }
                label(classes = "field") {
                    span(classes = "field-label") { +"Admin password" }
                    passwordInput(name = "password") {
                        attributes["autocomplete"] = "current-password"
                        attributes["required"] = "required"
                        attributes["autofocus"] = "autofocus"
                        attributes["maxlength"] = "200"
                    }
                }
                button(type = ButtonType.submit, classes = "btn primary wide") { +"Sign in" }
            }
            p(classes = "meta center") { +"The admin password you chose when you set up this server." }
        }
    }

    /** First run: the one-time setup code from the server's output, and the new admin password (twice). */
    fun setup(h: HTML, csrf: String, error: String?, limited: Boolean, serverName: String) = h.bare("Set up Orbit Server") {
        section(classes = "login-card") {
            div(classes = "login-logo") { raw(logoSvg(92, "setup")) }
            h1(classes = "title brand-title") { +"Set up "; em { +"Orbit Server" } }
            p(classes = "lead center") { +"Enter the one-time setup code from the server's output, then choose the admin password." }
            if (limited) notice("error", "Too many attempts from this address. Try again in 15 minutes.")
            error?.let { notice("error", it) }
            form(action = "/setup", method = FormMethod.post, classes = "stack") {
                csrfField(csrf)
                label(classes = "field") {
                    span(classes = "field-label") { +"Setup code" }
                    textInput(name = "code") {
                        placeholder = "XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX"
                        attributes["autocomplete"] = "one-time-code"
                        attributes["spellcheck"] = "false"
                        attributes["autocapitalize"] = "characters"
                        attributes["required"] = "required"
                        attributes["autofocus"] = "autofocus"
                        attributes["maxlength"] = "64"
                    }
                }
                // Lets password managers file the new credential under this server; not submitted as anything meaningful.
                textInput(name = "username") {
                    value = serverName; attributes["readonly"] = "readonly"; attributes["hidden"] = "hidden"
                    attributes["autocomplete"] = "username"; attributes["tabindex"] = "-1"
                }
                label(classes = "field") {
                    span(classes = "field-label") { +"New admin password (at least 10 characters)" }
                    passwordInput(name = "password") {
                        attributes["autocomplete"] = "new-password"
                        attributes["required"] = "required"
                        attributes["minlength"] = "10"
                        attributes["maxlength"] = "72"
                    }
                }
                label(classes = "field") {
                    span(classes = "field-label") { +"Repeat the password" }
                    passwordInput(name = "confirm") {
                        attributes["autocomplete"] = "new-password"
                        attributes["required"] = "required"
                        attributes["minlength"] = "10"
                        attributes["maxlength"] = "72"
                    }
                }
                button(type = ButtonType.submit, classes = "btn primary wide") { +"Set password and sign in" }
            }
            p(classes = "meta center") {
                +"The code is printed when the server starts and works once, for 24 hours. Lost it? Run "
                code { +"orbit-server setup-code" }; +"."
            }
        }
    }

    fun message(h: HTML, title: String, text: String, back: String = "/") = h.bare(title) {
        section(classes = "login-card") {
            div(classes = "login-logo") { raw(logoSvg(64, "msg")) }
            h1(classes = "title brand-title") { +title }
            p(classes = "lead center") { +text }
            a(href = back, classes = "btn ghost wide") { +"Go back" }
        }
    }

    fun overview(h: HTML, c: Chrome, f: Fmt, vm: OverviewVm) = h.shell(
        c, "Overview", f.today(), { +vm.greeting; em { +"." } },
        "Everything your ring has synced, kept on this machine. Nothing leaves it unless you allow it.",
    ) {
        section(classes = "sky night hero") {
            div(classes = "sky-layer") { attributes["aria-hidden"] = "true" }
            div(classes = "hero-body") {
                p(classes = "overline") { +"Last sync" }
                p(classes = "insight-lg") { +vm.heroInsight }
                p(classes = "meta sky-meta") { +vm.heroMeta }
                vm.heroLink?.let { (href, text) -> a(href = href, classes = "btn primary") { +text; raw(Glyph.svg("send", 18)) } }
            }
        }

        sectionTitle("Latest readings")
        div(classes = "metric-grid") {
            for (m in vm.cards) metricCard(m)
        }

        div(classes = "two-col") {
            section(classes = "card list-card") {
                div(classes = "card-head") {
                    h3(classes = "card-title") { +"Devices" }
                    a(href = "/devices", classes = "card-link") { +"Manage" }
                }
                if (vm.devices.isEmpty()) p(classes = "empty") { +"No phones paired yet." }
                for (d in vm.devices.take(4)) div(classes = "row") {
                    span(classes = "row-glyph heart") { raw(Glyph.svg("phone", 18)) }
                    div(classes = "row-main") {
                        p(classes = "row-title") { +d.name; if (d.revoked) badge("Revoked", "muted") }
                        p(classes = "meta") {
                            +"Last sync ${f.rel(d.lastSeenMs)}"
                            d.battery?.let { +" · ring battery $it%" }
                        }
                    }
                }
            }
            section(classes = "card list-card") {
                div(classes = "card-head") {
                    h3(classes = "card-title") { +"Recent agent reads" }
                    a(href = "/access", classes = "card-link") { +"Access log" }
                }
                if (vm.recent.isEmpty()) p(classes = "empty") { +"No agent has read your data yet." }
                for (e in vm.recent) div(classes = "row") {
                    span(classes = "row-glyph sleep") { raw(Glyph.svg(if (e.actor == "dashboard") "lock" else "agent", 18)) }
                    div(classes = "row-main") {
                        p(classes = "row-title") { +e.actor; span(classes = "row-action") { +" · ${e.action}" } }
                        p(classes = "meta") { +"${f.rel(e.atMs)} · ${e.rows} rows" }
                    }
                }
                p(classes = "meta card-foot") { +"${vm.agentLine} · ${vm.tokenCount} active agent token${if (vm.tokenCount == 1) "" else "s"}" }
            }
        }
    }

    private fun FlowContent.metricCard(m: MetricCard) = div(classes = "card mini tint-${m.tint}") {
        div(classes = "mini-head") {
            span(classes = "mini-glyph hue-${m.tint}") { raw(Glyph.svg(m.glyph, 16)) }
            span(classes = "label") { +m.label }
            if (m.estimate) badge("ESTIMATE")
        }
        div(classes = "mini-value") {
            if (m.value == null) span(classes = "value none") { +"—" }
            else {
                span(classes = "value") { +m.value }
                if (m.unit.isNotEmpty()) span(classes = "unit") { +m.unit }
            }
        }
        if (m.spark.size >= 2) raw(sparkline(m.spark, m.tint))
        p(classes = "caption") { +m.caption }
    }

    fun pair(h: HTML, c: Chrome, f: Fmt, vm: PairVm) = h.shell(
        c, "Pair a phone", "Pair a phone", { +"Connect "; em { +"Orbit Health" } },
        "Scan this code in the app to pair a phone with this server. The code works once and expires in 10 minutes.",
    ) {
        if (vm.uri == null || vm.qrSvg == null) {
            notice("error", "Pairing needs HTTPS. This server is running with --insecure-dev; restart it without that flag.")
            return@shell
        }
        div(classes = "pair-grid") {
            section(classes = "card qr-card") {
                div(classes = "qr-frame") {
                    attributes["data-expires-at"] = vm.expiresAtMs.toString()
                    attributes["data-now"] = vm.nowMs.toString()
                    attributes["data-refresh"] = vm.refreshCount.toString()
                    raw(vm.qrSvg)
                    div(classes = "qr-expired") {
                        p { +"This code has expired." }
                        a(href = "/pair", classes = "btn primary") { +"New code" }
                    }
                }
                div(classes = "countdown") {
                    span(classes = "dot-live") {}
                    span { +"Expires in " }
                    strong(classes = "tnum") { attributes["data-countdown"] = ""; +"10:00" }
                    span(classes = "meta") { +" · at ${f.clock(vm.expiresAtMs)}" }
                }
                a(href = "/pair", classes = "btn ghost") { raw(Glyph.svg("sync", 18)); +"New code" }
            }
            section(classes = "card steps-card") {
                ol(classes = "steps") {
                    li { strong { +"Open Orbit Health" }; p(classes = "meta") { +"Me → Agent & sync → Scan pairing code." } }
                    li { strong { +"Scan the code" }; p(classes = "meta") { +"Or paste the pairing link below into the app." } }
                    li { strong { +"Choose what to share" }; p(classes = "meta") { +"Pick the data groups this server may receive. You can change it later." } }
                }
                div(classes = "field-label") { +"Pairing link (one-time, do not share)" }
                copyBlock("pair-uri", vm.uri, "Copy link")
                div(classes = "kv") {
                    div { span(classes = "label") { +"Server address" }; code { +vm.serverUrl } }
                    if (vm.publicCa) {
                        div { span(classes = "label") { +"Certificate" }; code { +"Public certificate, checked for this host name" } }
                    } else {
                        div { span(classes = "label") { +"Certificate fingerprint (SHA-256)" }; code(classes = "fp") { +(vm.fingerprint?.chunked(4)?.joinToString(" ") ?: "") } }
                    }
                }
                p(classes = "meta") {
                    if (vm.publicCa) {
                        +"The phone connects to the public address above through your reverse proxy. To change it, restart with "
                        code { +"orbit-server run --behind-proxy --public-url https://<your domain>" }; +"."
                    } else {
                        +"The phone must reach the server address above. If it can't, restart with "
                        code { +"orbit-server run --host <LAN name or IP>" }; +"."
                    }
                }
            }
        }
        if (vm.walkerUri != null && vm.walkerQrSvg != null) {
            section(classes = "card walker-pair") {
                div(classes = "walker-qr") { raw(vm.walkerQrSvg) }
                div(classes = "walker-text") {
                    h3 { +"Pairing OrbitWalker (AR glasses)?" }
                    p(classes = "meta") {
                        +"Scan this code in OrbitWalker instead. It is the same one-time code as above, so it pairs one device: "
                        +"whichever app uses it first."
                    }
                    div(classes = "field-label") { +"OrbitWalker pairing link (one-time, do not share)" }
                    copyBlock("pair-uri-walker", vm.walkerUri, "Copy link")
                }
            }
        }
    }

    fun devices(h: HTML, c: Chrome, f: Fmt, devices: List<DeviceVm>) = h.shell(
        c, "Devices", "Devices", { +"Paired "; em { +"phones" } },
        "Each phone has its own token and its own data groups. Revoking a phone stops its token at once.",
    ) {
        section(classes = "card list-card") {
            if (devices.isEmpty()) {
                p(classes = "empty") { +"No phones paired yet. "; a(href = "/pair") { +"Pair one" } }
            }
            for (d in devices) div(classes = if (d.revoked) "row big revoked" else "row big") {
                span(classes = "row-glyph heart") { raw(Glyph.svg("phone", 20)) }
                div(classes = "row-main") {
                    p(classes = "row-title") { +d.name; if (d.revoked) badge("Revoked", "muted") }
                    p(classes = "meta") {
                        +"Paired ${f.day(d.pairedMs)} · last sync ${f.rel(d.lastSeenMs)}"
                        d.battery?.let { +" · ring battery $it%" }
                        d.firmware?.let { +" · firmware $it" }
                    }
                    div(classes = "chips") {
                        if (d.scopes.isEmpty()) span(classes = "chip muted") { +"sync paused" }
                        for (s in Scope.entries) if (s.wire in d.scopes) span(classes = "chip") { +s.wire }
                    }
                }
                if (!d.revoked) form(action = "/devices/${d.id}/revoke", method = FormMethod.post, classes = "row-action-form") {
                    attributes["data-confirm"] = "Revoke ${d.name}? It will stop syncing until you pair it again."
                    csrfField(c.csrf)
                    button(type = ButtonType.submit, classes = "btn ghost danger small") { +"Revoke" }
                }
            }
        }
    }

    fun agent(h: HTML, c: Chrome, vm: AgentVm) = h.shell(
        c, "Agent", "Agent", { +"Your "; em { +"model" } },
        "The model that answers questions from the app. It reads your data only through tools, and every read is logged.",
    ) {
        vm.error?.let { notice("error", it) }
        vm.refusal?.let { notice("error", it) }
        if (vm.keyRemoved) notice("error", "Your saved API key was removed because the provider or host changed. Enter it again if this provider needs one.")
        else if (vm.saved) notice("ok", "Saved.")
        div(classes = "two-col wide-left") {
            section(classes = "card form-card") {
                h3(classes = "card-title") { +"Provider" }
                form(action = "/agent", method = FormMethod.post, classes = "stack") {
                    csrfField(c.csrf)
                    label(classes = "field") {
                        span(classes = "field-label") { +"Provider" }
                        select {
                            name = "provider"; attributes["id"] = "provider"
                            for (k in vm.kinds) option {
                                value = k.wire
                                selected = k.wire == vm.form.kind
                                +when (k) {
                                    ProviderKind.ANTHROPIC -> "Anthropic (Claude)"
                                    ProviderKind.OPENAI_COMPAT -> "OpenAI-compatible (Ollama, LM Studio, OpenAI…)"
                                    ProviderKind.SCRIPTED -> "Scripted (debug)"
                                }
                            }
                        }
                    }
                    label(classes = "field") {
                        attributes["data-show-for"] = "openai_compat"
                        span(classes = "field-label") { +"Base URL" }
                        textInput(name = "baseUrl") {
                            value = vm.form.baseUrl; placeholder = "http://localhost:11434/v1"
                            attributes["autocomplete"] = "off"; attributes["spellcheck"] = "false"
                        }
                    }
                    label(classes = "field") {
                        span(classes = "field-label") { +"Model" }
                        textInput(name = "model") {
                            value = vm.form.model; placeholder = "claude-sonnet-5-5"
                            attributes["autocomplete"] = "off"; attributes["spellcheck"] = "false"
                        }
                    }
                    label(classes = "field") {
                        span(classes = "field-label") { +"API key" }
                        passwordInput(name = "apiKey") {
                            placeholder = if (vm.hasKey) "•••••••••••• saved key" else "Not set"
                            attributes["autocomplete"] = "off"
                        }
                        span(classes = "hint") {
                            +(if (vm.hasKey) "A key is saved (encrypted on this machine). Leave blank to keep it. " else "Stored encrypted on this machine and never shown again. ")
                            +"Re-enter the key when changing provider or host: a saved key is never sent to a different one."
                        }
                    }
                    if (vm.hasKey) label(classes = "check") {
                        checkBoxInput(name = "removeKey") { value = "1" }
                        span { +"Remove the saved key" }
                    }
                    button(type = ButtonType.submit, classes = "btn primary") { +"Save" }
                }
            }
            section(classes = "card tint-sleep test-card") {
                h3(classes = "card-title") { +"Try it" }
                p(classes = "insight") { +"“$TEST_QUESTION”" }
                form(action = "/agent/test", method = FormMethod.post) {
                    csrfField(c.csrf)
                    button(type = ButtonType.submit, classes = "btn ghost") {
                        if (!vm.configured) attributes["disabled"] = "disabled"
                        raw(Glyph.svg("ask", 18)); +"Ask the agent"
                    }
                }
                if (!vm.configured) p(classes = "meta") { +"Save a provider first." }
                p(classes = "meta") { +"The test reads only what your paired phones share with this server." }
                vm.test?.let { t ->
                    div(classes = "answer") {
                        attributes["aria-live"] = "polite"
                        t.error?.let { notice("error", it) }
                        t.answer?.let { p(classes = "insight") { +it } }
                        if (t.provenance.isNotEmpty()) {
                            p(classes = "label") { +"Data it used" }
                            ul(classes = "provenance") {
                                for (u in t.provenance) li {
                                    code { +u.tool }
                                    span(classes = "meta") { +u.args.entries.joinToString(", ") { "${it.key}=${it.value}" } }
                                }
                            }
                        }
                        t.model?.let { p(classes = "caption") { +"Model: $it · logged as “dashboard”" } }
                    }
                }
            }
        }
        section(classes = "card form-card") {
            h3(classes = "card-title") { +"Conversations" }
            if (vm.conversationsCleared) notice("ok", "Conversation history deleted.")
            p(classes = "insight") {
                +"${"%,d".format(vm.conversationMessages)} stored message${if (vm.conversationMessages == 1) "" else "s"}"
            }
            p(classes = "meta") {
                +"OrbitWalker keeps each conversation on this server (your words and the assistant's answers) so follow-up "
                +"questions have context. At most 2,000 messages per device; a device's history is deleted when it is revoked."
            }
            form(action = "/agent/conversations/delete", method = FormMethod.post) {
                attributes["data-confirm"] = "Delete all OrbitWalker conversation history? This cannot be undone."
                csrfField(c.csrf)
                button(type = ButtonType.submit, classes = "btn ghost danger") {
                    if (vm.conversationMessages == 0) attributes["disabled"] = "disabled"
                    +"Delete all conversation history"
                }
            }
        }
    }

    fun voices(h: HTML, c: Chrome, vm: VoicesVm) = h.shell(
        c, "Voices", "Voices", { +"Server "; em { +"voices" } },
        "Text-to-speech for the OrbitWalker glasses. Configure any of these providers; the glasses use their own voice when none is set or a request fails.",
    ) {
        vm.error?.let { notice("error", it) }
        if (vm.saved) notice("ok", "Saved.")
        div(classes = "two-col") {
            for (pv in vm.providers) section(classes = "card form-card") {
                h3(classes = "card-title") { +pv.name }
                when {
                    !pv.hasKey -> p(classes = "meta") { +"Not configured." }
                    pv.error != null -> notice("error", "The voice list could not be loaded (${pv.error}). Check the key.")
                    pv.voiceCount != null -> p(classes = "meta") { +"${pv.voiceCount} voice${if (pv.voiceCount == 1) "" else "s"} available." }
                }
                form(action = "/voices/${pv.wire}", method = FormMethod.post, classes = "stack") {
                    csrfField(c.csrf)
                    label(classes = "field") {
                        span(classes = "field-label") { +"API key" }
                        passwordInput(name = "apiKey") {
                            placeholder = if (pv.hasKey) "•••••••••••• saved key" else "Not set"
                            attributes["autocomplete"] = "off"
                        }
                        span(classes = "hint") {
                            +(if (pv.hasKey) "A key is saved (encrypted on this machine). Leave blank to keep it. " else "Stored encrypted on this machine and never shown again. ")
                            +pv.keyHint
                        }
                    }
                    label(classes = "field") {
                        span(classes = "field-label") { +"Model" }
                        textInput(name = "model") {
                            value = pv.model; placeholder = pv.defaultModel
                            attributes["autocomplete"] = "off"; attributes["spellcheck"] = "false"
                        }
                        span(classes = "hint") { +"Leave blank for ${pv.defaultModel}." }
                    }
                    if (pv.baseUrl != null) label(classes = "field") {
                        span(classes = "field-label") { +"Base URL" }
                        textInput(name = "baseUrl") {
                            value = pv.baseUrl; placeholder = pv.defaultBaseUrl.orEmpty()
                            attributes["autocomplete"] = "off"; attributes["spellcheck"] = "false"
                        }
                        span(classes = "hint") { +"Optional (https only). Leave blank for the official endpoint." }
                    }
                    if (pv.hasKey) label(classes = "check") {
                        checkBoxInput(name = "removeKey") { value = "1" }
                        span { +"Remove the saved key" }
                    }
                    button(type = ButtonType.submit, classes = "btn primary") { +"Save" }
                }
            }
        }
        div(classes = "two-col") {
            section(classes = "card form-card") {
                h3(classes = "card-title") { +"Default voice" }
                form(action = "/voices/default", method = FormMethod.post, classes = "stack") {
                    csrfField(c.csrf)
                    label(classes = "field") {
                        span(classes = "field-label") { +"Voice" }
                        select {
                            name = "voice"
                            option { value = ""; selected = vm.storedDefault == null; +"Automatic (first available voice)" }
                            for ((provider, opts) in vm.options.groupBy { it.providerName }) optGroup(provider) {
                                for (o in opts) option { value = o.id; selected = o.id == vm.storedDefault; +o.name }
                            }
                        }
                        span(classes = "hint") {
                            +(vm.effectiveDefault?.let { "The glasses currently get $it when they ask for the default." }
                                ?: "No voice is available yet: save a provider key first.")
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn primary") { +"Save" }
                }
            }
            section(classes = "card tint-sleep test-card") {
                h3(classes = "card-title") { +"Try it" }
                p(classes = "insight") { +"“$VOICE_TEST_TEXT”" }
                form(action = "/voices/test", method = FormMethod.post) {
                    csrfField(c.csrf)
                    button(type = ButtonType.submit, classes = "btn ghost") {
                        if (vm.effectiveDefault == null) attributes["disabled"] = "disabled"
                        +"Speak with the default voice"
                    }
                }
                vm.testError?.let { notice("error", it) }
                vm.testBytes?.let { n ->
                    p(classes = "meta") { +"${vm.testVoice}: ${"%,d".format(n)} bytes of MP3." }
                    audio { attributes["controls"] = "controls"; attributes["src"] = "/voices/test.mp3" }
                }
            }
        }
    }

    fun tokens(h: HTML, c: Chrome, f: Fmt, vm: TokensVm) = h.shell(
        c, "Agent tokens", "Agent tokens", { +"Your "; em { +"agents" } },
        "Tokens let your own tools (Claude Code, scripts) read your data over MCP or the data API. Each one sees only the groups you pick.",
    ) {
        vm.created?.let { t -> createdToken(t, vm) }
        vm.error?.let { notice("error", it) }
        div(classes = "two-col wide-right") {
            section(classes = "card form-card") {
                h3(classes = "card-title") { +"New token" }
                form(action = "/tokens", method = FormMethod.post, classes = "stack") {
                    csrfField(c.csrf)
                    label(classes = "field") {
                        span(classes = "field-label") { +"Name (shown in the access log)" }
                        textInput(name = "name") {
                            value = vm.formName; placeholder = "claude-code"; attributes["required"] = "required"
                            attributes["maxlength"] = "64"; attributes["autocomplete"] = "off"
                        }
                    }
                    div(classes = "field") {
                        span(classes = "field-label") { +"Data it may read" }
                        div(classes = "scope-grid") {
                            for (s in Scope.entries) label(classes = "scope") {
                                checkBoxInput(name = "scope") { value = s.wire; checked = s.wire in vm.formScopes }
                                span { +s.wire }
                            }
                        }
                    }
                    button(type = ButtonType.submit, classes = "btn primary") { raw(Glyph.svg("plus", 18)); +"Create token" }
                }
            }
            section(classes = "card list-card") {
                h3(classes = "card-title") { +"Tokens" }
                if (vm.tokens.isEmpty()) p(classes = "empty") { +"No agent tokens yet." }
                for (t in vm.tokens.sortedWith(compareBy<AgentTokenRow> { it.revoked }.thenByDescending { it.createdMs })) {
                    div(classes = if (t.revoked) "row big revoked" else "row big") {
                        span(classes = "row-glyph sleep") { raw(Glyph.svg("key", 18)) }
                        div(classes = "row-main") {
                            p(classes = "row-title") { +t.name; if (t.revoked) badge("Revoked", "muted") }
                            p(classes = "meta") { +"Created ${f.day(t.createdMs)} · id ${t.id.take(8)}" }
                            div(classes = "chips") { for (s in Scope.entries) if (s.wire in t.scopes) span(classes = "chip") { +s.wire } }
                        }
                        if (!t.revoked) form(action = "/tokens/${t.id}/revoke", method = FormMethod.post, classes = "row-action-form") {
                            attributes["data-confirm"] = "Revoke ${t.name}? Agents using it lose access at once."
                            csrfField(c.csrf)
                            button(type = ButtonType.submit, classes = "btn ghost danger small") { +"Revoke" }
                        }
                    }
                }
            }
        }
    }

    private fun HtmlBlockTag.createdToken(t: CreatedToken, vm: TokensVm) = section(classes = "card created tint-readiness") {
        div(classes = "card-head") {
            h3(classes = "card-title") { +"Token for ${t.name} created" }
            badge("SHOWN ONCE", "good")
        }
        p(classes = "meta") { +"Copy it now. Only a hash is kept, so it can't be shown again. Scopes: ${t.scopes.sorted().joinToString(", ")}." }
        copyBlock("tok", t.token, "Copy token")
        p(classes = "field-label") { +"Claude Code" }
        copyBlock("snip-cli", "claude mcp add --transport http orbit ${t.mcpUrl} --header \"Authorization: Bearer ${t.token}\"", "Copy command")
        p(classes = "field-label") { +"JSON config (.mcp.json, Claude Desktop, other MCP clients)" }
        copyBlock("snip-json", """{
  "mcpServers": {
    "orbit": {
      "type": "http",
      "url": "${t.mcpUrl}",
      "headers": { "Authorization": "Bearer ${t.token}" }
    }
  }
}""", "Copy JSON", multiline = true)
        if (vm.publicCa) {
            p(classes = "meta") { +"This server uses a public certificate through your reverse proxy, so MCP clients need no extra trust setup." }
            return@section
        }
        p(classes = "field-label") { +"Trust the self-signed certificate" }
        p(classes = "meta") {
            +"Node-based clients such as Claude Code need the server certificate. "
            if (vm.certAvailable) { a(href = "/cert.pem") { +"Download orbit-server.pem" }; +" (or run " } else +"Run "
            code { +"orbit-server cert export orbit-server.pem" }
            if (vm.certAvailable) +")"
            +", then start the client with:"
        }
        copyBlock("snip-ca", "NODE_EXTRA_CA_CERTS=\$PWD/orbit-server.pem claude", "Copy")
        vm.fingerprint?.let { p(classes = "caption") { +"Fingerprint (SHA-256): ${it.chunked(4).joinToString(" ")}" } }
    }

    fun access(h: HTML, c: Chrome, f: Fmt, vm: AccessVm) = h.shell(
        c, "Access log", "Access log", { +"Who read "; em { +"what" } },
        "Every read of your data by an agent, an MCP client, the data API or this dashboard.",
    ) {
        section(classes = "card table-card") {
            if (vm.rows.isEmpty()) p(classes = "empty") { +"Nothing has read your data yet." }
            else table(classes = "log") {
                thead { tr { th { +"When" }; th { +"Who" }; th { +"What" }; th { +"Detail" }; th(classes = "num") { +"Rows" } } }
                tbody {
                    for (e in vm.rows) tr(classes = if (e.detail.startsWith("DENIED") || e.detail.startsWith("ERROR")) "warn" else null) {
                        td(classes = "when") { attributes["title"] = f.abs(e.atMs); +f.rel(e.atMs) }
                        td(classes = "who") { +e.actor }
                        td { code { +e.action } }
                        td(classes = "detail") { +e.detail }
                        td(classes = "num tnum") { +e.rows.toString() }
                    }
                }
            }
        }
        if (vm.pages <= 1 && vm.total > 0) p(classes = "caption table-foot") { +"${vm.total} entr${if (vm.total == 1) "y" else "ies"}" }
        if (vm.pages > 1) div(classes = "pager") {
            if (vm.page > 1) a(href = "/access?page=${vm.page - 1}", classes = "btn ghost small") { +"Newer" }
            span(classes = "meta") { +"Page ${vm.page} of ${vm.pages} · ${vm.total} entries" }
            if (vm.page < vm.pages) a(href = "/access?page=${vm.page + 1}", classes = "btn ghost small") { +"Older" }
        }
    }

    fun export(h: HTML, c: Chrome, vm: ExportVm) = h.shell(
        c, "Export", "Export", { +"Your data, "; em { +"portable" } },
        "Download everything this server holds as one JSON file. The download is written to the access log.",
    ) {
        section(classes = "card export-card tint-neutral") {
            div(classes = "stats") {
                stat(vm.samples.toString(), "samples")
                stat(vm.nights.toString(), "sleep sessions")
                stat(vm.devices.toString(), "devices")
                stat(vm.accessRows.toString(), "access-log rows")
            }
            p(classes = "meta") { +"Includes samples, sleep sessions with stages, devices (without their tokens) and the access log. Keep the file somewhere private: it is your health record." }
            form(action = "/export", method = FormMethod.post) {
                csrfField(c.csrf)
                button(type = ButtonType.submit, classes = "btn primary") { raw(Glyph.svg("down", 18)); +"Download JSON" }
            }
        }
    }

    private fun FlowContent.stat(value: String, label: String) = div(classes = "stat") {
        span(classes = "value tnum") { +value }
        span(classes = "label") { +label }
    }

    /** A 120 × 36 sparkline: Catmull-Rom smoothed, stroked with the metric's gradient (tokens.md charts). */
    fun sparkline(values: List<Double>, tint: String): String {
        val w = 120.0; val hgt = 36.0; val pad = 3.0
        val min = values.min(); val max = values.max()
        val span = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val pts = values.mapIndexed { i, v -> (i * w / (values.size - 1)) to (hgt - pad - (v - min) / span * (hgt - 2 * pad)) }
        val d = StringBuilder("M${n(pts[0].first)} ${n(pts[0].second)}")
        for (i in 0 until pts.size - 1) {
            val p0 = pts[maxOf(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[minOf(i + 2, pts.size - 1)]
            val c1 = (p1.first + (p2.first - p0.first) / 6) to (p1.second + (p2.second - p0.second) / 6)
            val c2 = (p2.first - (p3.first - p1.first) / 6) to (p2.second - (p3.second - p1.second) / 6)
            d.append("C${n(c1.first)} ${n(c1.second)} ${n(c2.first)} ${n(c2.second)} ${n(p2.first)} ${n(p2.second)}")
        }
        val (a, b) = HUES[tint] ?: ("#F4F1EA" to "#F4F1EA")
        val last = pts.last()
        return """<svg class="spark" viewBox="0 0 120 36" preserveAspectRatio="none" aria-hidden="true"><defs><linearGradient id="sp-$tint" x1="0" x2="1"><stop offset="0" stop-color="$a"/><stop offset="1" stop-color="$b"/></linearGradient></defs>""" +
            """<path d="$d" fill="none" stroke="url(#sp-$tint)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" vector-effect="non-scaling-stroke"/>""" +
            """<circle cx="${n(last.first - 2)}" cy="${n(last.second)}" r="2.6" fill="#FFF7EE"/></svg>"""
    }

    private fun n(v: Double) = ((v * 10).roundToInt() / 10.0).toString()

    private val HUES = mapOf(
        "heart" to ("#FF5E8E" to "#B04BD8"), "sleep" to ("#4B3FD0" to "#9C86FF"), "activity" to ("#FF7759" to "#FFC65C"),
        "stress" to ("#3FC1B0" to "#F2B65E"), "spo2" to ("#4FC7DB" to "#A9EEF2"), "readiness" to ("#2FB8A6" to "#FFB48A"),
    )
}

