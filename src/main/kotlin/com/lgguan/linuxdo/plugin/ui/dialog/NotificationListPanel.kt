package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.lgguan.linuxdo.plugin.model.DiscourseNotification
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
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

    private var currentCategory = NotificationCategory.ALL
    private val notificationModel = DefaultListModel<DiscourseNotification>()
    val list = JBList(notificationModel)

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
    private val markAllReadBtn = JButton(AllIcons.Actions.Checked).apply {
        toolTipText = "全部标为已读"
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(24))
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
        contentDeck.add(JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
        }, "LIST")
        contentDeck.add(emptyPanel, "EMPTY")
        centerPanel.add(contentDeck, BorderLayout.CENTER)
        add(centerPanel, BorderLayout.CENTER)

        // Listen for live updates
        val listener: (List<DiscourseNotification>) -> Unit = {
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                updateListItems()
            }
        }
        LinuxDoNotificationService.getInstance().addNotificationListener(listenerLifetime, listener)

        updateListItems()
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

        val topStack = JPanel(BorderLayout())
        topStack.add(tabBox, BorderLayout.SOUTH)
        (getComponent(0) as? JPanel)?.add(tabBox, BorderLayout.SOUTH)
    }

    private fun setupList() {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = NotificationCellRenderer()
        list.emptyText.text = "暂无通知"

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index in 0 until notificationModel.size()) {
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
        val emptyTitle = JBLabel("暂无相关通知").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 12f)
        }
        emptyPanel.add(emptyIcon, gbc)
        gbc.gridy++
        emptyPanel.add(emptyTitle, gbc)
    }

    private fun setupFooter() {
        val footer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 12)
            background = JBColor(0xF6F8FA, 0x1F2328)
        }
        val tipLabel = JBLabel("💡 点击通知可直接跳转至对应楼层").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }
        val webBtn = JBLabel("网页通知 ↗").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(0x0969DA, 0x58A6FF)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    val user = LinuxDoAuthService.getInstance().currentUser?.username
                    val url = if (!user.isNullOrBlank()) {
                        "https://linux.do/u/$user/notifications"
                    } else {
                        "https://linux.do/notifications"
                    }
                    BrowserUtil.browse(url)
                    onClose?.invoke()
                }
            })
        }
        footer.add(tipLabel, BorderLayout.WEST)
        footer.add(webBtn, BorderLayout.EAST)
        add(footer, BorderLayout.SOUTH)
    }

    private fun handleNotificationClick(item: DiscourseNotification) {
        // Mark as read immediately
        LinuxDoNotificationService.getInstance().markAsRead(item.id)

        // Jump to target topic & floor
        if (item.topicId != null) {
            LinuxDoEditorOpener.openTopic(
                project = project,
                topicId = item.topicId,
                topicTitle = item.getDisplayTitle(),
                postNumber = item.postNumber
            )
            onClose?.invoke()
        } else if (item.data?.badgeId != null) {
            val badgeUrl = "https://linux.do/badges/${item.data.badgeId}"
            BrowserUtil.browse(badgeUrl)
            onClose?.invoke()
        }
    }

    private fun reloadNotifications() {
        val notifService = LinuxDoNotificationService.getInstance()
        if (notifService.isCircuitBroken()) {
            val remainingSec = notifService.getRemainingCircuitBreakerSeconds()
            val min = (remainingSec + 59) / 60
            Messages.showWarningDialog(
                this,
                "当前处于安全熔断保护中 (还剩约 $min 分钟)。\n已自动暂停向论坛发送请求，防止高频访问导致账号被风控。\n冷却结束后将自动恢复正常。",
                "安全熔断保护 (HTTP 429)"
            )
            return
        }
        refreshBtn.isEnabled = false
        notifService.refreshNotifications {
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                refreshBtn.isEnabled = true
                updateListItems()
            }
        }
    }

    private fun markAllAsRead() {
        val notifService = LinuxDoNotificationService.getInstance()
        if (notifService.isCircuitBroken()) {
            val remainingSec = notifService.getRemainingCircuitBreakerSeconds()
            val min = (remainingSec + 59) / 60
            Messages.showWarningDialog(
                this,
                "当前处于安全熔断保护中 (还剩约 $min 分钟)。\n已自动暂停向论坛发送请求，防止高频访问导致账号被风控。\n冷却结束后将自动恢复正常。",
                "安全熔断保护 (HTTP 429)"
            )
            return
        }
        markAllReadBtn.isEnabled = false
        notifService.markAsRead(null) {
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                markAllReadBtn.isEnabled = true
                updateListItems()
            }
        }
    }

    fun updateListItems() {
        val allList = LinuxDoNotificationService.getInstance().recentNotifications.toList()
        val filtered = when (currentCategory) {
            NotificationCategory.ALL -> allList
            NotificationCategory.REPLIES -> allList.filter { it.notificationType in listOf(1, 2, 3, 9, 15, 34) }
            NotificationCategory.LIKES -> allList.filter { it.notificationType in listOf(5, 19, 25) }
            NotificationCategory.SYSTEM -> allList.filter { it.notificationType !in listOf(1, 2, 3, 5, 9, 15, 19, 25, 34) }
        }

        notificationModel.clear()
        for (item in filtered) {
            notificationModel.addElement(item)
        }

        val unreadTotal = allList.count { !it.read }
        if (unreadTotal > 0) {
            unreadBadgeLabel.text = "($unreadTotal 条未读)"
            unreadBadgeLabel.isVisible = true
        } else {
            unreadBadgeLabel.text = ""
            unreadBadgeLabel.isVisible = false
        }

        if (filtered.isEmpty()) {
            cardLayout.show(contentDeck, "EMPTY")
        } else {
            cardLayout.show(contentDeck, "LIST")
        }
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
            val author = value.getDisplayAuthor()
            val action = value.getTypeActionLabel()
            val isUnread = !value.read

            // 1. Icon Badge styling by notification type
            when (value.notificationType) {
                5, 19 -> { // Like
                    iconBadge.text = "♥"
                    iconBadge.foreground = Color(0xE0, 0x48, 0x5D)
                    iconBadge.background = JBColor(Color(0xFF, 0xEE, 0xF0), Color(0x3B, 0x1E, 0x22))
                }
                2, 9 -> { // Reply
                    iconBadge.text = "↩"
                    iconBadge.foreground = Color(0x09, 0x69, 0xDA)
                    iconBadge.background = JBColor(Color(0xDD, 0xF4, 0xFF), Color(0x1B, 0x2B, 0x3E))
                }
                1, 15 -> { // Mention
                    iconBadge.text = "@"
                    iconBadge.foreground = Color(0x82, 0x50, 0xDF)
                    iconBadge.background = JBColor(Color(0xF6, 0xEB, 0xFF), Color(0x2D, 0x1E, 0x3E))
                }
                34 -> { // Boost
                    iconBadge.text = "🚀"
                    iconBadge.foreground = Color(0xBF, 0x87, 0x00)
                    iconBadge.background = JBColor(Color(0xFF, 0xF8, 0xC5), Color(0x3B, 0x32, 0x1B))
                }
                12 -> { // Badge
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
