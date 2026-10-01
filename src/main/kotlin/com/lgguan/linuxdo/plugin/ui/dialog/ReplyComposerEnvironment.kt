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

internal object ForumReplyComposerEnvironment : ReplyComposerEnvironment {
    override val isLoggedIn: Boolean get() = LinuxDoAuthService.getInstance().isLoggedIn
    override fun addAuthListener(owner: Disposable, changed: () -> Unit) =
        LinuxDoAuthService.getInstance().addAuthListener(owner) { changed() }
    override fun getPost(postId: Long): Post = DiscourseApiClient.getPost(postId).getOrThrow()
    override fun createReply(topicId: Long, body: String, floor: Int?, version: Long): Post =
        DiscourseApiClient.createReply(topicId, body, floor, expectedVersion = version).getOrThrow()
}
