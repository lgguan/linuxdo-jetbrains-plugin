package com.lgguan.linuxdo.plugin

import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.theme.ForumHtml
import com.lgguan.linuxdo.plugin.common.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class MarketplaceSafetyTest {
    @Test fun `response inspection failures close the response without replay`() {
        var closed = 0
        var attempts = 0
        assertThrows(java.io.IOException::class.java) {
            CsrfRecovery.execute("token", SessionEpoch.current,
                send = { attempts++; Unit },
                failure = { throw java.io.IOException("incomplete response") },
                close = { closed++ }, refresh = { fail("Must not retry an incomplete response") })
        }
        assertEquals(1, attempts)
        assertEquals(1, closed)
    }

    @Test fun `queued writes from a previous account fail before accessing credentials or network`() {
        val version = SessionEpoch.current
        SessionEpoch.advance()
        val api = com.lgguan.linuxdo.plugin.api.DiscourseApiClient
        val results = listOf(
            api.createTopic("title", "draft", 1, expectedVersion = version),
            api.createReply(1, "draft", expectedVersion = version),
            api.boostPost(1, "draft", expectedVersion = version),
            api.toggleLike(1, false, expectedVersion = version),
            api.uploadImageBytes(byteArrayOf(), "image.png", expectedVersion = version),
            api.uploadImageFile(java.io.File("does-not-exist.png"), expectedVersion = version)
        )
        results.forEach { assertInstanceOf(StaleSessionException::class.java, it.exceptionOrNull()) }
    }

    @Test fun `writes retry one definite csrf rejection but never replay errors or timeouts`() {
        data class Response(val status: Int, val body: String = "")
        for (status in listOf(401, 403, 429, 500)) {
            var attempts = 0
            var refreshes = 0
            assertThrows(java.io.IOException::class.java) {
                CsrfRecovery.execute<Response>("old", SessionEpoch.current,
                    send = { attempts++; Response(status) },
                    failure = { HttpFailure.classify(it.status, emptyMap(), it.body) }, close = {},
                    refresh = { refreshes++; "new" })
            }
            assertEquals(1, attempts)
            assertEquals(0, refreshes)
        }
        val tokens = mutableListOf<String?>()
        val response = CsrfRecovery.execute("old", SessionEpoch.current,
            send = { token -> tokens += token; if (token == "old") Response(403, "invalid_csrf") else Response(204) },
            failure = { HttpFailure.classify(it.status, emptyMap(), it.body) }, close = {}, refresh = { "new" })
        assertEquals(204, response.status)
        assertEquals(listOf("old", "new"), tokens)
        var attempts = 0
        assertThrows(java.net.SocketTimeoutException::class.java) {
            CsrfRecovery.execute<Response>("old", SessionEpoch.current,
                send = { attempts++; throw java.net.SocketTimeoutException() }, failure = { null }, close = {},
                refresh = { fail("An ambiguous write must not be retried") })
        }
        assertEquals(1, attempts)
    }

    @Test fun `csrf refresh cannot replay an old account write after logout`() {
        var attempts = 0
        assertThrows(StaleSessionException::class.java) {
            CsrfRecovery.execute("old", SessionEpoch.current,
                send = { attempts++; 403 }, failure = { CsrfRejectedException() }, close = {},
                refresh = { SessionEpoch.advance(); "new-account-token" })
        }
        assertEquals(1, attempts)
    }

    @Test fun `expired cookie cannot sustain confirmed login`() {
        val jar = PersistentCookieJar(false)
        jar.injectCookie("_t", "test")
        val auth = LinuxDoAuthService(jar)
        auth.setCurrentUserDirectly(UserInfo(7, "user"))
        assertTrue(auth.isLoggedIn)
        jar.removeCookie("_t")
        assertFalse(auth.isLoggedIn)
        assertEquals(LinuxDoAuthService.Status.SIGNED_OUT, auth.status)
    }

    @Test fun `document capability rejects foreign pages frames and old documents`() {
        val trust = DocumentTrust()
        val first = trust.renew()
        val token = trust.token
        assertTrue(trust.accepts(first, true, token))
        assertTrue(trust.accepts("$first#post-2", true, token))
        for (url in listOf("https://evil.test/#post-2", "https://linux.do/t/1#post-2", "javascript:alert(1)", "data:text/html,test", "$first?x=1")) {
            assertFalse(trust.accepts(url, true, token), url)
            assertFalse(trust.isFloorJump(url), url)
        }
        assertFalse(trust.accepts(first, false, token))
        assertFalse(trust.accepts(first, true, "forged"))
        trust.renew()
        assertFalse(trust.accepts(first, true, token))
        assertFalse(trust.accepts(trust.url, true, token))
        assertTrue(trust.accepts(trust.url, true, trust.token))
    }

    @Test fun `forum markup retains media and formatting while removing executable content`() {
        val source = """<script>alert('attack')</script><iframe src="https://evil.test"></iframe>
            <base href="https://evil.test"><object data="file:///etc/passwd"></object>
            <svg onload="alert(1)"><a href="javascript:alert(1)">x</a></svg>
            <p onclick="alert(1)" style="position:fixed" id="intellijBridge">text</p>
            <a href="jav&#x61;script:alert(1)">bad</a><img src="https://cdn.test/a.png" onerror="alert(1)">
            <details open><summary>example</summary><pre><code>println(&quot;safe&quot;)</code></pre></details>
            <video controls><source src="https://cdn.test/a.webm" type="video/webm"></video>
            <div class="video-placeholder" data-video-src="javascript:alert(1)">video</div>"""
        val clean = ForumHtml.clean(source)
        for (bad in listOf("<script", "<iframe", "<base", "<object", "<svg", "onclick", "onerror", "javascript:", "position:fixed", "id=")) assertFalse(clean.contains(bad), clean)
        assertTrue(clean.contains("<code>"))
        assertTrue(clean.contains("https://cdn.test/a.png"))
        assertTrue(clean.contains("https://cdn.test/a.webm"))
        assertTrue(clean.contains("<details open>"))
    }

    @Test fun `status classification is independent of transport and does not disclose body`() {
        val secret = "private reply content"
        for (status in listOf(401, 403, 500)) {
            val javaFailure = HttpFailure.classify(status, emptyMap(), secret)
            val response = LinuxDoJcefBridge.BridgeResponse(status = status, body = secret)
            val cefFailure = HttpFailure.classify(response.status, response.headers.orEmpty(), response.body.orEmpty())
            assertEquals(javaFailure!!::class, cefFailure!!::class)
            assertEquals("HTTP $status", cefFailure.message)
        }
        assertNull(HttpFailure.classify(204, emptyMap(), ""))
        assertInstanceOf(CsrfRejectedException::class.java, HttpFailure.classify(403, emptyMap(), "invalid_csrf"))
        assertInstanceOf(HttpStatusException::class.java, HttpFailure.classify(403, mapOf("Server" to "cloudflare"), "Forbidden"))
        assertInstanceOf(CloudflareChallengeException::class.java, HttpFailure.classify(403, mapOf("Cf-Mitigated" to "challenge"), ""))
        val limited = HttpFailure.classify(429, mapOf("retry-after" to "120", "Server" to "cloudflare"), "Too Many Requests") as RateLimitException
        assertEquals(120, limited.retryAfterSeconds)
        assertEquals(60, HttpFailure.retryAfter("Wed, 30 Sep 2026 00:01:00 GMT", java.time.Instant.parse("2026-09-30T00:00:00Z").toEpochMilli()))
    }

    @Test fun `Cloudflare 429 requires verification in Java and JCEF without disclosing error content`() {
        val payloads = listOf(
            mapOf("cf-mitigated" to "challenge") to "",
            mapOf("Content-Type" to "text/html") to "<title>Just a moment</title><script>challenge-platform</script>",
            emptyMap<String, String>() to "CloudFlare 429: private error detail",
            mapOf("Content-Type" to "application/json") to """{"errors":["Cloudflare verification required"]}"""
        )
        for ((headers, body) in payloads) {
            val failure = HttpFailure.classify(429, headers, body)
            val response = LinuxDoJcefBridge.BridgeResponse(status = 429, headers = headers, body = body)
            val cefFailure = HttpFailure.classify(response.status, response.headers.orEmpty(), response.body.orEmpty())
            assertInstanceOf(CloudflareChallengeException::class.java, failure)
            assertEquals(failure!!::class, cefFailure!!::class)
            assertEquals("Cloudflare 人机验证未通过 (HTTP 429)", failure.message)
            assertTrue(com.lgguan.linuxdo.plugin.ui.dialog.ComposerErrors.parse(failure).contains("人机验证"))
            assertTrue(com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel.formatErrorDisplay(failure.message).contains("人机验证"))
        }
    }

    @Test fun `session changes reject stale csrf and cookie responses`() {
        val cache = SessionCache<String>()
        val jar = PersistentCookieJar(false)
        val oldVersion = SessionEpoch.current
        cache.put(oldVersion, "old")
        jar.requestEpoch.set(oldVersion)
        jar.clearAll()
        cache.put(oldVersion, "late")
        jar.saveFromResponse("https://linux.do".toHttpUrl(), listOf(Cookie.Builder().name("_t").value("old").hostOnlyDomain("linux.do").build()))
        jar.requestEpoch.remove()
        assertNull(cache.get())
        assertNull(jar.getUserToken())
        cache.put(SessionEpoch.current, "new")
        assertEquals("new", cache.get())
    }

    @Test fun `failed verification keeps credentials pending without a fictional user`() {
        val jar = PersistentCookieJar(false)
        jar.injectCookie("_t", "pending")
        val auth = LinuxDoAuthService(jar, { Result.failure(HttpStatusException(429)) }, { it() })
        auth.refreshCurrentUser(force = true)
        assertFalse(auth.isLoggedIn)
        assertNull(auth.currentUser)
        assertEquals(LinuxDoAuthService.Status.CREDENTIALS_PENDING, auth.status)
        assertThrows(IllegalArgumentException::class.java) { auth.setCurrentUserDirectly(UserInfo(0, "LinuxDoer")) }
    }

    @Test fun `logout while account request is pending cannot restore confirmed user`() {
        val jar = PersistentCookieJar(false)
        val jobs = mutableListOf<() -> Unit>()
        val auth = LinuxDoAuthService(jar, { Result.success(UserInfo(7, "user")) }, { jobs += it })
        auth.refreshCurrentUser(force = true)
        auth.logout()
        jobs.single().invoke()
        assertNull(auth.currentUser)
        assertEquals(LinuxDoAuthService.Status.SIGNED_OUT, auth.status)
    }

    @Test fun `late notifications cannot repopulate an account after logout`() {
        val auth = LinuxDoAuthService(PersistentCookieJar(false).apply { injectCookie("_t", "test") })
        auth.setCurrentUserDirectly(UserInfo(7, "user"))
        val service = LinuxDoNotificationService(auth, {
            auth.logout()
            Result.success(listOf(DiscourseNotification(1, notificationType = 1, read = false)))
        }, initialize = false)
        try {
            service.refreshNotifications()
            assertEquals(0, service.unreadCount)
            assertTrue(service.recentNotifications.isEmpty())
        } finally { service.dispose() }
    }

    @Test fun `listener ownership removes every subscription on dispose`() {
        val settings = LinuxDoSettingsState()
        var calls = 0
        repeat(30) {
            val owner = Disposer.newDisposable()
            settings.addSettingsListener(owner) { calls++ }
            settings.fireSettingsChanged()
            Disposer.dispose(owner)
        }
        assertEquals(30, calls)
        settings.fireSettingsChanged()
        assertEquals(30, calls)
    }

    @Test fun `read floors and last position are isolated per account including guest`() {
        var account = "first"
        val service = LinuxDoReadTrackingService { account }
        service.markFloorRead(1, 10)
        assertFalse(service.isPostRead(1, 9, false, null))
        account = "guest"
        assertNull(service.getLastReadPostNumber(1))
        service.markReadLocally(1, 2)
        account = "second"
        assertFalse(service.isPostRead(1, 10, false, null))
        account = "first"
        assertEquals(10, service.getLastReadPostNumber(1))
        assertTrue(service.isPostRead(1, 10, false, null))
    }

    @Test fun `reading measures only foreground dwell and batches without multiplying elapsed time`() {
        var nanos = 0L
        val clock = ReadingClock { nanos }
        clock.sample(setOf(2, 9), true)
        repeat(15) { nanos += 1_000_000_000; clock.sample(setOf(2, 9), true) }
        assertTrue(clock.due())
        assertEquals(mapOf(2 to 7500L, 9 to 7500L), clock.drain())
        assertFalse(clock.due())
        nanos += 1_000_000_000
        clock.sample(setOf(2, 9), false)
        nanos += 60_000_000_000
        clock.sample(setOf(2, 9), true)
        assertTrue(clock.drain().isEmpty())
    }

    @Test fun `old site settings always normalize to Linux Do`() {
        val settings = LinuxDoSettingsState()
        settings.baseUrl = "https://another.example/forum"
        assertEquals("https://linux.do", settings.baseUrl)
        assertEquals("https://linux.do", settings.toNetworkConfig().baseUrl)
    }

    @Test fun `image decode checks dimensions before pixel allocation`() {
        val bytes = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        assertEquals(2, ImageSafety.decode(bytes).width)
        // PNG IHDR width/height are available before the decompressor allocates pixels.
        java.nio.ByteBuffer.wrap(bytes).putInt(16, 100000).putInt(20, 100000)
        assertThrows(IllegalArgumentException::class.java) { ImageSafety.decode(bytes) }
    }

    @Test fun `cache evicts expired and oldest files within capacity`(@TempDir directory: Path) {
        val now = System.currentTimeMillis()
        val old = directory.resolve("old.png").toFile().apply { writeBytes(ByteArray(10)); setLastModified(now - ImageCachePolicy.MAX_AGE_MS - 1000) }
        val older = directory.resolve("older.png").toFile().apply { writeBytes(ByteArray(10)); setLastModified(now - 10000) }
        val newest = directory.resolve("new.png").toFile().apply { writeBytes(ByteArray(10)) }
        ImageCachePolicy.prune(directory.toFile(), now, 10)
        assertFalse(old.exists())
        assertFalse(older.exists())
        assertTrue(newest.exists())
    }
}
