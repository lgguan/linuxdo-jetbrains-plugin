package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.theme.ForumContent
import com.lgguan.linuxdo.plugin.ui.dialog.ComposerPreview
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ForumContentAndComposerTest {
    private fun preview(source: String) = Jsoup.parse(ComposerPreview.markdownToHtml(source))
    @Test fun `fenced and inline code keep literal markdown bbcode and line breaks`() {
        val source = "**原样**\n[spoiler]代码[/spoiler]\n[quote=neo]x[/quote]\n<tag> & 中文"
        val doc = preview("```kotlin\n$source\n```\n\n`[spoiler]**代码**[/spoiler]`")
        assertEquals("$source\n", doc.selectFirst("pre code")?.wholeText())
        assertEquals("[spoiler]**代码**[/spoiler]", doc.select("p code").text())
        assertTrue(doc.select("pre strong, pre .spoiler, pre aside, pre br").isEmpty())
    }
    @Test fun `tilde and indented code are protected`() {
        val doc = preview("~~~text\n[details=代码]\n原样\n[/details]\n~~~\n\n    [spoiler]原样[/spoiler]")
        assertEquals(2, doc.select("pre").size)
        assertTrue(doc.select("details,.spoiler").isEmpty())
    }
    @Test fun `ordinary line breaks match forum paragraphs without changing code`() {
        val doc = preview("中文第一行\n第二行\n\n```\n代码第一行\n代码第二行\n```")
        assertEquals(1, doc.select("p br").size)
        assertEquals("代码第一行\n代码第二行\n", doc.selectFirst("pre code")?.wholeText())
    }
    @Test fun `quotes restore author topic and floor and render inner markdown`() {
        val doc = preview("[quote=\"neo, post:5, topic:2909396\"]\n中文 **引用**\n\n第二行\n[/quote]")
        val quote = doc.selectFirst("aside.quote")!!
        assertEquals("2909396", quote.attr("data-topic"))
        assertEquals("5", quote.attr("data-post"))
        assertTrue(quote.select(".title").text().contains("neo"))
        assertEquals("引用", quote.select("blockquote strong").text())
        assertEquals(2, quote.select("blockquote p").size)
    }
    @Test fun `nested details and inline spoilers keep formatted content`() {
        val doc = preview("[details=外层]\n[details=内层]\n内容\n[/details]\n[/details]\n\n[spoiler]**隐藏**[/spoiler]")
        assertEquals(2, doc.select("details").size)
        assertEquals("外层", doc.selectFirst("summary")?.text())
        assertEquals("隐藏", doc.select(".spoiler strong").text())
    }
    @Test fun `tables lists tasks and strikethrough have semantic structure`() {
        val doc = preview("| 中文 | 值 |\n| --- | --- |\n| 数据 | 1 |\n\n- [x] 已完成\n- [ ] 待办\n\n1. 第一项\n2. 第二项\n\n~~删除~~")
        assertEquals(1, doc.select(".forum-table table").size)
        assertEquals(2, doc.select("ul li input[disabled]").size)
        assertEquals(2, doc.select("ol li").size)
        assertEquals("删除", doc.select("del").text())
    }
    @Test fun `real quote and onebox wrappers survive sanitization`() {
        val doc = Jsoup.parse(ForumContent.render("""
          <aside class="quote quote-modified" data-post="5" data-topic="2909396"><div class="title"><div class="quote-controls"></div>作者</div><blockquote>引用</blockquote></aside>
          <aside class="onebox allowlistedgeneric"><header class="source"><img class="site-icon" width="256" src="https://example.com/icon.png"><a href="https://example.com/article">来源</a></header><article class="onebox-body"><h3>标题</h3><div class="meta"><span>摘要元数据</span></div><p>摘要</p></article></aside>
          <div class="lightbox-wrapper"><a class="lightbox" href="/original.png"><img src="/thumb.png"><div class="meta">图片尺寸</div></a></div>
        """, false))
        assertEquals("2909396", doc.selectFirst("aside.quote")?.attr("data-topic"))
        assertEquals(1, doc.select("aside.onebox header + article").size)
        assertEquals("摘要元数据", doc.select(".onebox .meta").text())
        assertFalse(doc.text().contains("图片尺寸"))
        assertTrue(doc.select(".site-icon").isNotEmpty())
    }
    @Test fun `fold captions cannot inject markup and IDs are scoped to their post`() {
        val source = "<p><img src='/image.png' alt='&quot;&gt;&lt;script&gt;坏&lt;/script&gt;'></p>"
        val a = Jsoup.parse(ForumContent.render(source, true, "100"))
        val b = Jsoup.parse(ForumContent.render(source, true, "200"))
        assertTrue(a.select("script").isEmpty())
        assertTrue(a.select(".img-placeholder").text().contains("<script>"))
        assertNotEquals(a.selectFirst("img")?.id(), b.selectFirst("img")?.id())
        assertEquals(1, a.select(".fold-img-box img").size)
    }
    @Test fun `heading anchors cannot collide with reader controls or another post`() {
        val doc = Jsoup.parse(ForumContent.render("<h2 id='floor-9'>标题</h2><a href='#floor-9'>跳转</a>", false, "31"))
        val id = doc.selectFirst("h2")!!.id()
        assertTrue(id.startsWith("content-31-"))
        assertEquals("#$id", doc.selectFirst("a")?.attr("href"))
    }
    @Test fun `onebox linked thumbnail keeps an image URL for zoom`() {
        val doc = Jsoup.parse(ForumContent.render("<aside class='onebox'><article><a href='https://example.com/article'><img src='https://example.com/thumb.png'></a></article></aside>", true))
        assertEquals("https://example.com/thumb.png", doc.selectFirst("img")?.attr("data-orig-src"))
        assertEquals("https://example.com/article", doc.selectFirst("a")?.attr("href"))
    }
    @Test fun `scripts form controls styles and unsafe URLs remain inert`() {
        val doc = Jsoup.parse(ForumContent.render("<script>bad()</script><aside onclick='bad()' style='color:red'><a href='javascript:bad()'>坏链接</a><input type='text' value='secret'><input type='checkbox' checked><iframe src='https://evil.example'></iframe></aside>", false))
        assertTrue(doc.select("script,iframe,input[type=text],[onclick],[style],a[href^=javascript]").isEmpty())
        assertEquals(1, doc.select("input[type=checkbox][disabled][checked]").size)
    }
    @Test fun `unsupported poll retains source and indicates browser rendering`() {
        val doc = preview("[poll]\n* 中文选项\n* 第二项\n[/poll]\n\n```mermaid\ngraph LR; A-->B\n```")
        assertTrue(doc.select("pre").text().contains("[poll]"))
        assertTrue(doc.select("pre").text().contains("graph LR"))
        assertTrue(doc.select(".forum-unsupported").isNotEmpty())
    }
    @Test fun `uploaded discourse short URLs render through authenticated forum image routes`() {
        val doc = preview("![中文图片](upload://aBc123.png)")
        assertEquals("https://linux.do/uploads/short-url/aBc123.png", doc.selectFirst("img")?.attr("src"))
        assertEquals("中文图片", doc.selectFirst("img")?.attr("alt"))
    }
    @Test fun `bootstrap reads only composer limits and handles string settings`() {
        val settings = """{"min_topic_title_length":9,"max_topic_title_length":90,"min_first_post_length":25,"min_post_length":18,"max_post_length":1000,"max_tags_per_topic":8,"default_composer_category":"49"}"""
        for (payload in listOf(settings, Gson().toJson(settings))) {
            val rules = ComposerCapabilities.parse("<script id='data-preloaded' type='application/json'>{\"siteSettings\":$payload,\"isReadOnly\":true,\"currentUser\":{\"email\":\"private\"}}</script>")
            assertEquals(8, rules.maxTags); assertEquals(49, rules.defaultCategory)
            assertTrue(rules.confirmed); assertTrue(rules.readOnly)
            assertFalse(rules.titleValid("12345678")); assertTrue(rules.titleValid("123456789"))
            assertFalse(rules.bodyValid("x".repeat(17), false))
            assertFalse(rules.bodyValid("x".repeat(1001), true))
            assertFalse(rules.toString().contains("private"))
        }
    }
    @Test fun `category tags retain disabled explanation and required group`() {
        val tags = Gson().fromJson("""{"results":[{"id":1451,"text":"软件开发","name":"软件开发","slug":"1451-tag"},{"id":129,"text":"软件激活","disabled":true,"title":"不能用于此类别"}],"required_tag_group":{"name":"类版块组","min_count":1}}""", TagSearchResultResponse::class.java)
        assertEquals("1451", tags.results[0].id)
        assertEquals("1451-tag", tags.results[0].slug)
        assertTrue(tags.results[1].disabled)
        assertEquals("不能用于此类别", tags.results[1].title)
        assertEquals(1, tags.requiredTagGroup?.minCount)
    }
    @ParameterizedTest @ValueSource(strings = ["{\"id\":7,\"topic_id\":9}", "{\"success\":true,\"post\":{\"id\":7,\"topic_id\":9}}"])
    fun `flat and nested published responses yield a valid topic`(json: String) {
        val outcome = PublishOutcome.parse(JsonParser.parseString(json).asJsonObject) as PublishOutcome.Published
        assertEquals(9L, outcome.post.topicId)
    }
    @Test fun `queued result is accepted without inventing a post`() {
        val result = PublishOutcome.parse(JsonParser.parseString("""{"success":true,"action":"enqueued","message":"等待审核"}""").asJsonObject)
        assertEquals(PublishOutcome.Queued("等待审核"), result)
    }
    @ParameterizedTest @ValueSource(strings = ["{}", "{\"id\":7}", "{\"success\":true}"])
    fun `ambiguous publish response cannot clear drafts or produce navigation`(json: String) {
        assertThrows(UnconfirmedPublishException::class.java) { PublishOutcome.parse(JsonParser.parseString(json).asJsonObject) }
    }
    @Test fun `server rejection retains its validation message`() {
        val error = assertThrows(com.lgguan.linuxdo.plugin.net.ForumValidationException::class.java) {
            PublishOutcome.parse(JsonParser.parseString("""{"errors":["必须包含类版块组标签"]}""").asJsonObject)
        }
        assertTrue(com.lgguan.linuxdo.plugin.ui.dialog.ComposerErrors.parse(error).contains("类版块组"))
        assertEquals("HTTP 422", error.message)
    }
    @ParameterizedTest @ValueSource(ints = [302, 408, 500, 502, 503, 504])
    fun `server and timeout statuses cannot prove a publish was rejected`(status: Int) {
        val error = com.lgguan.linuxdo.plugin.net.HttpStatusException(status)
        val uncertain = PublishOutcome.failure(error)
        assertTrue(uncertain is UnconfirmedPublishException)
        assertSame(error, uncertain.cause)
    }
    @ParameterizedTest @ValueSource(ints = [400, 401, 403, 404, 409, 422, 429])
    fun `definite client rejection retains the actionable status`(status: Int) {
        val error = com.lgguan.linuxdo.plugin.net.HttpStatusException(status)
        assertSame(error, PublishOutcome.failure(error))
    }
    @Test fun `malformed successful response cannot enable a duplicate submission`() {
        val malformed = assertThrows(NumberFormatException::class.java) {
            PublishOutcome.parse(JsonParser.parseString("""{"id":"invalid","topic_id":9}""").asJsonObject)
        }
        assertTrue(PublishOutcome.failure(malformed) is UnconfirmedPublishException)
    }
}
