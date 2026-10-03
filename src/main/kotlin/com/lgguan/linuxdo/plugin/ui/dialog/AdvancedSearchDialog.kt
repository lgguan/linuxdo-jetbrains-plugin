package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.*
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.event.DocumentEvent

class AdvancedSearchDialog(
    project: Project,
    initialQuery: String = "",
    private val categories: List<IssueListPanel.CategoryItem> = emptyList(),
    private val initialCategoryId: Int? = null,
    initialTag: String? = null,
    private val onSearch: (String) -> Unit
) : DialogWrapper(project, true) {
    constructor(project: Project, initialQuery: String = "", onSearch: (String) -> Unit) :
        this(project, initialQuery, emptyList(), null, null, onSearch)
    constructor(project: Project, initialQuery: String, categories: List<IssueListPanel.CategoryItem>, onSearch: (String) -> Unit) :
        this(project, initialQuery, categories, null, null, onSearch)

    private val seed = if (initialQuery.isBlank()) listOfNotNull(initialCategoryId?.let { "category:$it" }, initialTag?.let { "tag:$it" }).joinToString(" ")
        else AdvancedSearchQuery.inherit(initialQuery, initialCategoryId, initialTag)
    private var queryModel = AdvancedSearchQuery.parse(seed)
    private val keywordField = JBTextField(queryModel.text)
    private val categoryCombo = ComposerCategoryPicker()
    private val tagSelector = BrowseTagSelector(disposable, TagSelectionField.Mode.SEARCH) { updatePreview() }
    private val allTags = JBCheckBox("全部匹配（取消后任一匹配）", queryModel.filters["tags"]?.contains('+') == true)
    private val authorField = JBTextField(queryModel.filters["author"].orEmpty())
    private val authorMatches = JComboBox<String>().apply { isVisible = false }
    private val userAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val tasks = BackgroundTasks()
    private var userGeneration = 0L
    private var capabilitiesGeneration = 0L
    private var capabilities = SearchCapabilities()
    private var changing = false
    private var categoryTouched = false
    private var statusTouched = false
    private var orderTouched = false
    private val checks = linkedMapOf<String, JBCheckBox>()
    private val dateFields = linkedMapOf("after" to JBTextField(), "before" to JBTextField())
    private val numberFields = linkedMapOf("min_posts" to JBTextField(), "max_posts" to JBTextField(), "min_views" to JBTextField(), "max_views" to JBTextField())
    private data class Option(val value: String, val label: String) { override fun toString() = label }
    private val scopeCombo = JComboBox(arrayOf(Option("", "话题与帖子"), Option("all", "所有内容（含个人消息）"), Option("messages", "个人消息")))
    private val statusCombo = JComboBox<Option>()
    private val orderCombo = JComboBox<Option>()
    private val preview = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3; name = "search-query-preview" }
    private val featureStatus = JBLabel("正在核对论坛搜索选项…")
    private val featureRetry = JButton("重试").apply { isVisible = false; addActionListener { loadCapabilities() } }
    private val tagContainer = JPanel(BorderLayout(0, 5)).apply { add(tagSelector.field); add(allTags, BorderLayout.SOUTH) }
    private val morePanel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val categoryListener: (List<Category>) -> Unit = { populateCategories() }

    init {
        title = "高级搜索 - Linux Do"
        setOKButtonText("搜索"); setCancelButtonText("取消")
        keywordField.emptyText.text = "关键词、引用短语或手写查询语法"
        tagSelector.setSelection(queryModel.filters["tags"]?.split('+', ',').orEmpty())
        (dateFields + numberFields).forEach { (key, field) -> field.text = queryModel.filters[key].orEmpty() }
        dateFields.values.forEach { it.emptyText.text = "YYYY-MM-DD"; it.toolTipText = "日期（YYYY-MM-DD），也兼容手写查询中的距今天数" }
        numberFields.values.forEach { it.emptyText.text = "不限" }
        (AdvancedSearchQuery.publicScopes + AdvancedSearchQuery.personalScopes).forEach { (value, label) ->
            checks["in:$value"] = JBCheckBox(label, queryModel.filters.containsKey("in:$value"))
        }
        checks["with"] = JBCheckBox("含图片", queryModel.filters["with"] == "images")
        mapOf("is:category_expert_question" to "板块专家问题", "with:category_expert_response" to "有专家回应", "without:category_expert_post" to "尚无专家帖子").forEach { (key, label) ->
            checks[key] = JBCheckBox(label, queryModel.filters.containsKey(key))
        }
        populateCategories()
        configureOptions()
        init()
        keywordField.document.addDocumentListener(changeListener())
        (dateFields + numberFields).values.forEach { it.document.addDocumentListener(changeListener()) }
        authorField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { updatePreview(); suggestUsers() }
        })
        authorMatches.addActionListener {
            if (!changing && authorMatches.selectedItem != null) { authorField.text = authorMatches.selectedItem.toString(); authorMatches.isVisible = false }
        }
        categoryCombo.addActionListener { if (!changing) { categoryTouched = true; updatePreview() } }
        scopeCombo.addActionListener { updatePreview() }
        statusCombo.addActionListener { if (!changing) statusTouched = true; updatePreview() }
        orderCombo.addActionListener { if (!changing) orderTouched = true; updatePreview() }
        allTags.addActionListener { updatePreview() }
        checks.values.forEach { it.addActionListener { updatePreview() } }
        LinuxDoTopicService.getInstance().addCategoryListener(disposable, categoryListener)
        LinuxDoTopicService.getInstance().loadCategories()
        LinuxDoAuthService.getInstance().addAuthListener(disposable) { queryModel = collect(); configureOptions(); loadCapabilities() }
        updatePreview(); loadCapabilities()
    }

    private fun changeListener() = object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) = updatePreview() }
    private fun populateCategories() {
        if (isDisposed) return
        changing = true
        val selectedId = (categoryCombo.selectedItem as? CategoryItem)?.id
        val filter = queryModel.filters["category"]
        categoryCombo.removeAllItems(); categoryCombo.addItem(CategoryItem(null, "全部板块", null, null))
        val service = LinuxDoTopicService.getInstance()
        val options = ComposerCategories.options(service.categories.toList(), postingOnly = false).ifEmpty {
            categories.filter { it.id != null }.map { CategoryItem(it.id, it.name, it.slug, null) }
        }
        options.forEach(categoryCombo::addItem)
        val chosen = options.firstOrNull { item ->
            if (categoryTouched) item.id == selectedId else filter?.let { raw ->
                raw == item.id.toString() || raw.removePrefix("#") == item.slug || service.getCategory(item.id)?.let { cat ->
                    val parent = service.getCategory(cat.parentCategoryId)
                    raw == "#${parent?.slug}:${cat.slug}"
                } == true
            } == true
        }
        categoryCombo.selectedItem = chosen ?: categoryCombo.getItemAt(0)
        changing = false
    }
    private fun configureOptions() {
        if (isDisposed) return
        changing = true
        val loggedIn = LinuxDoAuthService.getInstance().isLoggedIn
        checks.forEach { (key, box) -> box.isVisible = when {
            key.removePrefix("in:") in AdvancedSearchQuery.personalScopes -> loggedIn
            key.contains("category_expert") -> capabilities.experts
            else -> true
        } }
        scopeCombo.isVisible = loggedIn
        fun populate(combo: JComboBox<Option>, values: Map<String, String>, current: String?) {
            combo.removeAllItems(); values.forEach { (value, label) -> combo.addItem(Option(value, label)) }
            combo.selectedItem = (0 until combo.itemCount).map { combo.getItemAt(it) }.firstOrNull { it.value == current } ?: combo.getItemAt(0)
        }
        populate(statusCombo, linkedMapOf("" to "不限状态") + AdvancedSearchQuery.statuses.filterKeys { it !in setOf("solved", "unsolved") || capabilities.solved }, queryModel.filters["status"])
        populate(orderCombo, AdvancedSearchQuery.orders.filterKeys { (it != "read" || loggedIn) && (it != "votes" || capabilities.votes) }, queryModel.filters["order"])
        scopeCombo.selectedItem = (0 until scopeCombo.itemCount).map { scopeCombo.getItemAt(it) }.firstOrNull { it.value == queryModel.filters["scope"] } ?: scopeCombo.getItemAt(0)
        tagContainer.isVisible = capabilities.tagging
        changing = false
        morePanel.revalidate(); updatePreview()
    }
    private fun loadCapabilities() {
        val version = SessionEpoch.current
        val request = ++capabilitiesGeneration
        featureStatus.text = "正在核对论坛搜索选项…"; featureRetry.isVisible = false
        tasks.submit {
            val result = runCatching { DiscourseApiClient.searchCapabilities().getOrThrow() }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || version != SessionEpoch.current || request != capabilitiesGeneration) return@invokeLater
                result.onSuccess { queryModel = collect(); capabilities = it; featureStatus.text = ""; configureOptions() }
                    .onFailure { featureStatus.text = "论坛扩展选项读取失败"; featureStatus.toolTipText = IssueListPanel.formatErrorDisplay(it.message.orEmpty()); featureRetry.isVisible = true }
            }, ModalityState.any())
        }
    }
    private fun suggestUsers() {
        userAlarm.cancelAllRequests()
        val request = ++userGeneration
        val version = SessionEpoch.current
        val query = authorField.text.trim().removePrefix("@")
        authorMatches.isVisible = false
        if (query.length < 2 || changing) return
        userAlarm.addRequest({ tasks.submit {
            val result = DiscourseApiClient.searchUsers(query)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || version != SessionEpoch.current || request != userGeneration || query != authorField.text.trim().removePrefix("@")) return@invokeLater
                changing = true; authorMatches.removeAllItems()
                result.getOrNull().orEmpty().forEach(authorMatches::addItem)
                authorMatches.selectedIndex = -1; authorMatches.isVisible = authorMatches.itemCount > 0; changing = false
            }, ModalityState.any())
        } }, 300, ModalityState.any())
    }
    private fun collect(): AdvancedSearchQuery {
        val model = queryModel.copy(text = keywordField.text.trim(), filters = LinkedHashMap(queryModel.filters))
        fun set(key: String, value: String?) { if (value.isNullOrBlank()) model.filters.remove(key) else model.filters[key] = value }
        if (categoryTouched) set("category", (categoryCombo.selectedItem as? CategoryItem)?.id?.toString())
        if (tagContainer.isVisible) set("tags", tagSelector.selection().joinToString(if (allTags.isSelected) "+" else ","))
        set("author", authorField.text.trim().removePrefix("@"))
        checks.forEach { (key, box) -> if (box.isVisible) set(key, if (box.isSelected) when { key == "with" -> "images"; key.startsWith("in:") -> key.removePrefix("in:"); else -> "true" } else null) }
        if (scopeCombo.isVisible) set("scope", (scopeCombo.selectedItem as? Option)?.value)
        if (statusTouched || model.filters["status"] == null || (0 until statusCombo.itemCount).any { statusCombo.getItemAt(it).value == model.filters["status"] })
            set("status", (statusCombo.selectedItem as? Option)?.value)
        if (orderTouched || model.filters["order"] == null || (0 until orderCombo.itemCount).any { orderCombo.getItemAt(it).value == model.filters["order"] })
            set("order", (orderCombo.selectedItem as? Option)?.value)
        (dateFields + numberFields).forEach { (key, field) -> set(key, field.text.trim()) }
        return model
    }
    private fun updatePreview() {
        if (changing || isDisposed) return
        preview.text = collect().query()
    }
    override fun doValidate(): ValidationInfo? {
        val problem = collect().validate() ?: return null
        morePanel.isVisible = true
        val field = dateFields[problem.field] ?: numberFields[problem.field] ?: keywordField
        field.scrollRectToVisible(Rectangle(field.size)); field.requestFocusInWindow()
        return ValidationInfo(problem.message, field)
    }
    override fun doOKAction() {
        val invalid = doValidate()
        if (invalid != null) { setErrorText(invalid.message); return }
        onSearch(collect().query()); super.doOKAction()
    }
    override fun getPreferredFocusedComponent(): JComponent = keywordField
    override fun createCenterPanel(): JComponent {
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 12) }
        fun section(label: String, vararg controls: JComponent): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(4, 0, 8, 0)
            add(JBLabel(label).apply { font = font.deriveFont(Font.BOLD); border = JBUI.Borders.empty(0, 0, 5, 0) })
            controls.forEach { control -> control.alignmentX = Component.LEFT_ALIGNMENT; add(control) }
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val scopeChecks = JPanel(ComposerWrapLayout()).apply { checks.filterKeys { it.removePrefix("in:") in AdvancedSearchQuery.publicScopes || it == "with" }.values.forEach(::add) }
        body.add(section("关键词与范围", keywordField, scopeChecks, scopeCombo))
        body.add(section("板块与标签", categoryCombo, tagContainer))
        body.add(section("作者", authorField, authorMatches))
        val more = JBCheckBox("更多条件", queryModel.filters.keys.any { it in dateFields || it in numberFields || it == "status" || it == "order" || it.contains("category_expert") || it.removePrefix("in:") in AdvancedSearchQuery.personalScopes })
        morePanel.isVisible = more.isSelected
        more.addActionListener { morePanel.isVisible = more.isSelected; morePanel.revalidate() }
        body.add(more)
        val personalChecks = JPanel(ComposerWrapLayout()).apply { checks.filterKeys { it.removePrefix("in:") in AdvancedSearchQuery.personalScopes }.values.forEach(::add) }
        val experts = JPanel(ComposerWrapLayout()).apply { checks.filterKeys { it.contains("category_expert") }.values.forEach(::add) }
        val dates = JPanel(GridLayout(2, 2, JBUI.scale(6), JBUI.scale(4))).apply {
            add(JBLabel("帖子日期晚于")); add(dateFields.getValue("after")); add(JBLabel("帖子日期早于")); add(dateFields.getValue("before"))
        }
        morePanel.add(section("状态与时间", personalChecks, statusCombo, dates, experts))
        val counts = JPanel(GridLayout(2, 3, JBUI.scale(6), JBUI.scale(4))).apply {
            add(JBLabel("帖子数（含首楼）")); add(numberFields.getValue("min_posts")); add(numberFields.getValue("max_posts"))
            add(JBLabel("话题浏览量")); add(numberFields.getValue("min_views")); add(numberFields.getValue("max_views"))
        }
        numberFields.filterKeys { it.startsWith("min_") }.values.forEach { it.toolTipText = "下限（含）" }
        numberFields.filterKeys { it.startsWith("max_") }.values.forEach { it.toolTipText = "上限（含）" }
        morePanel.add(section("数量与排序", counts, orderCombo))
        body.add(morePanel)
        body.add(section("查询预览", JBScrollPane(preview)))
        body.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("重置").apply { addActionListener {
                changing = true; queryModel = AdvancedSearchQuery(); keywordField.text = ""; authorField.text = ""
                (dateFields + numberFields).values.forEach { it.text = "" }; checks.values.forEach { it.isSelected = false }
                tagSelector.setSelection(emptyList()); allTags.isSelected = false; categoryTouched = true; categoryCombo.selectedIndex = 0
                changing = false; configureOptions(); updatePreview()
            } })
            add(JButton("复制查询").apply { addActionListener { CopyPasteManager.getInstance().setContents(StringSelection(collect().query())) } })
        })
        body.add(JPanel(BorderLayout()).apply { add(featureStatus); add(featureRetry, BorderLayout.EAST) })
        body.components.filterIsInstance<JComponent>().forEach { it.alignmentX = Component.LEFT_ALIGNMENT }
        return JBScrollPane(body).apply {
            preferredSize = Dimension(JBUI.scale(590), JBUI.scale(610)); border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            DialogTheme.follow(disposable, this)
        }
    }
    override fun dispose() { tasks.dispose(); userAlarm.cancelAllRequests(); super.dispose() }
}
