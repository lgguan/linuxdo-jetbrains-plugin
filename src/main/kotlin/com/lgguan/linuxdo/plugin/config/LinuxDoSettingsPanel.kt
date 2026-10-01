package com.lgguan.linuxdo.plugin.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.net.DohDnsResolver
import com.lgguan.linuxdo.plugin.net.EngineMode
import com.lgguan.linuxdo.plugin.net.LinuxDoCefConfigManager
import com.lgguan.linuxdo.plugin.net.LinuxDoDiagnostics
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge
import com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig
import com.lgguan.linuxdo.plugin.net.ProxyPolicy
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.lgguan.linuxdo.plugin.ui.dialog.LoginAuthDialog
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class LinuxDoSettingsPanel : com.intellij.openapi.Disposable {
    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    @Volatile private var disposed = false

    val mainPanel: JPanel

    // 1. Camouflage Mode
    private val hideAvatarsCheckBox = JBCheckBox("隐藏用户头像 (移除所有头像图形，呈现纯净代码注释风格)", true)
    private val foldImagesCheckBox = JBCheckBox("折叠正文图片为文档占位符 (转为 [Figure: ...] 注释，点击展开或放大)", true)
    private val categoryNamespaceCheckBox = JBCheckBox("技术命名空间化版块分类 (例如将“开发调优”格式化为 dev.tuning)", true)
    private val autoJumpToLastReadFloorCheckBox = JBCheckBox("打开话题时自动跳转至上次阅读楼层 (自动记住历史进度并平滑定位)", true)
    private val autoReportTimingsCheckBox = JBCheckBox("自动向社区同步阅读进度与停留时长 (/topics/timings)", true)

    // 2. DNS-over-HTTPS (DoH)
    private val dohProviderComboBox = ComboBox(Constants.DohProvider.values())
    private val customDohUrlField = JBTextField()
    private val customBootstrapIpField = JBTextField()
    private val testDnsButton = JButton("测试 DNS 解析")
    private val testJcefButton = JButton("应用并测试插件连接")
    private val dohTestResultLabel = JBLabel("点击“测试 DNS 解析”或“应用并测试插件连接”验证通道")
    private val jcefStatusLabel = JBLabel("")
    private val networkCaptureCheckBox = JBCheckBox("下次启动采集网络诊断（一次性）")

    // 3. Network & Proxy Policy
    private val networkModeComboBox = ComboBox(LinuxDoSettingsState.NetworkMode.values()).apply {
        renderer = object : com.intellij.ui.SimpleListCellRenderer<LinuxDoSettingsState.NetworkMode>() {
            override fun customize(
                list: javax.swing.JList<out LinuxDoSettingsState.NetworkMode>,
                value: LinuxDoSettingsState.NetworkMode?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                text = value?.label ?: ""
            }
        }
    }
    private val proxyPolicyComboBox = ComboBox(ProxyPolicy.values()).apply {
        renderer = object : com.intellij.ui.SimpleListCellRenderer<ProxyPolicy>() {
            override fun customize(
                list: javax.swing.JList<out ProxyPolicy>,
                value: ProxyPolicy?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                text = value?.label ?: ""
            }
        }
    }
    private val timeoutField = JBTextField("15")
    private val userAgentField = JBTextField(Constants.DEFAULT_USER_AGENT)

    // 4. Notifications & Anti-429
    private val enableNotificationPollingCheckBox = JBCheckBox("启用后台通知轮询推送 (实时接收回复、提及与系统通知)", true)
    private val notificationActiveIntervalField = JBTextField("60")
    private val notificationInactiveIntervalField = JBTextField("300")

    // 5. Authentication & Cloudflare
    private val authStatusLabel = JBLabel("当前状态: 未登录")
    private val loginButton = JButton("🔑 打开登录 / Cloudflare 验证弹窗")
    private val logoutButton = JButton("🚪 退出登录 / 清除会话凭据")

    init {
        val formBuilder = FormBuilder.createFormBuilder()

        // 1. 开发者文档与代码伪装 (Camouflage Mode)
        formBuilder.addComponent(TitledSeparator("开发者文档与代码伪装 (Camouflage Mode)"))
        formBuilder.addComponent(hideAvatarsCheckBox)
        formBuilder.addComponent(foldImagesCheckBox)
        formBuilder.addComponent(categoryNamespaceCheckBox)
        formBuilder.addComponent(autoJumpToLastReadFloorCheckBox)
        formBuilder.addComponent(autoReportTimingsCheckBox)
        val camouflageTip = JBLabel("<html><small style='color:gray;'>* 开启代码伪装后，社区帖子将转换为规范 RFC 技术文档风格呈现，完美融入 IDE 编辑器与日常开发环境。</small></html>")
        formBuilder.addComponent(camouflageTip)

        // 2. 安全加密解析 (DNS-over-HTTPS)
        formBuilder.addComponent(TitledSeparator("安全加密解析 (DNS-over-HTTPS)"))
        formBuilder.addLabeledComponent("DoH 解析服务商:", dohProviderComboBox)
        formBuilder.addLabeledComponent("自定义 DoH 接口地址:", customDohUrlField)
        formBuilder.addLabeledComponent("自定义引导解析 IP (可选):", customBootstrapIpField)

        val dohTestPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        dohTestPanel.add(testDnsButton)
        dohTestPanel.add(testJcefButton)
        dohTestPanel.add(dohTestResultLabel)
        formBuilder.addComponent(dohTestPanel)
        formBuilder.addComponent(jcefStatusLabel)
        val dohTip = JBLabel("<html><small style='color:gray;'>* DoH 仅用于本插件的独立浏览器和网络请求。登录、正文和图片共用插件会话；不会修改 IDE 或其他浏览器的 DNS。修改后重新建立插件连接。</small></html>")
        formBuilder.addComponent(dohTip)

        // 3. 网络连接与代理策略 (Network & Proxy Policy)
        formBuilder.addComponent(TitledSeparator("网络连接与代理策略 (Network & Proxy Policy)"))
        formBuilder.addLabeledComponent("网络通道引擎:", networkModeComboBox)
        formBuilder.addLabeledComponent("期望代理策略:", proxyPolicyComboBox)
        formBuilder.addLabeledComponent("社区:", JBLabel("Linux Do · https://linux.do"))
        formBuilder.addLabeledComponent("请求超时时间 (秒):", timeoutField)
        formBuilder.addLabeledComponent("自定义 User-Agent:", userAgentField)
        val networkTip = JBLabel("<html><small style='color:gray;'>* <b>直连模式</b>：插件的 Java 请求、DoH 和独立浏览器均不使用代理。<br>* 使用 DoH/ECH 访问论坛时，可选择“强制 JCEF 网桥”。</small></html>")
        formBuilder.addComponent(networkTip)

        // 4. 安全通知与轮询防风控 (Notification & Anti-429)
        formBuilder.addComponent(TitledSeparator("通知与请求频率 (Notifications & Rate Limits)"))
        formBuilder.addComponent(enableNotificationPollingCheckBox)
        formBuilder.addLabeledComponent("前台活跃轮询基准间隔 (秒):", notificationActiveIntervalField)
        formBuilder.addLabeledComponent("未激活/后台时轮询基准间隔 (秒):", notificationInactiveIntervalField)
        val safetyTipLabel = JBLabel("<html><small style='color:gray;'>* 轮询间隔带 ±20% 随机浮动，未激活窗口自动降频；收到论坛频率限制 (HTTP 429) 后暂停请求，优先采用服务器指定的冷却时间，未指定时等待 15 分钟。</small></html>")
        formBuilder.addComponent(safetyTipLabel)

        // 5. 账号授权与 Cloudflare 验证 (Authentication & Session)
        formBuilder.addComponent(TitledSeparator("账号授权与 Cloudflare 验证 (Authentication & Session)"))
        val authActionPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        authActionPanel.add(authStatusLabel)
        authActionPanel.add(loginButton)
        authActionPanel.add(logoutButton)
        formBuilder.addComponent(authActionPanel)
        val authTip = JBLabel("<html><small style='color:gray;'>* 登录凭据通过系统安全钥匙串 (PasswordSafe) 加密持久化保存；支持通过独立弹窗完成 Cloudflare 人机安全验证。</small></html>")
        formBuilder.addComponent(authTip)

        // 6. 运行日志与系统诊断 (Diagnostics & Logs)
        formBuilder.addComponent(TitledSeparator("运行日志与系统诊断 (Diagnostics & Logs)"))
        val logPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        val viewLogsBtn = JButton("📋 查看运行日志").apply {
            addActionListener {
                com.lgguan.linuxdo.plugin.ui.dialog.LinuxDoLogDialog(null).show()
            }
        }
        val openLogFileBtn = JButton("📄 打开日志文件").apply {
            addActionListener {
                val file = com.lgguan.linuxdo.plugin.common.LinuxDoLog.getLogFile()
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                }
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(file)
                } else {
                    Messages.showInfoMessage("日志文件路径: ${file.absolutePath}", "日志文件")
                }
            }
        }
        val diagnosticsBtn = JButton("🔍 系统与网络诊断").apply {
            addActionListener {
                showDiagnosticsDialog()
            }
        }
        logPanel.add(viewLogsBtn)
        logPanel.add(openLogFileBtn)
        logPanel.add(diagnosticsBtn)
        formBuilder.addComponent(logPanel)
        formBuilder.addComponent(networkCaptureCheckBox)
        formBuilder.addComponent(JBLabel("<html><small>应用后冷启动 IDE，在 5 分钟内复现。NetLog 保存在本机，可能包含其他 JCEF 插件的连接元数据。</small></html>"))
        formBuilder.addComponent(JButton("查看 NetLog 采集状态与路径").apply {
            addActionListener { Messages.showInfoMessage(com.lgguan.linuxdo.plugin.net.NetworkCapture.status(), "网络诊断") }
        })
        val logPathLabel = JBLabel("日志文件路径: ${com.lgguan.linuxdo.plugin.common.LinuxDoLog.logFilePath}").apply {
            font = JBUI.Fonts.smallFont()
            foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        }
        formBuilder.addComponent(logPathLabel)

        mainPanel = JPanel(BorderLayout())
        mainPanel.border = JBUI.Borders.empty(10)
        mainPanel.add(formBuilder.panel, BorderLayout.NORTH)

        setupInteractions()
        updateAuthDisplay()
        updateJcefStatusDisplay()
    }

    private fun getCurrentConfigSnapshot(): LinuxDoNetworkConfig {
        val provider = dohProviderComboBox.selectedItem as? Constants.DohProvider ?: Constants.DohProvider.LINUXDO
        val customUrl = customDohUrlField.text.trim()
        val customBootstrap = customBootstrapIpField.text.trim()
        val engine = when ((networkModeComboBox.selectedItem as? LinuxDoSettingsState.NetworkMode)?.name) {
            LinuxDoSettingsState.NetworkMode.FORCE_JCEF.name -> EngineMode.FORCE_JCEF
            LinuxDoSettingsState.NetworkMode.JAVA_ONLY.name -> EngineMode.JAVA_ONLY
            else -> EngineMode.AUTO
        }
        val proxy = proxyPolicyComboBox.selectedItem as? ProxyPolicy ?: ProxyPolicy.DIRECT
        val timeout = timeoutField.text.toIntOrNull() ?: 15
        val baseUrl = Constants.DEFAULT_BASE_URL
        val ua = userAgentField.text.trim().ifBlank { Constants.DEFAULT_USER_AGENT }

        return LinuxDoNetworkConfig(
            baseUrl = baseUrl,
            dohProvider = provider,
            customDohUrl = customUrl,
            customBootstrapIp = customBootstrap,
            engineMode = engine,
            proxyPolicy = proxy,
            requestTimeoutSeconds = timeout,
            userAgent = ua
        )
    }

    private fun updateJcefStatusDisplay() {
        val active = com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.currentOrNull()?.config
        val running = LinuxDoCefConfigManager.isJcefStarted()
        val statusText = buildString {
            append("JCEF 状态: ")
            if (running) {
                append("插件独立实例运行中 | DoH: ")
            } else {
                append("插件独立实例尚未启动 | DoH: ")
            }
            if (active?.isDohEnabled == true) {
                append(com.lgguan.linuxdo.plugin.net.NetworkTrace.safeUrl(active.effectiveDohUrl))
            } else {
                append("未配置")
            }
        }
        jcefStatusLabel.text = "<html><small style='color:gray;'>$statusText</small></html>"
    }

    private fun setupInteractions() {
        // 1. Test DNS only (pure resolver query without saving/modifying browser state)
        testDnsButton.addActionListener {
            val config = getCurrentConfigSnapshot()
            dohTestResultLabel.text = "正在通过 ${config.dohProvider} 查询 linux.do..."
            testDnsButton.isEnabled = false

            backgroundTasks.submit {
                val (_, message) = DohDnsResolver.testDoH(config, "linux.do")
                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    testDnsButton.isEnabled = true
                    dohTestResultLabel.text = "[DNS] $message"
                    updateJcefStatusDisplay()
                }
            }
        }

        // The button explicitly applies the form, so the bridge tests exactly what is displayed.
        testJcefButton.addActionListener {
            if (!LinuxDoJcefBridge.isSupported()) {
                dohTestResultLabel.text = "[网桥] 当前环境不支持 JCEF 运行时"
                return@addActionListener
            }

            try { applyTo(LinuxDoSettingsState.getInstance()) }
            catch (e: com.intellij.openapi.options.ConfigurationException) {
                dohTestResultLabel.text = "<html>${e.messageHtml}</html>"
                return@addActionListener
            }
            val config = LinuxDoSettingsState.getInstance().toNetworkConfig()
            dohTestResultLabel.text = "正在通过 JCEF 网桥建立连接并访问 ${config.baseUrl}/site.json ..."
            testJcefButton.isEnabled = false

            backgroundTasks.submit {
                val start = System.currentTimeMillis()
                val req = LinuxDoJcefBridge.BridgeRequest(
                    url = "${config.baseUrl.removeSuffix("/")}/site.json",
                    method = "GET",
                    headers = mapOf("Accept" to "application/json")
                )
                val res = LinuxDoJcefBridge.execute(req, timeoutSeconds = 30)
                val elapsed = System.currentTimeMillis() - start

                ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                    testJcefButton.isEnabled = true
                    res.onSuccess { resp ->
                        val code = resp.status
                        if (code in 200..399) {
                            dohTestResultLabel.text = "[网桥连接成功] HTTP $code (${elapsed}ms) 页面及接口可正常访问"
                        } else {
                            dohTestResultLabel.text = "[网桥响应异常] HTTP $code (${elapsed}ms): ${resp.error ?: resp.statusText}"
                        }
                    }.onFailure { err ->
                        dohTestResultLabel.text = "[网桥连接失败] (${elapsed}ms): ${err.message}"
                    }
                    updateJcefStatusDisplay()
                }
            }
        }

        loginButton.addActionListener {
            val dialog = LoginAuthDialog(null) {
                updateAuthDisplay()
            }
            dialog.show()
            updateAuthDisplay()
        }

        logoutButton.addActionListener {
            LinuxDoAuthService.getInstance().logout()
            updateAuthDisplay()
            Messages.showInfoMessage(mainPanel, "已成功退出登录并清除本地保存的会话凭据。", "已退出登录")
        }
    }

    private fun showDiagnosticsDialog() {
        val config = LinuxDoSettingsState.getInstance().toNetworkConfig()
        Messages.showInfoMessage(
            mainPanel,
            "正在收集系统环境、JCEF 状态、DoH 解析与通道测试信息，请稍候...",
            "正在诊断"
        )
        backgroundTasks.submit {
            val report = LinuxDoDiagnostics.runFullDiagnostics(config)
            val summary = report.formatSanitizedSummary()
            ApplicationManager.getApplication().invokeLater {
                    if (disposed) return@invokeLater
                val area = com.intellij.ui.components.JBTextArea(summary).apply {
                    rows = 20
                    columns = 70
                    isEditable = false
                    font = com.intellij.util.ui.UIUtil.getFontWithFallback(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12))
                }
                val scroll = com.intellij.ui.components.JBScrollPane(area)
                val dialogPanel = JPanel(BorderLayout(0, 8)).apply {
                    add(scroll, BorderLayout.CENTER)
                    val copyBtn = JButton("复制诊断内容").apply {
                        addActionListener {
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                java.awt.datatransfer.StringSelection(summary), null
                            )
                            Messages.showInfoMessage("诊断信息已复制到剪贴板（已严格脱敏）", "复制成功")
                        }
                    }
                    add(copyBtn, BorderLayout.SOUTH)
                }

                com.intellij.openapi.ui.DialogBuilder().apply {
                    setTitle("🔍 Linux Do 插件系统与网络诊断")
                    setCenterPanel(dialogPanel)
                    addOkAction().setText("关闭")
                }.show()
            }
        }
    }

    fun updateAuthDisplay() {
        val user = LinuxDoAuthService.getInstance().currentUser
        if (user != null) {
            authStatusLabel.text = "当前状态: 已登录 @${user.username} (信任级别: ${user.trustLevel})"
            logoutButton.isEnabled = true
        } else if (LinuxDoHttpClient.cookieJar.hasValidSession()) {
            authStatusLabel.text = "当前状态: 已配置会话凭据 (检测有效性中...)"
            logoutButton.isEnabled = true
        } else {
            authStatusLabel.text = "当前状态: 未登录"
            logoutButton.isEnabled = false
        }
    }

    fun applyTo(state: LinuxDoSettingsState) {
        try { getCurrentConfigSnapshot().validateDoh() }
        catch (e: IllegalArgumentException) { throw com.intellij.openapi.options.ConfigurationException(e.message ?: "DoH 配置无效") }
        val oldNetworkKey = state.toNetworkConfig().runtimeKey()
        if (networkCaptureCheckBox.isSelected && state.networkDiagnosticToken.isBlank()) {
            state.networkDiagnosticArmedSession = com.lgguan.linuxdo.plugin.net.NetworkTrace.sessionId
        }
        state.networkDiagnosticToken = if (networkCaptureCheckBox.isSelected) {
            state.networkDiagnosticToken.ifBlank { java.util.UUID.randomUUID().toString() }
        } else ""
        state.baseUrl = Constants.DEFAULT_BASE_URL
        state.dohProvider = dohProviderComboBox.selectedItem as? Constants.DohProvider ?: Constants.DohProvider.LINUXDO
        state.customDohUrl = customDohUrlField.text.trim()
        state.customBootstrapIp = customBootstrapIpField.text.trim()

        state.hideAvatars = hideAvatarsCheckBox.isSelected
        state.foldImages = foldImagesCheckBox.isSelected
        state.categoryNamespaceFormat = categoryNamespaceCheckBox.isSelected
        state.autoJumpToLastReadFloor = autoJumpToLastReadFloorCheckBox.isSelected
        state.autoReportReadTimings = autoReportTimingsCheckBox.isSelected

        state.userAgent = userAgentField.text.trim()
        state.requestTimeoutSeconds = timeoutField.text.toIntOrNull() ?: 15
        state.networkMode = (networkModeComboBox.selectedItem as? LinuxDoSettingsState.NetworkMode)?.name ?: LinuxDoSettingsState.NetworkMode.AUTO.name
        state.proxyPolicy = (proxyPolicyComboBox.selectedItem as? ProxyPolicy)?.name ?: ProxyPolicy.DIRECT.name

        com.lgguan.linuxdo.plugin.api.DiscourseApiClient.resetSniBlockDetection()

        state.enableNotificationPolling = enableNotificationPollingCheckBox.isSelected
        state.notificationActiveIntervalSeconds = (notificationActiveIntervalField.text.toIntOrNull() ?: 60).coerceAtLeast(Constants.MIN_NOTIFICATION_INTERVAL_SECONDS)
        state.notificationInactiveIntervalSeconds = (notificationInactiveIntervalField.text.toIntOrNull() ?: 300).coerceAtLeast(120)

        // Reset bridge if base URL changed
        if (oldNetworkKey != state.toNetworkConfig().runtimeKey()) {
            LinuxDoJcefBridge.resetBridge()
            com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.currentOrNull()?.dispose()
        }

        LinuxDoHttpClient.rebuildClient()

        // Prepare config for JCEF startup
        val status = LinuxDoCefConfigManager.getConfigStatus(state.toNetworkConfig())
        if (status == LinuxDoCefConfigManager.CefConfigStatus.RECREATE_ON_NEXT_CONNECTION) {
            dohTestResultLabel.text = "配置已保存，下次连接将重建插件独立浏览器。"
        }

        updateJcefStatusDisplay()
        state.fireSettingsChanged()
    }

    fun resetFrom(state: LinuxDoSettingsState) {
        networkCaptureCheckBox.isSelected = state.networkDiagnosticToken.isNotBlank()
        dohProviderComboBox.selectedItem = state.dohProvider
        customDohUrlField.text = state.customDohUrl
        customBootstrapIpField.text = state.customBootstrapIp

        hideAvatarsCheckBox.isSelected = state.hideAvatars
        foldImagesCheckBox.isSelected = state.foldImages
        categoryNamespaceCheckBox.isSelected = state.categoryNamespaceFormat
        autoJumpToLastReadFloorCheckBox.isSelected = state.autoJumpToLastReadFloor
        autoReportTimingsCheckBox.isSelected = state.autoReportReadTimings

        userAgentField.text = state.userAgent
        timeoutField.text = state.requestTimeoutSeconds.toString()
        networkModeComboBox.selectedItem = try {
            LinuxDoSettingsState.NetworkMode.valueOf(state.networkMode)
        } catch (_: Throwable) {
            LinuxDoSettingsState.NetworkMode.AUTO
        }
        proxyPolicyComboBox.selectedItem = try {
            ProxyPolicy.valueOf(state.proxyPolicy)
        } catch (_: Throwable) {
            ProxyPolicy.DIRECT
        }

        enableNotificationPollingCheckBox.isSelected = state.enableNotificationPolling
        notificationActiveIntervalField.text = state.notificationActiveIntervalSeconds.toString()
        notificationInactiveIntervalField.text = state.notificationInactiveIntervalSeconds.toString()

        updateAuthDisplay()
        updateJcefStatusDisplay()
    }

    fun isModified(state: LinuxDoSettingsState): Boolean {
        val selectedMode = (networkModeComboBox.selectedItem as? LinuxDoSettingsState.NetworkMode)?.name ?: LinuxDoSettingsState.NetworkMode.AUTO.name
        val selectedProxy = (proxyPolicyComboBox.selectedItem as? ProxyPolicy)?.name ?: ProxyPolicy.DIRECT.name

        return networkCaptureCheckBox.isSelected != state.networkDiagnosticToken.isNotBlank() ||
                dohProviderComboBox.selectedItem != state.dohProvider ||
                customDohUrlField.text.trim() != state.customDohUrl ||
                customBootstrapIpField.text.trim() != state.customBootstrapIp ||
                hideAvatarsCheckBox.isSelected != state.hideAvatars ||
                foldImagesCheckBox.isSelected != state.foldImages ||
                categoryNamespaceCheckBox.isSelected != state.categoryNamespaceFormat ||
                autoJumpToLastReadFloorCheckBox.isSelected != state.autoJumpToLastReadFloor ||
                autoReportTimingsCheckBox.isSelected != state.autoReportReadTimings ||
                userAgentField.text.trim() != state.userAgent ||
                timeoutField.text.trim() != state.requestTimeoutSeconds.toString() ||
                selectedMode != state.networkMode ||
                selectedProxy != state.proxyPolicy ||
                enableNotificationPollingCheckBox.isSelected != state.enableNotificationPolling ||
                notificationActiveIntervalField.text.trim() != state.notificationActiveIntervalSeconds.toString() ||
                notificationInactiveIntervalField.text.trim() != state.notificationInactiveIntervalSeconds.toString()
    }
    override fun dispose() {
        backgroundTasks.dispose()
        disposed = true
    }

}
