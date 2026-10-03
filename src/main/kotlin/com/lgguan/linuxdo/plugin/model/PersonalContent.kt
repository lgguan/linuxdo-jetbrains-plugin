package com.lgguan.linuxdo.plugin.model

import com.google.gson.*
import com.lgguan.linuxdo.plugin.service.ForumDraft
import com.lgguan.linuxdo.plugin.service.isTopicDraft
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup

enum class PersonalContentKind(val label: String, val webPath: String) {
    TOPICS("话题", "topics"), REPLIES("回复", "replies"), BOOKMARKS("书签", "bookmarks"), DRAFTS("草稿", "drafts")
}
enum class PersonalDraftType(val label: String) { TOPIC("新话题"), REPLY("回复"), OTHER("其他类型") }
data class PersonalContentItem(val key: String, val title: String, val summary: String = "", val time: String = "",
    val detail: String = "", val topicId: Long? = null, val floor: Int? = null, val webUrl: String? = null,
    val draftKey: String? = null, val draftType: PersonalDraftType? = null, val postId: Long? = null) {
    fun matches(query: String) = query.isBlank() || listOf(title, summary, detail).any { it.contains(query.trim(), true) }
}
data class PersonalContentQuery(val kind: PersonalContentKind, val offset: Int = 0) { init { require(offset in 0..1_000_000) } }
data class PersonalContentPage(val items: List<PersonalContentItem>, val rawCount: Int, val next: PersonalContentQuery?, val warning: String? = null)
data class PersonalContentState(val items: List<PersonalContentItem> = emptyList(), val next: PersonalContentQuery? = null,
    val loaded: Boolean = false, val loading: Boolean = false, val error: String? = null,
    val failedQuery: PersonalContentQuery? = null, val warning: String? = null, val dirty: Boolean = false)

internal object PersonalContentParser {
    const val LIMIT = 30
    fun encoded(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    fun text(value: String): String = Jsoup.parse(value).apply { select("script,style").remove() }.text().take(160)
    fun summary(value: String): String = text(org.commonmark.renderer.html.HtmlRenderer.builder().build()
        .render(org.commonmark.parser.Parser.builder().build().parse(value)))
    fun string(obj: JsonObject, key: String): String = runCatching { obj[key]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }.getOrDefault("")
    fun number(obj: JsonObject, key: String): Long? = runCatching { obj[key]?.asLong?.takeIf { it > 0 } }.getOrNull()
    private fun floor(obj: JsonObject, key: String): Int? = number(obj, key)?.takeIf { it <= Int.MAX_VALUE }?.toInt()
    fun path(query: PersonalContentQuery, username: String): String = when(query.kind) {
        PersonalContentKind.TOPICS, PersonalContentKind.REPLIES -> "/user_actions.json?username=${encoded(username)}&filter=${if(query.kind == PersonalContentKind.TOPICS) 4 else 5}&offset=${query.offset}&limit=$LIMIT"
        PersonalContentKind.BOOKMARKS -> "/u/${encoded(username)}/bookmarks.json?page=${query.offset}"
        PersonalContentKind.DRAFTS -> "/drafts.json?offset=${query.offset}&limit=$LIMIT"
    }
    fun safeUrl(raw: String, base: String): String? = runCatching {
        val origin = URI(base); val url = origin.resolve(raw)
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port && url.userInfo == null && url.fragment == null)
        require(url.path.matches(Regex("/t/(?:[A-Za-z0-9_-]+/)?[1-9][0-9]*(?:/[1-9][0-9]*)?")) && url.query == null)
        url.toASCIIString()
    }.getOrNull()
    fun bookmarkNext(raw: String, base: String, username: String, current: Int): Int? = runCatching {
        val origin = URI(base); val url = origin.resolve(raw)
        require(url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port && url.userInfo == null && url.fragment == null)
        require(url.path in setOf("/u/$username/bookmarks", "/u/$username/bookmarks.json"))
        val page = Regex("page=([0-9]+)").matchEntire(url.query.orEmpty())?.groupValues?.get(1)?.toInt() ?: error("Invalid page")
        require(page > current && page <= 10000); page
    }.getOrNull()
    fun bookmark(obj: JsonObject, base: String): PersonalContentItem? {
        val id = number(obj, "id") ?: return null
        val type = string(obj, "bookmarkable_type")
        val topic = number(obj, "topic_id") ?: if(type == "Topic") number(obj, "bookmarkable_id") else null
        val floor = floor(obj, "linked_post_number") ?: floor(obj, "post_number")
        val known = type in setOf("", "Post", "Topic")
        val target = topic?.takeIf { known && (floor != null || type == "Topic") }
        val web = listOf("url", "bookmarkable_url").firstNotNullOfOrNull { safeUrl(string(obj, it), base) }
        return PersonalContentItem("bookmark:$id", text(string(obj, "title")).ifBlank { "书签" }, time = string(obj, "created_at"),
            detail = listOf(string(obj, "name"), floor?.let { "#$it" }.orEmpty(), string(obj, "reminder_at")).filter { it.isNotBlank() }.joinToString(" · "),
            topicId = target, floor = if(type == "Topic") null else floor, webUrl = web)
    }
    fun parse(response: JsonObject, query: PersonalContentQuery, base: String, username: String): PersonalContentPage {
        val container = if(query.kind == PersonalContentKind.BOOKMARKS) response.get("user_bookmark_list")?.takeIf { it.isJsonObject }?.asJsonObject ?: response else response
        val field = when(query.kind) { PersonalContentKind.BOOKMARKS -> "bookmarks"; PersonalContentKind.DRAFTS -> "drafts"; else -> "user_actions" }
        val rows = container[field]?.takeIf { it.isJsonArray }?.asJsonArray ?: error("接口未返回 $field 列表")
        var failures = 0
        val items = rows.mapNotNull { raw -> runCatching {
            val obj = raw.asJsonObject
            when(query.kind) {
                PersonalContentKind.BOOKMARKS -> bookmark(obj, base)
                PersonalContentKind.DRAFTS -> {
                    val key = string(obj, "draft_key").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,200}")) } ?: error("Invalid key")
                    val data = runCatching { obj["data"]?.takeUnless { it.isJsonNull }?.let { if(it.isJsonObject) it.asJsonObject else JsonParser.parseString(it.asString).asJsonObject } }
                    if(data.isFailure) failures++
                    val draft = ForumDraft(number(obj, "sequence") ?: 0, data.getOrNull())
                    val topic = Regex("topic_([1-9][0-9]*)").matchEntire(key)?.groupValues?.get(1)?.toLongOrNull()
                    val type = runCatching { when { data.getOrNull() == null -> PersonalDraftType.OTHER
                        (key == "new_topic" || key.startsWith("new_topic_")) && draft.isTopicDraft -> PersonalDraftType.TOPIC
                        topic != null && draft.supported -> PersonalDraftType.REPLY
                        else -> PersonalDraftType.OTHER } }.onFailure { failures++ }.getOrDefault(PersonalDraftType.OTHER)
                    PersonalContentItem("draft:$key", text(data.getOrNull()?.let { string(it, "title").ifBlank { string(it, "topicTitle") } }.orEmpty()).ifBlank { topic?.let { "话题 #$it" } ?: type.label },
                        summary(data.getOrNull()?.let { string(it, "reply") }.orEmpty()), string(obj, "updated_at").ifBlank { string(obj, "created_at") }, type.label, topicId = topic, draftKey = key, draftType = type)
                }
                else -> {
                    val topic = number(obj, "topic_id") ?: error("Missing topic")
                    val floor = floor(obj, "post_number")
                    val reply = query.kind == PersonalContentKind.REPLIES
                    val key = if(!reply) "topic:$topic" else number(obj, "post_id")?.let { "post:$it" } ?: floor?.let { "reply:$topic:$it" } ?: error("Missing reply target")
                    PersonalContentItem(key, text(string(obj, "title")).ifBlank { "话题 #$topic" }, if(reply) text(string(obj, "excerpt")) else "",
                        string(obj, "created_at"), listOf(if(reply) floor?.let { "#$it" }.orEmpty() else "", string(obj, "category_name").ifBlank {
                            number(obj, "category_id")?.let { id -> "板块 #$id" }.orEmpty()
                        }).filter { it.isNotBlank() }.joinToString(" · "), topic, if(reply) floor else 1,
                        postId = if(reply) number(obj, "post_id") else null)
                }
            }
        }.onFailure { failures++ }.getOrNull() }
        val next = if(query.kind == PersonalContentKind.BOOKMARKS) bookmarkNext(string(container, "more_bookmarks_url"), base, username, query.offset)?.let { query.copy(offset = it) }
            else if(rows.size() >= LIMIT) query.copy(offset = query.offset + rows.size()) else null
        return PersonalContentPage(items.distinctBy { it.key }, rows.size(), next, if(failures > 0) "$failures 条内容部分解析失败，可在网页核对" else null)
    }
}
