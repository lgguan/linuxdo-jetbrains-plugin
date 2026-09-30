package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.intellij.openapi.application.ApplicationManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class PersistentCookieJar internal constructor(
    private val persistenceEnabled: Boolean,
    private val storage: CookieCredentialStorage,
    private val background: (() -> Unit) -> Unit,
    private val isUiThread: () -> Boolean
) : CookieJar {
    constructor(persistenceEnabled: Boolean = true) : this(persistenceEnabled, PasswordSafeCookieStorage,
        { work -> ApplicationManager.getApplication()?.executeOnPooledThread { work() } },
        javax.swing.SwingUtilities::isEventDispatchThread)

    // The UI only takes stateLock. Slow keyring calls must never own that lock.
    private val stateLock = Any()
    private val storageLock = Any()
    private val loadQueued = java.util.concurrent.atomic.AtomicBoolean()
    private val saveQueued = java.util.concurrent.atomic.AtomicBoolean()
    private val touchedNames = mutableSetOf<String>()
    private var revision = 0L
    internal val requestEpoch = ThreadLocal<Long>()
    private data class Key(val name: String, val domain: String, val path: String)
    private val cookieStore = mutableMapOf<Key, Cookie>()
    @Volatile
    var generation: Long = 0
        private set
    private val forumUrl = "https://linux.do/".toHttpUrl()
    private val persistedKeys = mapOf(
        Constants.COOKIE_TOKEN to Constants.PASSWORD_SAFE_KEY_TOKEN,
        Constants.COOKIE_FORUM_SESSION to Constants.PASSWORD_SAFE_KEY_SESSION,
        Constants.COOKIE_CF_CLEARANCE to Constants.PASSWORD_SAFE_KEY_CF
    )

    @Volatile
    private var isLoaded = false

    private fun ensureLoaded() {
        if (isLoaded || !persistenceEnabled) return
        if (isUiThread()) {
            if (loadQueued.compareAndSet(false, true)) background { loadPersistedCookies() }
        } else loadPersistedCookies()
    }

    private fun loadPersistedCookies() = synchronized(storageLock) {
        if (isLoaded) return@synchronized
        val restored = loadPersistedCookiesInternal()
        synchronized(stateLock) {
            // Logout/manual import may have completed while the keyring was still locked.
            if (!isLoaded) {
                for (cookie in restored) if (cookie.name !in touchedNames) {
                    cookieStore.putIfAbsent(Key(cookie.name, cookie.domain, cookie.path), cookie)
                }
                isLoaded = true
                touchedNames.clear()
            }
        }
    }

    private fun loadPersistedCookiesInternal(): List<Cookie> {
        val restored = mutableListOf<Cookie>()
        try {
            for ((name, key) in persistedKeys) {
                val stored = storage.read(key) ?: continue
                // Preserve expiry/scope, while accepting old value-only credentials.
                val cookie = if (stored.startsWith("cookie-v1:")) {
                    Cookie.parse(forumUrl, stored.removePrefix("cookie-v1:"))
                } else {
                    Cookie.Builder().name(name).value(stored).hostOnlyDomain(forumUrl.host)
                        .path("/").secure().build()
                }
                if (cookie != null && cookie.name == name && cookie.matches(forumUrl) &&
                    cookie.expiresAt > System.currentTimeMillis() && cookie.value.isNotBlank()) {
                    restored += cookie
                }
            }
        } catch (_: Exception) {
            LinuxDoLog.warn("Failed to load cookies from PasswordSafe")
        }
        return restored
    }

    // Serialize and coalesce saves without holding stateLock during native keyring IO.
    private fun persist() {
        if (!persistenceEnabled || !saveQueued.compareAndSet(false, true)) return
        background {
            synchronized(storageLock) {
                loadPersistedCookies()
                while (true) {
                    val (savedRevision, values) = synchronized(stateLock) {
                        revision to persistedKeys.map { (name, key) ->
                            key to matchingCookies(forumUrl).firstOrNull { it.name == name }?.let { "cookie-v1:$it" }
                        }
                    }
                    try { values.forEach { (key, value) -> storage.write(key, value) } }
                    catch (_: Exception) { LinuxDoLog.warn("Failed to save cookies to PasswordSafe") }
                    val finished = synchronized(stateLock) {
                        if (revision == savedRevision) { saveQueued.set(false); true } else false
                    }
                    if (finished) break
                }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (requestEpoch.get()?.let { it != SessionEpoch.current } == true) return
        ensureLoaded()
        synchronized(stateLock) { saveCookies(url, cookies) }
    }

    private fun saveCookies(url: HttpUrl, cookies: List<Cookie>) {
        if (requestEpoch.get()?.let { it != SessionEpoch.current } == true) return
        var changed = false
        for (cookie in cookies) {
            val cleanDomain = cookie.domain.removePrefix(".")
            if (url.host != cleanDomain && (cookie.hostOnly || !url.host.endsWith(".$cleanDomain"))) continue
            // A deletion received before restoration also needs to reach the keyring.
            if (!isLoaded && touchedNames.add(cookie.name)) changed = true
            val key = Key(cookie.name, cookie.domain, cookie.path)
            // Remove any existing duplicate entry with same name and path (e.g. .linux.do vs linux.do)
            cookieStore.keys.filter { it.name == cookie.name && it.path == cookie.path && it != key }
                .forEach { cookieStore.remove(it); changed = true }
            if (cookie.expiresAt <= System.currentTimeMillis() || cookie.value.isBlank()) {
                changed = cookieStore.remove(key) != null || changed
            } else if (cookieStore[key] != cookie) {
                cookieStore[key] = cookie
                changed = true
            }
        }
        if (changed) { revision++; persist() }
    }

    private fun matchingCookies(url: HttpUrl): List<Cookie> {
        return cookieStore.values
            .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
            .sortedByDescending { it.path.length }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureLoaded()
        return synchronized(stateLock) { matchingCookies(url) }
    }

    fun saveBrowserCookies(cookies: List<Cookie>, expectedGeneration: Long) {
        ensureLoaded()
        synchronized(stateLock) { if (generation == expectedGeneration) saveCookies(forumUrl, cookies) }
    }

    fun injectCookie(name: String, value: String, domain: String = "linux.do") {
        val host = domain.trim().lowercase().removePrefix(".")
        val cookie = Cookie.Builder().name(name).value(value).domain(host).path("/").secure().build()
        saveFromResponse("https://$host/".toHttpUrl(), listOf(cookie))
    }

    fun removeCookie(name: String) {
        ensureLoaded()
        synchronized(stateLock) {
            if (!isLoaded) touchedNames += name
            cookieStore.entries.removeIf { it.key.name == name }
            revision++
            persist()
        }
    }

    fun clearAll() {
        synchronized(stateLock) {
            generation++
            SessionEpoch.advance()
            isLoaded = true
            touchedNames.clear()
            cookieStore.clear()
            revision++
            persist()
        }
    }

    fun getCookie(name: String, domain: String = "linux.do"): String? =
        loadForRequest("https://${domain.trim().removePrefix(".")}/".toHttpUrl())
            .firstOrNull { it.name == name }?.value

    fun getUserToken(): String? = getCookie(Constants.COOKIE_TOKEN)

    fun hasValidSession(): Boolean = !getUserToken().isNullOrBlank()
}
