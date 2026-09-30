package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.common.invokeLoginUiLater
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.UserInfo
import com.lgguan.linuxdo.plugin.net.CloudflareBypassService
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.lgguan.linuxdo.plugin.net.LoginCookieSupport
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser as JBCefBrowser
import com.lgguan.linuxdo.plugin.net.LinuxDoJSQuery as JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

class LoginAuthDialog(
    private val project: Project? = null,
    private val onSuccessfulAuth: (() -> Unit)? = null
) : DialogWrapper(project, true) {
    private val gson = Gson()
    private var browser: JBCefBrowser? = null
    private var uaQuery: JBCefJSQuery? = null
    private var userQuery: JBCefJSQuery? = null
    @Volatile
    private var browserExtractedUser: UserInfo? = null
    private val tabs = JBTabbedPane()
    private val manualCookieArea = JBTextArea(10, 50)
    private val liveStatusLabel = JBLabel("请在内置浏览器完成登录或人机验证，或手动粘贴 Cookie")
    private val guestButton = JButton("以游客身份浏览").apply {
        toolTipText = "仅通过 Cloudflare 验证，无需登录账号即可直接浏览公开话题"
        addActionListener { enterAsGuest() }
    }
    private var pollTimer: Timer? = null
    private var retryTimer: Timer? = null
    private var reloadAttempted = false
    private var closed = false
    private var saving = false
    private var syncing = false
    private val syncCallbacks = mutableListOf<() -> Unit>()

    companion object {
        private val EXTRACT_USER_JS = """
            (function() {
                try {
                    var u = null;
                    if (window.Discourse && window.Discourse.User) {
                        u = window.Discourse.User.current();
                    }
                    if (!u) {
                        var el = document.getElementById('data-preloaded');
                        if (el && el.dataset && el.dataset.preloaded) {
                            var data = JSON.parse(el.dataset.preloaded);
                            u = data.currentUser;
                        }
                    }
                    if (u && u.username) {
                        return JSON.stringify({
                            id: u.id || 0,
                            username: u.username,
                            name: u.name || u.username,
                            avatar_template: u.avatar_template || '',
                            trust_level: (typeof u.trust_level === 'number') ? u.trust_level : 1
                        });
                    }
                    /* var userLink = document.querySelector('#current-user a, .current-user a, a[href*="/u/"]');
                    if (userLink) {
                        var href = userLink.getAttribute('href') || '';
                        var match = href.match(/\/u\/([^\/\?#]+)/);
                        if (match && match[1]) {
                            var uname = decodeURIComponent(match[1]);
                            var img = userLink.querySelector('img');
                            var avatar = img ? (img.getAttribute('src') || '') : '';
                            return JSON.stringify({
                                id: 0,
                                username: uname,
                                name: uname,
                                avatar_template: avatar,
                                trust_level: 1
                            });
                        }
                    }
                    */
                } catch(e) {}
                return '';
            })()
        """.trimIndent()
    }

    init {
        title = "Authentication / Connect to Remote (Linux Do)"
        setOKButtonText("保存并进入论坛")
        setCancelButtonText("取消")
        init()
        pollTimer = Timer(1500) {
            if (!closed && !saving && !isManualTab()) syncCookies()
        }.apply { start() }
    }

    private fun isManualTab() = tabs.getTitleAt(tabs.selectedIndex) == "手动粘贴 Cookie"

    override fun createCenterPanel(): JComponent {
        tabs.preferredSize = Dimension(1020, 750)
        if (com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.isSupported()) {
            tabs.addTab("内置浏览器登录 / 验证", JBLabel("正在启动登录浏览器..."))
            com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.prepare { prepared ->
                if (closed) return@prepare
                if (prepared.isFailure) {
                    tabs.setComponentAt(0, JBLabel("浏览器启动失败，可切换至手动粘贴 Cookie"))
                    return@prepare
                }
            val web = JBCefBrowser(prepared.getOrThrow())
            browser = web
            com.lgguan.linuxdo.plugin.net.JcefNetworkTrace.install(web, "login-${com.lgguan.linuxdo.plugin.net.NetworkTrace.newId()}")
            Disposer.register(disposable, web)

            val uQuery = JBCefJSQuery.create(web)
            uaQuery = uQuery
            Disposer.register(disposable, uQuery)
            uQuery.addHandler { userAgent ->
                if (!closed && userAgent.startsWith("Mozilla/") && userAgent.length < 1024 &&
                    !userAgent.contains('\r') && !userAgent.contains('\n')) {
                    LinuxDoSettingsState.getInstance().userAgent = userAgent
                    LinuxDoHttpClient.rebuildClient()
                }
                null
            }

            val usrQuery = JBCefJSQuery.create(web)
            userQuery = usrQuery
            Disposer.register(disposable, usrQuery)
            usrQuery.addHandler { json ->
                if (!closed && !json.isNullOrBlank()) {
                    try {
                        val user = gson.fromJson(json, UserInfo::class.java)
                        if (user != null && user.username.isNotBlank()) {
                            browserExtractedUser = user
                            LinuxDoLog.info("Detected authenticated browser user (present=true)")
                            invokeLoginUiLater {
                                if (!closed && !saving && !isManualTab()) {
                                    liveStatusLabel.text = "已检测到登录账号 @${user.username}，点击「保存并进入论坛」"
                                }
                            }
                        }
                    } catch (e: Exception) {
                        LinuxDoLog.warn("Failed to parse user from browser DOM: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}")
                    }
                }
                null
            }

            web.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true && LoginCookieSupport.isForumUrl(frame.url)) {
                        b?.executeJavaScript(uQuery.inject("navigator.userAgent"), frame.url, 0)
                        b?.executeJavaScript(usrQuery.inject(EXTRACT_USER_JS), frame.url, 0)
                        invokeLoginUiLater {
                            if (!closed && !saving && !isManualTab()) syncCookies()
                        }
                    }
                }

                override fun onLoadError(
                    cefBrowser: CefBrowser?,
                    frame: CefFrame?,
                    errorCode: org.cef.handler.CefLoadHandler.ErrorCode?,
                    errorText: String?,
                    failedUrl: String?
                ) {
                    if (frame?.isMain == true) {
                        // Ignore ERR_ABORTED (-3) as it is triggered normally during page redirects and Cloudflare verification
                        if (errorCode == org.cef.handler.CefLoadHandler.ErrorCode.ERR_ABORTED) {
                            return
                        }
                        LinuxDoLog.warn("LoginAuthDialog: failed to load $failedUrl: $errorText ($errorCode)")
                        if (!reloadAttempted && (errorCode == org.cef.handler.CefLoadHandler.ErrorCode.ERR_CONNECTION_TIMED_OUT ||
                            errorCode == org.cef.handler.CefLoadHandler.ErrorCode.ERR_NAME_NOT_RESOLVED)) {
                            reloadAttempted = true
                            invokeLoginUiLater {
                                if (!closed && !saving && !isManualTab()) {
                                    liveStatusLabel.text = "<html>浏览器连接失败 ($errorCode)，正在重试一次。</html>"
                                }
                            }
                            retryTimer = Timer(2000) {
                                (it.source as? Timer)?.stop()
                                if (!closed && !saving && !isManualTab()) {
                                    cefBrowser?.reload()
                                }
                            }.apply { isRepeats = false; start() }
                            return
                        }
                        invokeLoginUiLater {
                            if (!closed && !saving && !isManualTab()) {
                                liveStatusLabel.text = "<html>插件独立浏览器连接失败 ($errorCode)，请检查本插件的 DoH 地址和引导 IP。</html>"
                            }
                        }
                    }
                }
            }, web.cefBrowser)

            val toolbar = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0))
            toolbar.add(JButton("刷新页面").apply {
                addActionListener { if (!saving) { retryTimer?.stop(); reloadAttempted = false; web.cefBrowser.reload() } }
            })
            toolbar.add(JButton("退出当前网页登录 / 切换账号").apply {
                addActionListener {
                    if (!saving && !syncing) {
                        LinuxDoAuthService.getInstance().logout(web)
                        browserExtractedUser = null
                        web.loadURL("https://linux.do/login")
                        liveStatusLabel.text = "已清除登录状态，请重新登录"
                    }
                }
            })
            val panel = JPanel(BorderLayout())
            panel.add(toolbar, BorderLayout.NORTH)
            panel.add(web.component, BorderLayout.CENTER)
            tabs.setComponentAt(0, panel)
            web.loadURL("https://linux.do/login")
                    }
        }

        val manualPanel = JPanel(BorderLayout(0, 10))
        manualPanel.border = JBUI.Borders.empty(16)
        manualPanel.add(JBLabel(
            "<html><b>从已登录的 Chrome / Edge 复制 Cookie：</b><br>" +
                "F12 → Network / 网络 → 刷新页面 → 选择 linux.do 请求 → Request Headers → Cookie。<br>" +
                "请复制完整 Cookie，包含 <code>_t</code>；公开话题也可仅包含 <code>cf_clearance</code> 进行免密浏览。<br>" +
                "不要使用 document.cookie，它无法读取 HttpOnly 登录凭证。</html>"
        ), BorderLayout.NORTH)
        manualCookieArea.lineWrap = true
        manualCookieArea.wrapStyleWord = true
        manualCookieArea.emptyText.text = "_t=...; _forum_session=...; cf_clearance=..."
        manualPanel.add(manualCookieArea, BorderLayout.CENTER)
        tabs.addTab("手动粘贴 Cookie", manualPanel)
        return tabs
    }

    override fun createSouthPanel(): JComponent {
        val panel = JPanel(BorderLayout(12, 0))
        panel.border = JBUI.Borders.empty(8, 14)
        panel.add(liveStatusLabel, BorderLayout.CENTER)
        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        actions.add(JButton("立即同步 Cookie").apply {
            isEnabled = browser != null
            addActionListener {
                if (!saving && !isManualTab()) syncCookies()
            }
        })
        actions.add(guestButton)
        actions.add(createJButtonForAction(okAction))
        actions.add(createJButtonForAction(cancelAction))
        panel.add(actions, BorderLayout.EAST)
        return panel
    }

    private fun syncCookies(afterSync: (() -> Unit)? = null) {
        if (closed) return
        afterSync?.let { syncCallbacks.add(it) }
        if (syncing) return
        syncing = true

        // Also request user extraction from browser DOM
        browser?.let { web ->
            userQuery?.let { uq ->
                web.cefBrowser.executeJavaScript(uq.inject(EXTRACT_USER_JS), web.cefBrowser.url ?: "", 0)
            }
        }

        CloudflareBypassService.syncCookiesFromJcef(browser) { count ->
            syncing = false
            if (closed) {
                syncCallbacks.clear()
                return@syncCookiesFromJcef
            }
            if (!saving && !isManualTab()) {
                val jar = LinuxDoHttpClient.cookieJar
                val hasSession = jar.hasValidSession()
                val hasCf = jar.getCookie(Constants.COOKIE_CF_CLEARANCE) != null
                liveStatusLabel.text = when {
                    browserExtractedUser != null ->
                        "已识别登录用户 @${browserExtractedUser?.username}，点击「保存并进入论坛」"
                    hasSession ->
                        "已捕获登录凭证 _t，点击「保存并进入论坛」"
                    hasCf ->
                        "Cloudflare 验证已通过！可点击「以游客身份浏览」或继续登录"
                    count > 0 ->
                        "已捕获 $count 个 Cookie，请继续登录或通过验证"
                    else ->
                        "尚未获取 Cookie，请在上方完成页面加载或登录"
                }
            }
            val callbacks = syncCallbacks.toList()
            syncCallbacks.clear()
            callbacks.forEach { it() }
        }
    }

    private fun enterAsGuest() {
        val jar = LinuxDoHttpClient.cookieJar
        val hasCf = jar.getCookie(Constants.COOKIE_CF_CLEARANCE) != null
        if (!hasCf) {
            liveStatusLabel.text = "正在同步 Cloudflare 验证凭证..."
            syncCookies {
                if (closed) return@syncCookies
                if (LinuxDoHttpClient.cookieJar.getCookie(Constants.COOKIE_CF_CLEARANCE) != null) {
                    finishAndClose()
                } else {
                    setErrorText("尚未检测到 Cloudflare 验证凭证，请在页面完成人机验证后再试。")
                }
            }
        } else {
            finishAndClose()
        }
    }

    override fun doOKAction() {
        if (saving) return
        var importedCookies: List<okhttp3.Cookie>? = null
        if (isManualTab()) {
            val cookies = LoginCookieSupport.parseHeader(manualCookieArea.text)
            val hasToken = cookies.any { it.name == Constants.COOKIE_TOKEN && it.value.isNotBlank() }
            val hasCf = cookies.any { it.name == Constants.COOKIE_CF_CLEARANCE && it.value.isNotBlank() }
            if (!hasToken && !hasCf) {
                setErrorText("请粘贴包含 _t 的登录 Cookie，或包含 cf_clearance 的验证凭证。")
                return
            }
            // Do not mix credentials from a previous account/browser.
            LinuxDoAuthService.getInstance().replaceCredentials(cookies)
            importedCookies = cookies
        } else {
            LinuxDoAuthService.getInstance().credentialsPending()
        }
        saving = true
        isOKActionEnabled = false
        setErrorText(null)
        liveStatusLabel.text = "正在同步凭证并进入论坛..."
        if (isManualTab()) {
            val web = browser
            val cookies = importedCookies
            if (web != null && cookies != null) {
                val version = LinuxDoAuthService.getInstance().sessionVersion
                web.replaceSessionCookies(cookies, version).whenComplete { _, error ->
                    invokeLoginUiLater {
                        if (closed) return@invokeLoginUiLater
                        if (error != null) showLoginFailure("Cookie 已保存，但浏览器同步失败，请重试。")
                        else {
                            com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.cookiesImportedToBrowser(version)
                            verifyLogin()
                        }
                    }
                }
            } else verifyLogin()
        } else {
            syncCookies {
                // Request DOM user extraction once more and wait briefly
                browser?.let { web ->
                    userQuery?.let { uq ->
                        web.cefBrowser.executeJavaScript(uq.inject(EXTRACT_USER_JS), web.cefBrowser.url ?: "", 0)
                    }
                }
                Timer(300) {
                    (it.source as? Timer)?.stop()
                    if (!closed) verifyLogin()
                }.apply { isRepeats = false; start() }
            }
        }
    }

    private fun verifyLogin() {
        val jar = LinuxDoHttpClient.cookieJar
        val hasSession = jar.hasValidSession()
        val hasCf = jar.getCookie(Constants.COOKIE_CF_CLEARANCE) != null

        if (!hasSession && !hasCf) {
            showLoginFailure("尚未捕获登录 Cookie 或 Cloudflare 凭证，请先完成页面验证。")
            return
        }

        // 2. If we have _t token
        if (hasSession) {
            liveStatusLabel.text = "正在确认账号..."
            LinuxDoAuthService.getInstance().refreshCurrentUser(force = true) { loggedIn ->
                if (closed) return@refreshCurrentUser
                if (loggedIn) {
                    finishAndClose()
                } else {
                    showLoginFailure(loginFailureMessage(LinuxDoAuthService.getInstance().lastVerificationFailure))
                }
            }
            return
        }

        // 3. Unauthenticated guest with cf_clearance
        liveStatusLabel.text = "已完成 Cloudflare 验证，正在进入论坛..."
        finishAndClose()
    }

    private fun finishAndClose() {
        onSuccessfulAuth?.invoke()
        close(OK_EXIT_CODE)
    }

    private fun showLoginFailure(message: String) {
        saving = false
        isOKActionEnabled = true
        liveStatusLabel.text = "登录未确认，请重试"
        setErrorText(message)
    }

    private fun loginFailureMessage(error: Throwable?): String {
        val reason = when (error) {
            is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException -> "请切换到内置浏览器完成人机验证后重试"
            is com.lgguan.linuxdo.plugin.net.RateLimitException -> "请求过于频繁，请在 ${error.retryAfterSeconds} 秒后重试"
            is com.lgguan.linuxdo.plugin.net.HttpStatusException -> when (error.status) {
                401, 404 -> "服务器未认可该登录 Cookie，请从已登录的浏览器重新复制完整 Cookie（包含 _t）"
                else -> "账号验证返回 HTTP ${error.status}，请稍后重试"
            }
            is java.net.SocketTimeoutException -> "账号验证超时，请检查网络后重试"
            else -> "账号验证未成功，请重试或通过内置浏览器登录"
        }
        return "Cookie 已保存，但登录尚未确认。$reason。"
    }

    override fun dispose() {
        closed = true
        pollTimer?.stop()
        retryTimer?.stop()
        syncCallbacks.clear()
        manualCookieArea.text = ""
        super.dispose()
    }
}
