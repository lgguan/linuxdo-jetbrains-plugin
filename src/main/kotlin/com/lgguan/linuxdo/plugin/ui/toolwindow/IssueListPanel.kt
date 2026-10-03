package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsConfigurable
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.lgguan.linuxdo.plugin.service.LinuxDoNotificationService
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.NamespaceFormatter
import com.lgguan.linuxdo.plugin.ui.dialog.LoginAuthDialog
import com.lgguan.linuxdo.plugin.ui.dialog.NotificationListPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants


class IssueListPanel(
    private val project: Project,
    private val onTopicSelected: (Topic) -> Unit,
    private val onBossKeyTriggered: () -> Unit
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private var listTask: java.util.concurrent.Future<*>? = null
    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()
    @Volatile private var disposed = false
    private var observedAuthSession = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
    private var observedAuthUser = LinuxDoAuthService.getInstance().currentUser?.id
    private val authRefreshTimer = Timer(120) {
        if (!disposed && !project.isDisposed && LinuxDoAuthService.getInstance().status != LinuxDoAuthService.Status.CREDENTIALS_PENDING) loadInitialData()
    }.apply { isRepeats = false }

    private val topicListModel = DefaultListModel<Topic>()
    private val topicRenderer = TopicCardCellRenderer()
    private val topicList = object : JBList<Topic>(topicListModel) {
        override fun getScrollableTracksViewportWidth() = true
        override fun getToolTipText(event: MouseEvent): String? {
            val index = locationToIndex(event.point)
            if (index < 0 || getCellBounds(index, index)?.contains(event.point) != true) return null
            val topic = topicListModel[index]
            return "${topic.title} — ${topic.lastPostedAt ?: topic.bumpedAt ?: topic.createdAt ?: "时间未知"}"
        }
    }
    private val listScrollPane = JBScrollPane(topicList)
    val personalContentPanel = PersonalContentPanel(project)
    private val contentViews = JPanel(CardLayout())
    private val forumContent = JPanel(BorderLayout())
    private val forumControls = JPanel()
    var personalView = false
        private set
    fun selectPersonalView(value: Boolean) {
        personalView = value
        forumControls.isVisible = !value
        (contentViews.layout as CardLayout).show(contentViews, if(value) "MY" else "FORUM")
        personalContentPanel.setActive(value)
        revalidate(); repaint()
    }
    private var displayedCondition: String? = null

    private val categoryComboBox = ComboBox<CategoryItem>()
    private val filterComboBox = ComboBox(Constants.TopicFilter.values())
    private val tagSelector = com.lgguan.linuxdo.plugin.ui.dialog.BrowseTagSelector(listenerLifetime,
        com.lgguan.linuxdo.plugin.ui.dialog.TagSelectionField.Mode.BROWSE) {
        if (!isUpdatingDropdown) { activeSearchQuery = null; refreshList() }
    }
    private val searchField = JBTextField(10)

    // Top outside quick action buttons
    private val loginButton = JButton("登录").apply {
        toolTipText = "登录账号或通过 Cloudflare 人机验证"
        icon = AllIcons.General.Web
        margin = JBUI.insets(2, 6)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val userLabel = JButton().apply {
        toolTipText = "当前登录用户 (点击重新认证)"
        margin = JBUI.insets(2, 6)
        font = font.deriveFont(Font.PLAIN, 12f)
        isVisible = false
    }
    private val notificationButton = JButton(AllIcons.Toolwindows.Notifications).apply {
        toolTipText = "Linux Do 通知 - 点击查看"
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        margin = JBUI.insets(1)
        isFocusable = false
        isVisible = false
        addActionListener {
            NotificationListPanel.showAsPopup(project, this)
        }
    }
    private val logoutButton = JButton(AllIcons.Actions.Exit).apply {
        toolTipText = "退出当前登录并清除 Cookie"
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        margin = JBUI.insets(1)
        isVisible = false
    }

    private val searchBtn = JButton(AllIcons.Actions.Search).apply {
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        isFocusable = false
        margin = JBUI.insets(1)
        toolTipText = "搜索话题"
        addActionListener { executeSearch() }
    }
    private val advSearchBtn = JButton(AllIcons.General.Filter).apply {
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        isFocusable = false
        margin = JBUI.insets(1)
        toolTipText = "高级搜索 (按标题/标签/作者/时间/排序精准检索)"
        addActionListener {
            val selectedCatId = (categoryComboBox.selectedItem as? CategoryItem)?.id
            val dialog = com.lgguan.linuxdo.plugin.ui.dialog.AdvancedSearchDialog(
                project = project,
                initialQuery = activeSearchQuery ?: searchField.text.trim(),
                initialCategoryId = selectedCatId,
                initialTag = tagSelector.selection().firstOrNull(),
                onSearch = { fullQuery ->
                    search(fullQuery, inheritList = false)
                }
            )
            dialog.show()
        }
    }
    private val settingsBtn = JButton(AllIcons.General.GearPlain).apply {
        toolTipText = "打开插件设置 (DoH / 网络 / 伪装等)"
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        isFocusable = false
        margin = JBUI.insets(1)
    }
    private val bossBtn = JButton(AllIcons.Actions.ToggleVisibility).apply {
        toolTipText = "隐藏话题和面板 / 老板键 (Alt+Shift+H 再次按下恢复)"
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        isFocusable = false
        margin = JBUI.insets(1)
    }
    private val logBtn = JButton(AllIcons.Debugger.Console).apply {
        preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        isFocusable = false
        margin = JBUI.insets(1)
        toolTipText = "查看插件运行日志 (View Logs)"
        addActionListener {
            com.lgguan.linuxdo.plugin.ui.dialog.LinuxDoLogDialog(project).show()
        }
    }

    private val loadMoreButton = JButton("加载更多话题")
    private val loadingProgress = JProgressBar().apply {
        preferredSize = Dimension(JBUI.scale(48), JBUI.scale(6))
        isVisible = false
    }
    private val loadStatusLabel = JBLabel()
    private val retryButton = JButton("重试").apply {
        margin = JBUI.insets(1, 6)
        isVisible = false
        addActionListener { retryRequest?.invoke() }
    }
    private val loadStatusPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.empty(6, 8)
        add(loadingProgress, BorderLayout.WEST)
        add(loadStatusLabel, BorderLayout.CENTER)
        add(retryButton, BorderLayout.EAST)
        isVisible = false
    }
    private val bottomPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4)
        isVisible = false
    }

    // Empty state container (shown when list is empty, loading failed, or Cloudflare blocked)
    private val centerContainer = JPanel(CardLayout())
    private val emptyStatePanel = JPanel(GridBagLayout())
    private val emptyTipIcon = JBLabel(AllIcons.General.Information)
    private val emptyTipTitle = JBLabel("暂未加载到话题", SwingConstants.CENTER).apply {
        font = JBUI.Fonts.label().deriveFont(Font.BOLD, 14f)
    }
    private val emptyTipDesc = JTextPane().apply {
        isEditable = false
        isOpaque = false
        isFocusable = false
        border = JBUI.Borders.empty()
        font = JBUI.Fonts.label().deriveFont(12f)
        foreground = JBColor.GRAY
    }
    private val emptyActionBtn = JButton("重试刷新").apply {
        font = font.deriveFont(Font.BOLD)
        addActionListener { refreshList() }
    }
    private val emptySecondaryBtn = JButton("登录账号 / 切换网络").apply {
        addActionListener { openLoginDialog() }
    }


    private var currentPage = 0
    private var hasMorePages = true
    private var isLoading = false
    private var isUpdatingDropdown = false
    private var requestGeneration = 0L
    private var activeSearchQuery: String? = null
    private var retryRequest: (() -> Unit)? = null
    private var categoryRequestGeneration = 0L
    private var categoriesLoading = false
    private var categoryLoadFailed = false

    data class CategoryItem(val id: Int?, val name: String, val slug: String?, val parentSlug: String? = null) {
        override fun toString(): String = name
    }

    companion object {
        val CONTROL_HEIGHT: Int = JBUI.scale(28)

        fun formatErrorDisplay(raw: String?): String {
            if (raw.isNullOrBlank()) {
                return "网络请求暂未成功。\n公开话题无需登录，请检查网络设置或稍后重试。"
            }
            if (raw.contains("Cloudflare", ignoreCase = true) || raw.contains("cf-chl", ignoreCase = true)) {
                return "论坛开启了安全防护。\n无需登录账号，仅需在验证窗口完成人机验证即可浏览公开话题。"
            }
            if (raw.contains("429") || raw.contains("Too Many Requests", ignoreCase = true)) {
                return "已触发论坛请求频率限制 (HTTP 429)。\n请等待冷却结束后再重试。"
            }
            if (raw.contains("404") || raw.contains("not_found", ignoreCase = true)) {
                return "所选分类或请求的内容未找到 (HTTP 404)。\n建议切换至全部版块，或点击“重试刷新”重新加载分类列表。"
            }
            if (raw.contains("403") || raw.contains("Forbidden", ignoreCase = true)) {
                return "访问受限 (HTTP 403)。当前分类可能需要更高信任等级或登录后才可查看。"
            }
            if (raw.contains("SocketTimeoutException", ignoreCase = true) || raw.contains("timeout", ignoreCase = true)) {
                return "网络连接超时，无法与论坛服务器通信。\n请检查当前代理或网络连接。"
            }
            if (raw.contains("Connection reset", ignoreCase = true) || raw.contains("Connection reset by peer", ignoreCase = true)) {
                return "连接被重置 (Connection reset)。\n具体原因尚未确认，请查看 DNS、连接与 TLS 分阶段诊断。"
            }
            if (raw.contains("ConnectException", ignoreCase = true) || raw.contains("Failed to connect", ignoreCase = true)) {
                return "无法连接至论坛服务器，请检查网络环境或代理配置。"
            }
            if (raw.contains("UnknownHostException", ignoreCase = true)) {
                return "DNS 解析失败，无法访问 linux.do。\n建议在设置中开启 DoH 安全解析。"
            }

            // Discourse JSON error extraction
            val jsonErrorMatch = Regex("\"errors\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(raw)
            if (jsonErrorMatch != null) {
                val extracted = jsonErrorMatch.groupValues[1]
                return "$extracted\n公开话题无需登录，请检查网络设置或稍后重试。"
            }

            val summary = raw.lineSequence().firstOrNull()?.take(80) ?: raw.take(80)
            return "$summary\n公开话题无需登录，请检查网络设置或稍后重试。"
        }
    }

    init {
        border = JBUI.Borders.empty()
        setupUI()
        setupListeners()
        loadInitialData()
    }

    private fun setupUI() {
        val topPanel = JPanel(BorderLayout(0, 4))
        topPanel.border = JBUI.Borders.empty(4, 6)

        categoryComboBox.preferredSize = Dimension(0, CONTROL_HEIGHT)
        filterComboBox.preferredSize = Dimension(0, CONTROL_HEIGHT)
        loginButton.preferredSize = Dimension(JBUI.scale(96), CONTROL_HEIGHT)
        userLabel.preferredSize = Dimension(JBUI.scale(100), CONTROL_HEIGHT)
        logoutButton.preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        logoutButton.minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
        logoutButton.maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)

        val headerStack = JPanel()
        headerStack.layout = BoxLayout(headerStack, BoxLayout.Y_AXIS)

        // Row 1: Auth / User profile and utilities; create/refresh live in the tool window title.
        val row1 = JPanel(BorderLayout(4, 0))
        val authBox = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        authBox.add(loginButton)
        authBox.add(userLabel)
        authBox.add(notificationButton)
        authBox.add(logoutButton)

        val utilBox = JPanel(FlowLayout(FlowLayout.RIGHT, 1, 0))
        utilBox.add(logBtn)
        utilBox.add(bossBtn)
        utilBox.add(settingsBtn)

        row1.add(authBox, BorderLayout.CENTER)
        row1.add(utilBox, BorderLayout.EAST)
        row1.addComponentListener(object : java.awt.event.ComponentAdapter() {
            private var stacked = false
            override fun componentResized(e: java.awt.event.ComponentEvent?) {
                val next = row1.width < authBox.preferredSize.width + utilBox.preferredSize.width + JBUI.scale(12)
                if (next == stacked) return
                stacked = next
                row1.remove(utilBox)
                row1.add(utilBox, if (stacked) BorderLayout.SOUTH else BorderLayout.EAST)
                row1.revalidate()
            }
        })

        // Adjacent category and searchable tag combos; list type wraps below at narrow widths.
        val row2 = com.lgguan.linuxdo.plugin.ui.dialog.TopicFilterRow(categoryComboBox, tagSelector.field, filterComboBox)

        // Row 3: Full-width Search field + Search & Advanced Search buttons
        val row3 = JPanel(BorderLayout(3, 0))
        searchField.emptyText.text = "搜索关键词 / 帖子 ID..."
        searchField.toolTipText = "输入关键词搜索，或输入帖子 ID（例如 123456、#123456）精确查找"
        searchField.preferredSize = Dimension(JBUI.scale(120), CONTROL_HEIGHT)

        val searchActions = JPanel(FlowLayout(FlowLayout.RIGHT, 1, 0))
        searchActions.add(searchBtn)
        searchActions.add(advSearchBtn)

        row3.add(searchField, BorderLayout.CENTER)
        row3.add(searchActions, BorderLayout.EAST)

        headerStack.add(row1)
        headerStack.add(Box.createVerticalStrut(JBUI.scale(4)))
        val views = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val forum = JToggleButton("论坛", true)
        val mine = JToggleButton("我的")
        ButtonGroup().apply { add(forum); add(mine) }
        forum.addActionListener { selectPersonalView(false) }
        mine.addActionListener { selectPersonalView(true) }
        views.add(forum); views.add(mine); headerStack.add(views)
        forumControls.layout = BoxLayout(forumControls, BoxLayout.Y_AXIS)
        forumControls.add(row2)
        forumControls.add(Box.createVerticalStrut(JBUI.scale(4)))
        forumControls.add(row3)
        headerStack.add(forumControls)

        topPanel.add(headerStack, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)

        // Center Area: List vs Empty State
        topicList.cellRenderer = topicRenderer
        topicList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val scrollPane = listScrollPane
        topicList.toolTipText = ""

        setupEmptyStatePanel()

        centerContainer.add(scrollPane, "LIST")
        centerContainer.add(emptyStatePanel, "EMPTY")

        forumContent.add(JPanel(BorderLayout()).apply {
            add(loadStatusPanel, BorderLayout.NORTH)
            add(centerContainer, BorderLayout.CENTER)
        }, BorderLayout.CENTER)

        // Bottom: Load more button
        bottomPanel.add(loadMoreButton, BorderLayout.CENTER)
        loadMoreButton.addActionListener { loadMore() }
        forumContent.add(bottomPanel, BorderLayout.SOUTH)
        contentViews.add(forumContent, "FORUM")
        contentViews.add(personalContentPanel, "MY")
        add(contentViews, BorderLayout.CENTER)

        updateAuthDisplay()
    }

    private fun setupEmptyStatePanel() {
        emptyStatePanel.border = JBUI.Borders.empty(20, 16)
        val gbc = GridBagConstraints()
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.CENTER

        // 1. Icon
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.insets = JBUI.insetsBottom(12)
        emptyStatePanel.add(emptyTipIcon, gbc)

        // 2. Title
        gbc.gridy++
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(0, 16, 8, 16)
        emptyStatePanel.add(emptyTipTitle, gbc)

        // 3. Description
        gbc.gridy++
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        gbc.insets = JBUI.insets(0, 20, 16, 20)
        emptyStatePanel.add(emptyTipDesc, gbc)

        // 4. Action Button
        gbc.gridy++
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.insets = JBUI.insets(4, 16, 6, 16)
        emptyStatePanel.add(emptyActionBtn, gbc)

        // 5. Secondary Button
        gbc.gridy++
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        gbc.insets = JBUI.insets(2, 16, 10, 16)
        emptyStatePanel.add(emptySecondaryBtn, gbc)
    }

    private fun updateDescText(text: String) {
        emptyTipDesc.text = text
        try {
            val doc = emptyTipDesc.styledDocument
            val center = SimpleAttributeSet()
            StyleConstants.setAlignment(center, StyleConstants.ALIGN_CENTER)
            doc.setParagraphAttributes(0, doc.length, center, false)
        } catch (_: Throwable) {}
    }

    private fun updateEmptyState(isCf: Boolean, isError: Boolean, errorMsg: String? = null) {
        emptyActionBtn.isVisible = true
        if (isCf) {
            emptyTipIcon.icon = AllIcons.General.Web
            emptyTipTitle.text = "需完成 Cloudflare 安全人机验证"
            updateDescText("论坛开启了安全防护。\n无需登录账号，仅需在验证窗口完成人机验证即可浏览公开话题。")
            emptyActionBtn.text = "🛡️ 通过 Cloudflare 验证 (无需登录)"
            emptyActionBtn.actionListeners.forEach { emptyActionBtn.removeActionListener(it) }
            emptyActionBtn.addActionListener { openLoginDialog() }
            emptySecondaryBtn.isVisible = true
            emptySecondaryBtn.text = "重试刷新"
            emptySecondaryBtn.actionListeners.forEach { emptySecondaryBtn.removeActionListener(it) }
            emptySecondaryBtn.addActionListener { refreshList() }
        } else if (isError) {
            emptyTipIcon.icon = AllIcons.General.WarningDialog
            emptyTipTitle.text = "话题加载暂未成功"
            updateDescText(formatErrorDisplay(errorMsg))
            emptyActionBtn.text = "重试刷新"
            emptyActionBtn.actionListeners.forEach { emptyActionBtn.removeActionListener(it) }
            emptyActionBtn.addActionListener { refreshList() }

            emptySecondaryBtn.isVisible = true
            if (errorMsg?.contains("404") == true) {
                emptySecondaryBtn.text = "切换至全部版块"
                emptySecondaryBtn.actionListeners.forEach { emptySecondaryBtn.removeActionListener(it) }
                emptySecondaryBtn.addActionListener {
                    categoryComboBox.selectedIndex = 0
                    refreshList()
                }
            } else if (errorMsg?.contains("Connection reset", ignoreCase = true) == true) {
                emptySecondaryBtn.text = "配置网络代理 / 查看诊断"
                emptySecondaryBtn.actionListeners.forEach { emptySecondaryBtn.removeActionListener(it) }
                emptySecondaryBtn.addActionListener { openNetworkOrProxyDiagnostic() }
            } else {
                emptySecondaryBtn.text = "登录账号 / 切换网络"
                emptySecondaryBtn.actionListeners.forEach { emptySecondaryBtn.removeActionListener(it) }
                emptySecondaryBtn.addActionListener { openLoginDialog() }
            }
        } else {
            emptyTipIcon.icon = AllIcons.General.Information
            emptyTipTitle.text = "暂无相关话题"
            updateDescText("当前分类或筛选条件下未找到话题。")
            emptyActionBtn.text = "查看最新话题"
            emptyActionBtn.actionListeners.forEach { emptyActionBtn.removeActionListener(it) }
            emptyActionBtn.addActionListener {
                activeSearchQuery = null
                searchField.text = ""
                filterComboBox.selectedItem = Constants.TopicFilter.LATEST
                refreshList()
            }
            emptySecondaryBtn.isVisible = false
        }
        emptyStatePanel.revalidate()
        emptyStatePanel.repaint()
    }

    private fun showCard(card: String) {
        val cl = centerContainer.layout as? CardLayout
        cl?.show(centerContainer, card)
        bottomPanel.isVisible = (card == "LIST" && topicListModel.size > 0)
    }

    private fun startLoading(message: String) {
        isLoading = true
        retryRequest = null
        retryButton.isVisible = false
        loadStatusLabel.text = message
        loadStatusLabel.toolTipText = message
        loadingProgress.isVisible = true
        loadingProgress.isIndeterminate = true
        loadStatusPanel.isVisible = true
        loadMoreButton.isEnabled = false
        loadMoreButton.text = message
        if (topicListModel.isEmpty) {
            emptyTipIcon.icon = AllIcons.General.Information
            emptyTipTitle.text = message
            updateDescText("正在连接 Linux Do，请稍候。")
            emptyActionBtn.isVisible = false
            emptySecondaryBtn.isVisible = false
            showCard("EMPTY")
        } else showCard("LIST")
        revalidate()
        repaint()
    }

    private fun finishLoading() {
        isLoading = false
        loadingProgress.isIndeterminate = false
        loadingProgress.isVisible = false
        loadStatusPanel.isVisible = false
        retryRequest = null
    }

    private fun showLoadFailure(error: Throwable, retry: () -> Unit) {
        finishLoading()
        val isCf = error is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
        if (topicListModel.isEmpty) {
            updateEmptyState(isCf, isError = true, errorMsg = error.message)
            showCard("EMPTY")
        } else {
            loadStatusLabel.text = when {
                isCf -> "需完成人机验证，已保留原列表"
                error is com.lgguan.linuxdo.plugin.net.RateLimitException -> "请求过于频繁，请稍后重试"
                else -> "加载失败，已保留原列表"
            }
            loadStatusLabel.toolTipText = formatErrorDisplay(error.message)
            retryRequest = if (isCf) ({ openLoginDialog() }) else retry
            retryButton.text = if (isCf) "前往验证" else "重试"
            retryButton.isVisible = true
            loadStatusPanel.isVisible = true
            loadMoreButton.text = "加载未完成，请先重试"
            loadMoreButton.isEnabled = false
            showCard("LIST")
        }
        revalidate()
        repaint()
    }

    private fun setupListeners() {
        topicList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = topicList.locationToIndex(e.point)
                if (index != -1 && topicList.getCellBounds(index, index)?.contains(e.point) == true) {
                    val selected = topicListModel.getElementAt(index)
                    val cell = topicList.getCellBounds(index, index)
                    val tag = topicRenderer.tagAt(topicList, selected, index,
                        Point(e.x - cell.x, e.y - cell.y), cell.size)
                    if (tag != null) { tagSelector.setSelection(listOf(tag), notify = true); return }
                    safeOpenTopic(selected)
                } else {
                    val selected = topicList.selectedValue ?: return
                    safeOpenTopic(selected)
                }
            }
        })

        topicList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    val selected = topicList.selectedValue ?: return
                    safeOpenTopic(selected)
                }
            }
        })

        categoryComboBox.addActionListener {
            if (!isUpdatingDropdown) {
                activeSearchQuery = null
                refreshList()
            }
        }
        filterComboBox.addActionListener {
            if (!isUpdatingDropdown) {
                activeSearchQuery = null
                refreshList()
            }
        }

        bossBtn.addActionListener { onBossKeyTriggered() }

        settingsBtn.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LinuxDoSettingsConfigurable::class.java)
            populateCategoryDropdown(LinuxDoTopicService.getInstance().categories)
            topicList.repaint()
        }

        loginButton.addActionListener { openLoginDialog() }
        userLabel.addActionListener { openLoginDialog() }

        logoutButton.addActionListener {
            val choice = Messages.showYesNoDialog(
                project,
                "确定要退出当前登录并清除会话 Cookie 吗？",
                "确认退出",
                Messages.getQuestionIcon()
            )
            if (choice == Messages.YES) {
                LinuxDoAuthService.getInstance().logout()
            }
        }

        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    executeSearch()
                }
            }
        })
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = invalidate()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = invalidate()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = invalidate()
            private fun invalidate() {
                requestGeneration++
                listTask?.cancel(true)
                finishLoading()
                retryRequest = null
                retryButton.isVisible = false
                loadMoreButton.isEnabled = false
            }
        })

        LinuxDoAuthService.getInstance().addAuthListener(listenerLifetime) {
            if (disposed) return@addAuthListener
            updateAuthDisplay()
            val auth = LinuxDoAuthService.getInstance()
            val session = auth.sessionVersion
            val user = auth.currentUser?.id
            if (session == observedAuthSession && user == observedAuthUser) return@addAuthListener
            val accountChanged = session != observedAuthSession
            observedAuthSession = session
            observedAuthUser = user
            requestGeneration++
            listTask?.cancel(true)
            finishLoading()
            if (accountChanged) {
                topicListModel.clear()
                displayedCondition = null
                activeSearchQuery = null
            }
            authRefreshTimer.stop()
            // Credential import and successful verification are separate events.
            // Wait for confirmation; the dialog callback shares this one refresh timer.
            if (auth.status != LinuxDoAuthService.Status.CREDENTIALS_PENDING) refreshAfterAuthentication()
        }

        LinuxDoTopicService.getInstance().addCategoryListener(listenerLifetime) { list ->
            populateCategoryDropdown(list)
        }

        com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService.getInstance().addSyncListener(listenerLifetime) {
            if (!disposed && !project.isDisposed) topicList.repaint()
        }

        LinuxDoSettingsState.getInstance().addSettingsListener(listenerLifetime) {
            ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
                populateCategoryDropdown(LinuxDoTopicService.getInstance().categories)
                topicList.repaint()
            }
        }

        LinuxDoNotificationService.getInstance().addCountListener(listenerLifetime) { count ->
            updateNotificationWidget(count)
        }
        updateNotificationWidget(LinuxDoNotificationService.getInstance().unreadCount)
    }

    private fun safeOpenTopic(topic: Topic) {
        try {
            onTopicSelected(topic)
        } catch (t: Throwable) {
            com.intellij.openapi.diagnostic.Logger.getInstance(IssueListPanel::class.java).error("Failed to open topic", t)
            Messages.showErrorDialog(project, "打开帖子失败: ${t.message ?: t.javaClass.simpleName}", "打开错误")
        }
    }

    private fun openLoginDialog() {
        try {
            val dialog = LoginAuthDialog(project) {
                updateAuthDisplay()
                refreshAfterAuthentication()
            }
            dialog.show()
            updateAuthDisplay()
        } catch (t: Throwable) {
            com.intellij.openapi.diagnostic.Logger.getInstance(IssueListPanel::class.java).error("Failed to open login dialog", t)
            Messages.showErrorDialog(project, "打开登录界面失败: ${t.message ?: t.javaClass.simpleName}", "登录错误")
        }
    }

    private fun refreshAfterAuthentication() {
        if (!disposed && !project.isDisposed) authRefreshTimer.restart()
    }

    private fun openNetworkOrProxyDiagnostic() {
        val options = arrayOf("打开 IDE HTTP 代理设置", "打开插件网络 / DoH 设置", "打开网页登录窗口", "取消")
        val choice = Messages.showDialog(
            project,
            "检测到连接失败，尚未确定原因。\n\n" +
            "DNS 解析成功不代表 TCP/TLS 连接成功，连接重置或超时也不能单独证明 SNI 阻断。\n" +
            "请在插件设置中开启一次性网络诊断，冷启动后复现，并查看 DNS、连接、TLS 与 JCEF 页面事件。\n" +
            "ECH 是否协商成功以实际连接记录为准。",
            "网络连接诊断",
            options,
            0,
            AllIcons.General.WarningDialog
        )
        when (choice) {
            0 -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "HTTP Proxy")
            1 -> ShowSettingsUtil.getInstance().showSettingsDialog(project, LinuxDoSettingsConfigurable::class.java)
            2 -> openLoginDialog()
        }
    }


    fun openCreateTopicDialog() {
        val authService = LinuxDoAuthService.getInstance()
        if (!authService.isLoggedIn) {
            val choice = Messages.showYesNoDialog(
                project,
                "发布话题需要登录 Linux Do 社区账号。\n是否立即打开登录与人机验证窗口？",
                "需要登录",
                Messages.getQuestionIcon()
            )
            if (choice == Messages.YES) {
                openLoginDialog()
            }
            return
        }

        val selectedCat = (categoryComboBox.selectedItem as? CategoryItem)?.id
        com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog.open(
            project = project,
            initialCategoryId = selectedCat
        ) {
            refreshList()
        }
    }

    private fun updateAuthDisplay() {
        val user = LinuxDoAuthService.getInstance().currentUser
        val hasSession = LinuxDoHttpClient.cookieJar.hasValidSession()

        if (user != null) {
            val displayUsername = if (user.username.length > 10) "@${user.username.take(8)}…" else "@${user.username}"
            userLabel.text = displayUsername
            userLabel.toolTipText = "当前登录用户: @${user.username} (信任等级 L${user.trustLevel})，点击重新认证"
            userLabel.icon = AllIcons.General.User
            userLabel.isVisible = true
            notificationButton.isVisible = true
            loginButton.isVisible = false
            logoutButton.isVisible = true
        } else {
            userLabel.isVisible = false
            notificationButton.isVisible = false
            loginButton.text = if (hasSession) "重新验证" else "登录账号"
            loginButton.icon = if (hasSession) AllIcons.General.Warning else AllIcons.General.Web
            loginButton.toolTipText = if (hasSession) "已保存会话凭证，但未获取到用户信息或需通过 Cloudflare 验证" else "登录账号 (公开话题无需登录即可直接浏览)"
            loginButton.isVisible = true
            logoutButton.isVisible = hasSession
        }
        revalidate()
        repaint()
    }

    private fun updateNotificationWidget(count: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed) return@invokeLater
            if (count > 0) {
                notificationButton.text = if (count > 99) "99+" else "$count"
                notificationButton.foreground = JBColor(0xCF222E, 0xF85149)
                notificationButton.icon = AllIcons.Toolwindows.NotificationsNew
                notificationButton.toolTipText = "Linux Do 通知：${LinuxDoNotificationService.getInstance().countState.label} - 点击查看"
                notificationButton.preferredSize = Dimension(JBUI.scale(42), CONTROL_HEIGHT)
                notificationButton.minimumSize = Dimension(JBUI.scale(42), CONTROL_HEIGHT)
                notificationButton.maximumSize = Dimension(JBUI.scale(48), CONTROL_HEIGHT)
            } else {
                notificationButton.text = if (count < 0) "?" else ""
                notificationButton.foreground = JBColor.foreground()
                notificationButton.icon = AllIcons.Toolwindows.Notifications
                notificationButton.toolTipText = "Linux Do 通知：${LinuxDoNotificationService.getInstance().countState.label} - 点击查看"
                notificationButton.preferredSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
                notificationButton.minimumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
                notificationButton.maximumSize = Dimension(CONTROL_HEIGHT, CONTROL_HEIGHT)
            }
            notificationButton.revalidate()
            notificationButton.repaint()
        }
    }

    fun loadInitialData() {
        if (disposed || project.isDisposed) return
        val service = LinuxDoTopicService.getInstance()
        val generation = ++categoryRequestGeneration
        categoriesLoading = true
        categoryLoadFailed = false
        categoryComboBox.isEnabled = false
        categoryComboBox.toolTipText = "正在加载分类…"
        populateCategoryDropdown(if (service.categoriesAreCurrent) service.categories else emptyList())
        // Public topics do not depend on the category endpoint completing first.
        refreshList()
        service.loadCategories { list ->
            if (disposed || project.isDisposed || generation != categoryRequestGeneration) return@loadCategories
            val selectedBefore = (categoryComboBox.selectedItem as? CategoryItem)?.id
            categoriesLoading = false
            categoryLoadFailed = !service.categoriesAreCurrent
            populateCategoryDropdown(list)
            categoryComboBox.isEnabled = true
            categoryComboBox.toolTipText = if (service.categoriesAreCurrent) "选择话题分类"
                else "分类加载失败，点击工具栏刷新可重试；仍可浏览全部话题"
            if (selectedBefore != (categoryComboBox.selectedItem as? CategoryItem)?.id && activeSearchQuery == null) {
                refreshList()
            }
        }
    }

    private fun populateCategoryDropdown(list: List<Category>) {
        if (disposed || project.isDisposed) return
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { populateCategoryDropdown(list) }
            return
        }
        isUpdatingDropdown = true
        try {
            val selectedBefore = (categoryComboBox.selectedItem as? CategoryItem)?.id
            val settings = LinuxDoSettingsState.getInstance()
            val allLabel = if (categoriesLoading && list.isEmpty()) "正在加载分类…"
                else if (categoryLoadFailed && list.isEmpty()) "全部版块（分类加载失败）"
                else if (settings.categoryNamespaceFormat) "All Packages" else "全部版块"
            categoryComboBox.removeAllItems()
            categoryComboBox.addItem(CategoryItem(null, allLabel, null))

            val allowedIds = list.map { it.id }.toSet()
            val hierarchicalList = LinuxDoTopicService.getInstance().getHierarchicalCategories()
                .filter { it.category.id in allowedIds }
            var restoreIndex = 0
            for ((idx, hCat) in hierarchicalList.withIndex()) {
                val cat = hCat.category
                val parent = hCat.parent
                val lockPrefix = if (cat.readRestricted == true) "🔒 " else ""
                val displayName = if (settings.categoryNamespaceFormat) {
                    if (parent == null) {
                        "$lockPrefix${NamespaceFormatter.format(cat.name, cat.slug)}"
                    } else {
                        val parentFormatted = NamespaceFormatter.format(parent.name, parent.slug)
                        "$lockPrefix$parentFormatted.${cat.slug}"
                    }
                } else {
                    if (parent == null) {
                        "$lockPrefix${cat.name}"
                    } else {
                        "  └ $lockPrefix${cat.name}"
                    }
                }
                categoryComboBox.addItem(CategoryItem(cat.id, displayName, cat.slug, parent?.slug))
                if (cat.id == selectedBefore) {
                    restoreIndex = idx + 1
                }
            }
            categoryComboBox.selectedIndex = restoreIndex
        } finally {
            isUpdatingDropdown = false
        }
    }

    fun refreshList() {
        if (activeSearchQuery != null) {
            loadSearchPage(activeSearchQuery!!, 1)
            return
        }
        loadPage(0)
    }

    private fun loadMore() {
        if (!hasMorePages || isLoading) return
        val query = activeSearchQuery
        if (query != null) {
            if (query == searchField.text.trim()) loadSearchPage(query, currentPage + 1)
        } else loadPage(currentPage + 1)
    }

    private fun applyTopics(topics: List<Topic>, condition: String, refresh: Boolean) {
        val same = condition == displayedCondition
        val current = (0 until topicListModel.size()).map { topicListModel[it] }
        val next = if (same) com.lgguan.linuxdo.plugin.model.TopicBrowsing.merge(current, topics, refresh)
            else topics.distinctBy { it.id }
        TopicListReconciler.apply(topicList, topicListModel, listScrollPane, next, same)
        displayedCondition = condition
    }

    private fun loadPage(page: Int) {
        if (disposed || project.isDisposed) return
        if (isLoading && page > 0) return
        val generation = ++requestGeneration
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        startLoading(when {
            page > 0 -> "正在加载更多话题…"
            topicListModel.isEmpty -> "正在加载话题…"
            else -> "正在刷新话题…"
        })

        val selectedCatItem = categoryComboBox.selectedItem as? CategoryItem
        val category = selectedCatItem?.id?.let { LinuxDoTopicService.getInstance().getCategory(it) }
        val filter = filterComboBox.selectedItem as? Constants.TopicFilter ?: Constants.TopicFilter.LATEST
        val tag = tagSelector.selection().firstOrNull()
        val condition = "list:$session:${selectedCatItem?.id}:$filter:$tag"

        listTask?.cancel(true)
        listTask = LinuxDoTopicService.getInstance().loadTopics(
            filter = filter,
            category = category,
            page = page,
            tag = tag,
            onSuccess = { topics, hasMore ->
                if (disposed || project.isDisposed || generation != requestGeneration ||
                    session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@loadTopics
                finishLoading()
                val preservingPages = page == 0 && displayedCondition == condition && topics.isNotEmpty()
                val preservingTail = preservingPages && currentPage > 0
                currentPage = if (preservingPages) currentPage else page
                if (!preservingTail) hasMorePages = hasMore
                loadMoreButton.isEnabled = hasMorePages
                loadMoreButton.text = if (hasMorePages) "加载更多话题" else "已加载全部话题"

                applyTopics(topics, condition, refresh = page == 0)

                if (topicListModel.isEmpty) {
                    updateEmptyState(isCf = false, isError = false)
                    showCard("EMPTY")
                } else {
                    showCard("LIST")
                }
            },
            onError = { err ->
                if (disposed || project.isDisposed || generation != requestGeneration ||
                    session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@loadTopics
                showLoadFailure(err) { loadPage(page) }
            }
        )
    }

    @JvmOverloads fun search(query: String, inheritList: Boolean = true) {
        searchField.text = query
        executeSearch(inheritList)
    }

    private fun executeSearch(inheritList: Boolean = true) {
        if (disposed || project.isDisposed) return
        val query = searchField.text.trim()
        if (query.isBlank()) {
            activeSearchQuery = null
            refreshList()
            return
        }
        val fullQuery = if (inheritList) com.lgguan.linuxdo.plugin.model.AdvancedSearchQuery.inherit(query,
            (categoryComboBox.selectedItem as? CategoryItem)?.id, tagSelector.selection().firstOrNull()) else query
        activeSearchQuery = fullQuery
        loadSearchPage(fullQuery, 1)
    }

    private fun loadSearchPage(query: String, page: Int) {
        if (disposed || project.isDisposed || (isLoading && page > 1)) return
        val generation = ++requestGeneration
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        val condition = "search:$session:$query"
        startLoading(if (page > 1) "正在加载更多搜索结果…" else "正在搜索话题…")
        emptyTipIcon.icon = AllIcons.Actions.Search
        updateDescText("正在查找符合条件的话题，请稍候。")

        listTask?.cancel(true)
        listTask = backgroundTasks.submit {
            val result = try { DiscourseApiClient.search(query, page) }
                catch (error: Exception) { Result.failure(error) }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || generation != requestGeneration ||
                    session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                finishLoading()
                result.onSuccess { searchResult ->
                    val topics = com.lgguan.linuxdo.plugin.model.TopicBrowsing.searchTopics(searchResult)
                    val preservingPages = page == 1 && displayedCondition == condition && topics.isNotEmpty()
                    val preservingTail = preservingPages && currentPage > 1
                    currentPage = if (preservingPages) currentPage.coerceAtLeast(1) else page
                    if (!preservingTail) hasMorePages = !Regex("#?[0-9]+").matches(query) && searchResult.groupedSearchResult?.moreFullPageResults == true
                    applyTopics(topics, condition, refresh = page == 1)
                    loadMoreButton.text = if (hasMorePages) "加载更多搜索结果（${topicListModel.size()} 个话题）" else "找到 ${topicListModel.size()} 个话题"
                    loadMoreButton.isEnabled = hasMorePages

                    if (topicListModel.isEmpty) {
                        updateEmptyState(isCf = false, isError = false)
                        showCard("EMPTY")
                    } else {
                        showCard("LIST")
                    }
                }.onFailure { err ->
                    showLoadFailure(err) { loadSearchPage(query, page) }
                    if (topicListModel.isEmpty) {
                        if (err !is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException &&
                            Regex("#?[0-9]+").matches(query) && err.message?.contains("404") == true) {
                            emptyTipTitle.text = "未找到帖子 $query"
                            updateDescText("请检查帖子 ID。帖子可能已删除，或当前账号没有查看权限。")
                            emptySecondaryBtn.isVisible = false
                        }
                        showCard("EMPTY")
                    }
                }
            }
        }
    }
    override fun dispose() {
        personalContentPanel.dispose()
        authRefreshTimer.stop()
        disposed = true
        requestGeneration++
        categoryRequestGeneration++
        listTask?.cancel(true)
        backgroundTasks.dispose()
        loadingProgress.isIndeterminate = false
        com.intellij.openapi.util.Disposer.dispose(listenerLifetime)
    }

}
