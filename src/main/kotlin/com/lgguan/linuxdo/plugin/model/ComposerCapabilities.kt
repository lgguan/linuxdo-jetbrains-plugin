package com.lgguan.linuxdo.plugin.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jsoup.Jsoup

internal data class ComposerCapabilities(
    val minTitle: Int = 6, val maxTitle: Int = 255, val minTopicBody: Int = 20, val minReplyBody: Int = 16,
    val maxBody: Int = 64000, val maxTags: Int = 8, val defaultCategory: Int? = null,
    val readOnly: Boolean = false, val confirmed: Boolean = false
) {
    fun titleValid(text: String) = text.trim().length in minTitle..maxTitle
    fun bodyValid(text: String, topic: Boolean) = text.trim().length in (if (topic) minTopicBody else minReplyBody)..maxBody
    companion object {
        fun parse(html: String): ComposerCapabilities {
            val bootstrap = requireNotNull(Jsoup.parse(html).selectFirst("script#data-preloaded[type=application/json]")) { "论坛设置尚未加载" }
            val data = JsonParser.parseString(bootstrap.data()).asJsonObject
            val settings = requireNotNull(data.get("siteSettings")) { "论坛设置尚未加载" }.let {
                if (it.isJsonPrimitive) JsonParser.parseString(it.asString).asJsonObject else it.asJsonObject
            }
            fun number(key: String, fallback: Int): Int = settings.get(key)?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it >= 0 } ?: fallback
            return ComposerCapabilities(number("min_topic_title_length", 6), number("max_topic_title_length", 255),
                number("min_first_post_length", 20), number("min_post_length", 16), number("max_post_length", 64000),
                number("max_tags_per_topic", 8), settings.get("default_composer_category")?.takeUnless { it.isJsonNull }?.asString?.toIntOrNull(),
                data.get("isReadOnly")?.asBoolean == true || data.get("isStaffWritesOnly")?.asBoolean == true, true)
        }
    }
}

internal sealed interface PublishOutcome {
    data class Published(val post: Post) : PublishOutcome
    data class Queued(val message: String) : PublishOutcome
    companion object {
        /** A server error or unusable response does not prove the write was rolled back. */
        fun failure(error: Throwable): Throwable = when {
            error is UnconfirmedPublishException -> error
            error is com.lgguan.linuxdo.plugin.net.HttpStatusException && error.status in 400..499 && error.status != 408 -> error
            error is com.lgguan.linuxdo.plugin.net.StaleSessionException ||
                error is com.lgguan.linuxdo.plugin.net.ForumValidationException ||
                error is com.lgguan.linuxdo.plugin.net.RateLimitException ||
                error is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException ||
                error is com.lgguan.linuxdo.plugin.net.CsrfRejectedException -> error
            else -> UnconfirmedPublishException(error)
        }
        fun parse(response: JsonObject): PublishOutcome {
            if (response.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray?.size()?.let { it > 0 } == true)
                throw com.lgguan.linuxdo.plugin.net.ForumValidationException(422, response.get("errors").asJsonArray.take(20).map { org.jsoup.Jsoup.parse(it.asString).text().take(2000) })
            if (response.get("success")?.takeUnless { it.isJsonNull }?.asBoolean == false)
                throw com.lgguan.linuxdo.plugin.net.ForumValidationException(422, listOf(response.get("message")?.asString?.take(2000) ?: "论坛拒绝发布"))
            val post = response.get("post")?.takeIf { it.isJsonObject }?.asJsonObject ?: response
            if ((post.get("id")?.asLong ?: 0) > 0 && (post.get("topic_id")?.asLong ?: 0) > 0)
                return Published(com.google.gson.Gson().fromJson(post, Post::class.java))
            if (response.get("success")?.asBoolean == true &&
                (response.get("action")?.asString in setOf("enqueued", "queued") || response.has("pending_post")))
                return Queued(response.get("message")?.asString ?: "已提交，正在等待论坛审核")
            throw UnconfirmedPublishException()
        }
    }
}

internal class UnconfirmedPublishException(cause: Throwable? = null) : java.io.IOException("发布结果未确认，请先到网页检查；内容已保留，请勿直接重复发送", cause)
