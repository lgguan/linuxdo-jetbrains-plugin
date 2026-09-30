package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.ClipboardImage
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.PostStream
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.lgguan.linuxdo.plugin.theme.RelativeTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.time.Instant
import java.time.ZoneId
import java.nio.file.Files
import java.nio.file.Path

class TopicPresentationTest {
    @Test
    fun `relative times handle boundaries and invalid values`() {
        val now = Instant.parse("2026-09-29T10:00:00Z")
        for ((seconds, expected) in listOf(0L to "刚刚", 59L to "刚刚", 60L to "1分钟前",
            3599L to "59分钟前", 3600L to "1个小时前", 86400L to "1天前",
            604799L to "6天前", 604800L to "2026-09-22 18:00",
            2592000L to "2026-08-30 18:00", 31536000L to "2025-09-29 18:00", -60L to "刚刚")) {
            assertEquals(expected, RelativeTime.format(now.minusSeconds(seconds).toString(), now, ZoneId.of("Asia/Shanghai")))
        }
        assertEquals("时间未知", RelativeTime.format(null, now))
        assertEquals("时间未知", RelativeTime.format("bad", now))
    }

    @Test
    fun `clipboard exposes image pixels without replacing desktop clipboard`() {
        val pixels = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val content = ClipboardImage(pixels)
        assertSame(pixels, content.getTransferData(DataFlavor.imageFlavor))
        assertFalse(content.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertThrows(UnsupportedFlavorException::class.java) { content.getTransferData(DataFlavor.stringFlavor) }
    }

    @Test
    fun `render media regression fixture with timestamp and source preserved`() {
        val cooked = """<p>${"LongUnbrokenText".repeat(12)}</p>
            <div class="lightbox-wrapper"><a class="lightbox" style="display:block" href="https://example.test/original.gif">
            <img src="https://example.test/thumb.png" width="120" height="80"></a></div>
            <video><source src="https://example.test/movie.webm" type="video/webm"></video>
            <div class="video-placeholder" data-video-src="https://example.test/movie.mp4"><img src="https://example.test/poster.png"></div>
            <pre>${"code ".repeat(50)}</pre><table><tr><td>${"wide".repeat(90)}</td></tr></table>"""
        val post = Post(id = 1, username = "author", cooked = cooked, postNumber = 1, createdAt = "2026-09-29T09:59:00Z")
        val topic = TopicDetailResponse(id = 1, title = "Responsive media fixture", postStream = PostStream(listOf(post)))
        for (fold in listOf(false, true)) {
            val html = TopicDocumentRenderer.buildFullDocHtml(topic, listOf(post), "dev", "dev",
                EditorColorSchemeAdapter.getCurrentThemeColors(), LinuxDoSettingsState().apply { foldImages = fold }, currentUsername = "reader")
            assertTrue(html.contains("datetime=\"2026-09-29T09:59:00Z\""))
            assertTrue(html.contains("<source src=\"https://example.test/movie.webm\""))
            assertTrue(html.contains("data-video-src=\"https://example.test/movie.mp4\""))
            val path = Path.of("build", "topic-media-$fold.html")
            Files.createDirectories(path.parent)
            Files.writeString(path, html)
        }
    }
}
