package com.lgguan.linuxdo.plugin.service

import com.google.gson.*
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import org.jsoup.Jsoup
import java.io.IOException

internal data class OperationRequest(val path: String, val method: String, val data: JsonObject)
internal interface TopicOperationTransport {
    fun post(id: Long, version: Long): Result<Post>
    fun write(request: OperationRequest, version: Long): Result<JsonElement>
}
internal object ForumOperationTransport : TopicOperationTransport {
    override fun post(id: Long, version: Long): Result<Post> = runCatching {
        val post = Gson().fromJson(DiscourseApiClient.readerGet("/posts/$id.json", version).getOrThrow(), Post::class.java)
        if (post.postVotingVoteCount == null) post else {
            // Voting direction is serialized only inside a TopicView, including the posts endpoint.
            val stream = DiscourseApiClient.readerGet("/t/${post.topicId}/posts.json?post_ids[]=$id", version).getOrThrow()
                .asJsonObject.getAsJsonObject("post_stream")
            val current = stream?.getAsJsonArray("posts")?.firstOrNull()?.let { Gson().fromJson(it, Post::class.java) }
                ?: error("服务器未返回问答投票状态")
            require(current.id == id)
            current.copy(raw = post.raw)
        }
    }
    override fun write(request: OperationRequest, version: Long) = DiscourseApiClient.readerWrite(request.path, request.method, request.data, version)
}
internal data class FlagType(val id: Int, val name: String, val description: String, val requireMessage: Boolean)
internal data class ReaderRules(val reactions: List<String>, val flags: List<FlagType>, val groups: Set<String>, val pollUndo: Boolean, val readOnly: Boolean) {
    companion object {
        fun parse(html: String, site: JsonObject): ReaderRules {
            val raw = Jsoup.parse(html).selectFirst("script#data-preloaded")?.data() ?: error("论坛配置不可用")
            val data = JsonParser.parseString(raw).asJsonObject
            fun objectValue(key: String): JsonObject? = data.get(key)?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) JsonParser.parseString(it.asString).asJsonObject else it.asJsonObject }
            val settings = objectValue("siteSettings") ?: error("论坛配置不可用")
            val user = objectValue("currentUser")
            val reactions = settings.get("discourse_reactions_enabled_reactions")?.asString?.split('|')?.filter { it.matches(Regex("[\\w+-]{1,80}")) }.orEmpty()
            val flags = site.getAsJsonArray("post_action_types")?.mapNotNull { value ->
                val flag = value.asJsonObject
                if (flag.get("is_flag")?.asBoolean != true || flag.get("enabled")?.asBoolean != true) null else FlagType(flag.get("id").asInt,
                    Jsoup.parse(flag.get("name")?.asString.orEmpty()).text(), Jsoup.parse(flag.get("description")?.asString.orEmpty()).text(), flag.get("require_message")?.asBoolean == true)
            }.orEmpty()
            return ReaderRules(reactions, flags, user?.getAsJsonArray("groups")?.mapNotNull { it.asJsonObject.get("name")?.asString }?.toSet().orEmpty(),
                settings.get("poll_enable_remove_vote")?.asBoolean == true, data.get("isReadOnly")?.asBoolean == true || data.get("isStaffWritesOnly")?.asBoolean == true)
        }
    }
}
internal class UnconfirmedOperationException(cause: Throwable? = null) : IOException("操作结果未确认；已读取服务器核对，请勿直接重复提交", cause)
internal data class OperationResult(val post: Post?, val response: JsonElement?, val error: Throwable?)

/** One gate across reader windows prevents overlapping writes and respects account-wide 429 cooldown. */
internal class ReaderWriteGate(private val now: () -> Long = System::currentTimeMillis, private val pause: (Long) -> Unit = Thread::sleep) {
    private var nextWrite = 0L
    private var cooldownUntil = 0L
    @Synchronized fun <T> serialized(action: () -> T): T = action()
    fun beforeWrite() {
        if (cooldownUntil > now()) throw RateLimitException((cooldownUntil - now() + 999) / 1000)
        val wait = nextWrite - now(); if (wait > 0) pause(wait.coerceAtMost(800))
        nextWrite = now() + 800
    }
    fun failure(error: Throwable?) { if (error is RateLimitException) cooldownUntil = now() + error.retryAfterSeconds * 1000 }
    companion object { val shared = ReaderWriteGate() }
}

internal class TopicOperationService(
    private val transport: TopicOperationTransport = ForumOperationTransport,
    private val checkSession: (Long) -> Unit = SessionEpoch::requireCurrent,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: (Long) -> Unit = Thread::sleep
) {
    private val gate = if (transport === ForumOperationTransport) ReaderWriteGate.shared else ReaderWriteGate(now,pause)
    fun perform(topic: TopicDetailResponse, postId: Long, action: String, input: JsonObject, version: Long, rules: ReaderRules? = null, active: () -> Boolean = { true }): OperationResult = gate.serialized {
        var observed: Post? = null
        try {
            checkSession(version)
            check(active()) { "页面已关闭或切换" }
            val post = transport.post(postId, version).getOrThrow()
            require(post.id == postId && post.topicId == topic.id) { "帖子不属于当前话题" }
            observed = post
            if (rules?.readOnly == true) error("论坛处于只读状态")
            val request = request(topic, post, action, input, rules)
            gate.beforeWrite()
            checkSession(version); check(active()) { "页面已关闭或切换" }
            val response = transport.write(request, version)
            checkSession(version)
            val error = response.exceptionOrNull()
            gate.failure(error)
            // Read after both success and an ambiguous result; this never repeats a mutation.
            val refreshed = transport.post(postId, version).getOrNull()
            checkSession(version)
            val reconciled = error != null && !definite(error) && refreshed != null && confirmed(post,refreshed,action,input)
            OperationResult(refreshed, response.getOrNull(), if(reconciled)null else error?.let { if (definite(it)) it else UnconfirmedOperationException(it) })
        } catch (error: Throwable) { OperationResult(observed, null, error) }
    }
    companion object {
        fun confirmed(before: Post, after: Post, action: String, input: JsonObject): Boolean = when(action) {
            "like" -> after.isLiked() == (input.get("like")?.asBoolean==true)
            "edit" -> after.raw == input.get("raw")?.asString
            "bookmark" -> after.bookmarked==true && after.bookmarkName.orEmpty()==input.get("name")?.asString.orEmpty() &&
                runCatching { after.bookmarkReminderAt?.let(java.time.Instant::parse)==input.get("reminder")?.asString?.takeIf{it.isNotBlank()}?.let(java.time.Instant::parse) }.getOrDefault(false)
            "unbookmark" -> after.bookmarked==false
            "delete" -> after.deletedAt!=null || after.userDeleted==true
            "recover" -> after.deletedAt==null && after.userDeleted==false
            "reaction" -> after.currentUserReaction?.id == input.get("reaction")?.asString?.takeUnless { it==before.currentUserReaction?.id }
            "accept" -> after.acceptedAnswer==true
            "unaccept" -> after.acceptedAnswer==false
            "poll" -> after.pollsVotes?.get(input.get("name")?.asString)?.let{it==input.get("options")}==true
            "unpoll" -> after.pollsVotes!=null && !after.pollsVotes.has(input.get("name")?.asString)
            "flag" -> after.actionsSummary?.any{it.id==input.get("type")?.asInt && it.acted==true}==true
            "postVote" -> (input.get("direction")?.asString ?: "up").let { direction -> after.postVotingDirection == if(before.postVotingDirection==direction) null else direction }
            else -> false
        }
        fun definite(error: Throwable) = error is HttpStatusException && error.status in 400..499 && error.status != 408 ||
            error is ForumValidationException || error is RateLimitException || error is CloudflareChallengeException || error is StaleSessionException || error is CsrfRejectedException
        fun request(topic: TopicDetailResponse, post: Post, action: String, input: JsonObject, rules: ReaderRules?): OperationRequest {
            fun text(key: String) = input.get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            fun json(vararg pairs: Pair<String, Any?>) = JsonObject().apply { pairs.forEach { (key, value) -> add(key, Gson().toJsonTree(value)) } }
            val id = post.id
            return when (action) {
                "like" -> { val like = input.get("like")?.asBoolean == true; require(PostCapabilities.like(post, like)) { "没有点赞或撤赞权限" }
                    OperationRequest(if (like) "/post_actions.json" else "/post_actions/$id.json", if (like) "POST" else "DELETE", json("id" to id, "post_action_type_id" to 2)) }
                "bookmark" -> { require(post.bookmarked != null); val name = text("name"); require(name.length <= 100)
                    val reminder = text("reminder").takeIf { it.isNotBlank() }?.also { require(java.time.Instant.parse(it).isAfter(java.time.Instant.now())) { "提醒时间必须晚于当前时间" } }
                    OperationRequest(post.bookmarkId?.let { "/bookmarks/$it.json" } ?: "/bookmarks.json", if (post.bookmarkId == null) "POST" else "PUT",
                        json("bookmarkable_id" to id, "bookmarkable_type" to "Post", "name" to name, "reminder_at" to reminder)) }
                "unbookmark" -> OperationRequest("/bookmarks/${requireNotNull(post.bookmarkId)}.json", "DELETE", JsonObject())
                "edit" -> { require(post.canEdit == true); require(text("original").isNotEmpty() && text("raw").isNotBlank() && text("raw").length <= 100000)
                    OperationRequest("/posts/$id.json", "PUT", json("post" to json("raw" to text("raw"), "original_text" to text("original"), "edit_reason" to text("reason").take(500)))) }
                "delete" -> { require(post.canDelete == true); OperationRequest("/posts/$id.json", "DELETE", JsonObject()) }
                "recover" -> { require(post.canRecover == true); OperationRequest("/posts/$id/recover.json", "PUT", JsonObject()) }
                "flag" -> { val type = requireNotNull(rules).flags.firstOrNull { it.id == input.get("type")?.asInt } ?: error("举报类型无效")
                    require(PostCapabilities.flag(post, type.id)); require(!type.requireMessage || text("message").isNotBlank()); require(text("message").length <= 4000)
                    OperationRequest("/post_actions.json", "POST", json("id" to id, "post_action_type_id" to type.id, "message" to text("message"), "flag_topic" to false)) }
                "reaction" -> { val reaction = text("reaction"); require(reaction in requireNotNull(rules).reactions)
                    require(PostCapabilities.reaction(post))
                    OperationRequest("/discourse-reactions/posts/$id/custom-reactions/${TopicReadingService.encoded(reaction)}/toggle.json", "PUT", JsonObject()) }
                "accept", "unaccept" -> { require(if (action == "accept") post.canAcceptAnswer == true else post.canUnacceptAnswer == true)
                    OperationRequest("/solution/$action.json", "POST", json("id" to id)) }
                "postVote" -> { require(PostCapabilities.postVote(topic,post)); val direction=text("direction").ifBlank { "up" };require(direction in setOf("up","down"))
                    OperationRequest("/post_voting/vote.json", if (post.postVotingDirection==direction) "DELETE" else "POST", json("post_id" to id, "direction" to direction)) }
                "poll", "unpoll" -> pollRequest(post, input, action, rules)
                else -> error("不支持的帖子操作")
            }
        }
        private fun pollRequest(post: Post, input: JsonObject, action: String, rules: ReaderRules?): OperationRequest {
            val name = input.get("name")?.asString ?: error("缺少问卷名")
            val poll = post.polls?.firstOrNull { it.get("name")?.asString == name } ?: error("问卷不存在")
            require(poll.get("status")?.asString == "open") { "问卷已关闭" }
            val groups = poll.get("groups")?.takeUnless { it.isJsonNull }?.asString?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            require(groups.isEmpty() || groups.any { it in requireNotNull(rules).groups }) { "不在允许投票的用户组" }
            val data = JsonObject().apply { addProperty("post_id", post.id); addProperty("poll_name", name) }
            if (action == "unpoll") { require(rules?.pollUndo == true && post.pollsVotes?.has(name) == true); return OperationRequest("/polls/remove_vote.json", "PUT", data) }
            val options = input.getAsJsonArray("options")?.map { it.asString }.orEmpty()
            val allowed = poll.getAsJsonArray("options")?.map { it.asJsonObject.get("id").asString }.orEmpty()
            require(options.isNotEmpty() && options.distinct() == options && allowed.containsAll(options)) { "选项无效" }
            val type = poll.get("type")?.asString
            if (type == "multiple") require(options.size in (poll.get("min")?.asInt ?: 1)..(poll.get("max")?.asInt ?: allowed.size)) { "选择数量不符合问卷要求" }
            else if (type != "ranked_choice") require(options.size == 1)
            if (type == "ranked_choice") require(options.size == allowed.size) { "请为所有选项排序" }
            data.add("options", Gson().toJsonTree(options)); return OperationRequest("/polls/vote.json", "PUT", data)
        }
    }
}
