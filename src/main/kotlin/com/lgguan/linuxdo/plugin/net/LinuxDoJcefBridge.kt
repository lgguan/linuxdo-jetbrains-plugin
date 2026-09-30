package com.lgguan.linuxdo.plugin.net

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser as JBCefBrowser
import com.lgguan.linuxdo.plugin.net.LinuxDoJSQuery as JBCefJSQuery
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.UploadResponse
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headless JCEF (Chromium) Network Bridge.
 *
 * Utilizes the JetBrains Runtime built-in Chromium engine to execute network requests
 * via native TLS 1.3 Encrypted Client Hello (ECH) and same-origin fetch().
 *
 * Lifecycle Architecture (Section 7 Two-tier lifecycle fix):
 * - Explicitly calls `createImmediately()` on headless OSR browser.
 * - Registers life-span handler before creation; navigates in `onAfterCreated`.
 * - Ignores initial `about:blank` placeholder loading.
 * - Validates carrier page origin strictly (normalized scheme, host, port).
 * - Distinguishes internal initialization stages:
 *   - WAITING_NATIVE_BROWSER: Waiting for native CEF instance creation.
 *   - LOADING_CARRIER: Target forum URL navigation in progress.
 *   - WAITING_HANDSHAKE: Carrier page loaded, awaiting JSQuery handshake confirmation.
 * - Monotonic budget enforcement without artificial extensions.
 */
object LinuxDoJcefBridge : Disposable {

    enum class BridgeState {
        NEW,
        INITIALIZING,
        READY,
        CHALLENGE_REQUIRED,
        FAILED,
        DISPOSED
    }

    enum class InitStage {
        NONE,
        WAITING_NATIVE_BROWSER,
        LOADING_CARRIER,
        WAITING_HANDSHAKE
    }

    @PublishedApi
    internal val gson = Gson()

    data class BridgeRequest(
        val requestId: String = UUID.randomUUID().toString(),
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val isUpload: Boolean = false,
        val uploadFileName: String? = null,
        val uploadMimeType: String? = null,
        val uploadBase64: String? = null,
        val uploadType: String? = "composer",
        val isBinary: Boolean = false,
        val sessionVersion: Long = SessionEpoch.current
    )

    data class BridgeResponse(
        val requestId: String = "",
        val success: Boolean = false,
        val status: Int = 0,
        val statusText: String? = null,
        val headers: Map<String, String>? = null,
        val body: String? = null,
        val binaryBase64: String? = null,
        val error: String? = null
    )

    /**
     * Session context managing a single initialization attempt with synchronized completion.
     */
    internal class BridgeInitSession(
        val generation: Long,
        val future: CompletableFuture<Boolean>,
        val startNano: Long,
        val deadlineNano: Long,
        val timeoutSeconds: Long
    ) {
        val authVersion = SessionEpoch.current
        val completed = AtomicBoolean(false)
        @Volatile
        var stage: InitStage = InitStage.WAITING_NATIVE_BROWSER
        @Volatile
        var watchdogTimer: javax.swing.Timer? = null
        @Volatile
        var retryTimer: javax.swing.Timer? = null
        @Volatile
        var navigationStarted: Boolean = false
        @Volatile
        var reloadAttempted: Boolean = false
        @Volatile
        var lastLoadError: String? = null

        fun remainingMillis(): Long = ((deadlineNano - System.nanoTime()) / 1_000_000L).coerceAtLeast(0)

        fun tryFinish(success: Boolean, targetState: BridgeState, failureReason: String? = null): Boolean {
            if (completed.compareAndSet(false, true)) {
                watchdogTimer?.stop()
                watchdogTimer = null
                retryTimer?.stop()
                retryTimer = null

                synchronized(LinuxDoJcefBridge) {
                    if (currentGeneration == generation) {
                        state = targetState
                        if (failureReason != null) {
                            lastFailureReason = failureReason
                        }
                    }
                }
                future.complete(success)
                return true
            }
            return false
        }
    }

    private val pendingRequests = ConcurrentHashMap<String, CompletableFuture<BridgeResponse>>()

    @Volatile
    private var browser: JBCefBrowser? = null

    @Volatile
    private var jsQuery: JBCefJSQuery? = null

    @Volatile
    private var state: BridgeState = BridgeState.NEW

    @Volatile
    private var lastFailureReason: String? = null

    @Volatile
    internal var currentGeneration: Long = 0L

    @Volatile
    private var currentSession: BridgeInitSession? = null

    private val domCsrfCache = SessionCache<String>()
    private var importedCookies: Pair<Long, List<okhttp3.Cookie>>? = null

    @Synchronized
    fun credentialsImported(cookies: List<okhttp3.Cookie>, version: Long) {
        SessionEpoch.requireCurrent(version)
        resetBridge()
        importedCookies = version to cookies.toList()
    }

    @Synchronized
    fun cookiesImportedToBrowser(version: Long) {
        if (importedCookies?.first == version) importedCookies = null
    }

    fun isSupported(): Boolean {
        return try {
            IsolatedCefRuntime.isSupported()
        } catch (_: Throwable) {
            false
        }
    }

    fun getState(): BridgeState = state

    fun getInitStage(): InitStage = currentSession?.stage ?: InitStage.NONE

    fun getLastFailureReason(): String? = lastFailureReason

    fun getCachedDomCsrfToken(): String? = domCsrfCache.get()

    private fun isDisposed(): Boolean {
        val b = browser ?: return true
        return try {
            b.isDisposed
        } catch (_: Throwable) {
            true
        }
    }

    /**
     * Resets the bridge state, disposing any active browser.
     * Useful when the user changes network settings or base URL.
     */
    @Synchronized
    fun resetBridge() {
        disposeInternal()
        importedCookies = null
        state = BridgeState.NEW
        lastFailureReason = null
        domCsrfCache.clear()
    }

    /**
     * Validates whether targetUrl and baseUrl share the same normalized scheme, host, and port.
     */
    fun isSameOrigin(targetUrl: String, baseUrl: String): Boolean {
        return try {
            val u1 = URI(targetUrl)
            val u2 = URI(baseUrl)
            val s1 = u1.scheme?.lowercase() ?: ""
            val s2 = u2.scheme?.lowercase() ?: ""
            val h1 = u1.host?.lowercase() ?: ""
            val h2 = u2.host?.lowercase() ?: ""
            val p1 = if (u1.port != -1) u1.port else (if (s1 == "https") 443 else 80)
            val p2 = if (u2.port != -1) u2.port else (if (s2 == "https") 443 else 80)
            s1 == s2 && h1 == h2 && p1 == p2
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Ensure the headless Chromium browser is initialized and has established the origin connection.
     */
    @Synchronized
    fun ensureReady(timeoutSeconds: Long = 30): CompletableFuture<Boolean> {
        if (!isSupported()) {
            state = BridgeState.FAILED
            lastFailureReason = "当前运行环境不支持 JCEF Chromium 运行时"
            return CompletableFuture.completedFuture(false)
        }

        if (currentSession?.authVersion?.let { it != SessionEpoch.current } == true) resetBridge()
        if (state == BridgeState.READY && !isDisposed()) {
            return CompletableFuture.completedFuture(true)
        }

        val existingSession = currentSession
        if (state == BridgeState.INITIALIZING && existingSession != null && !existingSession.future.isDone) {
            return existingSession.future
        }

        val generation = ++currentGeneration
        val startNano = System.nanoTime()
        val deadlineNano = startNano + timeoutSeconds * 1_000_000_000L
        val newFuture = CompletableFuture<Boolean>()

        val session = BridgeInitSession(
            generation = generation,
            future = newFuture,
            startNano = startNano,
            deadlineNano = deadlineNano,
            timeoutSeconds = timeoutSeconds
        )
        currentSession = session
        state = BridgeState.INITIALIZING
        lastFailureReason = null

        val netConfig = try {
            LinuxDoSettingsState.getInstance().toNetworkConfig()
        } catch (_: Throwable) {
            LinuxDoNetworkConfig()
        }
        val baseUrl = netConfig.baseUrl.trim().removeSuffix("/")

        IsolatedCefRuntime.prepare { prepared ->
            if (session.generation != currentGeneration || session.completed.get()) {
                session.tryFinish(false, BridgeState.FAILED, "初始化请求已过时 (gen=$generation)")
                return@prepare
            }

            try {
                if (browser != null && !isDisposed()) {
                    try {
                        Disposer.dispose(browser!!)
                    } catch (_: Throwable) {}
                }

                LinuxDoLog.info("Initializing headless OSR JCEF (Chromium ECH) Network Bridge (gen=$generation) at $baseUrl ...")

                // 1. Build OSR browser without immediately creating (create immediately after handlers are registered)
                val b = try {
                    JBCefBrowser(prepared.getOrThrow())
                } catch (t: Throwable) {
                    LinuxDoLog.error("Failed to build OSR JBCefBrowser: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(t)}", t)
                    session.tryFinish(false, BridgeState.FAILED, "OSR JBCefBrowser 构建失败: ${t.message}")
                    return@prepare
                }
                browser = b
                JcefNetworkTrace.install(b, "bridge-$generation")

                val app = ApplicationManager.getApplication()
                if (app != null && !app.isDisposed) {
                    Disposer.register(app.getService(LinuxDoPluginLifetime::class.java), b)
                }

                // 2. Register JSQuery
                val query = JBCefJSQuery.create(b)
                jsQuery = query
                Disposer.register(b, query)

                query.addHandler { jsonStr ->
                    handleJsMessage(jsonStr, session)
                    JBCefJSQuery.Response("ok")
                }

                // 3. Register LifeSpanHandler to intercept onAfterCreated
                b.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
                    override fun onAfterCreated(cefBrowser: CefBrowser?) {
                        if (session.generation != currentGeneration || session.completed.get()) return
                        LinuxDoLog.info("LinuxDoJcefBridge: Native Chromium browser created (gen=${session.generation})")
                        session.stage = InitStage.LOADING_CARRIER

                        ApplicationManager.getApplication().invokeLater({
                            if (session.generation == currentGeneration && !session.completed.get() && !isDisposed()) {
                                val imported = importedCookies?.takeIf { it.first == session.authVersion }
                                val sync = if (imported != null) b.replaceSessionCookies(imported.second, imported.first)
                                    else { syncCookiesToJcef(b); CompletableFuture.completedFuture(null) }
                                sync.whenComplete { _, error ->
                                    ApplicationManager.getApplication().invokeLater({
                                        if (session.generation != currentGeneration || session.completed.get() || isDisposed()) return@invokeLater
                                        if (error != null) {
                                            session.tryFinish(false, BridgeState.FAILED, "Cookie 同步失败，请重新保存凭据")
                                        } else {
                                            if (importedCookies === imported) importedCookies = null
                                            session.navigationStarted = true
                                            b.loadURL("$baseUrl/site.json")
                                        }
                                    }, ModalityState.any())
                                }
                            }
                        }, ModalityState.any())
                    }
                }, b.cefBrowser)

                // 4. Register DisplayHandler for Chromium console logs
                b.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                    override fun onConsoleMessage(
                        browser: CefBrowser?,
                        level: CefSettings.LogSeverity?,
                        message: String?,
                        source: String?,
                        line: Int
                    ): Boolean {
                        // Console text may contain credentials, request URLs or user content.
                        return false
                    }
                }, b.cefBrowser)

                // 5. Register LoadHandler with origin check, about:blank filter, and handshake
                b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                    override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                        if (session.generation != currentGeneration || session.completed.get()) return
                        if (frame?.isMain == true) {
                            val currentUrl = frame.url ?: ""

                            // Ignore initial placeholder about:blank
                            if (currentUrl == "about:blank" || !session.navigationStarted) {
                                LinuxDoLog.info("LinuxDoJcefBridge: Ignoring early carrier loadUrl=$currentUrl (gen=${session.generation})")
                                return
                            }

                            LinuxDoLog.info("LinuxDoJcefBridge: Carrier page loaded URL=$currentUrl (HTTP $httpStatusCode, gen=${session.generation})")

                            if (currentUrl.startsWith("chrome-error://")) {
                                val elapsedMs = (System.nanoTime() - session.startNano) / 1_000_000L
                                session.tryFinish(false, BridgeState.FAILED, "载体页面加载显示浏览器错误页: $currentUrl (${elapsedMs}ms)")
                                return
                            }

                            if (!isSameOrigin(currentUrl, baseUrl)) {
                                session.tryFinish(false, BridgeState.FAILED, "载体页面重定向至非允许来源: $currentUrl (期望: $baseUrl)")
                                return
                            }

                            if (httpStatusCode in 200..399) {
                                session.stage = InitStage.WAITING_HANDSHAKE
                                performHandshake(b, query, session.generation)
                            } else if (httpStatusCode == 403 || httpStatusCode == 429) {
                                session.tryFinish(false, BridgeState.CHALLENGE_REQUIRED, "载体页面返回 HTTP $httpStatusCode (触发安全验证或频率限制)")
                            } else if (httpStatusCode == 0) {
                                // In CEF, httpStatusCode=0 signifies load termination due to network error/abort, NOT a valid HTTP status from server!
                                if (session.retryTimer != null) {
                                    LinuxDoLog.info("LinuxDoJcefBridge: Ignoring onLoadEnd(0) because retry is scheduled (gen=${session.generation})")
                                    return
                                }
                                val err = session.lastLoadError ?: "网络连接未建立 (未收到 HTTP 响应)"
                                session.tryFinish(false, BridgeState.FAILED, "载体页面加载失败: $err")
                            } else {
                                session.tryFinish(false, BridgeState.FAILED, "载体页面加载返回 HTTP 错误 $httpStatusCode")
                            }
                        }
                    }

                    override fun onLoadError(
                        cefBrowser: CefBrowser?,
                        frame: CefFrame?,
                        errorCode: CefLoadHandler.ErrorCode?,
                        errorText: String?,
                        failedUrl: String?
                    ) {
                        if (session.generation != currentGeneration || session.completed.get()) return
                        if (frame?.isMain == true) {
                            if (errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED || failedUrl == "about:blank") return

                            session.lastLoadError = "$errorText ($errorCode)"
                            LinuxDoLog.warn("LinuxDoJcefBridge: Carrier page load error: $errorText ($errorCode) url=$failedUrl (gen=${session.generation})")

                            if (!session.reloadAttempted &&
                                (errorCode == CefLoadHandler.ErrorCode.ERR_CONNECTION_TIMED_OUT ||
                                        errorCode == CefLoadHandler.ErrorCode.ERR_NAME_NOT_RESOLVED ||
                                        errorCode == CefLoadHandler.ErrorCode.ERR_CONNECTION_RESET)
                            ) {
                                val remaining = session.remainingMillis()
                                if (remaining > 2000) {
                                    session.reloadAttempted = true
                                    LinuxDoLog.info("LinuxDoJcefBridge: Scheduling one-time retry in 1.5s (remaining budget: ${remaining}ms)")
                                    val timer = javax.swing.Timer(1500) {
                                        if (session.generation == currentGeneration && !session.completed.get() && !isDisposed()) {
                                             session.retryTimer = null
                                            cefBrowser?.reload()
                                        }
                                    }.apply { isRepeats = false; start() }
                                    session.retryTimer = timer
                                    return
                                }
                            }

                            val elapsedMs = (System.nanoTime() - session.startNano) / 1_000_000L
                            session.tryFinish(false, BridgeState.FAILED, "载体页面加载失败: $errorText (错误码: $errorCode, 耗时: ${elapsedMs}ms)")
                        }
                    }
                }, b.cefBrowser)

                // 6. Setup unified watchdog covering native creation, carrier loading, and handshake
                val watchdog = javax.swing.Timer((timeoutSeconds * 1000).toInt()) {
                    if (session.generation == currentGeneration && !session.completed.get()) {
                        val stageDesc = when (session.stage) {
                            InitStage.WAITING_NATIVE_BROWSER -> "原生浏览器创建超时 (${timeoutSeconds}s)"
                            InitStage.LOADING_CARRIER -> {
                                val hint = if (session.lastLoadError != null) " (底层错误: ${session.lastLoadError})" else ""
                                "载体页面网络加载超时 (${timeoutSeconds}s)$hint"
                            }
                            InitStage.WAITING_HANDSHAKE -> "载体页面已加载但 JSQuery 握手超时 (${timeoutSeconds}s)"
                            else -> "网桥初始化超时 (${timeoutSeconds}s)"
                        }
                        LinuxDoLog.warn("LinuxDoJcefBridge: Watchdog expired at stage ${session.stage}: $stageDesc (gen=${session.generation})")
                        session.tryFinish(false, BridgeState.FAILED, stageDesc)
                    }
                }.apply { isRepeats = false; start() }
                session.watchdogTimer = watchdog

                // 7. Explicitly trigger native browser creation
                LinuxDoLog.info("LinuxDoJcefBridge: Calling createImmediately() on OSR browser (gen=${session.generation})")
                b.createImmediately()

            } catch (t: Throwable) {
                LinuxDoLog.error("Failed to initialize JCEF bridge: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(t)}", t)
                session.tryFinish(false, BridgeState.FAILED, "初始化异常: ${t.message}")
            }
        }

        return newFuture
    }

    private fun performHandshake(
        b: JBCefBrowser,
        q: JBCefJSQuery,
        generation: Long
    ) {
        val js = """
            (function() {
                try {
                    var m = document.querySelector('meta[name="csrf-token"]');
                    var token = m ? m.content : '';
                    ${q.inject("JSON.stringify({ requestId: '__handshake__', body: token, success: true })")}
                } catch(e) {
                    console.error('Handshake execution error: ' + e);
                }
            })()
        """.trimIndent()
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: "", 0)
    }

    private fun handleJsMessage(
        jsonStr: String?,
        session: BridgeInitSession
    ) {
        if (jsonStr.isNullOrBlank()) return
        if (session.generation != currentGeneration || state !in setOf(BridgeState.INITIALIZING, BridgeState.READY)) {
            return
        }

        try {
            val response = gson.fromJson(jsonStr, BridgeResponse::class.java) ?: return

            // CSRF extraction only updates cached token, does NOT change readiness
            if (response.requestId == "__csrf__") {
                if (!response.body.isNullOrBlank()) {
                    response.body?.let { domCsrfCache.put(session.authVersion, it) }
                    LinuxDoLog.info("Captured DOM CSRF token (present=true)")
                }
                return
            }

            // Handshake message can transition to READY only if we are awaiting handshake
            if (response.requestId == "__handshake__") {
                if (session.completed.get()) return
                if (!response.body.isNullOrBlank()) {
                    response.body?.let { domCsrfCache.put(session.authVersion, it) }
                    LinuxDoLog.info("Captured DOM CSRF token via handshake (present=true)")
                }
                if (state == BridgeState.INITIALIZING && session.stage == InitStage.WAITING_HANDSHAKE) {
                    session.tryFinish(true, BridgeState.READY)
                    LinuxDoLog.info("LinuxDoJcefBridge: Handshake verified successfully, bridge is READY (gen=${session.generation})")
                }
                return
            }

            if (response.requestId.isNotBlank()) {
                val future = pendingRequests.remove(response.requestId)
                if (future != null) {
                    future.complete(response)
                } else {
                    LinuxDoLog.warn("No pending future found for JCEF response requestId=${response.requestId}")
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.warn("Failed to parse JCEF bridge response: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}")
        }
    }

    /**
     * Synchronize session cookies from OkHttp persistent cookie jar into Chromium's cookie manager
     */
    fun syncCookiesToJcef(b: JBCefBrowser? = browser) {
        if (b == null) return
        try {
            val cookies = LinuxDoHttpClient.cookieJar.loadForRequest(LoginCookieSupport.forumUrl)
                .filter(LoginCookieSupport::canRestoreToBrowser)
            val mgr = b.jbCefCookieManager
            for (cookie in cookies) {
                val jbCookie = LoginCookieSupport.toJcefCookie(cookie)
                mgr.setCookie("https://${cookie.domain}/", jbCookie)
            }
            LinuxDoLog.info("Synced ${cookies.size} cookies from OkHttp jar into JCEF CookieManager")
        } catch (e: Throwable) {
            LinuxDoLog.warn("Unable to sync cookies to JCEF: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}")
        }
    }

    /**
     * Execute an arbitrary HTTP request through the headless Chromium ECH bridge.
     * Uses strict monotonic deadline calculation without artificial budget extensions.
     */
    fun execute(request: BridgeRequest, timeoutSeconds: Long = 45): Result<BridgeResponse> {
        val started = System.nanoTime()
        NetworkTrace.event(request.requestId, "JCEF", "request_start", "url" to NetworkTrace.safeUrl(request.url), "method" to request.method)
        return runCatching {
            val initialToken = request.headers.entries.firstOrNull { it.key.equals("X-CSRF-Token", true) }?.value
            CsrfRecovery.execute(initialToken, request.sessionVersion,
                send = { token ->
                    val attempt = if (token == initialToken) request else request.copy(
                        requestId = UUID.randomUUID().toString(),
                        headers = request.headers.filterKeys { !it.equals("X-CSRF-Token", true) } + ("X-CSRF-Token" to token.orEmpty()))
                    executeTraced(attempt, timeoutSeconds).getOrThrow()
                },
                failure = { HttpFailure.classify(it.status, it.headers.orEmpty(), it.body.orEmpty()) },
                close = {},
                refresh = {
                    if (request.method in setOf("GET", "HEAD")) null else {
                        domCsrfCache.clear()
                        val csrf = executeTraced(BridgeRequest(url = "https://linux.do/session/csrf", sessionVersion = request.sessionVersion), timeoutSeconds).getOrThrow()
                        HttpFailure.classify(csrf.status, csrf.headers.orEmpty(), csrf.body.orEmpty())?.let { throw it }
                        gson.fromJson(csrf.body, com.google.gson.JsonObject::class.java)?.get("csrf")?.asString
                    }
                })
        }.also { result ->
            NetworkTrace.event(request.requestId, "JCEF", "request_end", "generation" to currentGeneration,
                "state" to state.name, "stage" to getInitStage().name, "status" to result.getOrNull()?.status,
                "success" to (result.getOrNull()?.success == true), "errorType" to result.exceptionOrNull()?.let(NetworkTrace::errorType),
                "elapsedMs" to (System.nanoTime() - started) / 1_000_000)
        }
    }

    private fun executeTraced(request: BridgeRequest, timeoutSeconds: Long): Result<BridgeResponse> {
        if (!isSupported()) {
            return Result.failure(IOException("当前运行环境不支持 JCEF Chromium 运行时"))
        }

        val startNano = System.nanoTime()
        val totalBudgetNano = timeoutSeconds * 1_000_000_000L

        try {
            val initTimeout = minOf(timeoutSeconds, 30L)
            val readyFuture = ensureReady(timeoutSeconds = initTimeout)
            NetworkTrace.event(request.requestId, "JCEF", "await_carrier", "generation" to currentGeneration, "stage" to getInitStage().name)
            val ready = readyFuture.get(initTimeout, TimeUnit.SECONDS)
            if (!ready || state != BridgeState.READY) {
                val err = lastFailureReason ?: "JCEF Chromium 网桥未就绪 (当前状态: $state, 阶段: ${getInitStage()})"
                return Result.failure(if (state == BridgeState.CHALLENGE_REQUIRED) CloudflareChallengeException() else IOException(err))
            }
        } catch (te: TimeoutException) {
            return Result.failure(SocketTimeoutException("JCEF Chromium 网桥启动超时 (${minOf(timeoutSeconds, 30L)}s)"))
        } catch (e: Throwable) {
            return Result.failure(IOException("JCEF Chromium 网桥启动失败: ${e.message}", e))
        }

        val b = browser
        val q = jsQuery
        if (b == null || q == null || isDisposed()) {
            return Result.failure(IOException("JCEF Chromium 浏览器实例已失效"))
        }

        val elapsedNano = System.nanoTime() - startNano
        val remainingNano = totalBudgetNano - elapsedNano
        if (remainingNano <= 0) {
            return Result.failure(SocketTimeoutException("JCEF Chromium 网桥请求超时 (预算已耗尽)"))
        }
        val remainingMillis = remainingNano / 1_000_000L

        val future = CompletableFuture<BridgeResponse>()
        NetworkTrace.event(request.requestId, "JCEF", "fetch_dispatch", "generation" to currentGeneration,
            "browserId" to b.cefBrowser.identifier, "url" to NetworkTrace.safeUrl(request.url))
        pendingRequests[request.requestId] = future

        val reqJson = gson.toJson(request)
        val encodedReq = try {
            URLEncoder.encode(reqJson, "UTF-8").replace("+", "%20")
        } catch (e: Throwable) {
            pendingRequests.remove(request.requestId)
            return Result.failure(e)
        }

        val reqIdFallback = request.requestId
        val jsCode = """
            (async function() {
                function sendBridgeResponse(payloadObj) {
                    const payloadStr = JSON.stringify(payloadObj);
                    let attempts = 0;
                    function doSend() {
                        try {
                            ${q.inject("payloadStr")}
                        } catch(e) {
                            if (attempts < 20) {
                                attempts++;
                                setTimeout(doSend, 150);
                            } else {
                                console.error('[JCEF Bridge] sendBridgeResponse failed after retries: ' + e);
                            }
                        }
                    }
                    doSend();
                }

                try {
                    const reqStr = decodeURIComponent('$encodedReq');
                    const req = JSON.parse(reqStr);

                    const controller = new AbortController();
                    window.__abortFetch_${reqIdFallback.replace("-", "_")} = () => controller.abort();

                    const options = {
                        method: req.method || 'GET',
                        headers: Object.assign({}, req.headers || {}),
                        credentials: 'include',
                        signal: controller.signal
                    };
                    if (req.body && req.method !== 'GET' && req.method !== 'HEAD') {
                        options.body = req.body;
                    }
                    if (req.isUpload && req.uploadBase64) {
                        const formData = new FormData();
                        formData.append('type', req.uploadType || 'composer');
                        formData.append('synchronous', 'true');
                        const binary = atob(req.uploadBase64);
                        const len = binary.length;
                        const array = new Uint8Array(len);
                        for (let i = 0; i < len; i++) {
                            array[i] = binary.charCodeAt(i);
                        }
                        const blob = new Blob([array], { type: req.uploadMimeType || 'image/png' });
                        formData.append('files[]', blob, req.uploadFileName || 'upload.png');
                        options.body = formData;
                        if (options.headers['Content-Type']) {
                            delete options.headers['Content-Type'];
                        }
                    }
                    if (req.method !== 'GET' && req.method !== 'HEAD' && !options.headers['X-CSRF-Token']) {
                        const meta = document.querySelector('meta[name="csrf-token"]');
                        if (meta && meta.content) {
                            options.headers['X-CSRF-Token'] = meta.content;
                        }
                    }

                    const res = await fetch(req.url, options);

                    const respHeaders = {};
                    res.headers.forEach((v, k) => { respHeaders[k] = v; });

                    let bodyText = '';
                    let binaryBase64 = null;
                    const contentType = res.headers.get('content-type') || '';
                    if (req.isBinary || contentType.startsWith('image/')) {
                        const limit = 24 * 1024 * 1024;
                        if (Number(res.headers.get('content-length')) > limit) throw new Error('Image exceeds 24 MB');
                        const reader = res.body.getReader();
                        const chunks = []; let total = 0;
                        while (true) {
                            const next = await reader.read();
                            if (next.done) break;
                            total += next.value.length;
                            if (total > limit) { await reader.cancel(); throw new Error('Image exceeds 24 MB'); }
                            chunks.push(next.value);
                        }
                        const bytes = new Uint8Array(total); let offset = 0;
                        chunks.forEach(part => { bytes.set(part, offset); offset += part.length; });
                        const blen = bytes.byteLength;
                        const chunk = 8192;
                        let binaryStr = '';
                        for (let i = 0; i < blen; i += chunk) {
                            binaryStr += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunk, blen)));
                        }
                        binaryBase64 = btoa(binaryStr);
                    } else {
                        bodyText = await res.text();
                    }

                    sendBridgeResponse({
                        requestId: req.requestId,
                        success: res.ok,
                        status: res.status,
                        statusText: res.statusText,
                        headers: respHeaders,
                        body: bodyText,
                        binaryBase64: binaryBase64
                    });
                } catch (err) {
                    console.error('[JCEF Bridge] Fetch caught error: ' + (err && err.message ? err.message : err));
                    sendBridgeResponse({
                        requestId: '$reqIdFallback',
                        success: false,
                        status: 0,
                        error: (err && err.message) ? err.message : String(err)
                    });
                } finally {
                    delete window.__abortFetch_${reqIdFallback.replace("-", "_")};
                }
            })();
        """.trimIndent()

        ApplicationManager.getApplication().invokeLater({
            try {
                SessionEpoch.requireCurrent(request.sessionVersion)
                val scriptUrl = b.cefBrowser.url.takeIf { !it.isNullOrBlank() } ?: "https://linux.do"
                b.cefBrowser.executeJavaScript(jsCode, scriptUrl, 0)
            } catch (t: Throwable) {
                pendingRequests.remove(request.requestId)
                future.completeExceptionally(t)
            }
        }, ModalityState.any())

        return try {
            val response = future.get(remainingMillis, TimeUnit.MILLISECONDS)
            if (response.requestId == "__csrf__") {
                response.body?.let { domCsrfCache.put(request.sessionVersion, it) }
            }
            Result.success(response)
        } catch (te: TimeoutException) {
            pendingRequests.remove(request.requestId)
            try {
                val abortFn = "window.__abortFetch_${reqIdFallback.replace("-", "_")}"
                b.cefBrowser.executeJavaScript("if ($abortFn) { $abortFn(); }", b.cefBrowser.url ?: "", 0)
            } catch (_: Throwable) {}
            Result.failure(SocketTimeoutException("JCEF Chromium 网桥请求超时 (${remainingMillis}ms): ${request.url}"))
        } catch (e: Throwable) {
            pendingRequests.remove(request.requestId)
            Result.failure(IOException("JCEF Chromium 网桥请求异常: ${e.message}", e))
        }
    }

    /**
     * Typed GET helper
     */
    inline fun <reified T> executeGet(url: String, headers: Map<String, String> = emptyMap(), traceId: String = NetworkTrace.newId()): Result<T> {
        val reqHeaders = HashMap(headers).apply {
            putIfAbsent("Accept", "application/json")
        }
        val req = BridgeRequest(requestId = traceId, url = url, method = "GET", headers = reqHeaders)
        val result = execute(req)
        return result.mapCatching { resp ->
            if (resp.status !in 200..299) {
                throw HttpStatusException(resp.status)
            }
            val body = resp.body ?: ""
            val type = object : com.google.gson.reflect.TypeToken<T>() {}.type
            gson.fromJson(body, type)
        }
    }

    /**
     * Typed POST JSON helper
     */
    inline fun <reified T> executePostJson(
        url: String,
        jsonBody: String,
        csrfToken: String? = null,
        headers: Map<String, String> = emptyMap(),
        expectedVersion: Long = SessionEpoch.current
    ): Result<T> {
        val reqHeaders = HashMap(headers).apply {
            putIfAbsent("Content-Type", "application/json; charset=utf-8")
            putIfAbsent("Accept", "application/json")
            if (!csrfToken.isNullOrBlank()) {
                put("X-CSRF-Token", csrfToken)
            }
        }
        val req = BridgeRequest(url = url, method = "POST", headers = reqHeaders, body = jsonBody, sessionVersion = expectedVersion)
        val result = execute(req)
        return result.mapCatching { resp ->
            if (resp.status !in 200..299) {
                throw HttpStatusException(resp.status)
            }
            val body = resp.body ?: ""
            val type = object : com.google.gson.reflect.TypeToken<T>() {}.type
            gson.fromJson(body, type)
        }
    }

    /**
     * Form POST/PUT helper
     */
    fun executeForm(
        url: String,
        method: String,
        formBody: String,
        csrfToken: String? = null,
        headers: Map<String, String> = emptyMap(),
        expectedVersion: Long = SessionEpoch.current
    ): Result<BridgeResponse> {
        val reqHeaders = HashMap(headers).apply {
            putIfAbsent("Content-Type", "application/x-www-form-urlencoded")
            putIfAbsent("Accept", "application/json")
            if (!csrfToken.isNullOrBlank()) {
                put("X-CSRF-Token", csrfToken)
            }
        }
        val req = BridgeRequest(url = url, method = method, headers = reqHeaders, body = formBody, sessionVersion = expectedVersion)
        val result = execute(req)
        return result.mapCatching { resp ->
            if (resp.status !in 200..299) {
                throw HttpStatusException(resp.status)
            }
            resp
        }
    }

    /**
     * Upload Image / File via Multipart FormData inside Chromium
     */
    fun uploadImage(
        url: String,
        bytes: ByteArray,
        fileName: String,
        mimeType: String = "image/png",
        csrfToken: String? = null,
        expectedVersion: Long = SessionEpoch.current
    ): Result<UploadResponse> {
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val reqHeaders = HashMap<String, String>().apply {
            put("Accept", "application/json")
            if (!csrfToken.isNullOrBlank()) {
                put("X-CSRF-Token", csrfToken)
            }
        }
        val req = BridgeRequest(
            url = url,
            method = "POST",
            headers = reqHeaders,
            isUpload = true,
            uploadFileName = fileName,
            uploadMimeType = mimeType,
            sessionVersion = expectedVersion,
            uploadBase64 = base64
        )
        val result = execute(req)
        return result.mapCatching { resp ->
            if (resp.status !in 200..299) {
                throw HttpStatusException(resp.status)
            }
            val body = resp.body ?: ""
            gson.fromJson(body, UploadResponse::class.java)
        }
    }

    /**
     * Download binary bytes (e.g. avatar or attachment image)
     */
    fun downloadBytes(url: String): Result<ByteArray> {
        val req = BridgeRequest(url = url, method = "GET", isBinary = true)
        val result = execute(req)
        return result.mapCatching { resp ->
            if (resp.status !in 200..299) {
                throw HttpStatusException(resp.status)
            }
            val b64 = resp.binaryBase64
            if (!b64.isNullOrBlank()) {
                Base64.getDecoder().decode(b64)
            } else if (!resp.body.isNullOrBlank()) {
                resp.body.toByteArray(Charsets.UTF_8)
            } else {
                ByteArray(0)
            }
        }
    }

    private fun disposeInternal() {
        currentSession?.tryFinish(false, BridgeState.DISPOSED, "网桥已重置或释放")
        currentSession = null

        try {
            jsQuery?.dispose()
            browser?.let { Disposer.dispose(it) }
        } catch (_: Throwable) {}
        browser = null
        jsQuery = null

        val pending = ArrayList(pendingRequests.values)
        pendingRequests.clear()
        for (f in pending) {
            f.completeExceptionally(IOException("JCEF Chromium 网桥已关闭或释放"))
        }
    }

    @Synchronized
    override fun dispose() {
        state = BridgeState.DISPOSED
        importedCookies = null
        lastFailureReason = "JCEF Chromium 网桥已主动销毁"
        disposeInternal()
    }
}
