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
        data.get("archetypeId")?.takeUnless { it.isJsonNull }?.asString.let { it == null || it == "regular" } &&
        listOf("sharedDraft", "sharedDraftId", "formTemplateId", "recipients", "targetRecipients")
            .none { key -> data.get(key)?.takeUnless { it.isJsonNull }?.let { it.toString() !in setOf("false", "\"\"", "[]", "0") } == true })
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
    private val session = ForumDraftSession("topic_$topicId", version, transport, checkSession) { it.supported }
    val key: String get() = session.key
    val conflicted: Boolean get() = session.conflicted
    fun load(): ForumDraft = session.load()
    fun choose(server: ForumDraft) = session.choose(server)
    fun save(body: String, target: ReplyTarget): ForumDraft = session.save { data -> replyData(data, body, target) }
    private fun replyData(previous: JsonObject?, body: String, target: ReplyTarget): JsonObject =
        (previous ?: JsonObject()).apply {
            addProperty("action", "reply")
            addProperty("reply", body)
            addProperty("reply_to_post_number", target.floor)
            add("reply_to_user", target.author.takeIf { it.isNotBlank() }?.let { author ->
                (get("reply_to_user")?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()).apply {
                    addProperty("username", author)
                }
            })
            addProperty("postId", target.postId)
        }
    enum class Cleanup { CLEARED, OTHER_CLIENT }
    fun clearOwned(): Cleanup = Cleanup.valueOf(session.clearOwned().name)
    fun <T> publish(body: String, target: ReplyTarget, send: () -> T): Pair<T, Result<Cleanup>> {
        val (result, cleanup) = session.publish({ replyData(it, body, target) }, send)
        return result to cleanup.map { Cleanup.valueOf(it.name) }
    }
}
