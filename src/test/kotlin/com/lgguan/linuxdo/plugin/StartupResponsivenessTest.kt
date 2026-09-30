package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.model.UserInfo
import com.lgguan.linuxdo.plugin.net.CookieCredentialStorage
import com.lgguan.linuxdo.plugin.net.PersistentCookieJar
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.*
import javax.swing.SwingUtilities

class StartupResponsivenessTest {
    private class SlowKeyring : CookieCredentialStorage {
        val values = ConcurrentHashMap<String, String>()
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}
        override fun read(key: String): String? { onRead(); return values[key] }
        override fun write(key: String, value: String?) {
            onWrite()
            if (value == null) values.remove(key) else values[key] = value
        }
    }

    private fun <T> ui(action: () -> T): T {
        val result = CompletableFuture<T>()
        SwingUtilities.invokeLater {
            try { result.complete(action()) } catch (t: Throwable) { result.completeExceptionally(t) }
        }
        return result.get(2, TimeUnit.SECONDS)
    }

    @Test
    fun `locked keyring cannot block EDT queries or logout or restore old credentials`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = SlowKeyring().apply {
            values[Constants.PASSWORD_SAFE_KEY_TOKEN] = "old-account"
            onRead = { assertFalse(SwingUtilities.isEventDispatchThread()); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        val jar = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
        try {
            assertFalse(ui { jar.hasValidSession() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui {
                assertFalse(jar.hasValidSession())
                jar.clearAll()
                assertFalse(jar.hasValidSession())
            }
            release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertNull(jar.getUserToken())
            assertNull(storage.values[Constants.PASSWORD_SAFE_KEY_TOKEN])
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun `HTTP cookie restoration waiting on keyring does not hold the UI cookie lock`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = SlowKeyring().apply {
            values[Constants.PASSWORD_SAFE_KEY_TOKEN] = "old-account"
            onRead = {
                assertFalse(SwingUtilities.isEventDispatchThread())
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val jar = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
        // BridgeInterceptor calls this CookieJar method before it sends an HTTP request.
        // The reported Ubuntu freeze started with this worker waiting in PasswordSafe,
        // while UI account queries needed the same cookie monitor in the old implementation.
        val request = worker.submit<List<Cookie>> { jar.loadForRequest("https://linux.do/".toHttpUrl()) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui {
                assertFalse(jar.hasValidSession())
                jar.clearAll()
                jar.injectCookie("_t", "new-account")
                assertEquals("new-account", jar.getUserToken())
            }
            assertFalse(request.isDone, "UI must return while the request still waits for the keyring")
            release.countDown()
            assertEquals("new-account", request.get(3, TimeUnit.SECONDS).single { it.name == "_t" }.value)
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertFalse(storage.values.values.any { it.contains("old-account") })
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun `slow save does not freeze account switch and latest account wins on disk`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = SlowKeyring().apply {
            onWrite = { assertFalse(SwingUtilities.isEventDispatchThread()); entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        val jar = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
        try {
            jar.injectCookie("_t", "old-account")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui {
                assertEquals("old-account", jar.getUserToken())
                jar.clearAll()
                jar.injectCookie("_t", "new-account")
                assertEquals("new-account", jar.getUserToken())
            }
            release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertTrue(storage.values[Constants.PASSWORD_SAFE_KEY_TOKEN]!!.contains("_t=new-account"))
            assertFalse(storage.values.values.any { it.contains("old-account") })
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun `cookie received while credentials load preserves unrelated saved token`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = SlowKeyring().apply {
            values[Constants.PASSWORD_SAFE_KEY_TOKEN] = "saved-account"
            values[Constants.PASSWORD_SAFE_KEY_CF] = "old-clearance"
            onRead = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        val jar = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
        try {
            ui { jar.hasValidSession() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui { jar.injectCookie("cf_clearance", "new-clearance") }
            release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals("saved-account", jar.getUserToken())
            assertEquals("new-clearance", jar.getCookie("cf_clearance"))
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun `server deletion during slow restoration remains deleted after restart`() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = SlowKeyring().apply {
            values[Constants.PASSWORD_SAFE_KEY_TOKEN] = "expired-account"
            onRead = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        }
        val jar = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
        try {
            ui { jar.hasValidSession() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val url = "https://linux.do/".toHttpUrl()
            ui { jar.saveFromResponse(url, listOf(Cookie.parse(url, "_t=deleted; Max-Age=0; Path=/")!!)) }
            release.countDown()
            worker.submit {}.get(3, TimeUnit.SECONDS)
            assertNull(jar.getUserToken())
            val restarted = PersistentCookieJar(true, storage, { worker.submit(it) }, SwingUtilities::isEventDispatchThread)
            assertNull(restarted.getUserToken())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test
    fun `fresh installation defers credential check and does not request account or browser`() {
        val jobs = ArrayDeque<() -> Unit>()
        var requests = 0
        val auth = LinuxDoAuthService(PersistentCookieJar(false), {
            requests++; Result.success(null)
        }, { jobs.addLast(it) }, { _, _ -> fail("Startup must not import browser cookies") }, initialize = true)
        assertEquals(1, jobs.size)
        assertEquals(0, requests)
        while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
        assertEquals(0, requests)
        assertFalse(auth.isLoggedIn)
    }

    @Test
    fun `startup still verifies saved credentials in background`() {
        val jar = PersistentCookieJar(false).apply { injectCookie("_t", "saved-account") }
        val jobs = ArrayDeque<() -> Unit>()
        var requests = 0
        val auth = LinuxDoAuthService(jar, {
            requests++; Result.success(UserInfo(12, "saved-user"))
        }, { jobs.addLast(it) }, initialize = true)
        assertFalse(auth.isLoggedIn)
        while (jobs.isNotEmpty()) jobs.removeFirst().invoke()
        assertEquals(1, requests)
        assertEquals("saved-user", auth.currentUser?.username)
    }
}
