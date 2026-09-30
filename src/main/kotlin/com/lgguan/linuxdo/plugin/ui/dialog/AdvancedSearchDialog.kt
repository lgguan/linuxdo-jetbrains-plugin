package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.model.TagItem
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.NamespaceFormatter
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel.CategoryItem
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

class AdvancedSearchDialog(
    project: Project,
    initialQuery: String = "",
    private val categories: List<CategoryItem> = emptyList(),
    private val initialCategoryId: Int? = null,
    private val onSearch: (String) -> Unit
) : DialogWrapper(project, true) {

    // Secondary constructors for backward compatibility
    constructor(
        project: Project,
        initialQuery: String = "",
        onSearch: (String) -> Unit
    ) : this(project, initialQuery, emptyList(), null, onSearch)

    constructor(
        project: Project,
        initialQuery: String = "",
        categories: List<CategoryItem> = emptyList(),
        onSearch: (String) -> Unit
    ) : this(project, initialQuery, categories, null, onSearch)

    private val keywordField = JBTextField(initialQuery)
    private val inTitleCheckBox = JBCheckBox("仅搜索话题标题 (in:title)")
    private val categoryCombo = JComboBox<CategoryItem>()

    private val tagField = JBTextField()
    private val tagSuggestionsModel = DefaultListModel<TagItem>()
    private val tagSuggestionsList = JBList(tagSuggestionsModel)
    private var tagPopup: JBPopup? = null
    private val tagAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private val authorField = JBTextField()
    private val orderCombo = JComboBox(arrayOf(
        SortOption("默认相关度 (Relevance)", ""),
        SortOption("最新发布 (Latest Created)", "order:latest"),
        SortOption("最多点赞 (Most Liked)", "order:likes"),
        SortOption("最多浏览 (Most Viewed)", "order:views"),
        SortOption("最新活动 (Latest Activity)", "order:activity")
    ))
    private val timeRangeCombo = JComboBox(arrayOf(
        TimeOption("不限时间 (Any Time)", 0),
        TimeOption("最近 24 小时 (Past 24 Hours)", 1),
        TimeOption("最近 7 天 (Past 7 Days)", 7),
        TimeOption("最近 30 天 (Past 30 Days)", 30),
        TimeOption("最近 1 年 (Past Year)", 365)
    ))
    private val minPostsField = JBTextField()

    private val categoryListener: (List<Category>) -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            populateCategoryComboBox()
        }
    }

    data class SortOption(val label: String, val syntax: String) {
        override fun toString(): String = label
    }

    data class TimeOption(val label: String, val daysAgo: Int) {
        override fun toString(): String = label
    }

    init {
        title = "高级搜索 (Advanced Search) - Linux Do"
        setOKButtonText("搜索 (Search)")
        setCancelButtonText("取消 (Cancel)")

        tagField.emptyText.text = "如: 原创, 快问快答, dev 等 (支持动态联想搜索)"
        authorField.emptyText.text = "如: neo 或用户名"
        minPostsField.emptyText.text = "如: 5"

        setupTagSuggestionsList()

        // Populate hierarchical categories
        populateCategoryComboBox()
        LinuxDoTopicService.getInstance().addCategoryListener(disposable, categoryListener)
        LinuxDoTopicService.getInstance().loadCategories()

        // Preload popular system tags
        LinuxDoTopicService.getInstance().loadPopularTags()

        init()
    }

    override fun dispose() {
        tagAlarm.cancelAllRequests()
        tagPopup?.cancel()
        tagPopup = null
        LinuxDoTopicService.getInstance().removeCategoryListener(categoryListener)
        super.dispose()
    }

    private fun populateCategoryComboBox() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater { populateCategoryComboBox() }
            return
        }
        val prevSelectedId = (categoryCombo.selectedItem as? CategoryItem)?.id ?: initialCategoryId
        categoryCombo.removeAllItems()
        val settings = LinuxDoSettingsState.getInstance()
        val allLabel = if (settings.categoryNamespaceFormat) "All Packages (全部版块)" else "全部版块 (All Categories)"
        categoryCombo.addItem(CategoryItem(null, allLabel, null))

        val service = LinuxDoTopicService.getInstance()
        val hierarchicalList = service.getHierarchicalCategories()

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
            categoryCombo.addItem(CategoryItem(cat.id, displayName, cat.slug, parent?.slug))
            if (prevSelectedId != null && cat.id == prevSelectedId) {
                restoreIndex = idx + 1
            }
        }
        categoryCombo.selectedIndex = restoreIndex
    }

    private fun setupTagSuggestionsList() {
        tagSuggestionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tagSuggestionsList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): java.awt.Component {
                val comp = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is TagItem) {
                    text = if (value.count > 0) "#${value.text} (${value.count} 话题)" else "#${value.text}"
                    icon = AllIcons.Nodes.Tag
                }
                return comp
            }
        }

        tagSuggestionsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selected = tagSuggestionsList.selectedValue
                if (selected != null) {
                    applyTagSelection(selected.text)
                }
            }
        })

        tagField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN) {
                    if (tagPopup?.isVisible == true && tagSuggestionsModel.size() > 0) {
                        val next = (tagSuggestionsList.selectedIndex + 1).coerceAtMost(tagSuggestionsModel.size() - 1)
                        tagSuggestionsList.selectedIndex = next
                        tagSuggestionsList.ensureIndexIsVisible(next)
                        e.consume()
                    } else {
                        showAvailableSystemTags()
                    }
                } else if (e.keyCode == KeyEvent.VK_UP) {
                    if (tagPopup?.isVisible == true && tagSuggestionsModel.size() > 0) {
                        val prev = (tagSuggestionsList.selectedIndex - 1).coerceAtLeast(0)
                        tagSuggestionsList.selectedIndex = prev
                        tagSuggestionsList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                } else if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_TAB) {
                    if (tagPopup?.isVisible == true && tagSuggestionsList.selectedIndex >= 0) {
                        val selected = tagSuggestionsList.selectedValue
                        if (selected != null) {
                            applyTagSelection(selected.text)
                            e.consume()
                        }
                    }
                } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    tagPopup?.cancel()
                }
            }
        })

        tagField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                scheduleTagSearch()
            }
        })
    }

    private fun getCurrentTagToken(): String {
        val full = tagField.text
        val pos = tagField.caretPosition.coerceIn(0, full.length)
        val before = full.take(pos)
        val lastDelim = before.lastIndexOfAny(charArrayOf(',', '，', ' '))
        return if (lastDelim >= 0) before.substring(lastDelim + 1).trim() else before.trim()
    }

    private fun applyTagSelection(tag: String) {
        val cleanTag = tag.trim().replace(Regex("""^#+"""), "")
        val full = tagField.text
        val pos = tagField.caretPosition.coerceIn(0, full.length)
        val before = full.take(pos)
        val after = full.substring(pos)
        val lastDelim = before.lastIndexOfAny(charArrayOf(',', '，', ' '))
        val prefix = if (lastDelim >= 0) before.take(lastDelim + 1).trimEnd() + ", " else ""
        val suffix = if (after.isNotBlank()) " $after" else ""
        tagField.text = "$prefix$cleanTag$suffix"
        tagField.caretPosition = (prefix + cleanTag).length
        tagPopup?.cancel()
        tagField.requestFocusInWindow()
    }

    private fun appendTag(tag: String) {
        val clean = tag.trim().replace(Regex("""^#+"""), "")
        val current = tagField.text.trim()
        if (current.isBlank()) {
            tagField.text = clean
        } else {
            val existing = current.split(Regex("[,，\\s]+")).map { it.trim().replace(Regex("""^#+"""), "") }
            if (clean in existing) {
                val remaining = existing.filter { it != clean }
                tagField.text = remaining.joinToString(", ")
            } else {
                tagField.text = "$current, $clean"
            }
        }
        tagField.requestFocusInWindow()
    }

    private fun scheduleTagSearch() {
        tagAlarm.cancelAllRequests()
        val currentToken = getCurrentTagToken()
        if (currentToken.isBlank()) {
            tagPopup?.cancel()
            return
        }

        tagAlarm.addRequest({
            if (!tagField.isShowing) return@addRequest
            val query = getCurrentTagToken()
            if (query.isBlank()) {
                tagPopup?.cancel()
                return@addRequest
            }

            // 1. Local match from popular system tags
            val service = LinuxDoTopicService.getInstance()
            val allLocal = (service.popularTags.ifEmpty { LinuxDoTopicService.DEFAULT_SYSTEM_TAGS } + LinuxDoTopicService.DEFAULT_SYSTEM_TAGS).distinct()
            val localMatches = allLocal.filter { it.contains(query, ignoreCase = true) }
                .map { TagItem(it, it, 0) }

            if (localMatches.isNotEmpty()) {
                showTagSuggestions(localMatches.take(10))
            }

            // 2. Remote match from Discourse tag search
            service.searchTags(query) { remoteTags ->
                if (getCurrentTagToken() == query) {
                    val combined = (remoteTags + localMatches).distinctBy { it.text }
                    if (combined.isNotEmpty()) {
                        showTagSuggestions(combined.take(12))
                    } else if (localMatches.isEmpty()) {
                        tagPopup?.cancel()
                    }
                }
            }
        }, 200)
    }

    private fun showAvailableSystemTags() {
        if (!tagField.isShowing) return
        val currentToken = getCurrentTagToken()
        val service = LinuxDoTopicService.getInstance()
        val allLocal = (service.popularTags.ifEmpty { LinuxDoTopicService.DEFAULT_SYSTEM_TAGS } + LinuxDoTopicService.DEFAULT_SYSTEM_TAGS).distinct()
        val filtered = if (currentToken.isNotBlank()) {
            allLocal.filter { it.contains(currentToken, ignoreCase = true) }
        } else {
            allLocal
        }
        val items = filtered.take(15).map { TagItem(it, it, 0) }
        showTagSuggestions(items)
    }

    private fun showTagSuggestions(items: List<TagItem>) {
        if (items.isEmpty() || !tagField.isShowing) {
            tagPopup?.cancel()
            return
        }

        tagSuggestionsModel.clear()
        items.forEach { tagSuggestionsModel.addElement(it) }
        tagSuggestionsList.selectedIndex = 0

        if (tagPopup?.isVisible == true) {
            tagPopup?.pack(true, true)
            return
        }

        val scroll = JBScrollPane(tagSuggestionsList).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            preferredSize = Dimension(tagField.width.coerceAtLeast(JBUI.scale(240)), JBUI.scale(150))
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        tagPopup = popup
        popup.show(RelativePoint(tagField, Point(0, tagField.height)))
    }

    private fun createQuickTagsPanel(): JComponent {
        val quickPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
            border = JBUI.Borders.empty(2, 0, 2, 0)
        }
        val tip = JBLabel("热门标签:").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = UIUtil.getContextHelpForeground()
        }
        quickPanel.add(tip)

        val quickTags = listOf("纯水", "快问快答", "软件开发", "人工智能", "求资源", "配置优化", "VPS", "经验分享")
        for (tag in quickTags) {
            val link = ActionLink("#$tag") {
                appendTag(tag)
            }.apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                toolTipText = "点击添加/切换标签 #$tag"
            }
            quickPanel.add(link)
        }

        val moreLink = ActionLink("全部预载标签...") {
            tagField.requestFocusInWindow()
            showAvailableSystemTags()
        }.apply {
            font = font.deriveFont(Font.BOLD, 11f)
            toolTipText = "浏览所有预加载与系统标签"
        }
        quickPanel.add(moreLink)

        return quickPanel
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.preferredSize = Dimension(JBUI.scale(480), JBUI.scale(350))
        panel.border = JBUI.Borders.empty(8, 12)

        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4, 4)
            anchor = GridBagConstraints.WEST
        }

        var row = 0

        // Row 0: Keywords
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0; gbc.gridwidth = 1
        panel.add(JBLabel("搜索关键词:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(keywordField, gbc)

        // Row 1: In Title Checkbox
        row++
        gbc.gridx = 1; gbc.gridy = row; gbc.weightx = 1.0
        panel.add(inTitleCheckBox, gbc)

        // Row 2: Category
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("所属版块:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(categoryCombo, gbc)

        // Row 3: Tag Input & Quick Suggestions
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("指定标签:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        val tagContainer = JPanel(BorderLayout(0, 2)).apply {
            add(tagField, BorderLayout.CENTER)
            add(createQuickTagsPanel(), BorderLayout.SOUTH)
        }
        panel.add(tagContainer, gbc)

        // Row 4: Author
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("发帖作者:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(authorField, gbc)

        // Row 5: Sort order
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("结果排序:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(orderCombo, gbc)

        // Row 6: Time Range
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("时间范围:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(timeRangeCombo, gbc)

        // Row 7: Min posts count
        row++
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
        panel.add(JBLabel("最少回复数:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(minPostsField, gbc)

        return panel
    }

    override fun doOKAction() {
        val queryParts = mutableListOf<String>()

        val kw = keywordField.text.trim()
        if (kw.isNotBlank()) {
            queryParts.add(kw)
        }

        if (inTitleCheckBox.isSelected) {
            queryParts.add("in:title")
        }

        val selectedCat = categoryCombo.selectedItem as? CategoryItem
        if (selectedCat != null && selectedCat.id != null) {
            val slug = selectedCat.slug ?: selectedCat.name
            val parentSlug = selectedCat.parentSlug
            if (!parentSlug.isNullOrBlank()) {
                queryParts.add("#$parentSlug:$slug")
            } else {
                queryParts.add("#$slug")
            }
        }

        val rawTag = tagField.text.trim()
        if (rawTag.isNotBlank()) {
            val tags = rawTag.split(Regex("[,，\\s]+"))
                .map { it.trim().replace(Regex("""^#+"""), "") }
                .filter { it.isNotBlank() }
            if (tags.size == 1) {
                queryParts.add("tag:${tags[0]}")
            } else if (tags.size > 1) {
                queryParts.add("tags:${tags.joinToString(",")}")
            }
        }

        val author = authorField.text.trim().replace(Regex("""^@+"""), "")
        if (author.isNotBlank()) {
            queryParts.add("@$author")
        }

        val sortOption = orderCombo.selectedItem as? SortOption
        if (sortOption != null && sortOption.syntax.isNotBlank()) {
            queryParts.add(sortOption.syntax)
        }

        val timeOption = timeRangeCombo.selectedItem as? TimeOption
        if (timeOption != null && timeOption.daysAgo > 0) {
            val date = LocalDate.now().minusDays(timeOption.daysAgo.toLong())
            queryParts.add("after:${date.format(DateTimeFormatter.ISO_LOCAL_DATE)}")
        }

        val minPosts = minPostsField.text.trim().toIntOrNull()
        if (minPosts != null && minPosts > 0) {
            queryParts.add("min_posts:$minPosts")
        }

        val fullQuery = queryParts.joinToString(" ")
        super.doOKAction()
        if (fullQuery.isNotBlank()) {
            onSearch(fullQuery)
        }
    }
}
