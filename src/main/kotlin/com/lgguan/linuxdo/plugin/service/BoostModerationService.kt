package com.lgguan.linuxdo.plugin.service

import com.google.gson.*
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import org.jsoup.Jsoup

internal data class BoostFlagType(val id: Int, val key: String, val name: String, val description: String, val requireMessage: Boolean) {
    companion object {
        fun parse(site: JsonObject): List<BoostFlagType> = site.getAsJsonArray("post_action_types")?.mapNotNull { entry ->
            val flag = entry.asJsonObject
            if(flag.get("enabled")?.asBoolean != true || flag.get("is_flag")?.asBoolean != true ||
                flag.getAsJsonArray("applies_to")?.any { it.asString == "DiscourseBoosts::Boost" } != true) null
            else BoostFlagType(flag.get("id").asInt, flag.get("name_key").asString,
                Jsoup.parse(flag.get("name")?.asString.orEmpty()).text(), Jsoup.parse(flag.get("description")?.asString.orEmpty()).text(),
                flag.get("require_message")?.asBoolean == true)
        }.orEmpty()
    }
}
internal data class BoostActions(val post: Post, val boost: PostBoost, val types: List<BoostFlagType>)

/** Boost flags use their own target, permissions and catalog; never flag the containing post. */
internal class BoostModerationService(
    private val transport: TopicOperationTransport = ForumOperationTransport,
    private val readBoost: (Long, Long) -> Result<PostBoost> = { id, version -> DiscourseApiClient.readerGet("/discourse-boosts/boosts/$id", version).map { Gson().fromJson(it, PostBoost::class.java) } },
    private val session: (Long) -> Unit = SessionEpoch::requireCurrent,
    private val userId: () -> Long? = { LinuxDoAuthService.getInstance().currentUser?.id },
    private val gate: ReaderWriteGate = ReaderWriteGate.shared
) {
    fun read(topic: Long, postId: Long, boostId: Long, version: Long, catalog: List<BoostFlagType>): BoostActions {
        session(version); require(topic > 0 && postId > 0 && boostId > 0)
        val post = transport.post(postId, version).getOrThrow()
        require(post.id == postId)
        return readLoaded(topic, post, boostId, version, catalog)
    }
    /** Reading a report form needs the Boost permissions, not another floor/stream fetch. */
    fun readLoaded(topic: Long, post: Post, boostId: Long, version: Long, catalog: List<BoostFlagType> = emptyList()): BoostActions {
        session(version); require(topic > 0 && post.id > 0 && boostId > 0)
        require(post.topicId == topic && post.boosts?.any { it.id == boostId } == true) { "Boost 已不存在，请刷新楼层" }
        val boost = readBoost(boostId, version).getOrThrow(); session(version)
        require(boost.id == boostId) { "Boost 目标不匹配" }
        if(boost.userFlagStatus == 0) uncertain.remove(version to boostId)
        val types = catalog.filter { canReport(boost, version) && it.key in boost.availableFlags.orEmpty() }
        return BoostActions(post.copy(boosts = post.boosts?.map { if(it.id == boostId) boost else it }), boost, types)
    }
    fun canReport(boost: PostBoost, version: Long): Boolean =
        (version to boost.id) !in reported && (version to boost.id) !in uncertain && boost.canFlag == true && boost.userFlagStatus != 0 &&
            userId() != null && (boost.user?.id ?: boost.userId) != userId() && !boost.availableFlags.isNullOrEmpty()
    fun flag(topic: Long, postId: Long, boostId: Long, typeId: Int, message: String, version: Long,
        catalog: List<BoostFlagType>, readOnly: Boolean, active: () -> Boolean): OperationResult = gate.serialized {
        var observed: Post? = null
        val identity = version to boostId
        try {
            session(version); check(active()) { "页面已失效" }; requireNotNull(userId()) { "请先登录" }
            check(!readOnly) { "论坛处于只读状态" }
            uncertain.removeIf { it.first != version }
            reported.removeIf { it.first != version }
            check(identity !in reported) { "这条 Boost 已举报，等待论坛处理" }
            check(identity !in uncertain) { "上次 Boost 举报结果未确认，请先在网页核对" }
            val state = read(topic, postId, boostId, version, catalog); observed = state.post
            val type = state.types.firstOrNull { it.id == typeId } ?: error("服务器未允许使用该原因举报此 Boost")
            require(message.length <= 4000 && (!type.requireMessage || message.isNotBlank())) { "请填写有效的举报说明（最多 4000 字符）" }
            val body = JsonObject().apply {
                addProperty("flag_type_id", type.id); addProperty("message", message.trim())
                addProperty("take_action", false); addProperty("queue_for_review", false)
            }
            gate.beforeWrite(); session(version); check(active()) { "页面已失效" }
            val result = transport.write(OperationRequest("/discourse-boosts/boosts/$boostId/flags", "POST", body), version)
            gate.failure(result.exceptionOrNull()); session(version)
            val fresh = readBoost(boostId, version).getOrNull(); session(version)
            val failure = result.exceptionOrNull()
            val confirmed = result.isSuccess && result.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("success")?.asString == "OK" ||
                failure != null && !TopicOperationService.definite(failure) && fresh?.id == boostId && fresh.userFlagStatus == 0
            if(!confirmed) {
                val error = failure?.takeIf(TopicOperationService::definite) ?: UnconfirmedOperationException(failure).also { uncertain.add(identity) }
                return@serialized OperationResult(observed, null, error)
            }
            // Successful submission is authoritative even when the readback is unavailable or eventual.
            val updated = (fresh?.takeIf { it.id == boostId } ?: state.boost).copy(userFlagStatus = 0, canFlag = false, availableFlags = emptyList())
            reported.add(identity)
            OperationResult(state.post.copy(boosts = state.post.boosts?.map { if(it.id == boostId) updated else it }), result.getOrNull(), null)
        } catch(error: Throwable) { OperationResult(observed, null, error) }
    }
    companion object {
        private val uncertain = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()
        private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()
    }
}
