package com.lgguan.linuxdo.plugin.api

import com.lgguan.linuxdo.plugin.model.SearchResultResponse
import com.lgguan.linuxdo.plugin.model.Topic

internal object TopicSearch {
    fun search(
        query: String,
        byId: (Long) -> Result<Topic>,
        byText: (String) -> Result<SearchResultResponse>
    ): Result<SearchResultResponse> {
        val trimmed = query.trim()
        if (!Regex("#?[0-9]+").matches(trimmed)) return byText(trimmed)
        val id = trimmed.removePrefix("#").toLongOrNull()?.takeIf { it > 0 }
            ?: return Result.failure(IllegalArgumentException("帖子 ID 必须为有效的正整数"))
        return byId(id).map { SearchResultResponse(topics = listOf(it)) }
    }
}
