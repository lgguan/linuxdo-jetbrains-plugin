package com.lgguan.linuxdo.plugin

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.net.HttpStatusException
import com.lgguan.linuxdo.plugin.net.StaleSessionException
import com.lgguan.linuxdo.plugin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReplyDraftSessionTest {
    private fun data(body: String = "web", action: String = "reply") = JsonObject().apply {
        addProperty("reply", body); addProperty("action", action)
        addProperty("reply_to_post_number", 12)
        add("reply_to_user", JsonObject().apply { addProperty("username", "作者"); addProperty("id", 7) })
        addProperty("postId", 99)
        add("unknown", JsonObject().apply { addProperty("nested", "keep") })
    }
    private class Fake(var server: ForumDraft = ForumDraft(3, null)) : DraftTransport {
        val sequences = mutableListOf<Long>()
        var deletes = 0
        var offline = false
        var deleteHook: (() -> Unit)? = null
        override fun read(key: String, version: Long): Result<ForumDraft> = if (offline) Result.failure(IOException("offline")) else Result.success(server.copy(data = server.data?.deepCopy()))
        override fun save(key: String, sequence: Long, data: JsonObject, version: Long): Result<Long> {
            if (offline) return Result.failure(IOException("offline"))
            sequences.add(sequence)
            if (sequence != server.sequence) return Result.failure(HttpStatusException(409))
            val next = if (server.data == null) sequence else sequence + 1
            server = ForumDraft(next, data.deepCopy())
            return Result.success(next)
        }
        override fun delete(key: String, sequence: Long, version: Long): Result<Unit> {
            deletes++
            deleteHook?.invoke()
            if (server.sequence == sequence) server = ForumDraft(sequence, null)
            return Result.success(Unit) // Discourse silently skips stale deletes.
        }
    }
    private fun session(fake: Fake) = ReplyDraftSession(8, 100, fake) { }

    @Test fun `web draft restores reply target and unknown fields survive consecutive saves`() {
        val fake = Fake(ForumDraft(10, data()))
        val session = session(fake)
        val draft = session.load()
        assertEquals(ReplyTarget(12, "作者", 99), draft.target())
        assertEquals("web", draft.body)
        session.save("中文\n```kotlin\nval x = 1\n```", draft.target())
        session.save("second", draft.target())
        assertEquals(listOf(10L, 11L), fake.sequences)
        assertEquals(12, fake.server.sequence)
        assertEquals("keep", fake.server.data!!.getAsJsonObject("unknown").get("nested").asString)
        assertEquals(7, fake.server.data!!.getAsJsonObject("reply_to_user").get("id").asInt)
        assertEquals("second", session(fake).load().body)
    }

    @Test fun `first save can return unchanged sequence and later saves use server sequence`() {
        val fake = Fake()
        val session = session(fake)
        session.load()
        session.save("first", ReplyTarget())
        session.save("second", ReplyTarget())
        assertEquals(listOf(3L, 3L), fake.sequences)
        assertEquals(4, fake.server.sequence)
        assertEquals(ReplyTarget(), fake.server.target())
    }

    @Test fun `409 pauses saves and publish until a version is explicitly chosen`() {
        val fake = Fake(ForumDraft(10, data()))
        val session = session(fake)
        session.load()
        fake.server = ForumDraft(11, data("other client"))
        assertThrows(HttpStatusException::class.java) { session.save("local", ReplyTarget()) }
        assertTrue(session.conflicted)
        assertThrows(IllegalStateException::class.java) { session.publish("local", ReplyTarget()) { error("must not publish") } }
        val latest = session.load()
        assertTrue(session.conflicted)
        session.choose(latest)
        session.save("local chosen", ReplyTarget(2, "new", 100))
        assertEquals("local chosen", fake.server.body)
        assertEquals(ReplyTarget(2, "new", 100), fake.server.target())
    }

    @Test fun `offline save leaves server version intact and can retry same sequence`() {
        val fake = Fake(ForumDraft(10, data()))
        val session = session(fake)
        session.load()
        fake.offline = true
        assertThrows(IOException::class.java) { session.save("local", ReplyTarget()) }
        assertFalse(session.conflicted)
        assertEquals("web", fake.server.body)
        fake.offline = false
        session.save("local", ReplyTarget())
        assertEquals(listOf(10L), fake.sequences)
    }

    @Test fun `unsupported drafts cannot be changed or published`() {
        for (action in listOf("edit", "privateMessage", "createTopic")) {
            val fake = Fake(ForumDraft(10, data(action = action)))
            val session = session(fake)
            assertFalse(session.load().supported)
            assertThrows(IllegalArgumentException::class.java) { session.save("local", ReplyTarget()) }
            assertTrue(fake.sequences.isEmpty())
        }
    }

    @Test fun `account changes reject load save publish and delete`() {
        var current = 100L
        val fake = Fake()
        val session = ReplyDraftSession(8, current, fake) { if (it != current) throw StaleSessionException() }
        session.load()
        current++
        assertThrows(StaleSessionException::class.java) { session.load() }
        assertThrows(StaleSessionException::class.java) { session.save("local", ReplyTarget()) }
        assertThrows(StaleSessionException::class.java) { session.publish("local", ReplyTarget()) { error("must not publish") } }
        assertThrows(StaleSessionException::class.java) { session.clearOwned() }
        assertTrue(fake.sequences.isEmpty())
        assertEquals(0, fake.deletes)
    }

    @Test fun `send failure preserves the synced reply draft`() {
        val fake = Fake()
        val session = session(fake)
        session.load()
        assertThrows(IOException::class.java) { session.publish("local reply", ReplyTarget(12)) { throw IOException("send failed") } }
        assertEquals("local reply", fake.server.body)
        assertEquals(0, fake.deletes)
    }

    @Test fun `publish cleanup preserves a concurrent clients newer draft`() {
        val fake = Fake()
        val session = session(fake)
        session.load()
        val (posted, cleanup) = session.publish("local reply", ReplyTarget()) {
            fake.server = ForumDraft(fake.server.sequence + 1, data("new web draft"))
            "posted"
        }
        assertEquals("posted", posted)
        assertEquals(ReplyDraftSession.Cleanup.OTHER_CLIENT, cleanup.getOrThrow())
        assertEquals("new web draft", fake.server.body)
        assertEquals(0, fake.deletes)
    }

    @Test fun `cleanup verifies silent stale delete and never makes a published reply retryable`() {
        val fake = Fake()
        val session = session(fake)
        session.load()
        fake.deleteHook = { fake.server = ForumDraft(fake.server.sequence + 1, data("concurrent")) }
        assertEquals(ReplyDraftSession.Cleanup.OTHER_CLIENT, session.publish("local reply", ReplyTarget()) { "posted" }.second.getOrThrow())
        fake.deleteHook = null
        session.load()
        val result = session.publish("local reply 2", ReplyTarget()) { fake.offline = true; "posted 2" }
        assertEquals("posted 2", result.first)
        assertTrue(result.second.isFailure)
    }

    @Test fun `successful cleanup verifies the draft is absent`() {
        val fake = Fake()
        val session = session(fake)
        session.load()
        assertEquals(ReplyDraftSession.Cleanup.CLEARED, session.publish("reply", ReplyTarget()) { "posted" }.second.getOrThrow())
        assertNull(fake.server.data)
    }

    @Test fun `publish waits for in flight save using the same draft lock`() {
        val fake = Fake()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = object : DraftTransport by fake {
            override fun save(key: String, sequence: Long, data: JsonObject, version: Long): Result<Long> {
                if (data.get("reply").asString == "first") { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return fake.save(key, sequence, data, version)
            }
        }
        val session = ReplyDraftSession(8, 100, transport) { }
        session.load()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val save = pool.submit<ForumDraft> { session.save("first", ReplyTarget()) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val publish = pool.submit<String> { session.publish("second", ReplyTarget()) { "posted" }.first }
            assertFalse(publish.isDone)
            release.countDown()
            save.get(5, TimeUnit.SECONDS)
            assertEquals("posted", publish.get(5, TimeUnit.SECONDS))
            assertEquals(listOf(3L, 3L), fake.sequences)
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun `draft response parses string data null target and empty drafts`() {
        val parsed = ForumDraft.parse(JsonParser.parseString("""{"draft_sequence":7,"draft":"{\"action\":\"reply\",\"reply\":\"中文\\n第二行\",\"reply_to_user\":null}"}""").asJsonObject)
        assertEquals("中文\n第二行", parsed.body)
        assertEquals(ReplyTarget(), parsed.target())
        assertTrue(ForumDraft.parse(JsonParser.parseString("""{"draft_sequence":9,"draft":null}""").asJsonObject).supported)
    }

    @Test fun `native success OK response uses returned sequence including zero`() {
        assertEquals(0L, ForumDraft.savedSequence(JsonParser.parseString("""{"success":"OK","draft_sequence":0}""").asJsonObject))
        ForumDraft.requireSuccess(JsonParser.parseString("""{"success":"OK"}""").asJsonObject)
        assertThrows(IllegalArgumentException::class.java) { ForumDraft.savedSequence(JsonParser.parseString("""{"success":"OK"}""").asJsonObject) }
        assertThrows(IllegalArgumentException::class.java) { ForumDraft.requireSuccess(JsonParser.parseString("""{"failed":"FAILED"}""").asJsonObject) }
    }
}
