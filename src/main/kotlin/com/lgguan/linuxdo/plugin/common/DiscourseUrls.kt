package com.lgguan.linuxdo.plugin.common

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object DiscourseUrls {
    fun topicList(baseUrl: String, filter: Constants.TopicFilter, categorySlug: String? = null,
        categoryId: Int? = null, page: Int = 0, tag: String? = null): String {
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        val list = when (filter) { Constants.TopicFilter.TOP, Constants.TopicFilter.HOT -> "top"; else -> filter.key }
        val category = if (categoryId == null) "" else "${categorySlug?.takeIf { it.isNotBlank() }?.let { encode(it) + "/" }.orEmpty()}$categoryId"
        val path = when {
            !tag.isNullOrBlank() && categoryId != null -> "/tags/c/$category/${encode(tag)}/l/$list.json"
            !tag.isNullOrBlank() -> "/tag/${encode(tag)}/l/$list.json"
            categoryId != null -> "/c/$category/l/$list.json"
            else -> "/$list.json"
        }
        val period = when (filter) { Constants.TopicFilter.TOP -> "&period=weekly"; Constants.TopicFilter.HOT -> "&period=daily"; else -> "" }
        return "$baseUrl$path?page=$page$period"
    }

    fun browseTags(baseUrl: String, query: String): String =
        "$baseUrl/tags/filter/search.json?q=${URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)}&filterForInput=false"
    fun latest(baseUrl: String, page: Int = 0): String {
        return "$baseUrl/latest.json?page=$page"
    }

    fun top(baseUrl: String, period: String = "weekly", page: Int = 0): String {
        return "$baseUrl/top.json?period=$period&page=$page"
    }

    fun categoryLatest(baseUrl: String, categorySlug: String, categoryId: Int, page: Int = 0): String {
        return "$baseUrl/c/$categorySlug/$categoryId/l/latest.json?page=$page"
    }

    fun site(baseUrl: String): String {
        return "$baseUrl/site.json"
    }

    fun categories(baseUrl: String): String {
        return "$baseUrl/categories.json?include_subcategories=true"
    }

    fun topicDetail(baseUrl: String, topicId: Long, trackVisit: Boolean = true): String {
        val query = if (trackVisit) "?track_visit=true" else ""
        return "$baseUrl/t/$topicId.json$query"
    }

    fun topicPosts(baseUrl: String, topicId: Long, postIds: List<Long>): String {
        val params = postIds.joinToString("&") { "post_ids[]=$it" }
        return "$baseUrl/t/$topicId/posts.json?$params"
    }

    fun search(baseUrl: String, query: String, page: Int = 1): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return "$baseUrl/search.json?q=$encoded&page=$page"
    }

    fun postTimings(baseUrl: String): String {
        return "$baseUrl/topics/timings"
    }

    fun createPost(baseUrl: String): String {
        return "$baseUrl/posts.json"
    }

    fun postActions(baseUrl: String): String {
        return "$baseUrl/post_actions.json"
    }

    fun removePostAction(baseUrl: String, postId: Long, postActionTypeId: Int = 2): String {
        return "$baseUrl/post_actions/$postId.json?post_action_type_id=$postActionTypeId"
    }

    fun currentUser(baseUrl: String): String {
        return "$baseUrl/session/current.json"
    }

    fun notifications(baseUrl: String): String {
        return "$baseUrl/notifications.json"
    }

    fun markNotificationsRead(baseUrl: String): String {
        return "$baseUrl/notifications/mark-read"
    }

    fun upload(baseUrl: String): String {
        return "$baseUrl/uploads.json"
    }

    fun tags(baseUrl: String): String {
        return "$baseUrl/tags.json"
    }

    fun composerTags(baseUrl: String, query: String, categoryId: Int?, selectedIds: List<String>): String {
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
        // Let Discourse use its site-configured result limit. A fixed client limit
        // can exceed max_tag_search_results and makes even an empty search fail.
        val params = mutableListOf("q=${encode(query.trim())}", "filterForInput=true")
        if (categoryId != null) params.add("categoryId=$categoryId")
        selectedIds.filter { it.toLongOrNull() != null }.forEach { params.add("selected_tag_ids[]=${encode(it)}") }
        return "$baseUrl/tags/filter/search.json?${params.joinToString("&")}"
    }
}
