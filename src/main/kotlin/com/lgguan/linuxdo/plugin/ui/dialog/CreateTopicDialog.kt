package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService

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

class CreateTopicDialog(
    private val project: Project,
    private val initialCategoryId: Int? = null,
    private val onTopicCreated: ((Post) -> Unit)? = null
) : DialogWrapper(project, true) {

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val titleField = JBTextField()
    private val titleCounterLabel = JBLabel("0 / 6")

    private val categoryComboBox = ComboBox<CategoryItem>()

    // Tags dynamic search components
    private val tagInputField = JBTextField(12)
    private val addTagBtn = JButton("+").apply {
        preferredSize = Dimension(JBUI.scale(26), JBUI.scale(26))
        isFocusable = false
        toolTipText = "添加标签"
    }
    private val tagsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).apply {
        isVisible = false
        border = JBUI.Borders.empty(2, 0)
    }
    private val selectedTags = linkedSetOf<String>()

    // Tag autocomplete popup without focus stealing
    private val tagSuggestionsModel = DefaultListModel<TagItem>()
    private val tagSuggestionsList = JBList(tagSuggestionsModel)
    private var tagPopup: JBPopup? = null
    private val tagAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    private val textArea = JBTextArea(14, 50)
    private val bodyCounterLabel = JBLabel("0 / 20 勿用各类字数补丁")

    // Live preview: High performance, styled JEditorPane honoring IDE theme
    private var previewPane: JEditorPane? = null
    private val previewAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val splitter = JBSplitter(false, 0.52f)
    private var isPreviewVisible = true
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

    data class CategoryItem(val id: Int?, val name: String, val slug: String?, val color: String?) {
        override fun toString(): String = name
    }

    init {
        title = "创建话题 - Linux Do"
        setOKButtonText("创建话题")
        setCancelButtonText("舍弃")
        init()
        loadCategoriesAndTags()
        LinuxDoAuthService.getInstance().addAuthListener(disposable) {
            if (!isDisposed) { categoryComboBox.removeAllItems(); updateValidation(); loadCategoriesAndTags() }
        }
        updateValidation()
        updatePreview()
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 8))
        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.preferredSize = Dimension(JBUI.scale(840), JBUI.scale(580))

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
        val catTagRow = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.BOTH
        gbc.gridy = 0
        gbc.weighty = 1.0

        // Left: Category
        gbc.gridx = 0
        gbc.weightx = 0.45
        gbc.insets = JBUI.insets(0, 0, 0, 6)
        categoryComboBox.preferredSize = Dimension(0, JBUI.scale(28))
        catTagRow.add(categoryComboBox, gbc)

        // Right: Tags input container with dynamic suggestion
        gbc.gridx = 1
        gbc.weightx = 0.55
        gbc.insets = JBUI.emptyInsets()
        val tagInputContainer = JPanel(BorderLayout(4, 0))
        tagInputField.emptyText.text = "搜索或输入标签 (按 Enter 添加)..."
        tagInputField.preferredSize = Dimension(JBUI.scale(140), JBUI.scale(28))
        tagInputContainer.add(tagInputField, BorderLayout.CENTER)
        tagInputContainer.add(addTagBtn, BorderLayout.EAST)
        catTagRow.add(tagInputContainer, gbc)

        formPanel.add(catTagRow)

        // Preloaded / Recommended Quick Tags Panel
        formPanel.add(createQuickTagsPanel())

        // Tags Chip Panel (Compact, visible only when tags exist)
        formPanel.add(tagsPanel)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // Row 3: Flat ActionToolbar
        val toolbar = createFormattingToolbar()
        formPanel.add(toolbar)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // 3. Editor & Live Preview (Splitter)
        textArea.font = editorFont
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = scheme.defaultBackground
        textArea.foreground = scheme.defaultForeground
        textArea.emptyText.text = "在此处输入。使用 Markdown、BBCode 或 HTML 进行排版。拖放或粘贴图片以插入。"

        val editorPanel = JPanel(BorderLayout(0, 2))
        val editorScroll = JBScrollPane(textArea).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }
        editorPanel.add(editorScroll, BorderLayout.CENTER)

        val editorFooter = JPanel(BorderLayout())
        editorFooter.border = JBUI.Borders.empty(2, 4)
        bodyCounterLabel.font = bodyCounterLabel.font.deriveFont(Font.BOLD, 11f)
        bodyCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        editorFooter.add(bodyCounterLabel, BorderLayout.EAST)
        editorPanel.add(editorFooter, BorderLayout.SOUTH)

        // Setup live preview component (Styled JEditorPane matching IDE theme)
        val pane = JEditorPane().apply {
            contentType = "text/html"
            isEditable = false
            background = scheme.defaultBackground
            foreground = scheme.defaultForeground
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = editorFont
            val kit = HTMLEditorKit()
            kit.styleSheet.addRule("body { font-family: ${theme.fontName}, -apple-system, sans-serif; font-size: ${theme.fontSize}pt; color: ${theme.fgHex}; background-color: ${theme.bgHex}; margin: 12px; line-height: 1.5; }")
            kit.styleSheet.addRule("h1 { color: ${theme.fgHex}; font-size: 16pt; font-weight: bold; margin: 10px 0 6px 0; border-bottom: 1px solid ${theme.borderHex}; }")
            kit.styleSheet.addRule("h2 { color: ${theme.fgHex}; font-size: 14pt; font-weight: bold; margin: 8px 0 4px 0; }")
            kit.styleSheet.addRule("h3 { color: ${theme.fgHex}; font-size: 12pt; font-weight: bold; margin: 6px 0 2px 0; }")
            kit.styleSheet.addRule("p, div, li, span { color: ${theme.fgHex}; font-size: ${theme.fontSize}pt; }")
            kit.styleSheet.addRule("strong, b { color: ${theme.fgHex}; font-weight: bold; }")
            kit.styleSheet.addRule("em, i { color: ${theme.fgHex}; font-style: italic; }")
            kit.styleSheet.addRule("blockquote { color: ${theme.commentHex}; border-left: 3px solid #0969DA; margin-left: 0; padding-left: 8px; }")
            kit.styleSheet.addRule("pre { background-color: ${theme.codeBlockBgHex}; color: ${theme.fgHex}; font-family: Consolas, monospace; font-size: 11pt; padding: 6px; }")
            kit.styleSheet.addRule("code { background-color: ${theme.codeBlockBgHex}; color: ${theme.keywordHex}; font-family: Consolas, monospace; font-size: 11pt; }")
            kit.styleSheet.addRule("a { color: #58A6FF; text-decoration: none; }")
            kit.styleSheet.addRule("fieldset { border: 1px dashed ${theme.borderHex}; padding: 6px; margin: 6px 0; color: ${theme.fgHex}; }")
            kit.styleSheet.addRule("legend { font-weight: bold; padding: 0 4px; color: ${theme.keywordHex}; }")
            editorKit = kit
        }
        previewPane = pane
        val previewScroll = JBScrollPane(pane).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }
        previewContainer = previewScroll

        splitter.firstComponent = editorPanel
        splitter.secondComponent = previewScroll
        splitter.setHonorComponentsMinimumSize(true)

        val centerStack = JPanel(BorderLayout(0, 6))
        centerStack.add(formPanel, BorderLayout.NORTH)
        centerStack.add(splitter, BorderLayout.CENTER)

        rootPanel.add(centerStack, BorderLayout.CENTER)

        setupTagSuggestionsList()
        setupEventListeners()
        schedulePreviewUpdate()

        return rootPanel
    }

    private fun createBannerPanel(): JPanel {
        val banner = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
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
                foreground = UIUtil.getContextHelpForeground()
            }
            add(tipLabel)
            val actionLink = ActionLink("《社区准则》") {
                BrowserUtil.browse("https://linux.do/guidelines")
            }.apply {
                font = font.deriveFont(Font.PLAIN, 12f)
            }
            add(actionLink)
        }

        banner.add(infoBox, BorderLayout.CENTER)
        return banner
    }

    private fun createFormattingToolbar(): JComponent {
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        toolbar.border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)

        fun makeFlatBtn(
            icon: Icon? = null,
            text: String? = null,
            tooltip: String,
            onClick: () -> Unit
        ): JButton {
            return JButton().apply {
                if (icon != null) this.icon = icon
                if (text != null) {
                    this.text = text
                    this.font = this.font.deriveFont(Font.BOLD, 12f)
                }
                toolTipText = tooltip
                isFocusable = false
                isBorderPainted = false
                isContentAreaFilled = false
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                margin = JBUI.insets(2)
                preferredSize = Dimension(JBUI.scale(28), JBUI.scale(26))
                minimumSize = Dimension(JBUI.scale(28), JBUI.scale(26))
                maximumSize = Dimension(JBUI.scale(28), JBUI.scale(26))
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) {
                        isContentAreaFilled = true
                        background = JBColor(0xDFE1E5, 0x4E5157)
                        repaint()
                    }
                    override fun mouseExited(e: MouseEvent) {
                        isContentAreaFilled = false
                        repaint()
                    }
                })
                addActionListener { onClick() }
            }
        }

        // Preview toggle
        val previewBtn = makeFlatBtn(
            icon = AllIcons.Actions.Preview,
            tooltip = "切换实时预览分栏 (Toggle Live Preview)"
        ) {
            isPreviewVisible = !isPreviewVisible
            splitter.secondComponent = if (isPreviewVisible) previewContainer else null
            splitter.revalidate()
            splitter.repaint()
        }
        toolbar.add(previewBtn)
        toolbar.add(Box.createHorizontalStrut(JBUI.scale(6)))

        val boldBtn = makeFlatBtn(text = "B", tooltip = "粗体 (**text**)") {
            wrapSelection("**", "**", "粗体文本")
        }.apply { font = font.deriveFont(Font.BOLD, 12f) }
        toolbar.add(boldBtn)

        val italicBtn = makeFlatBtn(text = "I", tooltip = "斜体 (*text*)") {
            wrapSelection("*", "*", "斜体文本")
        }.apply { font = font.deriveFont(Font.ITALIC or Font.BOLD, 12f) }
        toolbar.add(italicBtn)

        toolbar.add(makeFlatBtn(text = "H", tooltip = "标题 (### text)") {
            prependToLines("### ")
        })
        toolbar.add(makeFlatBtn(icon = AllIcons.General.Web, tooltip = "插入超链接") {
            insertLink()
        })
        toolbar.add(makeFlatBtn(text = "”", tooltip = "引用文本 (> text)") {
            prependToLines("> ")
        })
        toolbar.add(makeFlatBtn(icon = AllIcons.FileTypes.Custom, tooltip = "代码块 (```code```)") {
            insertCodeBlock()
        })
        toolbar.add(makeFlatBtn(icon = AllIcons.Actions.Upload, tooltip = "上传图片附件") {
            chooseAndUploadImage()
        })
        toolbar.add(makeFlatBtn(icon = AllIcons.Actions.ListFiles, tooltip = "无序列表 (- item)") {
            prependToLines("- ")
        })
        toolbar.add(makeFlatBtn(text = "1.", tooltip = "有序列表 (1. item)") {
            prependToLines("1. ")
        })
        val emojiBtn = makeFlatBtn(icon = AllIcons.Actions.IntentionBulb, tooltip = "插入常用表情") {}
        emojiBtn.addActionListener { showEmojiPopup(emojiBtn) }
        toolbar.add(emojiBtn)

        toolbar.add(makeFlatBtn(icon = AllIcons.General.CollapseComponent, tooltip = "折叠详情 (Details/Spoiler)") {
            wrapSelection("[details=点击展开]\n", "\n[/details]", "在此输入隐藏内容")
        })

        return toolbar
    }

    override fun createSouthPanel(): JComponent {
        val southPanel = JPanel(BorderLayout(16, 0)).apply {
            border = JBUI.Borders.empty(8, 16, 14, 16)
        }

        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 12f)
        val statusContainer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            maximumSize = Dimension(JBUI.scale(450), JBUI.scale(32))
            add(statusLabel)
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
        tagSuggestionsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tagSuggestionsList.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                if (value is TagItem) {
                    label.icon = AllIcons.Nodes.Tag
                    label.text = if (value.count > 0) "#${value.text}  (${value.count} 话题)" else "#${value.text}"
                    label.border = JBUI.Borders.empty(3, 6)
                }
                return label
            }
        }

        tagSuggestionsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val selected = tagSuggestionsList.selectedValue
                if (selected != null) {
                    addTag(selected.text)
                    tagInputField.text = ""
                    tagPopup?.cancel()
                    tagInputField.requestFocusInWindow()
                }
            }
        })
    }

    private fun setupEventListeners() {
        titleField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = updateValidation()
            override fun removeUpdate(e: DocumentEvent?) = updateValidation()
            override fun changedUpdate(e: DocumentEvent?) = updateValidation()
        })
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onContentChanged()
            override fun removeUpdate(e: DocumentEvent?) = onContentChanged()
            override fun changedUpdate(e: DocumentEvent?) = onContentChanged()
        })

        categoryComboBox.addActionListener {
            updateValidation()
        }

        // Dynamic Tag Search with Alarm debounce
        tagInputField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleTagSearch()
            override fun removeUpdate(e: DocumentEvent?) = scheduleTagSearch()
            override fun changedUpdate(e: DocumentEvent?) = scheduleTagSearch()
        })

        // Tag input focus & mouse click: show preloaded system tags immediately!
        tagInputField.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                showAvailableSystemTags()
            }
        })
        tagInputField.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                showAvailableSystemTags()
            }
        })

        tagInputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_DOWN) {
                    if (tagPopup?.isVisible != true) {
                        showAvailableSystemTags()
                        e.consume()
                        return
                    }
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
                } else if (e.keyCode == KeyEvent.VK_ENTER || e.keyChar == ',') {
                    if (tagPopup?.isVisible == true && tagSuggestionsList.selectedIndex >= 0) {
                        val selected = tagSuggestionsList.selectedValue
                        if (selected != null) {
                            addTag(selected.text)
                            tagInputField.text = ""
                            tagPopup?.cancel()
                            e.consume()
                            return
                        }
                    }
                    e.consume()
                    addCurrentTag()
                } else if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    tagPopup?.cancel()
                    e.consume()
                }
            }
        })

        addTagBtn.addActionListener { addCurrentTag() }

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
        val text = try {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
                ?: Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Throwable) { null }
        if (!text.isNullOrEmpty()) {
            textArea.replaceSelection(text)
            onContentChanged()
        }
    }

    private fun scheduleTagSearch() {
        tagAlarm.cancelAllRequests()
        val query = tagInputField.text.trim()
        if (query.isBlank()) {
            tagPopup?.cancel()
            return
        }

        tagAlarm.addRequest({
            if (!tagInputField.isShowing) return@addRequest
            val currentQuery = tagInputField.text.trim()
            if (currentQuery.isBlank()) {
                tagPopup?.cancel()
                return@addRequest
            }

            // Local match from popular tags
            val popular = LinuxDoTopicService.getInstance().popularTags
            val localMatches = popular.filter { it.contains(currentQuery, ignoreCase = true) }
                .map { TagItem(it, it, 0) }

            if (localMatches.isNotEmpty()) {
                showTagSuggestions(localMatches.take(8))
            }

            // Remote match from Discourse
            LinuxDoTopicService.getInstance().searchTags(currentQuery) { remoteTags ->
                if (tagInputField.text.trim() == currentQuery) {
                    val combined = (remoteTags + localMatches).distinctBy { it.text }
                    if (combined.isNotEmpty()) {
                        showTagSuggestions(combined.take(10))
                    } else if (localMatches.isEmpty()) {
                        tagPopup?.cancel()
                    }
                }
            }
        }, 250)
    }

    private fun createQuickTagsPanel(): JComponent {
        val quickPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            border = JBUI.Borders.empty(1, 0, 3, 0)
        }
        val tip = JBLabel("推荐标签:").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(0, 1, 0, 2)
        }
        quickPanel.add(tip)

        val recommended = listOf("纯水", "快问快答", "软件开发", "人工智能", "VPS", "求资源", "配置优化", "经验分享", "网络安全", "树洞")
        for (tag in recommended) {
            val link = ActionLink("#$tag") {
                if (selectedTags.contains(tag)) {
                    selectedTags.remove(tag)
                } else {
                    addTag(tag)
                }
                renderTagChips()
                updateValidation()
            }.apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                toolTipText = "点击快速选择/取消标签 #$tag"
            }
            quickPanel.add(link)
        }

        val moreLink = ActionLink("全部系统标签...") {
            tagInputField.text = ""
            tagInputField.requestFocusInWindow()
            showAvailableSystemTags()
        }.apply {
            font = font.deriveFont(Font.BOLD, 11f)
            toolTipText = "浏览并选择所有常用系统标签"
        }
        quickPanel.add(moreLink)

        return quickPanel
    }

    private fun showAvailableSystemTags() {
        if (!tagInputField.isShowing) return
        val current = tagInputField.text.trim()
        val allTags = (LinuxDoTopicService.getInstance().popularTags.ifEmpty {
            LinuxDoTopicService.DEFAULT_SYSTEM_TAGS
        } + LinuxDoTopicService.DEFAULT_SYSTEM_TAGS).distinct()

        val unselected = allTags.filter { it !in selectedTags }
        val filtered = if (current.isNotBlank()) {
            unselected.filter { it.contains(current, ignoreCase = true) }
        } else {
            unselected
        }

        val items = filtered.take(15).map { TagItem(it, it, 0) }
        showTagSuggestions(items)
    }

    private fun showTagSuggestions(items: List<TagItem>) {
        if (items.isEmpty() || !tagInputField.isShowing) {
            tagPopup?.cancel()
            return
        }

        tagSuggestionsModel.clear()
        items.forEach { tagSuggestionsModel.addElement(it) }
        tagSuggestionsList.selectedIndex = -1

        if (tagPopup?.isVisible == true) {
            tagPopup?.pack(true, true)
            return
        }

        val scroll = JBScrollPane(tagSuggestionsList).apply {
            border = JBUI.Borders.customLine(JBColor.border())
            preferredSize = Dimension(tagInputField.width.coerceAtLeast(JBUI.scale(220)), JBUI.scale(150))
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
        popup.show(RelativePoint(tagInputField, Point(0, tagInputField.height)))
    }

    private fun sanitizeTag(raw: String): String {
        var t = raw.trim()
        t = t.removePrefix("name:").removePrefix("name：").removePrefix("-").trim()
        t = t.trim('"', '\'', '`', '#', ',', '，', '“', '”', '‘', '’', ' ')
        return t
    }

    private fun addTag(rawTag: String) {
        val clean = sanitizeTag(rawTag)
        if (clean.isNotBlank()) {
            if (selectedTags.size >= 5) {
                statusLabel.text = "⚠️ 最多只能添加 5 个标签"
                return
            }
            if (!selectedTags.contains(clean)) {
                selectedTags.add(clean)
                renderTagChips()
                updateValidation()
            }
        }
    }

    private fun addCurrentTag() {
        val raw = tagInputField.text.trim()
        if (raw.isNotBlank()) {
            val tags = raw.split(Regex("[,，\\s]+"))
            for (t in tags) {
                addTag(t)
            }
            tagInputField.text = ""
            tagPopup?.cancel()
        }
    }

    private fun renderTagChips() {
        tagsPanel.removeAll()
        if (selectedTags.isEmpty()) {
            tagsPanel.isVisible = false
            tagsPanel.revalidate()
            tagsPanel.repaint()
            return
        }

        tagsPanel.isVisible = true
        for (tag in selectedTags) {
            val chip = JPanel(BorderLayout(6, 0)).apply {
                background = JBColor(0xEAEEF2, 0x2D3136)
                border = CompoundBorder(
                    JBUI.Borders.customLine(JBColor.border(), 1),
                    JBUI.Borders.empty(2, 6)
                )

                val tagLabel = JBLabel("#$tag", AllIcons.Nodes.Tag, SwingConstants.LEFT).apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor(0x24292F, 0xC9D1D9)
                    border = JBUI.Borders.empty(0, 0, 0, 2)
                }

                val delLabel = JBLabel("×").apply {
                    font = font.deriveFont(Font.BOLD, 12f)
                    foreground = JBColor(0x8C959F, 0x8B949E)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "移除标签"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseEntered(e: MouseEvent?) {
                            foreground = JBColor.RED
                        }
                        override fun mouseExited(e: MouseEvent?) {
                            foreground = JBColor(0x8C959F, 0x8B949E)
                        }
                        override fun mouseClicked(e: MouseEvent?) {
                            selectedTags.remove(tag)
                            renderTagChips()
                            updateValidation()
                        }
                    })
                }

                add(tagLabel, BorderLayout.CENTER)
                add(delLabel, BorderLayout.EAST)
            }
            tagsPanel.add(chip)
        }
        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    private fun onContentChanged() {
        updateValidation()
        schedulePreviewUpdate()
    }

    private fun updateValidation() {
        if (isDisposed) return
        val titleText = titleField.text.trim()
        val titleLen = titleText.length
        val titleValid = titleLen >= 6

        titleCounterLabel.text = "$titleLen / 6"
        if (titleValid) {
            titleCounterLabel.foreground = JBColor(0x1A7F37, 0x3FB950)
        } else {
            titleCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        }

        val bodyText = textArea.text.trim()
        val bodyLen = bodyText.length
        val bodyValid = bodyLen >= 20

        if (bodyValid) {
            bodyCounterLabel.text = "$bodyLen 字符"
            bodyCounterLabel.foreground = JBColor(0x57606A, 0x8B949E)
        } else {
            bodyCounterLabel.text = "$bodyLen / 20 勿用各类字数补丁"
            bodyCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        }

        val catItem = categoryComboBox.selectedItem as? CategoryItem
        val catValid = catItem?.id != null && LinuxDoTopicService.getInstance().categoriesAreCurrent && LinuxDoAuthService.getInstance().isLoggedIn

        val allValid = titleValid && bodyValid && catValid
        isOKActionEnabled = allValid

        if (!LinuxDoTopicService.getInstance().categoriesAreCurrent) {
            statusLabel.text = "版块未加载成功，请点击「重新加载版块」；输入内容已保留"
        } else if (!LinuxDoAuthService.getInstance().isLoggedIn) {
            statusLabel.text = "请先确认登录账号，再发布话题"
        } else if (!catValid) {
            statusLabel.text = "⚪ 请选择发布版块"
        } else if (!titleValid) {
            statusLabel.text = "⚪ 标题至少需要 6 个字符 (当前: $titleLen)"
        } else if (!bodyValid) {
            statusLabel.text = "⚪ 正文至少需要 20 个字符 (当前: $bodyLen)"
        } else {
            statusLabel.text = "⚪ 就绪 (${PlatformShortcuts.submitLabel} 发布话题)"
        }
    }

    private fun loadCategoriesAndTags() {
        categoryComboBox.removeAllItems()
        isOKActionEnabled = false

        // Always fetch dynamic categories from site in background and update combobox
        LinuxDoTopicService.getInstance().loadCategories {
            if (isDisposed) return@loadCategories
            populateCategoryComboBox()
            updateValidation()
        }

        // Preload popular tags in background
        LinuxDoTopicService.getInstance().loadPopularTags()
    }

    private fun populateCategoryComboBox() {
        val service = LinuxDoTopicService.getInstance()
        val settings = LinuxDoSettingsState.getInstance()
        val prevSelectedId = (categoryComboBox.selectedItem as? CategoryItem)?.id ?: initialCategoryId

        categoryComboBox.removeAllItems()
        categoryComboBox.addItem(CategoryItem(null, "选择一个版块...", null, null))

        val hierarchicalList = if (service.categoriesAreCurrent) service.getHierarchicalCategories() else emptyList()
        var selectIndex = 0
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
            categoryComboBox.addItem(CategoryItem(cat.id, displayName, cat.slug, cat.color))
            if (prevSelectedId != null && cat.id == prevSelectedId) {
                selectIndex = idx + 1
            }
        }
        categoryComboBox.selectedIndex = selectIndex
    }

    private fun wrapSelection(prefix: String, suffix: String = prefix, defaultPlaceholder: String = "") {
        val start = textArea.selectionStart
        val end = textArea.selectionEnd
        val text = textArea.text
        if (start != end) {
            val selected = text.substring(start, end)
            val replacement = "$prefix$selected$suffix"
            textArea.replaceRange(replacement, start, end)
            textArea.select(start + prefix.length, start + prefix.length + selected.length)
        } else {
            val replacement = "$prefix$defaultPlaceholder$suffix"
            textArea.insert(replacement, start)
            textArea.select(start + prefix.length, start + prefix.length + defaultPlaceholder.length)
        }
        textArea.requestFocusInWindow()
        onContentChanged()
    }

    private fun prependToLines(prefix: String) {
        val start = textArea.selectionStart
        val end = textArea.selectionEnd
        val text = textArea.text
        val lineStart = text.lastIndexOf('\n', start - 1).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
        val selectedBlock = text.substring(lineStart, lineEnd)
        val lines = selectedBlock.split('\n')
        val newBlock = lines.joinToString("\n") { "$prefix$it" }
        textArea.replaceRange(newBlock, lineStart, lineEnd)
        textArea.select(lineStart, lineStart + newBlock.length)
        textArea.requestFocusInWindow()
        onContentChanged()
    }

    private fun insertLink() {
        val start = textArea.selectionStart
        val end = textArea.selectionEnd
        val text = textArea.text
        val selected = if (start != end) text.substring(start, end) else "链接文本"
        val url = Messages.showInputDialog(project, "请输入链接 URL (http:// 或 https://):", "插入超链接", Messages.getQuestionIcon())
        if (!url.isNullOrBlank()) {
            val replacement = "[$selected]($url)"
            if (start != end) {
                textArea.replaceRange(replacement, start, end)
            } else {
                textArea.insert(replacement, start)
            }
            onContentChanged()
        }
    }

    private fun insertCodeBlock() {
        val start = textArea.selectionStart
        val end = textArea.selectionEnd
        val text = textArea.text
        if (start != end && !text.substring(start, end).contains('\n')) {
            wrapSelection("`", "`", "code")
        } else {
            wrapSelection("```\n", "\n```\n", "code block")
        }
    }

    private fun showEmojiPopup(invoker: Component) {
        val emojis = arrayOf("👍", "🎉", "🔥", "🚀", "💡", "❤️", "😂", "👏", "🤝", "☕", "👀", "✨")
        val popup = JPopupMenu()
        val grid = JPanel(GridLayout(3, 4, 4, 4)).apply {
            border = JBUI.Borders.empty(4)
        }
        for (em in emojis) {
            val btn = JButton(em).apply {
                margin = JBUI.insets(2)
                font = font.deriveFont(Font.PLAIN, 16f)
                preferredSize = Dimension(JBUI.scale(32), JBUI.scale(32))
                isBorderPainted = false
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addActionListener {
                    textArea.insert(em, textArea.caretPosition)
                    popup.isVisible = false
                    textArea.requestFocusInWindow()
                    onContentChanged()
                }
            }
            grid.add(btn)
        }
        popup.add(grid)
        popup.show(invoker, 0, invoker.height)
    }

    private fun chooseAndUploadImage() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
            title = "选择要插入的图片"
            description = "支持 PNG, JPG, JPEG, GIF, WebP 等常见格式"
            withFileFilter { file ->
                val ext = file.extension?.lowercase()
                ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
            }
        }

        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return
        val file = File(chosen.path)
        doUploadImageFile(file)
    }

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

    private val imageUpload by lazy {
        ComposerImageUpload(project, textArea, statusLabel, backgroundTasks, { isDisposed }, ::onContentChanged)
    }

    private fun uploadImageBytesInternal(bytes: ByteArray, fileName: String) = imageUpload.bytes(bytes, fileName)

    private fun doUploadImageFile(file: File) = imageUpload.file(file)

    private fun queueBitmapUpload(img: Image) = imageUpload.bitmap(img)

    private fun isImageFile(file: File): Boolean {
        val ext = file.extension.lowercase()
        return ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg")
    }

    private fun updatePreview() {
        val pane = previewPane ?: return
        val rawBody = textArea.text
        val html = ComposerPreview.render(rawBody, ::schedulePreviewUpdate)
        pane.text = html
        pane.caretPosition = 0
    }

    private fun schedulePreviewUpdate() {
        if (isDisposed) return
        previewAlarm.cancelAllRequests()
        previewAlarm.addRequest({
            updatePreview()
        }, 80, ModalityState.any())
    }

    override fun doCancelAction() {
        val hasContent = titleField.text.isNotBlank() || textArea.text.isNotBlank()
        if (hasContent) {
            val choice = Messages.showYesNoDialog(
                project,
                "当前话题尚未发布，确定要舍弃已输入的内容吗？",
                "确认舍弃",
                Messages.getQuestionIcon()
            )
            if (choice != Messages.YES) {
                return
            }
        }
        super.doCancelAction()
    }

    override fun createActions(): Array<javax.swing.Action> = arrayOf(
        object : javax.swing.AbstractAction("重新加载版块") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { loadCategoriesAndTags() }
        }, *super.createActions())

    override fun doOKAction() {
        val session = SessionEpoch.current
        if (!com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().isLoggedIn) {
            statusLabel.text = "请先完成登录验证，内容已保留"
            return
        }
        val titleText = titleField.text.trim()
        val rawContent = textArea.text.trim()
        val catItem = categoryComboBox.selectedItem as? CategoryItem
        val categoryId = catItem?.id

        if (categoryId == null || !LinuxDoTopicService.getInstance().categoriesAreCurrent) {
            statusLabel.text = "❌ 请选择发布版块"
            return
        }
        if (titleText.length < 6) {
            statusLabel.text = "❌ 标题至少需要 6 个字符"
            return
        }
        if (rawContent.length < 20) {
            statusLabel.text = "❌ 正文至少需要 20 个字符 (Discourse 论坛规范)"
            return
        }

        statusLabel.text = "⏳ 正在发布话题并同步至社区..."
        isOKActionEnabled = false
        titleField.isEnabled = false
        textArea.isEnabled = false
        categoryComboBox.isEnabled = false

        val tagsList = selectedTags.toList()

        backgroundTasks.submit {
            try {
                LinuxDoLog.info("Submitting new topic: categoryId=$categoryId")
                val result = DiscourseApiClient.createTopic(titleText, rawContent, categoryId, tagsList, expectedVersion = session)

                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed || rejectChangedSession(session)) return@invokeLater
                    result.onSuccess { post ->
                        LinuxDoLog.info("Topic successfully posted: id=${post.topicId ?: post.id}")
                        statusLabel.text = "🟢 话题发布成功！正在跳转..."
                        onTopicCreated?.invoke(post)

                        val finalTopicId = post.topicId ?: post.id
                        if (finalTopicId > 0) {
                            LinuxDoEditorOpener.openTopic(project, finalTopicId, titleText)
                        }

                        close(OK_EXIT_CODE)
                    }.onFailure { err ->
                        isOKActionEnabled = true
                        titleField.isEnabled = true
                        textArea.isEnabled = true
                        categoryComboBox.isEnabled = true
                        LinuxDoLog.warn("Failed to create topic: ${err.message}", err)
                        val errorMsg = ComposerErrors.parse(err.message)
                        val shortError = if (errorMsg.length > 36) errorMsg.take(36) + "..." else errorMsg
                        statusLabel.text = "🔴 发布失败: $shortError"
                        statusLabel.toolTipText = errorMsg
                        val formattedError = ComposerErrors.format(errorMsg)
                        Messages.showErrorDialog(project, "发布话题失败:\n$formattedError", "创建话题失败")
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed || rejectChangedSession(session)) return@invokeLater
                    isOKActionEnabled = true
                    titleField.isEnabled = true
                    textArea.isEnabled = true
                    categoryComboBox.isEnabled = true
                    LinuxDoLog.error("Exception in create topic", t)
                    val errorMsg = t.message ?: "未知异常"
                    val shortError = if (errorMsg.length > 36) errorMsg.take(36) + "..." else errorMsg
                    statusLabel.text = "🔴 异常: $shortError"
                    statusLabel.toolTipText = errorMsg
                    val formattedError = ComposerErrors.format(errorMsg)
                    Messages.showErrorDialog(project, "发布话题异常:\n$formattedError", "异常")
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }

    private fun rejectChangedSession(session: Long): Boolean {
        if (session == SessionEpoch.current) return false
        textArea.isEnabled = true
        titleField.isEnabled = true
        categoryComboBox.isEnabled = true
        isOKActionEnabled = false
        statusLabel.text = "账号已切换，内容已保留；请确认账号后再提交"
        return true
    }

    override fun dispose() {
        backgroundTasks.dispose()
        previewAlarm.cancelAllRequests()
        tagAlarm.cancelAllRequests()
        tagPopup?.cancel()
        tagPopup = null
        super.dispose()
    }
}
