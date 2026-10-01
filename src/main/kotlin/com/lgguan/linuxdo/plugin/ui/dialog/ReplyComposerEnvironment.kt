package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.Disposable
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService

/** Account and post operations used by the composer; draft sequencing lives in ReplyDraftSession. */
internal interface ReplyComposerEnvironment {
    val isLoggedIn: Boolean
    fun addAuthListener(owner: Disposable, changed: () -> Unit)
    fun getPost(postId: Long): Post
    fun createReply(topicId: Long, body: String, floor: Int?, version: Long): Post
}

/** Separate extension keeps existing embedded/test environments binary compatible. */
internal interface ReplyPublishEnvironment : ReplyComposerEnvironment {
    fun capabilities(): com.lgguan.linuxdo.plugin.model.ComposerCapabilities
    fun publishReply(topicId: Long, body: String, floor: Int?, version: Long, key: String): com.lgguan.linuxdo.plugin.model.PublishOutcome
}

internal object ForumReplyComposerEnvironment : ReplyPublishEnvironment {
    override fun capabilities() = DiscourseApiClient.composerCapabilities().getOrElse { com.lgguan.linuxdo.plugin.model.ComposerCapabilities() }
    override fun publishReply(topicId: Long, body: String, floor: Int?, version: Long, key: String) =
        DiscourseApiClient.publishReply(topicId, body, floor, version, key).getOrThrow()
    override val isLoggedIn: Boolean get() = LinuxDoAuthService.getInstance().isLoggedIn
    override fun addAuthListener(owner: Disposable, changed: () -> Unit) =
        LinuxDoAuthService.getInstance().addAuthListener(owner) { changed() }
    override fun getPost(postId: Long): Post = DiscourseApiClient.getPost(postId).getOrThrow()
    override fun createReply(topicId: Long, body: String, floor: Int?, version: Long): Post =
        DiscourseApiClient.createReply(topicId, body, floor, expectedVersion = version).getOrThrow()
}
