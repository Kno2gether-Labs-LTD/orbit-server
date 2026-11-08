package dev.avijit.orbit.server.web

import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.HtmlBlockTag
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.em
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.hiddenInput
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title
import kotlinx.html.code
import kotlinx.html.h2
import kotlinx.html.pre

/** Stroke glyphs from the Living Skies mockup (24 × 24, stroke 1.6), plus a few dashboard-only ones. */
object Glyph {
    private val paths = mapOf(
        "today" to """<path d="M3 18h18"/><path d="M7 18a5 5 0 0 1 10 0"/><path d="M12 9V6.5M6.7 11.2 5 9.5M17.3 11.2 19 9.5"/>""",
        "sleep" to """<path d="M19.5 14.6A7.8 7.8 0 0 1 9.4 4.5a7.8 7.8 0 1 0 10.1 10.1z"/>""",
        "heart" to """<path d="M12 19.5s-7.5-4.6-7.5-10.2A4.2 4.2 0 0 1 12 6.9a4.2 4.2 0 0 1 7.5 2.4c0 5.6-7.5 10.2-7.5 10.2z"/>""",
        "ask" to """<path d="M12 3.5c.6 4.9 3 7.4 8 8.5-5 1.1-7.4 3.6-8 8.5-.6-4.9-3-7.4-8-8.5 5-1.1 7.4-3.6 8-8.5z"/>""",
        "ring" to """<circle cx="11" cy="13" r="6.8"/><circle cx="17.6" cy="6.4" r="2.1" fill="currentColor" stroke="none"/>""",
        "hrv" to """<path d="M3 12h3.2l2.1-5 3.4 10 2.8-7.5 1.8 2.5H21"/>""",
        "drop" to """<path d="M12 3.8s6 6.4 6 10.6a6 6 0 0 1-12 0c0-4.2 6-10.6 6-10.6z"/>""",
        "steps" to """<path d="M8 3.5c1.7 0 2.8 1.9 2.8 4.4 0 1.9-.6 3.3-.9 4.6H6.1c-.3-1.3-.9-2.7-.9-4.6 0-2.5 1.1-4.4 2.8-4.4z"/><path d="M6.4 15.4h3.3v1.2a1.65 1.65 0 0 1-3.3 0z"/><path d="M16 6.5c1.7 0 2.8 1.9 2.8 4.4 0 1.9-.6 3.3-.9 4.6h-3.8c-.3-1.3-.9-2.7-.9-4.6 0-2.5 1.1-4.4 2.8-4.4z"/><path d="M14.4 18.4h3.3v.2a1.65 1.65 0 0 1-3.3 0z"/>""",
        "breath" to """<path d="M3 9.5c3 0 3-3 6-3s3 3 6 3 3-3 6-3"/><path d="M3 16.5c3 0 3-3 6-3s3 3 6 3 3-3 6-3"/>""",
        "lotus" to """<path d="M12 19c-4.2 0-7.4-2.2-8.5-5.5 2.7-.5 5.4.3 8.5 3.2 3.1-2.9 5.8-3.7 8.5-3.2-1.1 3.3-4.3 5.5-8.5 5.5z"/><path d="M12 16.6c-1.9-2.1-2.7-4.8 0-9.6 2.7 4.8 1.9 7.5 0 9.6z"/>""",
        "lock" to """<rect x="5" y="10.5" width="14" height="9.5" rx="3"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>""",
        "shield" to """<path d="M12 3.5 19 6.3v5.2c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6.3z"/><path d="m9 12 2.2 2.2L15.5 10"/>""",
        "doc" to """<path d="M7 3.5h7l4 4v13H7z"/><path d="M14 3.5v4h4"/>""",
        "agent" to """<circle cx="12" cy="12" r="3"/><path d="M12 3v3M12 18v3M3 12h3M18 12h3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/>""",
        "sync" to """<path d="M19.5 10.5A7.7 7.7 0 0 0 5.6 7"/><path d="M4.5 13.5A7.7 7.7 0 0 0 18.4 17"/><path d="M5.3 3.8V7.2h3.4M18.7 20.2v-3.4h-3.4"/>""",
        "down" to """<path d="M12 5v13M6.5 12.5 12 18l5.5-5.5"/>""",
        "plus" to """<path d="M12 5v14M5 12h14"/>""",
        "send" to """<path d="M5 12h13M13 6.5 18.5 12 13 17.5"/>""",
        "phone" to """<rect x="7" y="3" width="10" height="18" rx="2.6"/><path d="M10.5 17.5h3"/>""",
        "key" to """<circle cx="8" cy="15" r="3.6"/><path d="M10.6 12.4 19 4M15.5 7.5l2.3 2.3M13.4 9.6l1.8 1.8"/>""",
        "copy" to """<rect x="8.5" y="8.5" width="11" height="11" rx="2.6"/><path d="M15.5 8.5V6.6a2.1 2.1 0 0 0-2.1-2.1H6.6a2.1 2.1 0 0 0-2.1 2.1v6.8a2.1 2.1 0 0 0 2.1 2.1h1.9"/>""",
        "out" to """<path d="M14 4.5H7.6a2.1 2.1 0 0 0-2.1 2.1v10.8a2.1 2.1 0 0 0 2.1 2.1H14"/><path d="M11 12h9M16.5 8.5 20 12l-3.5 3.5"/>""",
    )

    fun svg(name: String, size: Int = 20): String =
        """<svg class="glyph" width="$size" height="$size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths.getValue(name)}</svg>"""
}

/** The Orbit mark (tokens.md §1 geometry, 100-unit box) on a deep-indigo rounded square. */
fun logoSvg(size: Int, id: String = "lg"): String = """<svg class="logo" width="$size" height="$size" viewBox="0 0 100 100" aria-hidden="true">
<defs><linearGradient id="$id-r" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#9A7BFF"/><stop offset=".55" stop-color="#B67BEA"/><stop offset="1" stop-color="#F07BC2"/></linearGradient>
<radialGradient id="$id-h"><stop offset="0" stop-color="#FF9BD0" stop-opacity=".75"/><stop offset="1" stop-color="#FF9BD0" stop-opacity="0"/></radialGradient>
<radialGradient id="$id-b" cx=".5" cy=".42" r=".75"><stop offset="0" stop-color="#2A2170"/><stop offset="1" stop-color="#141038"/></radialGradient></defs>
<rect width="100" height="100" rx="24" fill="url(#$id-b)"/><circle cx="48" cy="54" r="29" fill="none" stroke="url(#$id-r)" stroke-width="15"/>
<circle cx="75.5" cy="25.5" r="16" fill="url(#$id-h)"/><circle cx="75.5" cy="25.5" r="8.5" fill="#FFE4F2" stroke="#F27BC2" stroke-width="2.6"/></svg>"""

data class NavItem(val path: String, val label: String, val glyph: String)

val NAV = listOf(
    NavItem("/", "Overview", "today"),
    NavItem("/pair", "Pair a phone", "phone"),
    NavItem("/devices", "Devices", "ring"),
    NavItem("/agent", "Agent", "ask"),
    NavItem("/tokens", "Agent tokens", "key"),
    NavItem("/access", "Access log", "shield"),
    NavItem("/export", "Export", "down"),
)

/** What every signed-in page needs. */
class Chrome(val serverName: String, val csrf: String, val active: String)

private fun HTML.docHead(pageTitle: String) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width,initial-scale=1")
        meta(name = "color-scheme", content = "dark")
        title { +"$pageTitle · Orbit Server" }
        link(rel = "icon", href = "/assets/favicon.svg", type = "image/svg+xml")
        link(rel = "stylesheet", href = "/assets/dashboard.css")
        script(src = "/assets/dashboard.js") { defer = true }
    }
}

fun FlowContent.csrfField(csrf: String) = hiddenInput(name = "_csrf") { value = csrf }

fun kotlinx.html.Tag.raw(html: String) = consumer.onTagContentUnsafe { +html }

/** The signed-in layout: sidebar navigation and a page header. */
fun HTML.shell(c: Chrome, pageTitle: String, eyebrow: String, heading: HtmlBlockTag.() -> Unit, lead: String?, content: HtmlBlockTag.() -> Unit) {
    docHead(pageTitle)
    body(classes = "app") {
        aside(classes = "side") {
            a(href = "/", classes = "brand") {
                raw(logoSvg(36, "nav"))
                span { +"Orbit "; em { +"Server" } }
            }
            nav(classes = "nav") {
                for (n in NAV) {
                    a(href = n.path, classes = if (n.path == c.active) "nav-item active" else "nav-item") {
                        if (n.path == c.active) attributes["aria-current"] = "page"
                        raw(Glyph.svg(n.glyph))
                        span { +n.label }
                    }
                }
            }
            div(classes = "side-foot") {
                p(classes = "server-name") { +c.serverName }
                form(action = "/logout", method = FormMethod.post) {
                    csrfField(c.csrf)
                    button(classes = "link-btn") { raw(Glyph.svg("out", 18)); span { +"Sign out" } }
                }
            }
        }
        main(classes = "main") {
            header(classes = "page-head") {
                p(classes = "eyebrow") { +eyebrow }
                h1(classes = "title") { heading() }
                lead?.let { p(classes = "lead") { +it } }
            }
            content()
        }
    }
}

/** A centred page on a night sky with no navigation (login, errors). */
fun HTML.bare(pageTitle: String, content: HtmlBlockTag.() -> Unit) {
    docHead(pageTitle)
    body(classes = "bare") {
        div(classes = "sky-bg") { attributes["aria-hidden"] = "true" }
        main(classes = "bare-main") { content() }
    }
}

fun FlowContent.notice(kind: String, text: String) = div(classes = "notice $kind") {
    attributes["role"] = if (kind == "error") "alert" else "status"
    +text
}

fun FlowContent.badge(text: String, kind: String = "est") = span(classes = "badge $kind") { +text }

fun HtmlBlockTag.sectionTitle(text: String, extra: (HtmlBlockTag.() -> Unit)? = null) = div(classes = "section-head") {
    h2(classes = "h2") { +text }
    extra?.invoke(this)
}

fun FlowContent.copyBlock(id: String, text: String, label: String = "Copy", multiline: Boolean = false) = div(classes = "copy-block") {
    if (multiline) pre(classes = "code") { attributes["id"] = id; +text }
    else code(classes = "code one-line") { attributes["id"] = id; +text }
    button(type = kotlinx.html.ButtonType.button, classes = "icon-btn copy") {
        attributes["data-copy"] = id
        attributes["aria-label"] = "$label to clipboard"
        attributes["title"] = label
        raw(Glyph.svg("copy", 18))
    }
}
