package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.ui.dialog.ComposerErrors
import com.lgguan.linuxdo.plugin.ui.dialog.ComposerImageUpload
import com.lgguan.linuxdo.plugin.ui.dialog.ComposerPreview
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComposerSupportTest {
    @Test
    fun `preview retains basic markdown and escapes user markup and attributes`() {
        val document = Jsoup.parse(ComposerPreview.markdownToHtml(
            "# Title\n**bold**\n<script>alert(1)</script>\n[link](https://linux.do/?q='x')\n[bad](javascript:alert)"))
        assertEquals("Title", document.selectFirst("h1")?.text())
        assertEquals("bold", document.selectFirst("strong")?.text())
        assertTrue(document.select("script").isEmpty())
        assertEquals(1, document.select("a").size)
        assertEquals("https://linux.do/?q='x'", document.selectFirst("a")?.attr("href"))
        assertEquals(1, document.selectFirst("a")?.attributes()?.size())
        assertTrue(document.text().contains("bad"))
    }

    @Test
    fun `server validation errors and rate limit responses share readable messages`() {
        assertEquals("标题太短; 请选择分类", ComposerErrors.parse("HTTP 422: {\"errors\":[\"标题太短\",\"请选择分类\"]}"))
        assertTrue(ComposerErrors.parse("HTTP 429").contains("冷却结束后重试"))
        assertTrue(ComposerErrors.parse("HTTP 429: Cloudflare Too Many Requests").contains("人机验证"))
        assertFalse(ComposerErrors.parse("HTTP 429").contains("发言"))
        assertTrue(ComposerErrors.parse("Just a moment").contains("验证"))
        assertEquals("未知错误", ComposerErrors.parse(null))
    }

    @Test
    fun `clipboard and file uploads use media type matching original format`() {
        for ((name, type) in mapOf("clipboard.JPG" to "image/jpeg", "copy.webp" to "image/webp",
            "animation.gif" to "image/gif", "bitmap.png" to "image/png", "diagram.svg" to "image/svg+xml")) {
            assertEquals(type, ComposerImageUpload.mimeType(name))
        }
    }
}
