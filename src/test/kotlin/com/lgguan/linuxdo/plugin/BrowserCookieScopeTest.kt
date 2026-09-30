package com.lgguan.linuxdo.plugin

import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.net.LoginCookieSupport
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserCookieScopeTest {
    private val url = "https://linux.do/".toHttpUrl()

    @Test fun `host only cookie must omit CEF domain`() {
        val cookie = Cookie.parse(url, "_t=synthetic; Secure; HttpOnly; Path=/")!!
        val cef = LoginCookieSupport.toJcefCookie(cookie)
        assertEquals("", cef.domain)
        assertTrue(cef.isSecure)
        assertTrue(cef.isHttpOnly)
        assertFalse(cef.hasExpires())
    }

    @Test fun `domain cookie retains scope and expiration`() {
        val cookie = Cookie.parse(url, "cf_clearance=synthetic; Domain=.linux.do; Path=/; Secure; Max-Age=3600")!!
        val cef = LoginCookieSupport.toJcefCookie(cookie)
        assertEquals(".linux.do", cef.domain)
        assertTrue(cef.hasExpires())
        assertEquals(cookie.expiresAt, cef.expires?.time)
    }

    @Test fun `never overwrite browser CSRF session with Java snapshot`() {
        assertFalse(LoginCookieSupport.canRestoreToBrowser(Cookie.parse(url, "_forum_session=stale")!!))
        assertTrue(LoginCookieSupport.canRestoreToBrowser(Cookie.parse(url, "_t=synthetic")!!))
        assertTrue(LoginCookieSupport.canRestoreToBrowser(Cookie.parse(url, "cf_clearance=synthetic")!!))
    }

    @Test fun `migration requires both root session scopes`() {
        fun has(vararg scopes: String) = LoginCookieSupport.hasLegacySessionDuplicate(
            JsonParser.parseString(scopes.joinToString(",", "[", "]")).asJsonArray)
        val host = """{"name":"_forum_session","domain":"linux.do","path":"/"}"""
        val domain = """{"name":"_forum_session","domain":".linux.do","path":"/"}"""
        assertTrue(has(host, domain))
        assertTrue(has(domain, host))
        assertFalse(has(host))
        assertFalse(has(domain))
        assertFalse(has(host, domain.replace("_forum_session", "_t")))
        assertFalse(has(host, domain.replace("\"/\"", "\"/other\"")))
        assertFalse(has(host, domain.replace(".linux.do", ".example.com")))
    }
}
