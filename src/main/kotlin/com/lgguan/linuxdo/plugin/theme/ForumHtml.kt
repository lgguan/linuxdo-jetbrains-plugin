package com.lgguan.linuxdo.plugin.theme

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/** Forum markup is data. Only the plugin template may supply executable content. */
internal object ForumHtml {
    private val allowed = Safelist.relaxed()
        .addTags("details", "summary", "span", "video", "audio", "source", "del", "s", "kbd", "mark")
        .addAttributes(":all", "class", "title")
        .addAttributes("details", "open")
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

    fun clean(html: String): String = Jsoup.clean(html, "https://linux.do/", allowed,
        org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
}
