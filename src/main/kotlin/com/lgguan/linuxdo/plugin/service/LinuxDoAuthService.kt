package com.lgguan.linuxdo.plugin.service

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.invokeLoginUiLater
import com.lgguan.linuxdo.plugin.model.UserInfo
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.intellij.openapi.application.ApplicationManager
import java.util.concurrent.CopyOnWriteArrayList

class LinuxDoAuthService(
    private val credentials: com.lgguan.linuxdo.plugin.net.PersistentCookieJar = LinuxDoHttpClient.cookieJar,
    private val fetchUser: () -> Result<UserInfo?> = { DiscourseApiClient.getCurrentUser() },
    private val background: (() -> Unit) -> Unit = { work ->
        ApplicationManager.getApplication()?.executeOnPooledThread { work() } ?: work()
    },
    private val importBrowserCookies: (List<okhttp3.Cookie>, Long) -> Unit = { cookies, version ->
        com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.credentialsImported(cookies, version)
    },
    initialize: Boolean = ApplicationManager.getApplication() != null
) {
    enum class Status { SIGNED_OUT, CREDENTIALS_PENDING, CONFIRMED }
    val status: Status get() = if (currentUser != null) Status.CONFIRMED
        else if (credentials.hasValidSession()) Status.CREDENTIALS_PENDING else Status.SIGNED_OUT
    val sessionVersion: Long get() = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
    @Volatile var lastVerificationFailure: Throwable? = null
        private set

    fun replaceCredentials(cookies: List<okhttp3.Cookie>) {
        credentials.clearAll()
        credentials.saveFromResponse(com.lgguan.linuxdo.plugin.net.LoginCookieSupport.forumUrl, cookies)
        currentUser = null
        lastVerificationFailure = null
        importBrowserCookies(cookies, sessionVersion)
        notifyAuthChanged(null)
    }

    fun credentialsPending() {
        com.lgguan.linuxdo.plugin.net.SessionEpoch.advance()
        currentUser = null
        lastVerificationFailure = null
        notifyAuthChanged(null)
    }


    @Volatile private var confirmedUser: UserInfo? = null
    @Volatile private var confirmedVersion: Long = -1
    var currentUser: UserInfo?
        get() = confirmedUser?.takeIf { confirmedVersion == sessionVersion && credentials.hasValidSession() }
        private set(value) { confirmedUser = value; confirmedVersion = sessionVersion }

    val isLoggedIn: Boolean
        get() = currentUser != null

    private val authListeners = CopyOnWriteArrayList<(UserInfo?) -> Unit>()

    fun addAuthListener(owner: com.intellij.openapi.Disposable, listener: (UserInfo?) -> Unit) {
        addAuthListener(listener)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { authListeners.remove(listener) })
    }

    fun addAuthListener(listener: (UserInfo?) -> Unit) {
        authListeners.add(listener)
    }

    fun removeAuthListener(listener: (UserInfo?) -> Unit) {
        authListeners.remove(listener)
    }

    // Refresh coordination and callbacks run on the EDT. Forced saves queue a fresh
    // request instead of reporting the result of a request using older cookies.
    private var isRefreshing = false
    private var refreshAgain = false
    private val refreshCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var lastRefreshTime = 0L

    fun refreshCurrentUser(force: Boolean = false, onComplete: ((Boolean) -> Unit)? = null) {
        invokeLoginUiLater {
            onComplete?.let { refreshCallbacks.add(it) }
            if (isRefreshing) {
                if (force) refreshAgain = true
            } else {
                startRefresh(force)
            }
        }
    }

    private fun finishRefresh(loggedIn: Boolean) {
        val callbacks = refreshCallbacks.toList()
        refreshCallbacks.clear()
        callbacks.forEach { it(loggedIn) }
    }

    private fun startRefresh(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastRefreshTime < 3000)) {
            finishRefresh(currentUser != null)
            return
        }
        isRefreshing = true
        lastRefreshTime = now
        val generation = sessionVersion
        background {
            val result = try {
                fetchUser()
            } catch (e: Exception) {
                Result.failure(e)
            }
            invokeLoginUiLater {
                isRefreshing = false
                if (refreshAgain) {
                    refreshAgain = false
                    startRefresh(force = true)
                } else {
                    if (sessionVersion == generation) {
                        val user = result.getOrNull()?.takeIf { it.id > 0 && !it.username.isNullOrBlank() }
                        lastVerificationFailure = result.exceptionOrNull()
                            ?: if (user == null) java.io.IOException("账号接口未返回有效用户信息") else null
                        val denied = (lastVerificationFailure as? com.lgguan.linuxdo.plugin.net.HttpStatusException)?.status in listOf(401, 403, 404)
                        currentUser = if (result.isFailure && !denied) currentUser else user
                        com.lgguan.linuxdo.plugin.common.LinuxDoLog.info(
                            "Account verification: confirmed=${currentUser != null}, credentialsPresent=${credentials.hasValidSession()}, errorType=${lastVerificationFailure?.javaClass?.simpleName ?: "none"}")
                        notifyAuthChanged(currentUser)
                    }
                    finishRefresh(currentUser != null)
                }
            }
        }
    }

    fun setCurrentUserDirectly(user: UserInfo) {
        require(user.id > 0 && user.username.isNotBlank())
        if (currentUser?.id != null && currentUser?.id != user.id) com.lgguan.linuxdo.plugin.net.SessionEpoch.advance()
        currentUser = user
        notifyAuthChanged(user)
    }

    fun logout(browser: com.lgguan.linuxdo.plugin.net.LinuxDoBrowser? = null) {
        credentials.clearAll()
        currentUser = null
        lastVerificationFailure = null
        com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.resetBridge()
        com.lgguan.linuxdo.plugin.common.LinuxDoImageCache.clear()
        com.lgguan.linuxdo.plugin.net.CloudflareBypassService.clearBrowserCookies(browser)
        notifyAuthChanged(null)
    }

    private fun notifyAuthChanged(user: UserInfo?) {
        val version = sessionVersion
        invokeLoginUiLater {
            if (version != sessionVersion) return@invokeLoginUiLater
            for (listener in authListeners) {
                try {
                    listener(user)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    init {
        // PasswordSafe restores credentials, not a verified account identity.
        // Verify once after service creation so a restart does not leave every action locked.
        if (initialize) background {
            // A fresh installation has no account to verify. Do not start network/JCEF
            // work merely because IDEA created a status bar widget.
            if (credentials.hasValidSession()) refreshCurrentUser(force = true)
        }
    }

    companion object {
        fun getInstance(): LinuxDoAuthService {
            return ApplicationManager.getApplication()?.getService(LinuxDoAuthService::class.java)
                ?: LinuxDoAuthServiceHolder.INSTANCE
        }
    }

    private object LinuxDoAuthServiceHolder {
        val INSTANCE = LinuxDoAuthService()
    }
}
