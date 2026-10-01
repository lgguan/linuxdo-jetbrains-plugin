package com.lgguan.linuxdo.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.net.HttpStatusException
import com.lgguan.linuxdo.plugin.net.StaleSessionException
import com.lgguan.linuxdo.plugin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TopicDraftSessionTest {
    private class Store : DraftTransport {
        val entries = mutableMapOf<String, ForumDraft>()
        var offline = false
        var deleted = mutableListOf<String>()
        var saves = 0
        var replaceOnDelete = false
        @Synchronized override fun read(key: String, version: Long) = if (offline) Result.failure(IOException("offline")) else
            Result.success(entries[key] ?: ForumDraft(0, null))
        @Synchronized override fun save(key: String, sequence: Long, data: JsonObject, version: Long): Result<Long> {
            if (offline) return Result.failure(IOException("offline"))
            val existing = entries[key] ?: ForumDraft(0, null)
            if (existing.sequence != sequence) return Result.failure(HttpStatusException(409))
            val next = sequence + 1
            saves++; entries[key] = ForumDraft(next, data.deepCopy())
            return Result.success(next)
        }
        @Synchronized override fun delete(key: String, sequence: Long, version: Long): Result<Unit> {
            if (replaceOnDelete) entries[key] = ForumDraft(sequence + 1, TopicDraftContent("其他客户端", "新内容").write(null))
            else if (entries[key]?.sequence == sequence) { deleted.add(key); entries.remove(key) }
            return Result.success(Unit)
        }
    }
    private fun session(store: Store, key: String = "new_topic_1") = ForumDraftSession(key, 1, store, {})
    @Test fun `new topic uses the native single draft key`() {
        assertEquals("new_topic", ForumDraftSession.NEW_TOPIC_KEY)
        val store = Store()
        val first = session(store, ForumDraftSession.NEW_TOPIC_KEY)
        assertNull(first.load().data)
        val content = TopicDraftContent("继续编辑的标题", "网页与插件接续的正文", 4, listOf("软件开发"))
        first.save(content::write)
        val reopened = session(store, ForumDraftSession.NEW_TOPIC_KEY)
        assertEquals(content, TopicDraftContent.read(reopened.load()))
        assertEquals(listOf("new_topic"), store.entries.keys.toList())
    }
    @Test fun `new draft content restores legacy and object tags with target category`() {
        val draft = ForumDraft(10, JsonParser.parseString("""{"action":"createTopic","reply":"中文正文","title":"原标题","categoryId":4,"tags":["纯水",{"id":1451,"name":"软件开发","slug":"1451-tag"}],"unknown":{"nested":"keep"}}""").asJsonObject)
        val restored = TopicDraftContent.read(draft)
        assertEquals(TopicDraftContent("原标题", "中文正文", 4, listOf("纯水", "软件开发")), restored)
        assertTrue(draft.isTopicDraft)
        val updated = restored.copy(body = "新正文").write(draft.data)
        assertEquals("keep", updated.getAsJsonObject("unknown").get("nested").asString)
        assertEquals(1451, updated.getAsJsonArray("tags")[1].asJsonObject.get("id").asInt)
        assertEquals("中文正文", draft.body)
    }
    @Test fun `two topic drafts save independently and serial saves use latest sequence`() {
        val store = Store()
        val a = session(store, "new_topic_1"); val b = session(store, "new_topic_2")
        a.load(); b.load()
        val first = TopicDraftContent("第一份", "正文", 4, listOf("纯水"))
        assertEquals(1, a.save(first::write).sequence)
        assertEquals(1, a.save(first::write).sequence)
        assertEquals(2, a.save(first.copy(body = "修改")::write).sequence)
        b.save(TopicDraftContent("第二份", "其他正文")::write)
        assertEquals("修改", store.entries["new_topic_1"]?.body)
        assertEquals("其他正文", store.entries["new_topic_2"]?.body)
        a.clearOwned()
        assertEquals(listOf("new_topic_1"), store.deleted)
        assertNotNull(store.entries["new_topic_2"])
    }
    @Test fun `conflict pauses saves until an explicit full version choice`() {
        val store = Store(); val s = session(store)
        s.load(); s.save(TopicDraftContent("本地", "正文", 4, listOf("纯水"))::write)
        val web = TopicDraftContent("网页标题", "网页正文", 5, listOf("软件开发"))
        store.entries[s.key] = ForumDraft(2, web.write(null))
        assertThrows(HttpStatusException::class.java) { s.save(TopicDraftContent("本地标题", "修改")::write) }
        assertTrue(s.conflicted)
        assertThrows(IllegalStateException::class.java) { s.save(web::write) }
        val server = s.load()
        assertTrue(s.conflicted, "Reading never silently chooses the server")
        s.choose(server)
        assertFalse(s.conflicted)
        val local = TopicDraftContent("保留本地", "本地版本", 4, listOf("纯水"))
        assertEquals(3, s.save(local::write).sequence)
        assertEquals(local, TopicDraftContent.read(store.entries[s.key]!!))
    }
    @Test fun `offline save leaves owned version intact for retry`() {
        val store = Store(); val s = session(store); s.load()
        store.offline = true
        val local = TopicDraftContent("未同步", "本地编辑内容")
        assertThrows(IOException::class.java) { s.save(local::write) }
        assertFalse(s.conflicted)
        store.offline = false
        assertEquals(1, s.save(local::write).sequence)
    }
    @Test fun `account change prevents reading writing publishing and cleanup`() {
        val store = Store(); var valid = true
        val s = ForumDraftSession("new_topic_1", 1, store, { if (!valid) throw StaleSessionException() })
        s.load(); valid = false
        assertThrows(StaleSessionException::class.java) { s.load() }
        assertThrows(StaleSessionException::class.java) { s.save(TopicDraftContent("标题", "正文")::write) }
        assertThrows(StaleSessionException::class.java) { s.clearOwned() }
        var sent = false
        assertThrows(StaleSessionException::class.java) { s.publish(TopicDraftContent("标题", "正文")::write) { sent = true } }
        assertFalse(sent); assertEquals(0, store.saves)
    }
    @Test fun `successful publish clears owned draft and failed publish preserves it`() {
        val store = Store(); val s = session(store); s.load()
        val content = TopicDraftContent("标题", "正文")
        assertThrows(IOException::class.java) { s.publish(content::write) { throw IOException("send failure") } }
        assertEquals(content, TopicDraftContent.read(store.entries[s.key]!!))
        val (accepted, cleanup) = s.publish(content::write) { "queued" }
        assertEquals("queued", accepted)
        assertEquals(ForumDraftSession.Cleanup.CLEARED, cleanup.getOrThrow())
    }
    @Test fun `other client draft is preserved before and during cleanup`() {
        val store = Store(); val s = session(store); s.load()
        s.save(TopicDraftContent("本地", "正文")::write)
        store.entries[s.key] = ForumDraft(2, TopicDraftContent("网页", "修改") .write(null))
        assertEquals(ForumDraftSession.Cleanup.OTHER_CLIENT, s.clearOwned())
        assertTrue(store.deleted.isEmpty())
        s.choose(s.load()); store.replaceOnDelete = true
        assertEquals(ForumDraftSession.Cleanup.OTHER_CLIENT, s.clearOwned())
        assertEquals("新内容", store.entries[s.key]?.body)
    }
    @ParameterizedTest @ValueSource(strings = ["{\"action\":\"reply\"}", "{\"action\":\"createTopic\",\"archetypeId\":\"private_message\"}",
        "{\"action\":\"createTopic\",\"sharedDraftId\":5}", "{\"action\":\"createTopic\",\"formTemplateId\":3}", "{\"action\":\"createTopic\",\"whisper\":true}"])
    fun `unsupported draft cannot be changed or removed`(json: String) {
        val store = Store(); store.entries["new_topic_1"] = ForumDraft(2, JsonParser.parseString(json).asJsonObject)
        val s = session(store); assertFalse(s.load().isTopicDraft)
        assertThrows(IllegalArgumentException::class.java) { s.save(TopicDraftContent("新标题", "正文")::write) }
        assertThrows(IllegalArgumentException::class.java) { s.clearOwned() }
        assertEquals(0, store.saves); assertTrue(store.deleted.isEmpty())
    }
    @Test fun `draft index reads native string data dates and unique keys`() {
        val entries = DraftEntry.parse(JsonParser.parseString("""{"drafts":[{"draft_key":"new_topic_123","sequence":8,"created_at":"2026-10-01T00:00:00Z","data":"{\"action\":\"createTopic\",\"title\":\"草稿标题\",\"reply\":\"正文\"}"},{"draft_key":"new_topic","sequence":4,"data":{"action":"createTopic","title":"旧键"}},{"draft_key":"topic_482293","sequence":2,"data":{"action":"reply"}}]}""").asJsonObject)
        assertEquals(3, entries.size)
        assertTrue(entries[0].topic); assertTrue(entries[1].topic); assertFalse(entries[2].topic)
        assertEquals("2026-10-01T00:00:00Z", entries[0].updatedAt)
        assertEquals("草稿标题", TopicDraftContent.read(entries[0].draft).title)
    }
    @Test fun `publish waits for an in flight save and uses its returned sequence`() {
        val store = Store(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val transport = object : DraftTransport by store {
            override fun save(key: String, sequence: Long, data: JsonObject, version: Long): Result<Long> {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                return store.save(key, sequence, data, version)
            }
        }
        val s = ForumDraftSession("new_topic_1", 1, transport, {}); s.load()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<ForumDraft> { s.save(TopicDraftContent("第一版", "正文")::write) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val sent = CountDownLatch(1)
            val publish = pool.submit { s.publish(TopicDraftContent("第二版", "最新正文")::write) { sent.countDown() } }
            assertFalse(sent.await(100, TimeUnit.MILLISECONDS))
            release.countDown(); first.get(5, TimeUnit.SECONDS); publish.get(5, TimeUnit.SECONDS)
            assertEquals(0, sent.count); assertEquals(2, store.saves)
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
