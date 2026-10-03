package com.lgguan.linuxdo.plugin

import com.google.gson.*
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.net.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PersonalContentTest {
    private val base = "https://linux.do"
    private fun parse(json: String, kind: PersonalContentKind, offset: Int = 0) = PersonalContentParser.parse(JsonParser.parseString(json).asJsonObject, PersonalContentQuery(kind, offset), base, "fixture")
    @Test fun `raw pagination advances independently of deduplication and missing fields`() {
        for(kind in listOf(PersonalContentKind.TOPICS, PersonalContentKind.REPLIES)) {
            val rows = JsonArray().apply { repeat(30) { add(JsonParser.parseString("""{"topic_id":1,"post_id":4,"post_number":2,"title":"safe"}""")) } }
            val page = PersonalContentParser.parse(JsonObject().apply { add("user_actions", rows) }, PersonalContentQuery(kind, 30), base, "fixture")
            assertEquals(1, page.items.size); assertEquals(30, page.rawCount); assertEquals(60, page.next?.offset)
            assertNull(parse("""{"user_actions":[]}""", kind, 60).next)
        }
        val page = parse("""{"user_actions":[{"topic_id":1,"post_id":2,"post_number":2},{"topic_id":1,"post_number":3},{"topic_id":1}]}""", PersonalContentKind.REPLIES)
        assertEquals(2, page.items.size); assertNotNull(page.warning); assertEquals(listOf(2,3), page.items.map { it.floor })
        val missingFloor = parse("""{"user_actions":[{"topic_id":1,"post_id":9}]}""", PersonalContentKind.REPLIES).items.single()
        assertEquals(9L, missingFloor.postId); assertNull(missingFloor.floor)
    }
    @Test fun `bookmark adapter preserves linked floor and never guesses post targets`() {
        val page = parse("""{"user_bookmark_list":{"bookmarks":[{"id":1,"topic_id":4,"bookmarkable_type":"Post","linked_post_number":6,"post_number":1,"name":"reminder"},{"id":2,"bookmarkable_id":4,"bookmarkable_type":"Topic"},{"id":3,"topic_id":4,"bookmarkable_type":"Post"},{"id":4,"topic_id":4,"bookmarkable_type":"Unknown","url":"https://evil.test/t/4"}],"more_bookmarks_url":"/u/fixture/bookmarks?page=1"}}""", PersonalContentKind.BOOKMARKS)
        assertEquals(6, page.items[0].floor); assertEquals(4L, page.items[1].topicId); assertNull(page.items[1].floor)
        assertNull(page.items[2].topicId); assertNull(page.items[3].topicId); assertNull(page.items[3].webUrl)
        assertEquals(1, page.next?.offset)
        assertEquals(1, parse("""{"bookmarks":[{"id":1}]}""", PersonalContentKind.BOOKMARKS).items.size)
        for(link in listOf("https://evil.test/u/fixture/bookmarks?page=1", "/u/other/bookmarks?page=1", "/u/fixture/bookmarks?page=0", "/u/fixture/bookmarks?page=1&evil=2", "//evil.test/u/fixture/bookmarks?page=1"))
            assertNull(PersonalContentParser.bookmarkNext(link, base, "fixture", 0), link)
        assertNull(PersonalContentParser.safeUrl("/t/4?token=x", base))
        assertNull(parse("""{"bookmarks":[{"id":1,"bookmarkable_type":"Post","topic_id":4,"linked_post_number":2147483648}]}""", PersonalContentKind.BOOKMARKS).items.single().topicId)
    }
    @Test fun `draft object and string data classify safely and corrupt rows retain a warning`() {
        val data = JsonObject().apply { add("drafts", JsonArray().apply {
            add(JsonParser.parseString("""{"draft_key":"new_topic_suffix","sequence":1,"data":{"action":"createTopic","title":"<img src=x onerror=evil()>Title","reply":"<script>evil()</script><b>text</b>"}}"""))
            add(JsonObject().apply { addProperty("draft_key", "topic_4"); addProperty("data", """{"action":"reply","reply":"body","reply_to_post_number":3}""") })
            add(JsonParser.parseString("""{"draft_key":"topic_5","data":{"action":"reply","archetypeId":"private_message"}}"""))
            add(JsonParser.parseString("""{"draft_key":"topic_6","data":{"action":"edit"}}"""))
            add(JsonParser.parseString("""{"draft_key":"bad","data":"{"}"""))
            add(JsonParser.parseString("""{"data":{}}"""))
        }) }
        val page = PersonalContentParser.parse(data, PersonalContentQuery(PersonalContentKind.DRAFTS), base, "fixture")
        assertEquals(6, page.rawCount); assertEquals(5, page.items.size); assertNotNull(page.warning)
        assertEquals(listOf(PersonalDraftType.TOPIC, PersonalDraftType.REPLY, PersonalDraftType.OTHER, PersonalDraftType.OTHER, PersonalDraftType.OTHER), page.items.map { it.draftType })
        assertEquals("Title", page.items[0].title); assertEquals("text", page.items[0].summary)
        assertTrue(page.items[0].matches("text")); assertEquals(160, PersonalContentParser.text("a".repeat(200)).length)
    }
    private class Harness {
        var user: PersonalContentAccount? = PersonalContentAccount("https://linux.do", 7, "fixture", 1)
        val tasks = mutableListOf<() -> Unit>()
        val requests = mutableListOf<PersonalContentQuery>()
        var response: (PersonalContentQuery) -> Result<PersonalContentPage> = { q -> Result.success(PersonalContentPage(listOf(PersonalContentItem("${q.offset}", "item")), 30, q.copy(offset = q.offset + 30))) }
        val service = PersonalContentService({ user }, { _, q -> requests.add(q); response(q) }, { tasks.add(it) }, { it() }, false)
        fun finish() { tasks.removeAt(0)() }
    }
    @Test fun `windows coalesce reads and failure retries the exact page while retaining items`() {
        val h = Harness(); val kind = PersonalContentKind.REPLIES
        h.service.visit(kind); h.service.visit(kind); assertEquals(1, h.tasks.size); h.finish()
        h.response = { Result.failure(HttpStatusException(403)) }; h.service.loadMore(kind); h.finish()
        assertEquals(1, h.service.state(kind).items.size); assertEquals(30, h.service.state(kind).failedQuery?.offset)
        assertTrue(h.service.state(kind).error!!.contains("403"))
        h.response = { q -> Result.success(PersonalContentPage(listOf(PersonalContentItem("30", "next")), 1, null)) }
        h.service.loadMore(kind); h.finish(); assertEquals(listOf(0,30,30), h.requests.map { it.offset }); assertEquals(2, h.service.state(kind).items.size)
        h.service.dispose()
    }
    @Test fun `refresh retains order updates existing rows and fills a nonoverlapping gap`() {
        val h = Harness(); val kind = PersonalContentKind.TOPICS
        h.service.visit(kind); h.finish(); h.service.loadMore(kind); h.finish()
        h.response = { Result.success(PersonalContentPage(listOf(PersonalContentItem("new", "new"), PersonalContentItem("0", "updated")), 30, PersonalContentQuery(kind, 30))) }
        h.service.refresh(kind); h.finish()
        assertEquals(listOf("new","0","30"), h.service.state(kind).items.map { it.key }); assertEquals("updated", h.service.state(kind).items[1].title)
        assertEquals(60, h.service.state(kind).next?.offset)
        h.response = { Result.success(PersonalContentPage(listOf(PersonalContentItem("shifted", "new")), 30, PersonalContentQuery(kind, 30))) }
        h.service.refresh(kind); h.finish(); assertEquals(30, h.service.state(kind).next?.offset)
        h.service.dispose()
    }
    @Test fun `signed out pending and stale account requests cannot populate private caches`() {
        val h = Harness(); val kind = PersonalContentKind.DRAFTS
        h.user = null; h.service.visit(kind); assertTrue(h.tasks.isEmpty())
        h.user = PersonalContentAccount("https://linux.do", 7, "fixture", 1); h.service.visit(kind)
        h.response = { h.user = h.user!!.copy(id = 8, epoch = 2); Result.success(PersonalContentPage(listOf(PersonalContentItem("secret", "secret")), 1, null)) }
        h.finish(); assertTrue(h.service.state(kind).items.isEmpty())
        h.service.visit(kind); h.service.dispose(); h.finish(); assertTrue(h.service.state(kind).items.isEmpty())
    }
    @Test fun `draft broadcasts update summaries without reads and invalidate only this account`() {
        val h = Harness(); val kind = PersonalContentKind.DRAFTS
        h.response = { Result.success(PersonalContentPage(listOf(PersonalContentItem("draft:new_topic", "old", draftKey = "new_topic")), 1, null)) }
        h.service.visit(kind); h.finish()
        h.service.draftSaved("new_topic", ForumDraft(1, JsonParser.parseString("""{"title":"changed","reply":"new"}""").asJsonObject), 1)
        assertEquals("new", h.service.state(kind).items.single().summary); assertTrue(h.tasks.isEmpty())
        h.service.draftCleared("new_topic", 2); assertEquals(1, h.service.state(kind).items.size)
        h.service.draftCleared("new_topic", 1); assertTrue(h.service.state(kind).items.isEmpty()); h.service.dispose()
    }
    @Test fun `draft pagination retains malformed identifiable rows and advances raw offset`() {
        val response = JsonObject().apply { add("drafts", JsonArray().apply { repeat(30) { i -> add(JsonParser.parseString("""{"draft_key":"new_topic_$i","data":{"action":{},"reply":{}}}""")) } }) }
        val page = PersonalContentParser.parse(response, PersonalContentQuery(PersonalContentKind.DRAFTS, 30), base, "fixture")
        assertEquals(30, page.items.size); assertEquals(60, page.next?.offset); assertNotNull(page.warning)
        assertTrue(page.items.all { it.draftType == PersonalDraftType.OTHER })
    }
    @Test fun `cloudflare rate limiting and not found require explicit retry without losing history`() {
        for(error in listOf(HttpStatusException(404), RateLimitException(30), CloudflareChallengeException("challenge"))) {
            val h = Harness(); val kind = PersonalContentKind.BOOKMARKS
            h.service.visit(kind); h.finish(); h.response = { Result.failure(error) }
            h.service.loadMore(kind); h.finish(); h.service.visit(kind)
            assertTrue(h.tasks.isEmpty()); assertEquals(1, h.service.state(kind).items.size); assertNotNull(h.service.state(kind).error)
            h.service.loadMore(kind); assertEquals(1, h.tasks.size); h.service.dispose(); h.finish()
        }
    }
    @Test fun `save during list read remains dirty for next visit and forum change clears every tab`() {
        val h = Harness(); val kind = PersonalContentKind.TOPICS
        h.service.visit(kind); h.service.invalidate(kind, 1); h.finish()
        assertTrue(h.service.state(kind).dirty); h.service.visit(kind); assertEquals(1, h.tasks.size); h.finish()
        h.user = h.user!!.copy(forum = "https://other.test"); assertTrue(h.service.state(kind).items.isEmpty()); h.service.dispose()
    }
}
