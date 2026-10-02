package com.lgguan.linuxdo.plugin.model

/** Body memory is bounded independently from the lightweight server ID index. */
internal object PostCache {
    const val MAX_POSTS = 400
    const val MAX_BYTES = 32L * 1024 * 1024
    fun bytes(post: Post): Long = post.cooked.toByteArray(Charsets.UTF_8).size.toLong() +
        (post.raw?.toByteArray(Charsets.UTF_8)?.size ?: 0) + (post.polls?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0)
    fun bound(posts: List<Post>, floor: Int): List<Post> {
        var size = 0L
        return posts.asReversed().distinctBy { it.id }.sortedBy { kotlin.math.abs(it.postNumber.toLong() - floor) }
            .take(MAX_POSTS).filter { post -> (size + bytes(post) <= MAX_BYTES).also { if (it) size += bytes(post) } }
            .sortedBy { it.postNumber }
    }
}
