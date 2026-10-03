package com.lgguan.linuxdo.plugin.service

import com.google.gson.*
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*

internal data class BoostEmojiRules(val custom: Set<String> = emptySet(), val denied: Set<String> = emptySet()) {
    companion object {
        fun parse(html: String): BoostEmojiRules {
            val raw = org.jsoup.Jsoup.parse(html).selectFirst("script#data-preloaded")?.data() ?: error("论坛表情配置不可用")
            val root = JsonParser.parseString(raw).asJsonObject
            fun value(key: String): JsonElement? = root.get(key)?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) JsonParser.parseString(it.asString) else it }
            val custom = value("customEmoji")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asJsonObject.get("name")?.asString }?.toSet().orEmpty()
            val denied = value("siteSettings")?.asJsonObject?.get("emoji_deny_list")?.asString?.split('|')?.toSet().orEmpty()
            return BoostEmojiRules(custom,denied)
        }
    }
}

/** Shared post gate also protects against overlapping submissions from multiple editor windows. */
internal class BoostService(
    private val transport: TopicOperationTransport = ForumOperationTransport,
    private val userId: () -> Long? = { LinuxDoAuthService.getInstance().currentUser?.id },
    private val session: (Long) -> Unit = SessionEpoch::requireCurrent,
    private val gate: ReaderWriteGate = ReaderWriteGate.shared
) {
    fun perform(topicId: Long, postId: Long, raw: String?, boostId: Long?, version: Long, rules: BoostEmojiRules = BoostEmojiRules(), active: () -> Boolean = { true }): OperationResult {
        val key = version to postId
        if (!pending.add(key)) return OperationResult(null,null,IllegalStateException("该楼层正在提交，请稍候"))
        try {
            return gate.serialized {
                var before: Post? = null
                try {
                    session(version); check(active()) { "页面已失效" }
                    val me = requireNotNull(userId()) { "请先登录" }
                    val post = transport.post(postId,version).getOrThrow(); before = post
                    require(post.topicId == topicId && post.id == postId)
                    check(key !in uncertain) { "上次 Boost 结果未确认；请核对服务器后重新登录再操作" }
                    val request = if (boostId == null) {
                        require(post.canBoost == true) { "服务器未允许发送 Boost，或您已 Boost 此楼层" }
                        require(post.boosts.orEmpty().none { (it.user?.id ?: it.userId) == me }) { "每个用户只能发送一条 Boost" }
                        BoostText.validate(requireNotNull(raw),rules.custom,rules.denied)
                        OperationRequest("/discourse-boosts/posts/$postId/boosts", "POST", JsonObject().apply { addProperty("raw",raw.trim()) })
                    } else {
                        val boost = post.boosts?.firstOrNull { it.id == boostId } ?: error("Boost 已不存在")
                        require(boost.canDelete == true && (boost.user?.id ?: boost.userId) == me) { "没有撤回自己 Boost 的权限" }
                        OperationRequest("/discourse-boosts/boosts/$boostId", "DELETE", JsonObject())
                    }
                    gate.beforeWrite(); session(version); check(active()) { "页面已失效" }
                    val response = transport.write(request,version).mapCatching { value ->
                        if (boostId == null) {
                            val boost = Gson().fromJson(value,PostBoost::class.java)
                            require(boost.id != null && (boost.user?.id ?: boost.userId) == me) { "服务器未返回有效 Boost" }
                        }
                        value
                    }
                    session(version)
                    gate.failure(response.exceptionOrNull())
                    if (response.isSuccess && boostId == null) {
                        val boost = Gson().fromJson(response.getOrThrow(),PostBoost::class.java)
                        require(boost.id != null && (boost.user?.id ?: boost.userId) == me) { "服务器未返回有效 Boost" }
                        OperationResult(post.copy(canBoost=false,boosts=post.boosts.orEmpty().filterNot { it.id==boost.id }+boost),response.getOrNull(),null)
                    } else {
                        // A read is the only recovery for a missing response; never replay the write.
                        val refreshed = transport.post(postId,version).getOrNull()
                        session(version)
                        val failure = response.exceptionOrNull()
                        val confirmed = refreshed != null && if (boostId == null)
                            refreshed.boosts.orEmpty().any { (it.user?.id ?: it.userId) == me } && post.boosts.orEmpty().none { (it.user?.id ?: it.userId) == me }
                            else refreshed.boosts != null && refreshed.boosts.none { it.id == boostId }
                        val error = if (failure == null || !TopicOperationService.definite(failure) && confirmed) null
                            else if (TopicOperationService.definite(failure)) failure else UnconfirmedOperationException(failure).also { uncertain.add(key) }
                        val changed = refreshed ?: if (failure == null && boostId != null) post.copy(canBoost=null,boosts=post.boosts.orEmpty().filterNot { it.id==boostId }) else post
                        OperationResult(changed,response.getOrNull(),error)
                    }
                } catch (error: Throwable) { OperationResult(before,null,error) }
            }
        } finally { pending.remove(key) }
    }
    companion object {
        private val pending = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long,Long>>()
        private val uncertain = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long,Long>>()
    }
}
