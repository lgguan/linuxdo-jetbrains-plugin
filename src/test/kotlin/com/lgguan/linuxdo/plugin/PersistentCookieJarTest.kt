package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.net.LoginCookieSnapshot
import com.lgguan.linuxdo.plugin.net.LoginCookieSupport
import com.lgguan.linuxdo.plugin.net.PersistentCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.cef.network.CefCookie
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Date

class PersistentCookieJarTest {
    private val url = "https://linux.do/".toHttpUrl()
    private fun jar() = PersistentCookieJar(persistenceEnabled = false)
    private fun cookie(header: String) = requireNotNull(Cookie.parse(url, header))

    @Test
    fun anonymousSessionIsNotLogin() {
        val jar = jar()
        jar.injectCookie("_forum_session", "anonymous")
        jar.injectCookie("cf_clearance", "clearance", ".linux.do")
        assertFalse(jar.hasValidSession())
        jar.injectCookie("_t", "auth")
        assertTrue(jar.hasValidSession())
    }

    @Test
    fun normalizesDomainsAndLoadsCookies() {
        val jar = jar()
        jar.injectCookie("cf_clearance", "clearance", ".linux.do")
        jar.injectCookie("_t", "auth")
        assertEquals(mapOf("cf_clearance" to "clearance", "_t" to "auth"),
            jar.loadForRequest(url).associate { it.name to it.value })
    }

    @Test
    fun expiredOrDeletedTokenIsNotReused() {
        val jar = jar()
        jar.saveFromResponse(url, listOf(cookie("_t=auth; Secure; HttpOnly; Path=/")))
        jar.saveFromResponse(url, listOf(cookie("_t=deleted; Max-Age=0; Path=/")))
        assertFalse(jar.hasValidSession())
        assertTrue(jar.loadForRequest(url).isEmpty())
    }

    @Test
    fun matchesSecureHostOnlyAndPathAttributes() {
        val jar = jar()
        jar.saveFromResponse(url, listOf(
            cookie("_t=root; Secure; HttpOnly; Path=/"),
            cookie("preference=private; Secure; Path=/account")
        ))
        assertTrue(jar.loadForRequest("http://linux.do/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://sub.linux.do/".toHttpUrl()).isEmpty())
        assertEquals(1, jar.loadForRequest(url).size)
        assertEquals(2, jar.loadForRequest("https://linux.do/account/profile".toHttpUrl()).size)
        assertEquals(1, jar.loadForRequest("https://linux.do/accounting".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("https://evillinux.do/".toHttpUrl()).isEmpty())
    }

    @Test
    fun preservesSameNameCookiesOnDifferentPaths() {
        val jar = jar()
        jar.saveFromResponse(url, listOf(cookie("a=root; Path=/"), cookie("a=nested; Path=/account")))
        assertEquals(listOf("nested", "root"),
            jar.loadForRequest("https://linux.do/account".toHttpUrl()).map { it.value })
    }

    @Test
    fun ignoresForeignDomainCookie() {
        val jar = jar()
        val foreign = Cookie.Builder().name("_t").value("foreign").domain("evillinux.do").build()
        jar.saveFromResponse(url, listOf(foreign))
        assertNull(jar.getUserToken())
    }

    @Test
    fun lateBrowserSyncCannotUndoLogoutOrManualImport() {
        val jar = jar()
        val oldGeneration = jar.generation
        jar.clearAll()
        jar.injectCookie("_t", "manual-new-account")
        jar.saveBrowserCookies(listOf(cookie("_t=old-account")), oldGeneration)
        assertEquals("manual-new-account", jar.getUserToken())
        jar.clearAll()
        assertNull(jar.getUserToken())
    }

    @Test
    fun parsesCookieHeaderWithPrefixAndEqualsInValues() {
        val parsed = LoginCookieSupport.parseHeader(
            "Cookie: _t=abc==; _forum_session=session%2Bvalue; cf_clearance=clearance"
        ).associate { it.name to it.value }
        assertEquals("abc==", parsed["_t"])
        assertEquals("session%2Bvalue", parsed["_forum_session"])
        assertEquals("clearance", parsed["cf_clearance"])
    }

    @Test
    fun ignoresMalformedPairsAndSetCookieAttributes() {
        val parsed = LoginCookieSupport.parseHeader(
            "_t=valid; invalid; =bad; bad name=bad; Path=/; Domain=linux.do; SameSite=Lax; Secure"
        )
        assertEquals(listOf("_t"), parsed.map { it.name })
    }

    private fun cefCookie(domain: String = ".linux.do") = CefCookie(
        "_t", "auth", domain, "/", true, true,
        Date(), Date(), true, Date(System.currentTimeMillis() + 60_000)
    )

    @Test
    fun cefConversionPreservesHttpOnlySecureExpiryAndDomainScope() {
        val input = cefCookie()
        val cookie = requireNotNull(LoginCookieSupport.fromCef(input))
        assertTrue(cookie.httpOnly)
        assertTrue(cookie.secure)
        assertFalse(cookie.hostOnly)
        assertEquals(input.expires.time, cookie.expiresAt)
        assertEquals("linux.do", cookie.domain)
        assertTrue(requireNotNull(LoginCookieSupport.fromCef(cefCookie("linux.do"))).hostOnly)
    }

    @Test
    fun rejectsUnrelatedAndSubdomainBrowserCookies() {
        for (domain in listOf("evillinux.do", "linux.do.evil.test", "cloudflare.com", "connect.linux.do")) {
            assertNull(LoginCookieSupport.fromCef(cefCookie(domain)))
        }
    }

    @Test
    fun checksActualHttpsOrigin() {
        assertTrue(LoginCookieSupport.isForumUrl("https://linux.do/login"))
        for (url in listOf("http://linux.do/", "https://linux.do.evil.test/",
            "https://evil.test/?next=linux.do", "https://linux.do@evil.test/",
            "https://linux.do:444/", "not a url")) {
            assertFalse(LoginCookieSupport.isForumUrl(url))
        }
    }

    @Test
    fun waitsForTokenEvenWhenClearanceAndAnonymousSessionArriveFirst() {
        val results = mutableListOf<List<Cookie>>()
        val snapshot = LoginCookieSnapshot { results.add(it) }
        assertTrue(snapshot.visit(cookie("cf_clearance=clearance"), 0, 3))
        assertTrue(snapshot.visit(cookie("_forum_session=anonymous"), 1, 3))
        assertTrue(results.isEmpty())
        assertFalse(snapshot.visit(cookie("_t=auth; HttpOnly"), 2, 3))
        assertEquals(1, results.size)
        assertEquals(listOf("cf_clearance", "_forum_session", "_t"), results.single().map { it.name })
        snapshot.finish()
        assertEquals(1, results.size)
    }

    @Test
    fun timeoutCompletesOnceAndRejectsLateCookies() {
        val results = mutableListOf<List<Cookie>>()
        val snapshot = LoginCookieSnapshot { results.add(it) }
        snapshot.finish()
        assertFalse(snapshot.visit(cookie("_t=late"), 0, 1))
        snapshot.finish()
        assertEquals(listOf(emptyList<Cookie>()), results)
    }

    @Test
    fun rejectedLastCookieStillCompletesVisit() {
        var completed = false
        val snapshot = LoginCookieSnapshot { completed = true }
        snapshot.visit(null, 0, 1)
        assertTrue(completed)
    }

    @Test
    fun laterSyncStillFindsLoginTokenAfterClearanceOnlySync() {
        val jar = jar()
        LoginCookieSnapshot { jar.saveBrowserCookies(it, jar.generation) }
            .visit(cookie("cf_clearance=clearance"), 0, 1)
        assertFalse(jar.hasValidSession())
        val next = LoginCookieSnapshot { jar.saveBrowserCookies(it, jar.generation) }
        next.visit(cookie("cf_clearance=clearance"), 0, 2)
        next.visit(cookie("_t=auth"), 1, 2)
        assertTrue(jar.hasValidSession())
    }

    @Test
    fun testRemoveCookiePurgesStaleClearance() {
        val jar = jar()
        jar.injectCookie("cf_clearance", "clearance-val", ".linux.do")
        jar.injectCookie("_t", "auth-token", "linux.do")
        assertEquals("clearance-val", jar.getCookie("cf_clearance"))
        assertEquals("auth-token", jar.getUserToken())

        jar.removeCookie("cf_clearance")
        assertNull(jar.getCookie("cf_clearance"))
        assertEquals("auth-token", jar.getUserToken())
        assertTrue(jar.hasValidSession())
    }

    @Test
    fun testCloudflareChallengeExceptionIsIOException() {
        val ex = com.lgguan.linuxdo.plugin.net.CloudflareChallengeException("Turnstile required")
        assertTrue(ex is java.io.IOException)
        assertEquals("Turnstile required", ex.message)
    }

    @Test
    fun testClassesLoadable() {
        assertNotNull(Class.forName("com.lgguan.linuxdo.plugin.ui.dialog.LoginAuthDialog"))
        assertNotNull(Class.forName("com.lgguan.linuxdo.plugin.ui.toolwindow.DocViewerPanel"))
        assertNotNull(Class.forName("com.lgguan.linuxdo.plugin.editor.LinuxDoTopicFileEditor"))
    }

    @Test
    fun testInitializationDoesNotEagerlyRequestPasswordSafe() {
        val jar = PersistentCookieJar(persistenceEnabled = true)
        assertNotNull(jar)
        assertNotNull(com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient)
        assertNotNull(com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient.cookieJar)
    }
}
