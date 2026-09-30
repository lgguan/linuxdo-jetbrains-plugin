package com.lgguan.linuxdo.plugin.common

object Constants {
    const val DEFAULT_BASE_URL = "https://linux.do"
    const val TOOL_WINDOW_ID = "API Docs"

    val DEFAULT_USER_AGENT: String get() = BrowserIdentity.defaultUserAgent()

    const val COOKIE_FORUM_SESSION = "_forum_session"
    const val COOKIE_TOKEN = "_t"
    const val COOKIE_CF_CLEARANCE = "cf_clearance"
    const val PASSWORD_SAFE_SERVICE_NAME = "LinuxDoPlugin"
    const val PASSWORD_SAFE_KEY_SESSION = "forum_session_cookie"
    const val PASSWORD_SAFE_KEY_TOKEN = "user_token_cookie"
    const val PASSWORD_SAFE_KEY_CF = "cf_clearance_cookie"

    const val DEFAULT_NOTIFICATION_ACTIVE_INTERVAL_SECONDS = 60
    const val DEFAULT_NOTIFICATION_INACTIVE_INTERVAL_SECONDS = 300
    const val MIN_NOTIFICATION_INTERVAL_SECONDS = 30
    const val DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS = 900L // 15 mins (防风控安全熔断冷却)

    enum class DohProvider(val displayName: String, val url: String, val bootstrapIp: String) {
        LINUXDO("LinuxDo DoH", "https://ldh.ddd.oaifree.com/query-dns", ""),
        DISABLED("禁用 DoH (使用系统默认 DNS)", "", ""),
        CUSTOM("自定义 DoH 服务", "", "");

        override fun toString(): String = displayName
    }

    enum class TopicFilter(val key: String, val displayName: String, val endpoint: String) {
        LATEST("latest", "最新话题 (Latest)", "/latest.json"),
        TOP("top", "全站热门 (Top)", "/top.json"),
        HOT("hot", "今日热帖 (Hot)", "/top.json?period=daily"),
        NEW("new", "最新发表 (New)", "/new.json"),
        UNREAD("unread", "未读话题 (Unread)", "/unread.json");

        override fun toString(): String = displayName
    }
}
