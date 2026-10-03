package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.model.ComposerCapabilities
import com.lgguan.linuxdo.plugin.model.PublishOutcome
import com.lgguan.linuxdo.plugin.model.UnconfirmedPublishException

import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.common.PlatformShortcuts
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.TagItem
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.lgguan.linuxdo.plugin.theme.NamespaceFormatter
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.*
import java.io.File
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.html.HTMLEditorKit

class CreateTopicDialog private constructor(
    private val project: Project,
    private val initialCategoryId: Int? = null,
    private val onTopicCreated: ((Post) -> Unit)?,
    private val draftSession: ForumDraftSession,
    private val environment: TopicComposerEnvironment,
    private val requireExistingDraft: Boolean = false
) : DialogWrapper(project, true) {

    constructor(project: Project, initialCategoryId: Int? = null, onTopicCreated: ((Post) -> Unit)? = null) :
        this(project, initialCategoryId, onTopicCreated, ForumDraftSession(ForumDraftSession.NEW_TOPIC_KEY), ForumTopicComposerEnvironment)
    companion object {
        private val editors = mutableMapOf<String, CreateTopicDialog>()
        fun open(project: Project, initialCategoryId: Int? = null, onTopicCreated: ((Post) -> Unit)? = null) {
            openEditor(project, initialCategoryId, onTopicCreated, ForumDraftSession(ForumDraftSession.NEW_TOPIC_KEY), ForumTopicComposerEnvironment)
        }
        fun openDraft(project: Project, draftKey: String) {
            require(draftKey.matches(Regex("new_topic(?:_[A-Za-z0-9_-]+)?")))
            openEditor(project, null, null, ForumDraftSession(draftKey), ForumTopicComposerEnvironment, true)
        }
        private fun openEditor(project: Project, initialCategoryId: Int?, onTopicCreated: ((Post) -> Unit)?,
            session: ForumDraftSession, environment: TopicComposerEnvironment, requireExisting: Boolean = false): CreateTopicDialog {
            val registry = "${DiscourseApiClient.getBaseUrl()}:${com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().currentUser?.id}:${session.version}:${session.key}"
            val existing = editors[registry]
            if (existing != null && !existing.isDisposed) {
                existing.window?.toFront()
                return existing
            }
            return CreateTopicDialog(project, initialCategoryId, onTopicCreated, session, environment, requireExisting).also { dialog ->
                dialog.registryKey = registry
                editors[registry] = dialog
                dialog.show()
            }
        }
        internal fun openForTesting(project: Project, session: ForumDraftSession, environment: TopicComposerEnvironment) =
            openEditor(project, null, null, session, environment)
        internal fun forTesting(project: Project, session: ForumDraftSession, environment: TopicComposerEnvironment) =
            CreateTopicDialog(project, null, null, session, environment)
    }
    private var registryKey: String? = null
    private var capabilities = ComposerCapabilities()
    private var categories = emptyList<Category>()
    private var categoriesReady = false
    private var restoredCategory: Int? = null
    private val tagMetadata = mutableMapOf<String, TagItem>()
    private val invalidTags = mutableMapOf<String, String>()
    private val tagStatus = JBLabel()
    private var tagHint: com.lgguan.linuxdo.plugin.model.TagHint = com.lgguan.linuxdo.plugin.model.TagHint.Hidden
    private var tagCheckFailed = false
    private val tagRetry = JButton("重试检查").apply { isVisible = false; addActionListener { validateSelectedTags() } }
    private val tagHintPanel = JPanel(BorderLayout(4, 0)).apply { add(tagStatus); add(tagRetry, BorderLayout.EAST); isVisible = false }
    private var tagGeneration = 0L
    private var validatingTags = false
    private var tagValidationGeneration = 0L
    private var draftReady = false
    private var draftBusy = false
    private var draftBlocked = false
    private var publishing = false
    private var unconfirmed = false
    private var suppressDraft = false
    private var pendingAction: (() -> Unit)? = null
    private val draftAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val draftStatus = JBLabel("正在读取论坛草稿…")
    private val publishCheck = JButton("检查发布结果").apply {
        isVisible = false
        addActionListener { checkPublishResult() }
    }
    private val draftRetry = JButton("重试同步")
    private var baseline = TopicDraftContent()

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val previewView by lazy { ComposerPreviewView(backgroundTasks, { isDisposed }) }
    private val editorSupport by lazy {
        ComposerEditorSupport(project, textArea, previewView, statusLabel, backgroundTasks, { isDisposed }, ::onContentChanged,
            disposable, ::togglePreview, ::showPreview)
    }
    private val titleField = JBTextField()
    private val titleCounterLabel = JBLabel("0 / 6")

    private val categoryComboBox = ComposerCategoryPicker()

    // Tags dynamic search components
    private val tagInputField = JBTextField(12)
    private val tagField = TagSelectionField(TagSelectionField.Mode.COMPOSE, ::removeTag)
    private val tagSelectButton = tagField.picker
    private val tagPickerStatus = JBLabel()
    private val tagsPanel = tagField.chips
    private val selectedTags = linkedSetOf<String>()

    // Searchable tag checklist keeps multiple selections in the same popup.
    private val tagSuggestionsModel = DefaultListModel<TagItem>()
    private val tagSuggestionsList = JBList(tagSuggestionsModel)
    private var tagPopup: JBPopup? = null
    private val tagAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val tagValidationAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private val textArea = JBTextArea(14, 50)
    private val bodyCounterLabel = JBLabel("0 / 20 勿用各类字数补丁")

    // Live preview: High performance, styled JEditorPane honoring IDE theme
    private var previewPane: JEditorPane? = null
    private val previewAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val splitter = JBSplitter(false, 0.52f)
    private var isPreviewVisible = false
    private var previewContainer: JComponent? = null

    private val statusLabel = object : JBLabel("⚪ 就绪") {
        override fun getPreferredSize(): Dimension {
            val d = super.getPreferredSize()
            return Dimension(d.width.coerceAtMost(JBUI.scale(420)), d.height.coerceAtMost(JBUI.scale(24)))
        }
        override fun getMaximumSize(): Dimension {
            return Dimension(JBUI.scale(420), JBUI.scale(24))
        }
    }

    init {
        setModal(false)
        title = "创建话题 - Linux Do"
        setOKButtonText("创建话题")
        setCancelButtonText("舍弃")
        init()
        loadCategoriesAndTags()
        environment.authListener(disposable) {
            if (!isDisposed && draftSession.version != SessionEpoch.current) {
                draftBlocked = true
                draftAlarm.cancelAllRequests()
                tagAlarm.cancelAllRequests()
                tagValidationAlarm.cancelAllRequests()
                tagPopup?.cancel()
                categoryComboBox.hidePopup()
                previewView.invalidate()
                imageUpload.cancel()
                draftStatus.text = "账号已切换，同步已停止；内容仅保留在此窗口"
                updateValidation()
            }
        }
        draftRetry.addActionListener { if (draftSession.conflicted) resolveConflict() else if (!draftReady) loadDraft() else saveDraft() }
        loadDraft()
        updateValidation()
        updatePreview()
    }

    override fun beforeShowCallback() {
        super.beforeShowCallback()
        window?.minimumSize = Dimension(320, 460)
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 8))
        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.preferredSize = Dimension(JBUI.scale(1040), JBUI.scale(700))

        val scheme = EditorColorsManager.getInstance().globalScheme
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val editorFont = UIUtil.getFontWithFallback(Font(theme.fontName, Font.PLAIN, theme.fontSize))

        // 1. Top IDE Native Information Banner
        val bannerPanel = createBannerPanel()
        rootPanel.add(bannerPanel, BorderLayout.NORTH)

        // 2. Middle Form Stack
        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

        // Row 1: Title Input + 0/6 Counter
        val titleRow = JPanel(BorderLayout(8, 0))
        titleField.font = editorFont
        titleField.emptyText.text = "输入标题，或在此处粘贴链接"
        titleCounterLabel.font = titleCounterLabel.font.deriveFont(Font.BOLD, 12f)
        titleCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        titleRow.add(titleField, BorderLayout.CENTER)
        titleRow.add(titleCounterLabel, BorderLayout.EAST)
        formPanel.add(titleRow)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(6)))

        // Row 2: Category Dropdown + Tags Dynamic Search
        categoryComboBox.preferredSize = Dimension(0, JBUI.scale(28))
        tagInputField.emptyText.text = "搜索标签…"
        tagInputField.name = "composer-tag-search"
        val tagColumn = JPanel(BorderLayout(0, 3)).apply { add(tagField); add(tagHintPanel, BorderLayout.SOUTH) }
        formPanel.add(ResponsiveFilterRow(categoryComboBox, tagColumn))

        // Tags Chip Panel (Compact, visible only when tags exist)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        textArea.font = editorFont
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = scheme.defaultBackground
        textArea.foreground = scheme.defaultForeground
        textArea.emptyText.text = "输入正文，支持 Markdown；可拖放或粘贴图片。"

        val editorPanel = ComposerAppearance.editor(textArea, createFormattingToolbar(), bodyCounterLabel)

        // Setup live preview component (Styled JEditorPane matching IDE theme)
        previewPane = previewView.fallbackPane
        val previewScroll = previewView.component
        previewContainer = previewScroll

        splitter.firstComponent = editorPanel
        splitter.secondComponent = null
        splitter.setHonorComponentsMinimumSize(true)
        splitter.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                val vertical = splitter.width < JBUI.scale(620)
                if (splitter.orientation != vertical) splitter.orientation = vertical
            }
        })

        val centerStack = JPanel(BorderLayout(0, 6))
        centerStack.add(formPanel, BorderLayout.NORTH)
        centerStack.add(splitter, BorderLayout.CENTER)

        rootPanel.add(centerStack, BorderLayout.CENTER)

        setupTagSuggestionsList()
        setupEventListeners()
        ComposerAppearance.followTheme(disposable, rootPanel, textArea, editorPanel, titleField) {
            updateValidation()
            setTagHint(tagHint)
            tagField.render(selectedTags, invalidTags)
            previewView.refreshTheme()
        }
        schedulePreviewUpdate()

        return rootPanel
    }

    private fun createBannerPanel(): JPanel {
        val banner = JPanel(BorderLayout()).apply {
            background = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(6, 10)
            )
        }

        val infoBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.General.Information))
            val tipLabel = JBLabel("请在发帖前仔细阅读社区准则：").apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                foreground = JBColor.namedColor("ContextHelp.foreground", UIUtil.getContextHelpForeground())
            }
            add(tipLabel)
            val actionLink = ActionLink("《社区准则》") {
                BrowserUtil.browse("https://linux.do/guidelines")
            }.apply {
                font = font.deriveFont(Font.PLAIN, 12f)
            }
            add(actionLink)
        }

        banner.add(infoBox, BorderLayout.NORTH)
        banner.add(JPanel(BorderLayout()).apply {
            add(draftStatus)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                add(draftRetry)
                add(JButton("重新加载版块").apply { addActionListener { if (!publishing) loadCategoriesAndTags() } })
            }, BorderLayout.EAST)
            toolTipText = "仅同步至论坛，不在本机保存正文或标题；重启恢复依赖已同步草稿"
        }, BorderLayout.SOUTH)
        return banner
    }

    private fun createFormattingToolbar(): JComponent = editorSupport.toolbar

    override fun createSouthPanel(): JComponent {
        val southPanel = JPanel(BorderLayout(16, 0)).apply {
            border = JBUI.Borders.empty(8, 16, 14, 16)
        }

        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 12f)
        val statusContainer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(JBUI.scale(450), JBUI.scale(32))
            add(statusLabel)
            add(publishCheck)
        }
        southPanel.add(statusContainer, BorderLayout.CENTER)

        val rightActionPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
        }
        val okBtn = createJButtonForAction(okAction).apply {
            icon = AllIcons.Actions.Commit
        }
        val cancelBtn = createJButtonForAction(cancelAction)

        rightActionPanel.add(okBtn)
        rightActionPanel.add(cancelBtn)
        southPanel.add(rightActionPanel, BorderLayout.EAST)

        return southPanel
    }

    private fun setupTagSuggestionsList() {
        tagField.followPopup { tagPopup }
        tagSuggestionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tagSuggestionsList.fixedCellHeight = JBUI.scale(34)
        tagSuggestionsList.cellRenderer = object : ListCellRenderer<TagItem> {
            // CellRendererPane retains renderer components: allocate one row, not one per paint.
            private val badge = JBLabel().apply { border = JBUI.Borders.empty(2, 6) }
            private val count = JBLabel()
            private val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
                border = JBUI.Borders.empty(0, 4)
                add(badge); add(count)
            }
            override fun getListCellRendererComponent(
                list: JList<out TagItem>,
                value: TagItem,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                row.background = if (isSelected) list.selectionBackground else list.background
                badge.text = value.text
                badge.isOpaque = true
                badge.background = JBColor(0xEAEEF2, 0x34383D)
                badge.foreground = list.foreground
                badge.isEnabled = !value.disabled && selectedTags.size < capabilities.maxTags
                count.text = if (value.disabled) value.title ?: "此板块不可用" else if (value.count > 0) "×${value.count}" else ""
                count.foreground = if (isSelected) list.selectionForeground else UIUtil.getContextHelpForeground()
                row.toolTipText = value.title ?: value.text
                return row
            }
        }

        tagSuggestionsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = tagSuggestionsList.locationToIndex(e.point)
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && tagSuggestionsList.getCellBounds(index, index)?.contains(e.point) == true) {
                    toggleTag(tagSuggestionsModel[index])
                }
            }
        })
    }

    private fun setupEventListeners() {
        titleField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onContentChanged()
            override fun removeUpdate(e: DocumentEvent?) = onContentChanged()
            override fun changedUpdate(e: DocumentEvent?) = onContentChanged()
        })
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onContentChanged()
            override fun removeUpdate(e: DocumentEvent?) = onContentChanged()
            override fun changedUpdate(e: DocumentEvent?) = onContentChanged()
        })

        categoryComboBox.addActionListener {
            if (!suppressDraft) {
                categoryComboBox.hidePopup()
                restoredCategory = null
                setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Hidden)
                val category = categories.firstOrNull { it.id == (categoryComboBox.selectedItem as? CategoryItem)?.id }
                if (textArea.text.isBlank() && baseline.empty && category?.topicTemplate?.isNotBlank() == true) textArea.text = category.topicTemplate
                validateSelectedTags()
                scheduleTagSearch()
                onContentChanged()
            }
        }

        // Dynamic Tag Search with Alarm debounce
        tagInputField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleTagSearch()
            override fun removeUpdate(e: DocumentEvent?) = scheduleTagSearch()
            override fun changedUpdate(e: DocumentEvent?) = scheduleTagSearch()
        })

        val tagKeys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN) {
                    if (tagSuggestionsModel.size() > 0) {
                        val next = (tagSuggestionsList.selectedIndex + 1).coerceAtMost(tagSuggestionsModel.size() - 1)
                        tagSuggestionsList.selectedIndex = next
                        tagSuggestionsList.ensureIndexIsVisible(next)
                        e.consume()
                    }
                } else if (e.keyCode == KeyEvent.VK_UP) {
                    if (tagPopup?.isVisible == true && tagSuggestionsModel.size() > 0) {
                        val prev = (tagSuggestionsList.selectedIndex - 1).coerceAtLeast(0)
                        tagSuggestionsList.selectedIndex = prev
                        tagSuggestionsList.ensureIndexIsVisible(prev)
                        e.consume()
                    }
                } else if (e.keyCode == KeyEvent.VK_ENTER || (e.keyCode == KeyEvent.VK_SPACE && e.component === tagSuggestionsList)) {
                    if (tagPopup?.isVisible == true && tagSuggestionsList.selectedIndex >= 0) {
                        val selected = tagSuggestionsList.selectedValue
                        if (selected != null) {
                            toggleTag(selected)
                            e.consume()
                            return
                        }
                    }
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_BACK_SPACE && e.component === tagInputField && tagInputField.text.isEmpty()) {
                    selectedTags.lastOrNull()?.let { removeTag(it) }
                    e.consume()
                } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    tagPopup?.cancel()
                    e.consume()
                }
            }
        }
        tagInputField.addKeyListener(tagKeys)
        tagSuggestionsList.addKeyListener(tagKeys)
        tagField.openPopup = { showTagPopup() }
        tagField.currentPopup = { tagPopup }

        // The IDE paste action is the sole keyboard paste handler, including custom keymaps.
        com.intellij.openapi.project.DumbAwareAction.create {
            if (!handleClipboardImagePaste()) {
                pasteClipboardText()
            }
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CommonShortcuts.getPaste(), textArea, disposable)

        // 2. Right-click context menu for textArea
        val contextMenu = JPopupMenu().apply {
            val cutItem = JMenuItem("剪切").apply { addActionListener { textArea.cut() } }
            val copyItem = JMenuItem("复制").apply { addActionListener { textArea.copy() } }
            val pasteItem = JMenuItem("粘贴 / 上传图片 (${PlatformShortcuts.pasteLabel})").apply {
                font = font.deriveFont(Font.BOLD)
                addActionListener {
                    if (!handleClipboardImagePaste()) {
                        pasteClipboardText()
                    }
                }
            }
            val uploadItem = JMenuItem("选择并上传图片...").apply {
                icon = AllIcons.Actions.Upload
                addActionListener { chooseAndUploadImage() }
            }
            add(cutItem)
            add(copyItem)
            add(pasteItem)
            addSeparator()
            add(uploadItem)
        }
        textArea.componentPopupMenu = contextMenu

        // Submit uses the host's primary modifier. Paste is handled by the IDE action above.
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (PlatformShortcuts.isSubmit(e)) {
                    if (isOKActionEnabled) {
                        doOKAction()
                    }
                    e.consume()
                }
            }
        })

        // 4. TransferHandler for Drag & Drop and Standard Paste
        val defaultTransferHandler = textArea.transferHandler
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                if (support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                    support.isDataFlavorSupported(DataFlavor.stringFlavor)
                ) {
                    return true
                }
                return defaultTransferHandler?.canImport(support) ?: super.canImport(support)
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!textArea.isEnabled) return false
                if (handleTransferableImageImport(support.transferable)) {
                    return true
                }
                val text = try {
                    support.transferable.getTransferData(DataFlavor.stringFlavor) as? String
                } catch (_: Throwable) { null }
                if (!text.isNullOrEmpty()) {
                    textArea.replaceSelection(text)
                    onContentChanged()
                    return true
                }
                return defaultTransferHandler?.importData(support) ?: super.importData(support)
            }
        }
    }

    private fun pasteClipboardText() {
        if (!textArea.isEnabled) return
        val text = try {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
                ?: Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Throwable) { null }
        if (!text.isNullOrEmpty()) {
            textArea.replaceSelection(text)
            onContentChanged()
        }
    }

    private fun scheduleTagSearch(clearResults: Boolean = true) {
        tagAlarm.cancelAllRequests()
        val request = ++tagGeneration
        if (tagPopup?.isVisible != true || draftSession.version != SessionEpoch.current) return
        tagPickerStatus.text = "正在读取标签…"
        if (clearResults) tagSuggestionsModel.clear()
        tagAlarm.addRequest({
            val query = tagInputField.text.trim()
            val category = (categoryComboBox.selectedItem as? CategoryItem)?.id
            val selected = selectedTags.mapNotNull { tagMetadata[it]?.id }
            val version = draftSession.version
            backgroundTasks.submit {
                val result = runCatching { environment.tags(query, category, selected) }
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed || tagPopup?.isVisible != true || request != tagGeneration || version != SessionEpoch.current || category != (categoryComboBox.selectedItem as? CategoryItem)?.id) return@invokeLater
                    result.onSuccess { response ->
                        tagPickerStatus.toolTipText = null
                        response.results.forEach { tagMetadata[it.text] = it }
                        // Composer hints are owned by validation, not a concurrent suggestion request.
                        showTagSuggestions(response.results.distinctBy { it.text }.filter { it.text !in selectedTags })
                        tagPickerStatus.text = when {
                            response.forbidden -> response.forbiddenMessage ?: "此板块不允许这些标签"
                            tagSuggestionsModel.isEmpty -> "没有匹配的标签；可修改搜索词"
                            else -> "已选 ${selectedTags.size} / ${capabilities.maxTags}；点击添加，× 移除"
                        }
                    }.onFailure { error ->
                        tagPickerStatus.text = tagFailureMessage(error)
                        tagPickerStatus.toolTipText = ComposerErrors.parse(error)
                    }
                }, ModalityState.any())
            }
        }, 250, ModalityState.any())
    }

    private fun validateSelectedTags() {
        tagValidationAlarm.cancelAllRequests()
        val generation = ++tagValidationGeneration
        val category = (categoryComboBox.selectedItem as? CategoryItem)?.id
        val snapshot = selectedTags.toList()
        val metadata = tagMetadata.toMap()
        val version = draftSession.version
        invalidTags.clear()
        renderTagChips()
        tagCheckFailed = false
        setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Hidden)
        if (version != SessionEpoch.current) { validatingTags = false; updateValidation(); return }
        if (category == null) { validatingTags = false; updateValidation(); return }
        validatingTags = true
        setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Checking)
        updateValidation()
        tagValidationAlarm.addRequest({ backgroundTasks.submit {
            val result = runCatching {
                val context = environment.tags("", category, snapshot.mapNotNull { metadata[it]?.id })
                val known = metadata.toMutableMap().apply { context.results.forEach { put(it.text, it) } }
                val results = snapshot.associateWith { name ->
                    val response = environment.tags(name, category, snapshot.filter { it != name }.mapNotNull { known[it]?.id })
                    (response.results.firstOrNull { it.text == name || it.name == name } ?: error("标签「$name」不可用"))
                        .also { known[name] = it }
                }
                // Recheck the outstanding group after resolving restored tag IDs.
                environment.tags("", category, results.values.map { it.id }).requiredTagGroup to results
            }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || generation != tagValidationGeneration || version != SessionEpoch.current || category != (categoryComboBox.selectedItem as? CategoryItem)?.id || snapshot != selectedTags.toList()) return@invokeLater
                validatingTags = false
                result.onSuccess { (required, results) ->
                    results.forEach { (name, tag) -> tagMetadata[name] = tag; if (tag.disabled) invalidTags[name] = tag.title ?: "不能用于此类别" }
                    setTagHint(when {
                        invalidTags.isNotEmpty() -> com.lgguan.linuxdo.plugin.model.TagHint.Warning("请修正不可用的标签", invalidTags.entries.joinToString("；") { "${it.key}：${it.value}" })
                        required != null -> com.lgguan.linuxdo.plugin.model.TagHint.Required(required)
                        (categories.firstOrNull { it.id == category }?.minimumRequiredTags ?: 0) > snapshot.size ->
                            com.lgguan.linuxdo.plugin.model.TagHint.Required(com.lgguan.linuxdo.plugin.model.RequiredTagGroup("此板块", categories.first { it.id == category }.minimumRequiredTags))
                        else -> com.lgguan.linuxdo.plugin.model.TagHint.Hidden
                    })
                }.onFailure { error ->
                    tagCheckFailed = true
                    snapshot.forEach { invalidTags[it] = "尚未确认，请重试标签检查" }
                    setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Warning(tagFailureMessage(error), ComposerErrors.parse(error)))
                }
                renderTagChips()
                tagSuggestionsList.repaint()
                updateValidation()
            }, ModalityState.any())
        } }, 250, ModalityState.any())
    }

    private fun tagFailureMessage(error: Throwable) = when (error) {
        is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException -> "Cloudflare 人机验证后重试（侧边栏登录/验证）"
        is com.lgguan.linuxdo.plugin.net.RateLimitException -> "论坛请求频率限制；冷却结束后重试"
        else -> "标签读取失败；点击重试或修改搜索词"
    }

    private fun setTagHint(hint: com.lgguan.linuxdo.plugin.model.TagHint) {
        tagHint = hint
        tagStatus.text = when (hint) {
            com.lgguan.linuxdo.plugin.model.TagHint.Hidden -> ""
            com.lgguan.linuxdo.plugin.model.TagHint.Checking -> "正在检查标签…"
            is com.lgguan.linuxdo.plugin.model.TagHint.Required -> "至少 ${hint.group.minCount} 个「${hint.group.name}」标签"
            is com.lgguan.linuxdo.plugin.model.TagHint.Warning -> hint.message
        }
        tagStatus.toolTipText = (hint as? com.lgguan.linuxdo.plugin.model.TagHint.Warning)?.detail
        tagStatus.icon = if (hint is com.lgguan.linuxdo.plugin.model.TagHint.Warning) AllIcons.General.Warning else null
        tagStatus.foreground = UIUtil.getContextHelpForeground()
        tagRetry.isVisible = hint is com.lgguan.linuxdo.plugin.model.TagHint.Warning
        tagHintPanel.isVisible = hint != com.lgguan.linuxdo.plugin.model.TagHint.Hidden
        tagHintPanel.revalidate()
    }

    private fun showTagSuggestions(items: List<TagItem>) {
        val previous = tagSuggestionsList.selectedValue?.text
        tagSuggestionsModel.clear()
        tagSuggestionsModel.addAll(items)
        tagSuggestionsList.selectedIndex = items.indexOfFirst { it.text == previous }.takeIf { it >= 0 } ?: if (items.isEmpty()) -1 else 0
    }

    private fun showTagPopup() {
        if (!tagSelectButton.isEnabled || tagPopup?.isVisible == true || draftSession.version != SessionEpoch.current) return
        tagSuggestionsModel.clear()
        tagInputField.text = ""
        tagPickerStatus.text = "正在读取标签…"
        val popupWidth = tagField.width.coerceAtLeast(JBUI.scale(390))
        val content = object : JPanel(BorderLayout(0, 0)) {
            override fun getPreferredSize(): Dimension = super.getPreferredSize().apply {
                width = popupWidth + insets.left + insets.right
            }
        }.apply {
            border = JBUI.Borders.empty(4)
            add(JBScrollPane(tagSuggestionsList).apply {
                border = JBUI.Borders.empty()
                preferredSize = Dimension(popupWidth, JBUI.scale(240))
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(0, 6)).apply {
                border = JBUI.Borders.emptyTop(6)
                add(tagsPanel, BorderLayout.NORTH)
                add(tagInputField, BorderLayout.CENTER)
                add(JPanel(BorderLayout(6, 0)).apply {
                    add(tagPickerStatus, BorderLayout.CENTER)
                    add(JButton("重试").apply { addActionListener { scheduleTagSearch(); validateSelectedTags() } }, BorderLayout.EAST)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        DialogTheme.refresh(content, includeWindow = false)
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, tagInputField)
            .setRequestFocus(true)
            .setFocusable(true)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        tagPopup = popup
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (tagPopup === popup) {
                    tagPopup = null
                    tagSelectButton.repaint()
                    ++tagGeneration
                    tagAlarm.cancelAllRequests()
                }
            }
        })
        popup.show(RelativePoint(tagField, Point(0, tagField.height)))
        scheduleTagSearch()
        if (invalidTags.isNotEmpty()) validateSelectedTags()
    }

    private fun toggleTag(tag: TagItem) {
        if (!textArea.isEnabled) return
        if (tag.text in selectedTags) removeTag(tag.text) else addTag(tag.text)
        tagSuggestionsList.repaint()
        if (tag.text in selectedTags) {
            val index = (0 until tagSuggestionsModel.size()).firstOrNull { tagSuggestionsModel[it].text == tag.text }
            if (index != null) tagSuggestionsModel.remove(index)
        }
        tagPickerStatus.text = if (tag.disabled && tag.text !in selectedTags) tag.title ?: "此板块不可用" else "已选 ${selectedTags.size} / ${capabilities.maxTags}；点击添加，× 移除"
        tagPopup?.pack(false, true)
        if (tag.text in selectedTags) scheduleTagSearch(clearResults = false)
    }

    private fun removeTag(tag: String) {
        if (!textArea.isEnabled) return
        selectedTags.remove(tag)
        invalidTags.remove(tag)
        validateSelectedTags()
        onContentChanged()
        renderTagChips()
        tagSuggestionsList.repaint()
        updateValidation()
        if (tagPopup?.isVisible == true) scheduleTagSearch(clearResults = false)
    }

    private fun sanitizeTag(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("name:").removePrefix("name：").removePrefix("-").trim()
        t = t.trim('"', '\'', '`', '#', ',', '，', '“', '”', '‘', '’', ' ')
        return t
    }

    private fun addTag(rawTag: String) {
        if (!textArea.isEnabled) return
        val clean = sanitizeTag(rawTag)
        if (clean.isNotBlank()) {
            if (selectedTags.size >= capabilities.maxTags) {
                statusLabel.text = "⚠️ 最多只能添加 ${capabilities.maxTags} 个标签"
                return
            }
            if (!selectedTags.contains(clean)) {
                val tag = tagMetadata[clean]
                if (tag?.disabled == true) { setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Warning(tag.title ?: "不能用于此类别")); return }
                selectedTags.add(clean)
                validateSelectedTags()
                onContentChanged()
                renderTagChips()
                updateValidation()
            }
        }
    }

    private fun renderTagChips() {
        tagField.render(selectedTags, invalidTags)
        tagPopup?.pack(false, true)
    }

    private fun onContentChanged() {
        updateValidation()
        schedulePreviewUpdate()
        if (!suppressDraft && draftReady && !draftBlocked && !draftSession.conflicted && !publishing && snapshot() != baseline) {
            draftStatus.text = "尚未同步；停止输入 2 秒后保存"
            draftAlarm.cancelAllRequests()
            draftAlarm.addRequest({ saveDraft() }, 2000, ModalityState.any())
        }
    }
    private fun snapshot() = TopicDraftContent(titleField.text, textArea.text, (categoryComboBox.selectedItem as? CategoryItem)?.id ?: restoredCategory, selectedTags.toList())
    private fun setEditable(enabled: Boolean) {
        titleField.isEnabled = enabled; textArea.isEnabled = enabled; categoryComboBox.isEnabled = enabled
        tagInputField.isEnabled = enabled; tagField.isEnabled = enabled
        tagsPanel.components.filterIsInstance<JPanel>().flatMap { it.components.toList() }.filterIsInstance<JButton>().forEach { it.isEnabled = enabled }
        if (!enabled) tagPopup?.cancel()
    }
    private fun updateValidation() {
        if (isDisposed) return
        publishCheck.isVisible = unconfirmed
        val content = snapshot()
        val category = categories.firstOrNull { it.id == content.categoryId }
        val titleValid = capabilities.titleValid(content.title)
        val bodyValid = capabilities.bodyValid(content.body, true)
        titleCounterLabel.text = "${content.title.trim().length} / ${capabilities.minTitle}–${capabilities.maxTitle}"
        bodyCounterLabel.text = "${content.body.trim().length} / ${capabilities.minTopicBody}–${capabilities.maxBody} 字符"
        titleCounterLabel.foreground = if (titleValid) UIUtil.getContextHelpForeground() else JBColor.RED
        bodyCounterLabel.foreground = if (bodyValid) UIUtil.getContextHelpForeground() else JBColor.RED
        isOKActionEnabled = titleValid && bodyValid && categoriesReady && category?.permission == 1 && environment.loggedIn &&
            content.tags.size in (category.minimumRequiredTags)..capabilities.maxTags && invalidTags.isEmpty() && !validatingTags && !tagCheckFailed &&
            tagHint !is com.lgguan.linuxdo.plugin.model.TagHint.Required &&
            draftReady && !draftBusy && !draftBlocked && !draftSession.conflicted && !publishing && !unconfirmed &&
            !capabilities.readOnly && imageUpload.pending == 0 && imageUpload.failures == 0 && draftSession.version == SessionEpoch.current
        statusLabel.text = when {
            publishing -> "正在提交，请等待结果"
            unconfirmed -> "发布结果未确认，请先在网页检查"
            imageUpload.failures > 0 -> "图片上传失败，请重试或舍弃失败图片"
            imageUpload.pending > 0 -> "还有 ${imageUpload.pending} 张图片正在上传"
            draftBlocked -> "同步已停止；输入内容已保留"
            !categoriesReady -> "版块尚未加载，请重新加载"
            category?.permission != 1 -> "请选择可发帖版块"
            capabilities.readOnly -> "论坛当前限制发帖"
            !titleValid -> "标题需要 ${capabilities.minTitle}–${capabilities.maxTitle} 个字符"
            !bodyValid -> "正文需要 ${capabilities.minTopicBody}–${capabilities.maxBody} 个字符"
            validatingTags -> "正在检查标签…"
            invalidTags.isNotEmpty() -> "请修正不可用的标签"
            tagHint is com.lgguan.linuxdo.plugin.model.TagHint.Required -> tagStatus.text
            else -> "${if (capabilities.confirmed) "就绪" else "论坛设置未确认，将由服务器校验"} (${PlatformShortcuts.submitLabel} 发布)"
        }
    }
    private fun loadCategoriesAndTags() {
        categoriesReady = false
        updateValidation()
        val version = draftSession.version
        backgroundTasks.submit {
            val result = runCatching { environment.categories() to environment.capabilities() }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || version != SessionEpoch.current) return@invokeLater
                result.onSuccess { (loaded, rules) ->
                    categories = ComposerCategories.flatten(loaded); capabilities = rules; categoriesReady = true
                    populateCategoryComboBox()
                    validateSelectedTags()
                }.onFailure { statusLabel.text = "版块加载失败，请重试；内容已保留" }
                updateValidation()
            }, ModalityState.any())
        }
    }
    private fun populateCategoryComboBox() {
        val previous = restoredCategory ?: (categoryComboBox.selectedItem as? CategoryItem)?.id ?: initialCategoryId ?: capabilities.defaultCategory
        suppressDraft = true
        categoryComboBox.removeAllItems()
        categoryComboBox.addItem(CategoryItem(null, "选择可发帖版块…", null, null))
        ComposerCategories.options(categories).forEach(categoryComboBox::addItem)
        val index = (0 until categoryComboBox.itemCount).firstOrNull { categoryComboBox.getItemAt(it).id == previous }
        categoryComboBox.selectedIndex = index ?: 0
        if (index == null && restoredCategory != null) setTagHint(com.lgguan.linuxdo.plugin.model.TagHint.Warning("草稿原版块不可发帖，请选择其他版块"))
        if (index != null) restoredCategory = null
        suppressDraft = false
    }

    private fun chooseAndUploadImage() = editorSupport.chooseImage()

    private fun handleClipboardImagePaste(): Boolean {
        // 1. Try AWT System Clipboard FIRST (Direct OS Windows clipboard, essential for Snipping Tool & WeChat screenshots)
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val transferable = clipboard.getContents(null)
            if (transferable != null && handleTransferableImageImport(transferable)) {
                return true
            }
        } catch (t: Throwable) {
            LinuxDoLog.warn("System clipboard image paste failed: ${t.message}")
        }

        // 2. Fallback to CopyPasteManager (IntelliJ Platform clipboard)
        try {
            val transferable = CopyPasteManager.getInstance().contents
            if (transferable != null && handleTransferableImageImport(transferable)) {
                return true
            }
        } catch (t: Throwable) {
            LinuxDoLog.warn("CopyPasteManager image paste failed: ${t.message}")
        }

        return false
    }

    private fun handleTransferableImageImport(transferable: Transferable): Boolean {
        if (!textArea.isEnabled) return false
        // 1. Direct Image Bitmap (e.g. Screenshot, Snipping Tool, WeChat/QQ screenshot, browser copy image)
        if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            val rawImg = try {
                transferable.getTransferData(DataFlavor.imageFlavor)
            } catch (t: Throwable) {
                LinuxDoLog.warn("Failed to get imageFlavor data: ${t.message}")
                null
            }
            if (rawImg is Image) {
                queueBitmapUpload(rawImg)
                return true
            }
        }

        // 2. Check all flavors for MIME image/* streams or images
        try {
            for (flavor in transferable.transferDataFlavors) {
                if (flavor.mimeType.startsWith("image/", ignoreCase = true)) {
                    val data = transferable.getTransferData(flavor)
                    if (data is java.io.InputStream) {
                        val bytes = data.use { it.readNBytes(com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES + 1) }
                        if (bytes.isNotEmpty()) {
                            val subType = flavor.subType.substringBefore(";").trim().lowercase()
                            val ext = if (subType in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")) subType else "png"
                            uploadImageBytesInternal(bytes, "clipboard-${System.currentTimeMillis()}.$ext")
                            return true
                        }
                    } else if (data is Image) {
                        queueBitmapUpload(data)
                        return true
                    }
                }
            }
        } catch (t: Throwable) {
            LinuxDoLog.warn("MIME image flavor import check failed: ${t.message}")
        }

        // 3. Image File(s) (e.g. Copied in Windows Explorer or Desktop)
        if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            val listData = try {
                transferable.getTransferData(DataFlavor.javaFileListFlavor)
            } catch (t: Throwable) {
                LinuxDoLog.warn("Failed to get javaFileListFlavor data: ${t.message}")
                null
            }
            if (listData is List<*>) {
                val imageFiles = listData.filterIsInstance<File>().filter { f -> isImageFile(f) }
                if (imageFiles.isNotEmpty()) {
                    for (file in imageFiles) {
                        doUploadImageFile(file)
                    }
                    return true
                }
            }
        }

        // 4. HTML data URI (e.g. copied from web page with base64 image)
        try {
            val htmlFlavors = listOfNotNull(
                DataFlavor.fragmentHtmlFlavor,
                DataFlavor.selectionHtmlFlavor
            )
            for (hf in htmlFlavors) {
                if (transferable.isDataFlavorSupported(hf)) {
                    val html = transferable.getTransferData(hf) as? String ?: ""
                    val base64Match = Regex("""data:image/(\w+);base64,([A-Za-z0-9+/=]+)""").find(html)
                    if (base64Match != null) {
                        val ext = base64Match.groupValues[1]
                        val base64Data = base64Match.groupValues[2]
                        require(base64Data.length <= 32 * 1024 * 1024) { "图片超过 24 MB" }
                        val bytes = java.util.Base64.getDecoder().decode(base64Data)
                        if (bytes.isNotEmpty()) {
                            uploadImageBytesInternal(bytes, "web-copy-${System.currentTimeMillis()}.$ext")
                            return true
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // 5. String content: check if it's a path to a local image file (e.g. "Copy as path" in Win 11 or pasted path)
        if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            val str = try {
                (transferable.getTransferData(DataFlavor.stringFlavor) as? String)?.trim()?.removeSurrounding("\"")
            } catch (_: Throwable) { null }
            if (!str.isNullOrBlank()) {
                try {
                    val file = File(str)
                    if (file.exists() && file.isFile && isImageFile(file)) {
                        doUploadImageFile(file)
                        return true
                    }
                } catch (_: Throwable) {}
            }
        }

        return false
    }

    private val imageUpload get() = editorSupport.upload

    private fun uploadImageBytesInternal(bytes: ByteArray, fileName: String) = imageUpload.bytes(bytes, fileName)

    private fun doUploadImageFile(file: File) = imageUpload.file(file)

    private fun queueBitmapUpload(img: Image) = imageUpload.bitmap(img)

    private fun isImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    }

    private fun showPreview() {
        if (isDisposed) return
        isPreviewVisible = true
        splitter.secondComponent = previewContainer
        splitter.revalidate()
    }

    private fun togglePreview(): Boolean {
        previewAlarm.cancelAllRequests()
        previewView.invalidate()
        if (isPreviewVisible) {
            isPreviewVisible = false
            splitter.secondComponent = null
            splitter.revalidate()
        } else {
            showPreview()
            updatePreview()
        }
        return isPreviewVisible
    }

    private fun updatePreview() { if (!isDisposed && isPreviewVisible) previewView.local(textArea.text) }

    private fun schedulePreviewUpdate() {
        if (isDisposed || !isPreviewVisible) return
        previewView.invalidate()
        previewAlarm.cancelAllRequests()
        previewAlarm.addRequest({
            updatePreview()
        }, 150, ModalityState.any())
    }

    private fun <T> draftWork(work: () -> T, complete: (T) -> Unit) {
        if (draftBusy || draftBlocked || draftSession.version != SessionEpoch.current) return
        draftBusy = true; draftRetry.isVisible = false; updateValidation()
        backgroundTasks.submit {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                draftBusy = false
                if (draftSession.version != SessionEpoch.current) { draftBlocked = true; pendingAction = null; updateValidation(); return@invokeLater }
                result.onSuccess {
                    complete(it)
                    val action = pendingAction; pendingAction = null; action?.invoke()
                }.onFailure {
                    pendingAction = null
                    setEditable(true)
                    val verification = it is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
                    draftStatus.text = when {
                        draftSession.conflicted -> "草稿冲突，自动同步已暂停"
                        verification -> "请在侧边栏完成 Cloudflare 人机验证；内容已保留"
                        else -> "同步失败，内容已保留在窗口"
                    }
                    draftStatus.toolTipText = it.message
                    draftRetry.text = if (draftSession.conflicted) "选择版本" else if (verification) "验证后重试" else "重试同步"
                    draftRetry.isVisible = true
                }
                updateValidation()
            }, ModalityState.any())
        }
    }
    private fun restore(content: TopicDraftContent) {
        suppressDraft = true
        titleField.text = content.title
        textArea.text = content.body
        textArea.caretPosition = textArea.text.length
        selectedTags.clear(); selectedTags.addAll(content.tags)
        restoredCategory = content.categoryId
        if (categoriesReady) populateCategoryComboBox()
        suppressDraft = true
        renderTagChips()
        suppressDraft = false
        editorSupport.resetUndo()
        baseline = snapshot()
        validateSelectedTags()
        schedulePreviewUpdate()
    }
    private fun loadDraft() {
        setEditable(false)
        draftStatus.text = "正在读取论坛草稿…"
        draftWork({ draftSession.load() }) { draft ->
            if(requireExistingDraft && draft.data == null) {
                draftBlocked = true
                draftStatus.text = "草稿已消失，请刷新我的草稿核对"
                com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance().draftCleared(draftSession.key, draftSession.version)
                return@draftWork
            }
            if (!draft.isTopicDraft) {
                if(requireExistingDraft) com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance().invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.DRAFTS, draftSession.version)
                draftBlocked = true; setEditable(true)
                draftStatus.text = "此草稿类型请在网页继续，插件不会改写"
                draftRetry.text = "在网页打开"; draftRetry.isVisible = true
                draftRetry.actionListeners.forEach { draftRetry.removeActionListener(it) }
                draftRetry.addActionListener { BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity/drafts") }
            } else {
                if (draft.data != null) restore(TopicDraftContent.read(draft)) else baseline = snapshot()
                draftReady = true; setEditable(true)
                draftStatus.text = if (draft.data != null) "已恢复论坛新话题草稿" else "尚无论坛草稿"
            }
        }
    }
    private fun saveDraft(after: (() -> Unit)? = null) {
        draftAlarm.cancelAllRequests()
        if (draftBusy) { if (after != null) pendingAction = { saveDraft(after) }; return }
        if (!draftReady || draftBlocked || draftSession.conflicted || publishing) return
        if (imageUpload.pending > 0) { imageUpload.whenIdle { saveDraft(after) }; return }
        val content = snapshot()
        if (content == baseline || (content.empty && baseline.empty)) { after?.invoke(); return }
        draftStatus.text = "正在同步论坛草稿…"
        draftWork({ draftSession.save(content::write) }) {
            baseline = content
            if (content == snapshot()) { draftStatus.text = "已同步到论坛"; after?.invoke() }
            else { draftStatus.text = "尚有未同步修改"; draftAlarm.addRequest({ saveDraft(after) }, 2000, ModalityState.any()) }
        }
    }
    private fun describe(content: TopicDraftContent): String =
        "标题：${content.title}\n版块：${categories.firstOrNull { it.id == content.categoryId }?.name ?: content.categoryId}\n标签：${content.tags.joinToString()}\n\n${content.body}"
    private fun resolveConflict() {
        draftAlarm.cancelAllRequests()
        val local = snapshot()
        draftWork({ draftSession.load() }) { server ->
            if (!server.isTopicDraft) { draftBlocked = true; draftStatus.text = "服务器草稿类型已改变，请在网页继续"; return@draftWork }
            val dialog = TopicDraftConflictDialog(project, describe(local), describe(TopicDraftContent.read(server)))
            dialog.show()
            when (dialog.exitCode) {
                TopicDraftConflictDialog.LOCAL -> {
                    draftSession.choose(server)
                    baseline = TopicDraftContent.read(server)
                    pendingAction = { saveDraft() }
                }
                TopicDraftConflictDialog.SERVER -> { draftSession.choose(server); restore(TopicDraftContent.read(server)); draftStatus.text = "已采用服务器版本" }
                else -> { draftStatus.text = "冲突未解决，自动同步仍暂停"; draftRetry.isVisible = true }
            }
        }
    }
    override fun doCancelAction() {
        if (publishing) { statusLabel.text = "正在提交，请等待结果"; return }
        if (draftBlocked || unconfirmed) {
            if (Messages.showYesNoDialog(project, "本机未保存正文或标题，关闭将舍弃窗口内容；论坛草稿保留。", "关闭话题窗口", Messages.getQuestionIcon()) == Messages.YES) super.doCancelAction()
            return
        }
        if (draftBusy) { pendingAction = { doCancelAction() }; draftStatus.text = "正在等待当前草稿请求结束…"; return }
        val choice = Messages.showDialog(project, "重启恢复依赖已同步的论坛草稿。本机不保存正文或标题。", "关闭话题窗口",
            arrayOf("保存草稿并关闭", "舍弃草稿", "继续编辑"), 2, Messages.getQuestionIcon())
        when (choice) {
            0 -> {
                if (draftSession.conflicted) { resolveConflict(); return }
                if (!draftReady) { loadDraft(); return }
                if (imageUpload.pending > 0) {
                    draftStatus.text = "正在等待图片上传完成后保存…"
                    // Upload must remain allowed to insert its captured result, but freeze user edits.
                    setEditable(false)
                    imageUpload.whenIdle {
                        if (imageUpload.failures == 0) saveDraft { close(CANCEL_EXIT_CODE) }
                        else { setEditable(true); draftStatus.text = "图片上传失败，请重试或舍弃失败图片；窗口内容已保留" }
                    }
                } else { setEditable(false); saveDraft { close(CANCEL_EXIT_CODE) } }
            }
            1 -> {
                if (draftSession.conflicted) { resolveConflict(); return }
                if (!draftReady) { loadDraft(); return }
                imageUpload.cancel(); draftAlarm.cancelAllRequests(); setEditable(false)
                draftWork({ draftSession.clearOwned() }) { cleanup ->
                    if (cleanup == ForumDraftSession.Cleanup.OTHER_CLIENT) Messages.showInfoMessage(project, "其他客户端的新草稿已保留。", "草稿已保留")
                    close(CANCEL_EXIT_CODE)
                }
            }
        }
    }
    private fun checkPublishResult() {
        com.intellij.ide.BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity")
        if (unconfirmed && Messages.showYesNoDialog(project,
            "请先检查网页版。如果已确认没有发布或进入审核，可解除发送限制。是否已经确认？", "确认发布结果", Messages.getQuestionIcon()) == Messages.YES) {
            unconfirmed = false; updateValidation()
        }
    }

    override fun doOKAction() {
        updateValidation()
        if (!isOKActionEnabled) return
        val content = snapshot()
        val version = draftSession.version
        publishing = true; draftBusy = true
        draftAlarm.cancelAllRequests(); setEditable(false); updateValidation()
        backgroundTasks.submit {
            val result = runCatching { draftSession.publish(content::write) { environment.publish(content, draftSession.key, version) } }
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                publishing = false; draftBusy = false
                if (version != SessionEpoch.current) { draftBlocked = true; setEditable(true); updateValidation(); return@invokeLater }
                result.onSuccess { (outcome, cleanup) ->
                    val personal = com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance()
                    personal.invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.TOPICS, version)
                    personal.invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.DRAFTS, version)
                    if (cleanup.isFailure || cleanup.getOrNull() == ForumDraftSession.Cleanup.OTHER_CLIENT)
                        Messages.showInfoMessage(project, "提交已确认；服务器草稿未清理或已由其他客户端修改，请在网页检查。", "提交已确认")
                    close(OK_EXIT_CODE)
                    when (outcome) {
                        is PublishOutcome.Published -> runCatching {
                            onTopicCreated?.invoke(outcome.post)
                            LinuxDoEditorOpener.openTopic(project, requireNotNull(outcome.post.topicId), content.title)
                        }.onFailure { LinuxDoLog.warn("Topic published but navigation failed: ${it.javaClass.simpleName}") }
                        is PublishOutcome.Queued -> Messages.showInfoMessage(project, outcome.message, "已提交，等待审核")
                    }
                }.onFailure { error ->
                    setEditable(true)
                    unconfirmed = error is UnconfirmedPublishException
                    draftRetry.isVisible = true
                    draftRetry.text = if (draftSession.conflicted) "选择版本" else "重试同步"
                    updateValidation()
                    statusLabel.text = ComposerErrors.parse(error)
                    Messages.showErrorDialog(project, ComposerErrors.format(ComposerErrors.parse(error)), if (unconfirmed) "发布结果未确认" else "提交失败，内容已保留")
                }
            }, ModalityState.any())
        }
    }

    override fun dispose() {
        registryKey?.let { if (editors[it] === this) editors.remove(it) }
        draftAlarm.cancelAllRequests()
        imageUpload.cancel()
        previewView.dispose()
        backgroundTasks.dispose()
        previewAlarm.cancelAllRequests()
        tagAlarm.cancelAllRequests()
        tagValidationAlarm.cancelAllRequests()
        categoryComboBox.hidePopup()
        tagPopup?.cancel()
        tagPopup = null
        super.dispose()
    }
}
