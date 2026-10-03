package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.service.LinuxDoNotificationService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class NotificationListPanel(
    private val project: Project,
    var onClose: (() -> Unit)? = null
) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()
    @Volatile private var disposed = false

    enum class NotificationCategory(val title: String) {
        ALL("全部"),
        REPLIES("回复/微评"),
        LIKES("获赞"),
        SYSTEM("系统/徽章")
    }

    private val service = LinuxDoNotificationService.getInstance()
    private var currentStatus = NotificationStatus.ALL
    private val statusCombo = JComboBox(NotificationStatus.entries.toTypedArray())
    private val emptyTitle = JBLabel("暂无相关通知")
    private val messageLabel = JBLabel("").apply { font = font.deriveFont(11f); foreground = JBColor.GRAY }
    private val loadMoreBtn = JButton("加载更多").apply { addActionListener { loadMore() } }
    private val manualReadBtn = JButton("选中项标读").apply {
        isEnabled = false
        toolTipText = "手动将选中通知标为已读；网页跳转不会自动标读"
        addActionListener { list.selectedValue?.let { markRead(it.id) } }
    }
    private var loading = false
    private var itemsVersion = SessionEpoch.current
    private var currentCategory = NotificationCategory.ALL
    private val notificationModel = DefaultListModel<DiscourseNotification>()
    val list = JBList(notificationModel)
    private val scrollPane = JBScrollPane(list).apply { border = JBUI.Borders.empty() }

    private val titleLabel = JBLabel("通知").apply {
        font = font.deriveFont(Font.BOLD, 13f)
    }
    private val unreadBadgeLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor(0xCF222E, 0xF85149)
    }
    private val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
        toolTipText = "刷新通知"
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(24))
        margin = JBUI.insets(1)
        isFocusable = false
        addActionListener { reloadNotifications() }
    }
    private val markAllReadBtn = JButton("账号全部标读", AllIcons.Actions.Checked).apply {
        toolTipText = "将账号全部通知标为已读（包含未加载的通知）"
        font = font.deriveFont(11f)
        preferredSize = Dimension(JBUI.scale(110), JBUI.scale(24))
        margin = JBUI.insets(1)
        isFocusable = false
        addActionListener { markAllAsRead() }
    }

    private val cardLayout = CardLayout()
    private val contentDeck = JPanel(cardLayout)
    private val emptyPanel = JPanel(GridBagLayout())

    init {
        border = JBUI.Borders.customLine(JBColor.border())
        preferredSize = Dimension(JBUI.scale(430), JBUI.scale(490))
        minimumSize = Dimension(JBUI.scale(360), JBUI.scale(320))

        setupHeader()
        setupCategoryTabs()
        setupList()
        setupEmptyPanel()
        setupFooter()

        val centerPanel = JPanel(BorderLayout())
        contentDeck.add(scrollPane, "LIST")
        contentDeck.add(emptyPanel, "EMPTY")
        centerPanel.add(contentDeck, BorderLayout.CENTER)
        add(centerPanel, BorderLayout.CENTER)

        service.addNotificationListener(listenerLifetime) { updateListItems() }
        service.addCountListener(listenerLifetime) { updateListItems() }
        updateListItems()
        reloadNotifications()
    }

    private fun setupHeader() {
        val header = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(8, 12, 6, 12)
            background = JBColor(0xF6F8FA, 0x1F2328)
        }

        val leftBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(titleLabel)
            add(unreadBadgeLabel)
        }

        val rightBox = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(refreshBtn)
            add(markAllReadBtn)
        }

        header.add(leftBox, BorderLayout.WEST)
        header.add(rightBox, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
    }

    private fun setupCategoryTabs() {
        val tabBox = JPanel(GridLayout(1, 4, JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4, 10, 4, 10)
            background = JBColor(0xF6F8FA, 0x1F2328)
        }

        for (cat in NotificationCategory.values()) {
            val tabBtn = JButton(cat.title).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                preferredSize = Dimension(0, JBUI.scale(24))
                margin = JBUI.insets(1, 4)
                isFocusable = false
                addActionListener {
                    currentCategory = cat
                    updateListItems()
                }
            }
            tabBox.add(tabBtn)
        }

        val filters = JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                add(JBLabel("状态"))
                add(statusCombo)
                add(JBLabel("类别仅筛选已加载通知").apply { foreground = JBColor.GRAY; font = font.deriveFont(11f) })
            }, BorderLayout.NORTH)
            add(tabBox, BorderLayout.SOUTH)
        }
        statusCombo.addActionListener {
            currentStatus = statusCombo.selectedItem as NotificationStatus
            updateListItems()
            reloadNotifications()
        }
        (getComponent(0) as? JPanel)?.add(filters, BorderLayout.SOUTH)
    }

    private fun setupList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = NotificationCellRenderer()
        list.emptyText.text = "暂无通知"

        list.addListSelectionListener { manualReadBtn.isEnabled = !loading && list.selectedValue?.read == false }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val index = list.locationToIndex(e.point)
                if (index in 0 until notificationModel.size() && list.getCellBounds(index, index)?.contains(e.point) == true) {
                    val item = notificationModel.getElementAt(index)
                    handleNotificationClick(item)
                }
            }
        })
    }

    private fun setupEmptyPanel() {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            insets = JBUI.insets(6)
            anchor = GridBagConstraints.CENTER
        }
        val emptyIcon = JBLabel(AllIcons.General.Information)
        emptyTitle.foreground = JBColor.GRAY
        emptyTitle.font = emptyTitle.font.deriveFont(Font.PLAIN, 12f)
        emptyPanel.add(emptyIcon, gbc)
        gbc.gridy++
        emptyPanel.add(emptyTitle, gbc)
    }

    private fun setupFooter() {
        val footer = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(6, 10) }
        footer.add(messageLabel, BorderLayout.NORTH)
        footer.add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(loadMoreBtn)
            add(manualReadBtn)
            add(JButton("网页通知 ↗").apply {
                addActionListener { BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/notifications") }
            })
        }, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    private fun handleNotificationClick(item: DiscourseNotification) {
        if (itemsVersion != SessionEpoch.current) { updateListItems(); return }
        service.openNotification(project, item) { success ->
            if (!disposed && !project.isDisposed) {
                if (success) onClose?.invoke() else updateListItems()
            }
        }
    }

    private fun reloadNotifications() {
        val status = currentStatus
        val version = SessionEpoch.current
        setLoading(true)
        service.refresh(status) {
            if (!disposed && !project.isDisposed && status == currentStatus && version == SessionEpoch.current) {
                setLoading(false)
                updateListItems()
            }
        }
    }

    private fun loadMore() {
        val status = currentStatus
        val version = SessionEpoch.current
        setLoading(true)
        service.loadMore(status) {
            if (!disposed && !project.isDisposed && status == currentStatus && version == SessionEpoch.current) {
                setLoading(false)
                updateListItems()
            }
        }
    }

    private fun setLoading(value: Boolean) {
        loading = value
        refreshBtn.isEnabled = !value
        loadMoreBtn.isEnabled = !value
        manualReadBtn.isEnabled = !value && list.selectedValue?.read == false
    }

    private fun markAllAsRead() = markRead(null)

    private fun markRead(id: Long?) {
        if (itemsVersion != SessionEpoch.current) { updateListItems(); return }
        markAllReadBtn.isEnabled = false
        manualReadBtn.isEnabled = false
        service.markAsRead(id) { success ->
            if (disposed || project.isDisposed) return@markAsRead
            markAllReadBtn.isEnabled = true
            if (!success) messageLabel.text = service.lastReadError ?: "标读失败，请稍后重试"
            updateListItems()
        }
    }

    fun updateListItems() {
        if (disposed || project.isDisposed) return
        val history = service.history(currentStatus)
        itemsVersion = SessionEpoch.current
        val filtered = history.items.filter { item -> when (currentCategory) {
            NotificationCategory.ALL -> true
            NotificationCategory.REPLIES -> NotificationTypes.category(item) == "replies"
            NotificationCategory.LIKES -> NotificationTypes.category(item) == "likes"
            NotificationCategory.SYSTEM -> NotificationTypes.category(item) == "system"
        } }
        val selected = list.selectedValue?.id
        val first = list.firstVisibleIndex
        val anchor = if (first >= 0 && first < notificationModel.size()) notificationModel.getElementAt(first).id else null
        val anchorOffset = if (first >= 0) scrollPane.viewport.viewPosition.y - (list.getCellBounds(first, first)?.y ?: 0) else 0
        notificationModel.clear()
        filtered.forEach(notificationModel::addElement)
        list.selectedIndex = filtered.indexOfFirst { it.id == selected }
        val anchorIndex = filtered.indexOfFirst { it.id == anchor }
        if (anchorIndex >= 0) {
            val y = list.getCellBounds(anchorIndex, anchorIndex)?.y ?: 0
            scrollPane.viewport.viewPosition = Point(0, (y + anchorOffset).coerceAtLeast(0))
        }
        unreadBadgeLabel.text = service.countState.label
        unreadBadgeLabel.isVisible = true
        val more = history.next != null
        emptyTitle.text = if (more) "已加载通知中暂无匹配，可继续加载" else "暂无相关通知"
        messageLabel.text = history.error ?: service.lastReadError ?: if (service.isCircuitBroken())
            "通知请求正在冷却；稍后重试" else "已加载 ${history.items.size} 条；定位成功后自动标读"
        loadMoreBtn.isVisible = more || history.failedQuery != null || !history.loaded
        loadMoreBtn.text = if (history.failedQuery != null || !history.loaded) "重试加载" else "加载更多"
        loadMoreBtn.isEnabled = !loading
        manualReadBtn.isEnabled = !loading && list.selectedValue?.read == false
        cardLayout.show(contentDeck, if (filtered.isEmpty()) "EMPTY" else "LIST")
        revalidate()
        repaint()
    }

    private class NotificationCellRenderer : ListCellRenderer<DiscourseNotification> {
        private val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            border = JBUI.Borders.empty(7, 12)
        }
        private val iconBadge = JLabel().apply {
            preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
            horizontalAlignment = SwingConstants.CENTER
            font = font.deriveFont(Font.BOLD, 13f)
            isOpaque = true
        }
        private val centerBox = JPanel(GridLayout(2, 1, 0, JBUI.scale(2))).apply {
            isOpaque = false
        }
        private val titleBox = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
        }
        private val authorActionLabel = JLabel()
        private val timeLabel = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.RIGHT
        }
        private val contentLabel = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        init {
            titleBox.add(authorActionLabel, BorderLayout.CENTER)
            titleBox.add(timeLabel, BorderLayout.EAST)
            centerBox.add(titleBox)
            centerBox.add(contentLabel)
            panel.add(iconBadge, BorderLayout.WEST)
            panel.add(centerBox, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out DiscourseNotification>,
            value: DiscourseNotification,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val author = com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer.escapeHtml(value.getDisplayAuthor())
            val action = value.getTypeActionLabel()
            val isUnread = !value.read

            // 1. Icon Badge styling by notification type
            when (NotificationTypes.name(value.notificationType)) {
                "liked", "liked_consolidated", "reaction" -> { // Like
                    iconBadge.text = "♥"
                    iconBadge.foreground = Color(0xE0, 0x48, 0x5D)
                    iconBadge.background = JBColor(Color(0xFF, 0xEE, 0xF0), Color(0x3B, 0x1E, 0x22))
                }
                "replied", "posted", "quoted" -> { // Reply
                    iconBadge.text = "↩"
                    iconBadge.foreground = Color(0x09, 0x69, 0xDA)
                    iconBadge.background = JBColor(Color(0xDD, 0xF4, 0xFF), Color(0x1B, 0x2B, 0x3E))
                }
                "mentioned", "group_mentioned" -> { // Mention
                    iconBadge.text = "@"
                    iconBadge.foreground = Color(0x82, 0x50, 0xDF)
                    iconBadge.background = JBColor(Color(0xF6, 0xEB, 0xFF), Color(0x2D, 0x1E, 0x3E))
                }
                "boost", "boosted", "boosted_consolidated" -> { // Boost
                    iconBadge.text = "🚀"
                    iconBadge.foreground = Color(0xBF, 0x87, 0x00)
                    iconBadge.background = JBColor(Color(0xFF, 0xF8, 0xC5), Color(0x3B, 0x32, 0x1B))
                }
                "granted_badge" -> { // Badge
                    iconBadge.text = "★"
                    iconBadge.foreground = Color(0x1A, 0x7F, 0x37)
                    iconBadge.background = JBColor(Color(0xDA, 0xF8, 0xE6), Color(0x1A, 0x38, 0x24))
                }
                else -> { // System / Mail
                    iconBadge.text = "✉"
                    iconBadge.foreground = JBColor.GRAY
                    iconBadge.background = JBColor(Color(0xEA, 0xEE, 0xF2), Color(0x24, 0x29, 0x2F))
                }
            }

            // 2. Text layout & styling
            val authorPart = if (isUnread) "<b>@$author</b>" else "@$author"
            val dotPart = if (isUnread) "<span style='color:#0969DA;font-size:13px;'>● </span>" else ""
            authorActionLabel.text = "<html><body>$dotPart$authorPart <span style='color:gray;'>$action</span></body></html>"
            timeLabel.text = value.getRelativeTime()

            val titleText = value.getDisplayTitle()
            val cleanTitle = if (titleText.length > 36) titleText.take(34) + "…" else titleText
            contentLabel.text = cleanTitle

            // 3. Selection vs Unread background
            if (isSelected) {
                panel.background = list.selectionBackground
                contentLabel.foreground = list.selectionForeground
            } else {
                panel.background = if (isUnread) {
                    JBColor(Color(0xEE, 0xF5, 0xFF), Color(0x1A, 0x25, 0x34))
                } else {
                    list.background
                }
                contentLabel.foreground = if (isUnread) JBColor.foreground() else JBColor.GRAY
            }

            return panel
        }
    }

    override fun dispose() { disposed = true; onClose = null; com.intellij.openapi.util.Disposer.dispose(listenerLifetime) }

    companion object {
        fun showAsPopup(project: Project, anchor: JComponent) {
            var popup: JBPopup? = null
            val panel = NotificationListPanel(project) {
                popup?.cancel()
            }
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, panel.list)
                .setRequestFocus(true)
                .setFocusable(true)
                .setResizable(true)
                .setMovable(true)
                .setCancelOnClickOutside(true)
                .setTitle("Linux Do 通知")
                .createPopup()
            com.intellij.openapi.util.Disposer.register(popup, panel)
            panel.onClose = { popup?.cancel() }
            popup.showUnderneathOf(anchor)
        }
    }
}
