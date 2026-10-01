package com.lgguan.linuxdo.plugin.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.net.HttpStatusException
import com.lgguan.linuxdo.plugin.net.SessionEpoch

internal data class ReplyTarget(val floor: Int? = null, val author: String = "", val postId: Long? = null)
internal data class ForumDraft(val sequence: Long, val data: JsonObject?) {
    val body: String get() = data?.get("reply")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    val supported: Boolean get() = data == null || (data.get("action")?.takeUnless { it.isJsonNull }?.asString == "reply" &&
        data.get("whisper")?.takeUnless { it.isJsonNull }?.asBoolean != true &&
        data.get("archetypeId")?.takeUnless { it.isJsonNull }?.asString.let { it == null || it == "regular" })
    fun target(): ReplyTarget = ReplyTarget(
        data?.get("reply_to_post_number")?.takeUnless { it.isJsonNull }?.asInt,
        data?.get("reply_to_user")?.takeIf { it.isJsonObject }?.asJsonObject?.get("username")?.takeUnless { it.isJsonNull }?.asString.orEmpty(),
        data?.get("postId")?.takeUnless { it.isJsonNull }?.asLong)

    companion object {
        fun requireSuccess(response: JsonObject) {
            require(response.get("success")?.takeUnless { it.isJsonNull }?.asString in setOf("OK", "true")) { "论坛未确认草稿操作" }
        }
        fun savedSequence(response: JsonObject): Long {
            requireSuccess(response)
            return requireNotNull(response.get("draft_sequence")) { "草稿保存响应缺少序列号" }.asLong
        }
        fun parse(response: JsonObject): ForumDraft {
            val sequence = requireNotNull(response.get("draft_sequence")) { "草稿响应缺少序列号" }.asLong
            val raw = response.get("draft")?.takeUnless { it.isJsonNull }
            val data = raw?.let { if (it.isJsonPrimitive) JsonParser.parseString(it.asString).asJsonObject else it.asJsonObject }
            return ForumDraft(sequence, data)
        }
    }
}

internal interface DraftTransport {
    fun read(key: String, version: Long): Result<ForumDraft>
    fun save(key: String, sequence: Long, data: JsonObject, version: Long): Result<Long>
    fun delete(key: String, sequence: Long, version: Long): Result<Unit>
}

internal object ForumDraftTransport : DraftTransport {
    override fun read(key: String, version: Long) = DiscourseApiClient.readDraft(key, version).mapCatching(ForumDraft::parse)
    override fun save(key: String, sequence: Long, data: JsonObject, version: Long) =
        DiscourseApiClient.writeDraft(key, sequence, data, version).mapCatching(ForumDraft::savedSequence)
    override fun delete(key: String, sequence: Long, version: Long) =
        DiscourseApiClient.writeDraft(key, sequence, null, version).mapCatching {
            ForumDraft.requireSuccess(it)
        }
}

/** Memory only. Every operation, including publish, shares this session's monitor. */
internal class ReplyDraftSession(
    topicId: Long,
    val version: Long = SessionEpoch.current,
    private val transport: DraftTransport = ForumDraftTransport,
    private val checkSession: (Long) -> Unit = SessionEpoch::requireCurrent
) {
    val key = "topic_$topicId"
    private var draft: ForumDraft? = null
    var conflicted = false
        private set

    @Synchronized fun load(): ForumDraft {
        checkSession(version)
        val loaded = transport.read(key, version).getOrThrow()
        checkSession(version)
        draft = loaded
        return loaded
    }

    @Synchronized fun choose(server: ForumDraft) {
        checkSession(version)
        require(server.supported) { "仅支持普通回复草稿" }
        draft = server
        conflicted = false
    }

    @Synchronized fun save(body: String, target: ReplyTarget): ForumDraft {
        checkSession(version)
        check(!conflicted) { "草稿冲突，请先选择保留版本" }
        val previous = checkNotNull(draft) { "请先读取论坛草稿" }
        require(previous.supported) { "仅支持普通回复草稿" }
        val data = previous.data?.deepCopy() ?: JsonObject()
        data.addProperty("action", "reply")
        data.addProperty("reply", body)
        data.addProperty("reply_to_post_number", target.floor)
        data.add("reply_to_user", target.author.takeIf { it.isNotBlank() }?.let {
            (data.get("reply_to_user")?.takeIf { value -> value.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()).apply {
                addProperty("username", it)
            }
        })
        data.addProperty("postId", target.postId)
        if (data == previous.data) return previous
        val result = transport.save(key, previous.sequence, data, version)
        if ((result.exceptionOrNull() as? HttpStatusException)?.status == 409) conflicted = true
        val sequence = result.getOrThrow()
        checkSession(version)
        return ForumDraft(sequence, data).also { draft = it }
    }

    enum class Cleanup { CLEARED, OTHER_CLIENT }
    @Synchronized fun clearOwned(): Cleanup {
        checkSession(version)
        check(!conflicted) { "草稿冲突，请先选择保留版本" }
        val owned = checkNotNull(draft) { "尚未读取论坛草稿" }
        require(owned.supported) { "仅支持普通回复草稿" }
        val current = transport.read(key, version).getOrThrow()
        checkSession(version)
        if (current.data == null) return Cleanup.CLEARED
        if (current != owned) return Cleanup.OTHER_CLIENT
        transport.delete(key, owned.sequence, version).getOrThrow()
        val after = transport.read(key, version).getOrThrow()
        checkSession(version)
        return if (after.data == null) Cleanup.CLEARED else Cleanup.OTHER_CLIENT
    }

    @Synchronized fun <T> publish(body: String, target: ReplyTarget, send: () -> T): Pair<T, Result<Cleanup>> {
        save(body, target)
        checkSession(version)
        val published = send()
        // A confirmed post is never made retryable by a cleanup failure.
        return published to runCatching { clearOwned() }
    }
}
