package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.theme.ScrollableSourceBlocks
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScrollableSourceBlocksTest {
    @Test
    fun `raw HTML remains inert code with nested divs and script strings intact`() {
        val source = """<!DOCTYPE html>
<html><head><style>body { display: grid; }</style></head>
<body><div title="a > b"><div>nested</div></div>
<svg><path d="M0 0" /></svg>
<script>const html = '<div>unclosed'; window.example = true;</script>
</body></html>"""
        val cooked = """<p>Before</p><div data-theme-scrollable="true">body { display: grid; }<div><div>nested</div></div><svg></svg></div><p>After</p>"""
        val raw = """Before<div data-theme-scrollable="true">$source</div>After"""
        val rendered = ScrollableSourceBlocks.render(cooked, raw)
        assertTrue(rendered.startsWith("<p>Before</p>"))
        assertTrue(rendered.endsWith("<p>After</p>"))
        assertFalse(rendered.contains("<script>"))
        assertFalse(rendered.contains("<style>"))
        assertTrue(rendered.contains(TopicDocumentRenderer.escapeHtml(source)))
        for (fold in listOf(false, true)) {
            val document = org.jsoup.Jsoup.parse(TopicDocumentRenderer.processContent(rendered, fold))
            assertEquals(source, document.selectFirst("pre code")?.wholeText())
            assertEquals("Before", document.selectFirst("p")?.text())
            assertTrue(document.select("script, style, svg").isEmpty())
        }
    }

    @Test
    fun `multiple blocks retain order and missing raw has explicit fallback`() {
        val cooked = """<div data-theme-scrollable='true'>broken one</div><blockquote>ordinary quote</blockquote><div data-theme-scrollable=true>broken two</div>"""
        val raw = """<div data-theme-scrollable='true'><h1>one</h1></div><div data-theme-scrollable=true><h1>two</h1></div>"""
        val rendered = ScrollableSourceBlocks.render(cooked, raw)
        assertTrue(rendered.indexOf("&lt;h1&gt;one") < rendered.indexOf("&lt;h1&gt;two"))
        assertTrue(rendered.contains("<blockquote>ordinary quote</blockquote>"))
        val missing = ScrollableSourceBlocks.render(cooked, null)
        assertTrue(missing.contains("完整源码暂时无法获取"))
        assertFalse(missing.contains("broken one"))
    }

    @Test
    fun `ordinary quotes and escaped examples require no extra request`() {
        val ordinary = """<aside class="quote"><div class="title">Author</div><blockquote>quote</blockquote></aside><pre>&lt;div data-theme-scrollable="true"&gt;</pre>"""
        assertFalse(ScrollableSourceBlocks.needsRaw(ordinary))
        assertEquals(ordinary, ScrollableSourceBlocks.render(ordinary, null))
    }
}
