package com.lgguan.linuxdo.plugin.model

import com.google.gson.JsonObject
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class NotificationStatus(val title: String, val filter: String?) {
    ALL("全部", null), UNREAD("未读", "unread"), READ("已读", "read");
    fun accepts(item: DiscourseNotification) = this == ALL || item.read == (this == READ)
    override fun toString() = title
}

data class NotificationQuery(val status: NotificationStatus = NotificationStatus.ALL, val offset: Int = 0, val limit: Int = 30) {
    init { require(offset >= 0 && limit in 1..60) }
    fun url(base: String): String = base.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("notifications.json").addQueryParameter("offset", offset.toString())
        .addQueryParameter("limit", limit.toString()).apply { status.filter?.let { addQueryParameter("filter", it) } }.build().toString()

    /** Validate the server link, then rebuild an account-local request without forwarding arbitrary parameters. */
    fun next(page: NotificationListResponse, base: String): NotificationQuery? {
        if (page.totalRows?.let { offset.toLong() + limit >= it } == true) return null
        val link = page.loadMoreNotifications
        if (link.isNullOrBlank()) {
            if (page.totalRows == null && page.notifications.size < limit) return null
            return copy(offset = Math.addExact(offset, limit))
        }
        val origin = base.trimEnd('/').toHttpUrl()
        val resolved = origin.resolve(link) ?: error("通知分页链接无效")
        require(resolved.scheme == origin.scheme && resolved.host == origin.host && resolved.port == origin.port &&
            resolved.username.isEmpty() && resolved.password.isEmpty() && resolved.fragment == null &&
            resolved.encodedPath in setOf("/notifications", "/notifications.json")) { "通知分页链接不属于当前论坛" }
        require(resolved.queryParameterNames.all { it in setOf("offset", "limit", "filter", "username") } &&
            resolved.queryParameterNames.all { resolved.queryParameterValues(it).size == 1 }) { "通知分页参数无效" }
        val nextOffset = resolved.queryParameter("offset")?.toIntOrNull() ?: error("通知分页缺少 offset")
        val nextLimit = resolved.queryParameter("limit")?.toIntOrNull() ?: limit
        require(nextOffset > offset && resolved.queryParameter("filter").orEmpty() == status.filter.orEmpty()) { "通知分页状态不一致" }
        return copy(offset = nextOffset, limit = nextLimit)
    }
}

data class NotificationTotals(val notifications: Int, val personalMessages: Int) {
    val total: Int get() = Math.addExact(notifications, personalMessages)
    companion object {
        fun parse(json: JsonObject): NotificationTotals {
            fun count(key: String, optional: Boolean = false): Int {
                val value = json.get(key)
                if (value == null && optional) return 0 // Disabled PM permission omits this attribute upstream.
                require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "通知计数字段缺失" }
                return value.asBigDecimal.intValueExact().also { require(it >= 0) }
            }
            return NotificationTotals(count("unread_notifications"), count("unread_personal_messages", true)).also { it.total }
        }
    }
}

object NotificationReadConfirmation {
    fun parse(json: JsonObject): Boolean = json.get("success")?.takeIf { it.isJsonPrimitive }?.asString in setOf("OK", "true")
}

data class NotificationCountState(val totals: NotificationTotals? = null, val stale: Boolean = false) {
    val label: String get() = totals?.let { "${it.total} 条未读${if (stale) "（未更新）" else ""}" } ?: "未读数量暂不可用"
}

data class NotificationHistory(
    val items: List<DiscourseNotification> = emptyList(), val next: NotificationQuery? = null,
    val loaded: Boolean = false, val error: String? = null, val failedQuery: NotificationQuery? = null
) {
    fun merge(query: NotificationQuery, page: NotificationListResponse, base: String): NotificationHistory {
        val continuation = query.next(page, base)
        val received = page.notifications.filter(query.status::accepts)
        val exhaustedHead = query.offset == 0 && page.totalRows?.let { it <= query.limit } == true
        val oldestHeadId = received.minOfOrNull { it.id }
        val retained = if (exhaustedHead) emptyList() else if (query.offset == 0 && oldestHeadId != null)
            items.filter { it.id < oldestHeadId || received.any { fresh -> fresh.id == it.id } } else items
        val merged = (received + retained).distinctBy { it.id }.sortedByDescending { it.id }
        val gapAtHead = query.offset == 0 && items.isNotEmpty() && received.isNotEmpty() &&
            received.none { fresh -> items.any { it.id == fresh.id } }
        // Refresh only the head. Historical pagination keeps its own offset and exhaustion state.
        return copy(items = merged, next = if (exhaustedHead || gapAtHead) continuation else if (query.offset == 0 && loaded) {
            next ?: continuation?.takeIf { page.totalRows?.let { total -> total > merged.size } == true }
        } else continuation,
            loaded = true, error = null, failedQuery = null)
    }
}

/** The site can assign plugin notification IDs differently from upstream. Unknown IDs remain generic. */
object NotificationTypes {
    private val core = mapOf(1 to "mentioned", 2 to "replied", 3 to "quoted", 4 to "edited", 5 to "liked",
        6 to "private_message", 7 to "invited_to_private_message", 9 to "posted", 11 to "linked", 12 to "granted_badge",
        15 to "group_mentioned", 19 to "liked_consolidated", 25 to "reaction")
    @Volatile private var configured: Pair<Long, Map<Int, String>>? = null
    fun configure(version: Long, names: Map<String, Int>) { SessionEpoch.ifCurrent(version) { configured = version to names.entries.associate { it.value to it.key } } }
    fun name(id: Int): String? = configured?.takeIf { it.first == SessionEpoch.current }?.second?.get(id) ?: core[id]
    fun category(item: DiscourseNotification): String = when (name(item.notificationType)) {
        "mentioned", "replied", "quoted", "posted", "group_mentioned", "boost", "boosted", "boosted_consolidated" -> "replies"
        "liked", "liked_consolidated", "reaction" -> "likes"
        else -> "system"
    }
    fun topicTarget(item: DiscourseNotification): Long? = item.topicId?.takeIf { it > 0 && name(item.notificationType) in
        setOf("mentioned", "replied", "quoted", "edited", "liked", "private_message", "invited_to_private_message", "posted", "linked", "group_mentioned", "liked_consolidated", "reaction", "boost", "boosted", "boosted_consolidated") }
    fun webTarget(item: DiscourseNotification, base: String): String = when {
        item.data?.badgeId?.let { it > 0 } == true -> "${base.trimEnd('/')}/badges/${item.data.badgeId}"
        item.topicId?.let { it > 0 } == true -> "${base.trimEnd('/')}/t/${item.topicId}" + (item.postNumber?.takeIf { it > 0 }?.let { "/$it" } ?: "")
        else -> "${base.trimEnd('/')}/notifications"
    }
}
