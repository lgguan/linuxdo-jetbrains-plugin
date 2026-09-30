package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.cef.network.CefCookie
import com.intellij.ui.jcef.JBCefCookie
import java.util.Date

internal object LoginCookieSupport {
    val forumUrl = "https://linux.do/".toHttpUrl()
    private val cookieName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    fun isForumUrl(url: String?): Boolean = url?.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.host == forumUrl.host && it.port == 443
    } == true

    /** CEF requires an empty Domain to create a host-only cookie. */
    fun toJcefCookie(cookie: Cookie): JBCefCookie = JBCefCookie(
        cookie.name, cookie.value, if (cookie.hostOnly) "" else ".${cookie.domain}",
        cookie.path, cookie.secure, cookie.httpOnly, null, null,
        cookie.persistent, if (cookie.persistent) Date(cookie.expiresAt) else null
    )

    // This transient session owns the page's CSRF token. Restoring an older Java
    // snapshot can invalidate a live login page even with the correct cookie scope.
    fun canRestoreToBrowser(cookie: Cookie): Boolean = cookie.name != Constants.COOKIE_FORUM_SESSION

    fun hasLegacySessionDuplicate(cookies: com.google.gson.JsonArray): Boolean {
        fun hasDomain(domain: String) = cookies.any { item ->
            val cookie = item.asJsonObject
            cookie.get("name")?.asString == Constants.COOKIE_FORUM_SESSION &&
                cookie.get("domain")?.asString == domain && cookie.get("path")?.asString == "/"
        }
        return hasDomain(forumUrl.host) && hasDomain(".${forumUrl.host}")
    }

    fun fromCef(cookie: CefCookie): Cookie? = runCatching {
        val domain = cookie.domain.orEmpty().lowercase()
        if (domain.removePrefix(".") != forumUrl.host) return null
        Cookie.Builder().name(cookie.name).value(cookie.value).apply {
            if (domain.startsWith(".")) domain(domain.removePrefix(".")) else hostOnlyDomain(domain)
            path(cookie.path?.takeIf { it.startsWith("/") } ?: "/")
            if (cookie.secure) secure()
            if (cookie.httponly) httpOnly()
            if (cookie.hasExpires && cookie.expires != null && cookie.expires.time > 0) {
                expiresAt(cookie.expires.time)
            }
        }.build()
    }.getOrNull()

    fun parseHeader(raw: String): List<Cookie> {
        val header = raw.trim().replaceFirst(Regex("^Cookie\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        return header.split(';', '\n', '\r').mapNotNull { pair ->
            val parts = pair.trim().split('=', limit = 2)
            if (parts.size != 2 || !cookieName.matches(parts[0].trim()) || parts[0].trim().lowercase() in setOf(
                    "path", "domain", "expires", "max-age", "samesite", "secure", "httponly"
                )) return@mapNotNull null
            runCatching {
                Cookie.Builder().name(parts[0].trim()).value(parts[1].trim())
                    .hostOnlyDomain(forumUrl.host).path("/").secure().build()
            }.getOrNull()
        }
    }
}

/** Collect the entire visit before publishing, including _t if it arrives last. */
internal class LoginCookieSnapshot(private val onComplete: (List<Cookie>) -> Unit) {
    private val cookies = linkedMapOf<Triple<String, String, String>, Cookie>()
    private var completed = false

    @Synchronized
    fun visit(cookie: Cookie?, index: Int, total: Int): Boolean {
        if (completed) return false
        if (cookie != null) {
            cookies[Triple(cookie.name, cookie.domain, cookie.path)] = cookie
            if (cookie.name in setOf(Constants.COOKIE_TOKEN, Constants.COOKIE_FORUM_SESSION, Constants.COOKIE_CF_CLEARANCE)) {
                LinuxDoLog.info("Captured critical cookie from CEF: ${cookie.name} (${cookie.domain}), total $total")
            }
        }
        if (total > 0 && index == total - 1) finish()
        return !completed
    }

    @Synchronized
    fun finish() {
        if (completed) return
        completed = true
        val list = cookies.values.toList()
        LinuxDoLog.info("syncCookiesFromJcef finished. Total extracted: ${list.size} cookies (hasSession=${list.any { it.name == Constants.COOKIE_TOKEN }})")
        onComplete(list)
    }
}
