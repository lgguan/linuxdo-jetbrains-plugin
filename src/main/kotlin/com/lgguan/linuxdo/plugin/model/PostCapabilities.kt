package com.lgguan.linuxdo.plugin.model

/** Missing capability data never grants permission. The server remains authoritative. */
internal object PostCapabilities {
    fun like(post: Post, desired: Boolean) = post.actionsSummary?.firstOrNull { it.id == 2 }?.let {
        if (desired) it.canAct == true else it.acted == true && it.canUndo == true
    } == true
    fun reply(topic: TopicDetailResponse) = topic.details?.canCreatePost == true && topic.closed != true && topic.archived != true
    fun flag(post: Post, id: Int) = id != 2 && post.actionsSummary?.any { it.id == id && it.canAct == true } == true
    fun postVote(topic: TopicDetailResponse, post: Post) = topic.isPostVoting == true && topic.closed != true && topic.archived != true &&
        post.postNumber > 1 && post.replyToPostNumber == null && (post.canVote == true || post.yours == false)
    fun reaction(post: Post) = post.reactions != null && (if(post.currentUserReaction != null)post.currentUserReaction.canUndo==true else like(post,true))
}
