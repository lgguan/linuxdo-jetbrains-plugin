package com.lgguan.linuxdo.plugin.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.net.HttpStatusException
import com.lgguan.linuxdo.plugin.net.SessionEpoch

internal data class TopicDraftContent(val title: String = "", val body: String = "", val categoryId: Int? = null,
    val tags: List<String> = emptyList()) {
    fun write(previous: JsonObject?): JsonObject = (previous?.deepCopy() ?: JsonObject()).apply {
        addProperty("action", "createTopic")
        addProperty("archetypeId", "regular")
        addProperty("title", title)
        addProperty("reply", body)
        addProperty("categoryId", categoryId)
        val existingTags = previous?.get("tags")?.takeIf { it.isJsonArray }?.asJsonArray?.filter { it.isJsonObject }
            ?.associateBy { (it.asJsonObject.get("name") ?: it.asJsonObject.get("text"))?.asString.orEmpty() }.orEmpty()
        add("tags", com.google.gson.JsonArray().apply { tags.forEach { name ->
            existingTags[name]?.let { add(it.deepCopy()) } ?: add(name)
        } })
    }
    val empty: Boolean get() = title.isBlank() && body.isBlank() && tags.isEmpty()
    companion object {
        fun read(draft: ForumDraft): TopicDraftContent {
            val data = draft.data ?: return TopicDraftContent()
            fun text(key: String) = data.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val tags = data.getAsJsonArray("tags")?.mapNotNull { item ->
                if (item.isJsonPrimitive) item.asString else if (item.isJsonObject)
                    item.asJsonObject.get("name")?.asString ?: item.asJsonObject.get("text")?.asString else null
            }.orEmpty()
            return TopicDraftContent(text("title"), text("reply"), data.get("categoryId")?.takeUnless { it.isJsonNull }?.asInt, tags)
        }
    }
}

internal data class DraftEntry(val key: String, val draft: ForumDraft, val updatedAt: String) {
    val topic: Boolean get() = (key == "new_topic" || key.startsWith("new_topic_")) && draft.isTopicDraft
    companion object {
        fun parse(response: JsonObject): List<DraftEntry> = response.getAsJsonArray("drafts")?.map { item ->
            val obj = item.asJsonObject
            val raw = obj.get("data")?.takeUnless { it.isJsonNull }
            val data = raw?.let { if (it.isJsonPrimitive) JsonParser.parseString(it.asString).asJsonObject else it.asJsonObject }
            DraftEntry(obj.get("draft_key").asString, ForumDraft(obj.get("sequence").asLong, data),
                (obj.get("updated_at") ?: obj.get("created_at"))?.takeUnless { it.isJsonNull }?.asString.orEmpty())
        }.orEmpty()
    }
}

internal val ForumDraft.isTopicDraft: Boolean get() = data == null ||
    (data.get("action")?.asString == "createTopic" &&
        data.get("archetypeId")?.takeUnless { it.isJsonNull }?.asString.let { it == null || it == "regular" } &&
        data.get("whisper")?.takeUnless { it.isJsonNull }?.asBoolean != true &&
        listOf("sharedDraft", "sharedDraftId", "formTemplateId", "featuredLink", "recipients", "targetRecipients")
            .none { key -> data.get(key)?.takeUnless { it.isJsonNull }?.let { it.toString() !in setOf("false", "\"\"", "[]", "0") } == true })

/** All reads, saves, cleanup and publishing for one key share a monitor. No disk persistence. */
internal class ForumDraftSession(
    val key: String,
    val version: Long = SessionEpoch.current,
    private val transport: DraftTransport = ForumDraftTransport,
    private val checkSession: (Long) -> Unit = SessionEpoch::requireCurrent,
    private val supported: (ForumDraft) -> Boolean = { it.isTopicDraft }
) {
    private var draft: ForumDraft? = null
    var conflicted = false
        private set
    @Synchronized fun load(): ForumDraft {
        checkSession(version)
        return transport.read(key, version).getOrThrow().also { checkSession(version); draft = it }
    }
    @Synchronized fun choose(server: ForumDraft) {
        checkSession(version)
        require(supported(server)) { "此草稿类型请在网页继续" }
        draft = server
        conflicted = false
    }
    @Synchronized fun save(transform: (JsonObject?) -> JsonObject): ForumDraft {
        checkSession(version)
        check(!conflicted) { "草稿冲突，请先选择保留版本" }
        val previous = checkNotNull(draft) { "请先读取论坛草稿" }
        require(supported(previous)) { "此草稿类型请在网页继续" }
        val data = transform(previous.data?.deepCopy())
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
        require(supported(owned)) { "此草稿类型请在网页继续" }
        val current = transport.read(key, version).getOrThrow()
        checkSession(version)
        if (current.data == null) return Cleanup.CLEARED
        if (current != owned) return Cleanup.OTHER_CLIENT
        transport.delete(key, owned.sequence, version).getOrThrow()
        val after = transport.read(key, version).getOrThrow()
        checkSession(version)
        return if (after.data == null) Cleanup.CLEARED else Cleanup.OTHER_CLIENT
    }
    @Synchronized fun <T> publish(transform: (JsonObject?) -> JsonObject, send: () -> T): Pair<T, Result<Cleanup>> {
        save(transform)
        checkSession(version)
        val result = send()
        return result to runCatching { clearOwned() }
    }
    companion object {
        const val NEW_TOPIC_KEY = "new_topic"
    }
}
