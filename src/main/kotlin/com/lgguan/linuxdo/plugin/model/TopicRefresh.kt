package com.lgguan.linuxdo.plugin.model

/** Refresh the server index while retaining loaded posts and their local UI state. */
internal object TopicRefresh {
    fun mergeAround(current: TopicDetailResponse, around: TopicDetailResponse, floor: Int): TopicDetailResponse {
        require(floor > 0 && around.postStream.posts.any { it.postNumber == floor && (it.topicId == null || it.topicId == current.id) }) { "Requested floor is unavailable" }
        val indexed = around.copy(postStream = around.postStream.copy(
            stream = around.postStream.stream ?: (current.postStream.stream.orEmpty() + around.postStream.posts.map { it.id }).distinct()))
        val merged = merge(current, indexed)
        return merged.copy(postStream = merged.postStream.copy(posts =
            (merged.postStream.posts + around.postStream.posts)
                .filter { it.id in indexed.postStream.stream.orEmpty() && (it.topicId == null || it.topicId == current.id) }
                .distinctBy { it.id }.sortedBy { it.postNumber }))
    }

    fun addReply(current: TopicDetailResponse, reply: Post): TopicDetailResponse {
        require(reply.id > 0 && reply.postNumber > 1 && (reply.topicId == null || reply.topicId == current.id)) {
            "Reply does not identify a published floor in this topic"
        }
        val stream = current.postStream.stream.orEmpty()
        val added = if (reply.id in stream || current.postStream.posts.any { it.id == reply.id }) 0 else 1
        return current.copy(
            postStream = current.postStream.copy(
                posts = (current.postStream.posts.filterNot { it.id == reply.id } + reply).sortedBy { it.postNumber },
                stream = (stream + reply.id).distinct()),
            postsCount = current.postsCount + added,
            replyCount = current.replyCount + added,
            highestPostNumber = maxOf(current.highestPostNumber ?: 1, reply.postNumber)
        )
    }

    fun merge(current: TopicDetailResponse, fresh: TopicDetailResponse): TopicDetailResponse {
        require(current.id == fresh.id) { "Topic changed during refresh" }
        val stream = requireNotNull(fresh.postStream.stream) { "Missing reply index" }.distinct()
        val visible = stream.toHashSet()
        val updates = fresh.postStream.posts.associateBy { it.id }
        return fresh.copy(postStream = fresh.postStream.copy(
            stream = stream,
            // Fresh detail usually contains only the first batch. Load new posts through normal pagination.
            posts = current.postStream.posts.filter { it.id in visible }.distinctBy { it.id }.map { updates[it.id] ?: it }.sortedBy { it.postNumber }
        ))
    }
}
