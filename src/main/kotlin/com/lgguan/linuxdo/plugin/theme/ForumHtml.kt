package com.lgguan.linuxdo.plugin.theme

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Forum markup is data. Only the plugin template may supply executable content. */
internal object ForumHtml {
    private val allowed = Safelist.relaxed()
        .preserveRelativeLinks(true)
        .addTags("section", "aside", "article", "header", "details", "summary", "span", "video", "audio", "source", "del", "s", "kbd", "mark", "input")
        .addAttributes(":all", "class", "title")
        .addAttributes("details", "open")
        .addAttributes("aside", "data-topic", "data-post", "data-username")
        .addAttributes("pre", "data-code-wrap")
        .addAttributes("pre", "tabindex", "aria-label")
        .addAttributes("span", "data-math-source")
        .addAttributes("div", "data-math-source", "data-poll-name", "data-poll-type", "data-poll-status")
        .addAttributes("h1", "id").addAttributes("h2", "id").addAttributes("h3", "id")
        .addAttributes("h4", "id").addAttributes("h5", "id").addAttributes("h6", "id")
        .addAttributes("a", "name")
        .addAttributes("input", "type", "checked", "disabled")
        .addAttributes("a", "data-topic", "data-post", "data-user", "data-base62-sha1")
        .addAttributes("div", "data-theme-table", "data-video-src")
        .addProtocols("div", "data-video-src", "http", "https")
        .addAttributes("img", "data-orig-src", "data-thumb-src", "data-base62-sha1")
        .addAttributes("video", "src", "poster", "controls", "preload", "width", "height")
        .addAttributes("audio", "src", "controls", "preload")
        .addAttributes("source", "src", "type")
        .addProtocols("video", "src", "https", "http")
        .addProtocols("video", "poster", "https", "http")
        .addProtocols("audio", "src", "https", "http")
        .addProtocols("source", "src", "https", "http")
        .addProtocols("img", "data-orig-src", "https", "http")
        .addProtocols("img", "data-thumb-src", "https", "http")

    fun clean(html: String): String {
        val input = Jsoup.parseBodyFragment(html)
        input.outputSettings().prettyPrint(false)
        // An iframe is untrusted data until the user explicitly loads a known player.
        input.select("iframe").forEach { frame ->
            val source = frame.attr("src").ifBlank { frame.attr("data-src") }
            val url = runCatching { java.net.URI("https://linux.do/").resolve(source) }.getOrNull()
            val card = org.jsoup.nodes.Element("div").addClass("forum-embed")
            card.text(frame.attr("title").ifBlank { "嵌入内容" })
            if (url != null && url.scheme in setOf("http", "https") && url.host != null && url.userInfo == null) {
                card.appendElement("a").attr("href", url.toASCIIString()).addClass("forum-embed-source").text("打开嵌入内容")
            }
            frame.replaceWith(card)
        }
        input.select("a[href]").filter { it.attr("href").isBlank() }.forEach { it.unwrap() }
        val document = Jsoup.parseBodyFragment(Jsoup.clean(input.body().html(), "https://linux.do/", allowed,
            org.jsoup.nodes.Document.OutputSettings().prettyPrint(false)))
        document.outputSettings().prettyPrint(false)
        document.select("input").forEach { input ->
            if (input.attr("type") != "checkbox") input.remove() else input.attr("disabled", "disabled")
        }
        return document.body().html()
    }
}
