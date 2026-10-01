package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.Disposable
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.service.*

internal interface TopicComposerEnvironment {
    val loggedIn: Boolean
    fun authListener(owner: Disposable, changed: () -> Unit)
    fun categories(): List<Category>
    fun capabilities(): ComposerCapabilities
    fun tags(query: String, category: Int?, selected: List<String>): TagSearchResultResponse
    fun publish(content: TopicDraftContent, key: String, version: Long): PublishOutcome
}
internal object ForumTopicComposerEnvironment : TopicComposerEnvironment {
    override val loggedIn get() = LinuxDoAuthService.getInstance().isLoggedIn
    override fun authListener(owner: Disposable, changed: () -> Unit) = LinuxDoAuthService.getInstance().addAuthListener(owner) { changed() }
    override fun categories() = DiscourseApiClient.getCategories().getOrThrow()
    override fun capabilities() = DiscourseApiClient.composerCapabilities().getOrElse { ComposerCapabilities() }
    override fun tags(query: String, category: Int?, selected: List<String>) = DiscourseApiClient.searchComposerTags(query, category, selected).getOrThrow()
    override fun publish(content: TopicDraftContent, key: String, version: Long) = DiscourseApiClient.publishTopic(
        content.title, content.body, content.categoryId, content.tags, version, key).getOrThrow()
}
