package com.lgguan.linuxdo.plugin.service

import com.google.gson.*
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object TopicReadingService {
    fun revisionBodies(diff: com.google.gson.JsonObject?): Pair<String,String> {
        val markdown=diff?.get("side_by_side_markdown")?.asString.orEmpty()
        val document=org.jsoup.Jsoup.parse(markdown.ifBlank { diff?.get("side_by_side")?.asString.orEmpty() })
        document.select("script,style").remove()
        fun side(name:String)=document.select(".$name").joinToString("") { it.wholeText() }
        return side("--previous") to side("--current")
    }
    fun encoded(text: String) = URLEncoder.encode(text, StandardCharsets.UTF_8)
    fun search(topic: Long, query: String, page: Int, version: Long): Result<JsonObject> = runCatching {
        require(topic > 0 && page in 1..1000 && query.length in 1..500)
        SessionEpoch.requireCurrent(version)
        // Scope is a separate, fixed search context; contradictory user topic operators are removed.
        val scoped = query.replace(Regex("(?i)(?:topic|in):\\S+"), "").trim() + " topic:$topic"
        val response = DiscourseApiClient.readerGet("/search.json?q=${encoded(scoped)}&page=$page", version).getOrThrow()
        val found = Gson().fromJson(response, SearchResultResponse::class.java)
        JsonObject().apply {
            add("items", JsonArray().apply { found.posts.orEmpty().filter { it.topicId == topic }.forEach { post -> add(JsonObject().apply {
                addProperty("floor", post.postNumber); addProperty("author", post.username)
                addProperty("text", org.jsoup.Jsoup.parse(post.blurb ?: post.cooked).text().take(1000))
            }) } })
            addProperty("more", found.groupedSearchResult?.more == true || found.groupedSearchResult?.moreFullPageResults == true)
        }
    }
    fun filtered(topic: Long, author: String?, popular: Boolean, version: Long, floor: Int? = null): Result<TopicDetailResponse> = runCatching {
        val suffix = if (popular) "filter=summary" else "username_filters[]=${encoded(requireNotNull(author).also { require(it.matches(Regex("[\\w.-]{1,60}"))) })}"
        val position=floor?.also { require(it>0) }?.let { "/$it" }.orEmpty()
        Gson().fromJson(DiscourseApiClient.readerGet("/t/$topic$position.json?$suffix&track_visit=false", version).getOrThrow(), TopicDetailResponse::class.java)
    }
    fun replies(post: Long, after: Int, version: Long): Result<List<Post>> = runCatching {
        require(post > 0 && after > 0)
        DiscourseApiClient.readerGet("/posts/$post/replies.json?after=$after", version).getOrThrow().asJsonArray.map { Gson().fromJson(it, Post::class.java) }
    }
    fun profile(username: String, version: Long): Result<JsonObject> = runCatching {
        require(username.matches(Regex("[\\w.-]{1,60}")))
        val user = DiscourseApiClient.readerGet("/u/${encoded(username)}/card.json", version).getOrThrow().asJsonObject.getAsJsonObject("user")
        JsonObject().apply { listOf("username", "name", "title", "bio_cooked", "created_at", "trust_level").forEach { key ->
            user.get(key)?.takeUnless { it.isJsonNull }?.let { addProperty(key, org.jsoup.Jsoup.parse(it.asString).text().take(3000)) }
        } }
    }
}
