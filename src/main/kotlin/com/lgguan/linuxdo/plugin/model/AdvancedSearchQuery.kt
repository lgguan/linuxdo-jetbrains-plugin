package com.lgguan.linuxdo.plugin.model

import java.time.LocalDate

/** Known positive filters are editable; phrases, exclusions and extensions remain verbatim. */
internal data class AdvancedSearchQuery(
    var text: String = "",
    val filters: MutableMap<String, String> = linkedMapOf()
) {
    fun query(): String {
        // Explicit syntax typed into the keyword field takes precedence over form defaults.
        val manual = parse(text)
        val combined = linkedMapOf<String, String>().apply { putAll(filters); putAll(manual.filters) }
        return (listOf(manual.text) + combined.map { (key, value) -> when (key) {
            "category" -> if (value.startsWith("#")) value else "category:$value"
            "author" -> "@$value"
            "tags" -> "tags:$value"
            "scope" -> "in:$value"
            else -> when {
                key.startsWith("in:") -> key
                key in setOf("is:category_expert_question", "with:category_expert_response", "without:category_expert_post") -> key
                else -> "$key:$value"
            }
        } }).filter { it.isNotBlank() }.joinToString(" ")
    }

    data class Problem(val field: String, val message: String)
    fun validate(): Problem? {
        val effective = parse(query()).filters
        for (field in listOf("after", "before")) {
            val value = effective[field] ?: continue
            if (value.toIntOrNull()?.takeIf { it >= 0 } == null && runCatching { LocalDate.parse(value) }.isFailure)
                return Problem(field, "请输入有效日期（YYYY-MM-DD）")
        }
        val after = effective["after"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val before = effective["before"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (after != null && before != null && after > before)
            return Problem("before", "结束日期不能早于开始日期")
        for (field in listOf("min_posts", "max_posts", "min_views", "max_views")) {
            val value = effective[field] ?: continue
            if (!value.matches(Regex("[0-9]+")) || value.toLongOrNull() == null)
                return Problem(field, "请输入有效的非负整数")
        }
        for (kind in listOf("posts", "views")) {
            val min = effective["min_$kind"]?.toLongOrNull()
            val max = effective["max_$kind"]?.toLongOrNull()
            if (min != null && max != null && min > max) return Problem("max_$kind", "上限不能小于下限")
        }
        return null
    }

    companion object {
        val publicScopes = linkedMapOf("title" to "仅标题", "first" to "仅首楼", "pinned" to "置顶", "wiki" to "Wiki")
        val personalScopes = linkedMapOf("created" to "我创建的", "posted" to "我参与的", "likes" to "我点赞的",
            "bookmarks" to "我的书签", "seen" to "已阅读", "unseen" to "未阅读", "watching" to "关注中", "tracking" to "跟踪中")
        val statuses = linkedMapOf("open" to "开放", "closed" to "已关闭", "public" to "公开", "archived" to "已归档",
            "noreplies" to "无回复", "single_user" to "仅一人参与", "solved" to "已解决", "unsolved" to "未解决")
        val orders = linkedMapOf("" to "相关度", "latest" to "最新回复", "likes" to "最多点赞", "views" to "最多浏览",
            "latest_topic" to "最新话题", "read" to "最近阅读", "votes" to "最多票数")
        fun tokens(query: String): List<String> {
            val result = mutableListOf<String>()
            val token = StringBuilder()
            var quote: Char? = null
            var escaped = false
            for (char in query) {
                if (char.isWhitespace() && quote == null) {
                    if (token.isNotEmpty()) { result.add(token.toString()); token.setLength(0) }
                    continue
                }
                token.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' && quote != null -> escaped = true
                    char == quote -> quote = null
                    quote == null && char in charArrayOf('\"', '\'') -> quote = char
                }
            }
            if (token.isNotEmpty()) result.add(token.toString())
            return result
        }

        fun parse(query: String): AdvancedSearchQuery {
            val model = AdvancedSearchQuery()
            val remaining = mutableListOf<String>()
            for (token in tokens(query)) {
                val key = token.substringBefore(':').lowercase()
                val value = token.substringAfter(':', "")
                val normalized = value.lowercase()
                val pair: Pair<String, String>? = when {
                    token.startsWith('-') || token.contains('"') || token.contains('\'') -> null
                    token.startsWith('#') && token.endsWith("::tag") -> "tags" to token.removePrefix("#").removeSuffix("::tag")
                    token.startsWith('#') && !token.drop(1).all { it.isDigit() } -> "category" to token
                    key == "category" && value.isNotBlank() -> "category" to value
                    token.startsWith('@') && token.length > 1 -> "author" to token.drop(1)
                    key == "user" && value.isNotBlank() -> "author" to value
                    key in setOf("tag", "tags") && value.isNotBlank() -> "tags" to value
                    key == "in" && normalized in publicScopes.keys + personalScopes.keys + setOf("all", "messages", "personal") ->
                        (if (normalized in setOf("all", "messages", "personal")) "scope" else "in:$normalized") to
                            (if (normalized == "personal") "messages" else normalized)
                    key == "with" && normalized == "images" -> "with" to normalized
                    key == "status" && normalized in statuses -> key to normalized
                    key == "order" && normalized in orders -> key to normalized
                    key in setOf("before", "after", "min_posts", "max_posts", "min_views", "max_views") && value.isNotBlank() -> key to value
                    token in setOf("is:category_expert_question", "with:category_expert_response", "without:category_expert_post") -> token to "true"
                    else -> null
                }
                if (pair == null) remaining.add(token) else model.filters[pair.first] = pair.second
            }
            model.text = remaining.joinToString(" ")
            return model
        }

        fun inherit(query: String, categoryId: Int?, tag: String?): String {
            if (query.isBlank() || Regex("#?[0-9]+").matches(query.trim())) return query.trim()
            val tokens = tokens(query)
            val hasCategory = tokens.any { it.removePrefix("-").let { t -> t.startsWith("category:", true) || t.startsWith('#') && !t.endsWith("::tag") } }
            val hasTag = tokens.any { it.removePrefix("-").let { t -> t.startsWith("tag:", true) || t.startsWith("tags:", true) || t.endsWith("::tag") } }
            return (listOf(query.trim()) + listOfNotNull(categoryId?.takeUnless { hasCategory }?.let { "category:$it" },
                tag?.takeIf { it.isNotBlank() && !hasTag }?.let { "tag:$it" })).joinToString(" ")
        }
    }
}
