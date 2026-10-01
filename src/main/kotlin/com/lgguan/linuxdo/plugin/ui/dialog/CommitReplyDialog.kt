package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.common.PlatformShortcuts
import com.lgguan.linuxdo.plugin.service.ReplyDraftSession
import com.lgguan.linuxdo.plugin.service.ReplyTarget
import com.lgguan.linuxdo.plugin.service.ForumDraft
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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

class CommitReplyDialog private constructor(
    private val project: Project,
    private val topicId: Long,
    private val floorNumber: Int,
    private val replyToAuthor: String,
    private val targetPostId: Long?,
    private val initialQuote: String?,
    private val onReplySuccess: ((com.lgguan.linuxdo.plugin.model.Post) -> Unit)? = null,
    private val draftSession: ReplyDraftSession = ReplyDraftSession(topicId),
    private val environment: ReplyComposerEnvironment = ForumReplyComposerEnvironment
) : DialogWrapper(project, true) {

    companion object {
        private val editors = mutableMapOf<String, CommitReplyDialog>()
        private fun editorKey(topicId: Long) = "${DiscourseApiClient.getBaseUrl()}:${com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().currentUser?.id}:$topicId"
        fun open(project: Project, topicId: Long, floor: Int, author: String, postId: Long? = null,
                 quote: String? = null, onSuccess: ((com.lgguan.linuxdo.plugin.model.Post) -> Unit)? = null) {
            val key = editorKey(topicId)
            val existing = editors[key]?.takeUnless { it.isDisposed }
            if (existing != null) {
                quote?.let { existing.insertQuote(it) }
                existing.window?.toFront()
                existing.textArea.requestFocusInWindow()
                return
            }
            val dialog = CommitReplyDialog(project, topicId, floor, author, postId, quote, onSuccess)
            dialog.registryKey = key
            editors[key] = dialog
            dialog.show()
        }
    }

    private var registryKey: String? = null
    private var target = ReplyTarget(floorNumber.takeIf { it > 1 }, replyToAuthor, targetPostId)
    private var draftReady = false
    private var draftBusy = false
    private var publishing = false
    private var draftBlocked = false
    private var suppressDraftChanges = false
    private var loadBaseline: String? = null
    private var pendingAction: (() -> Unit)? = null
    private val pendingQuotes = mutableListOf<String>()
    private val draftAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val draftStatus = JBLabel("正在读取论坛草稿…")
    private val targetLabel = JBLabel()
    private val previewStatus = JBLabel("预览已开启（本地 Markdown）")
    private val draftRetry = JButton("重试同步").apply {
        isVisible = false
        addActionListener {
            if (draftSession.conflicted) resolveConflict()
            else if (!draftReady) loadDraft()
            else saveDraft()
        }
    }

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val textArea = JBTextArea(12, 50)
    private val bodyCounterLabel = JBLabel("0 / 16 字符")

    // Live preview: High performance, styled JEditorPane honoring IDE theme
    private var previewPane: JEditorPane? = null
    private val previewAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val splitter = JBSplitter(false, 0.52f)
    private var isPreviewVisible = true
    private var previewContainer: JComponent? = null

    private val statusLabel = object : JBLabel("⚪ 就绪 (${PlatformShortcuts.submitLabel} 发送)") {
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
        title = "Commit Revision / Reply (Issue #$topicId)"
        setOKButtonText("Commit (${PlatformShortcuts.submitLabel})")
        setCancelButtonText("Cancel")
        init()
        environment.addAuthListener(disposable) {
            if (!isDisposed) {
                if (draftSession.version != SessionEpoch.current) {
                    draftAlarm.cancelAllRequests()
                    draftBlocked = true
                    draftStatus.text = "账号已切换，已停止同步；正文仅保留在此窗口"
                }
                updateValidation()
            }
        }
        updateValidation()
        updatePreview()
        initialQuote?.let { insertQuote(it) }
        loadDraft()
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 8))
        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.preferredSize = Dimension(JBUI.scale(820), JBUI.scale(520))

        val scheme = EditorColorsManager.getInstance().globalScheme
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val editorFont = UIUtil.getFontWithFallback(Font(theme.fontName, Font.PLAIN, theme.fontSize))

        // 1. Top IDE Native Information Banner
        val bannerPanel = createBannerPanel()
        rootPanel.add(bannerPanel, BorderLayout.NORTH)

        // 2. Middle Form Stack
        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

        // Formatting ActionToolbar
        val toolbar = createFormattingToolbar()
        formPanel.add(toolbar)
        formPanel.add(Box.createVerticalStrut(JBUI.scale(4)))

        // 3. Editor & Live Preview (Splitter)
        textArea.font = editorFont
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = scheme.defaultBackground
        textArea.foreground = scheme.defaultForeground
        textArea.emptyText.text = "Write your commit message / reply in Markdown (${PlatformShortcuts.submitLabel} to send)... 拖放或粘贴图片以插入"

        if (floorNumber > 1 && replyToAuthor.isNotBlank()) {
            textArea.text = "@$replyToAuthor "
            textArea.caretPosition = textArea.text.length
        }

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
        editorFooter.add(previewStatus, BorderLayout.WEST)
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
            kit.styleSheet.addRule("body { font-family: ${theme.fontName}, -apple-system, sans-serif; font-size: ${theme.fontSize}pt; color: ${theme.fgHex}; background-color: ${theme.bgHex}; margin: 10px; line-height: 1.5; }")
            kit.styleSheet.addRule("h1 { color: ${theme.fgHex}; font-size: 15pt; font-weight: bold; margin: 8px 0 4px 0; border-bottom: 1px solid ${theme.borderHex}; }")
            kit.styleSheet.addRule("h2 { color: ${theme.fgHex}; font-size: 13pt; font-weight: bold; margin: 6px 0 3px 0; }")
            kit.styleSheet.addRule("h3 { color: ${theme.fgHex}; font-size: 12pt; font-weight: bold; margin: 4px 0 2px 0; }")
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
        rootPanel.add(JPanel(BorderLayout(6, 4)).apply {
            add(draftStatus, BorderLayout.CENTER)
            add(draftRetry, BorderLayout.EAST)
            add(JBLabel("草稿同步到论坛；本机不保存正文。重启仅恢复已同步内容。"), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)

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

        val targetDesc = if (floorNumber > 1 && replyToAuthor.isNotBlank()) {
            "<html>Target: <b>Issue #$topicId</b> (Floor #$floorNumber by <code>@$replyToAuthor</code>)</html>"
        } else {
            "<html>Target: <b>Issue #$topicId</b> (Original Specification)</html>"
        }

        val infoBox = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JBLabel(AllIcons.General.Information))
            val tipLabel = targetLabel.apply {
                text = targetDesc
                font = font.deriveFont(Font.PLAIN, 12f)
            }
            add(tipLabel)
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
            previewStatus.text = if (isPreviewVisible) "预览已开启（本地 Markdown）" else "预览已隐藏"
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
        toolbar.add(makeFlatBtn(icon = AllIcons.Actions.Upload, tooltip = "上传图片附件 (支持 ${PlatformShortcuts.pasteLabel} 直接粘贴图片)") {
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

    private fun setupEventListeners() {
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onContentChanged()
            override fun removeUpdate(e: DocumentEvent?) = onContentChanged()
            override fun changedUpdate(e: DocumentEvent?) = onContentChanged()
        })

        // The IDE paste action is the sole keyboard paste handler, including custom keymaps.
        DumbAwareAction.create {
            if (!handleClipboardImagePaste()) {
                pasteClipboardText()
            }
        }.registerCustomShortcutSet(CommonShortcuts.getPaste(), textArea, disposable)

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

    private fun onContentChanged() {
        updateValidation()
        schedulePreviewUpdate()
        if (!suppressDraftChanges && draftReady && !draftBlocked && !draftSession.conflicted) {
            draftStatus.text = "尚未同步；停止输入 2 秒后保存"
            draftAlarm.cancelAllRequests()
            draftAlarm.addRequest({ saveDraft() }, 2000, ModalityState.any())
        }
    }

    private fun updateValidation() {
        val bodyText = textArea.text.trim()
        val bodyLen = bodyText.length
        val bodyValid = bodyLen >= 16

        if (bodyValid) {
            bodyCounterLabel.text = "$bodyLen 字符"
            bodyCounterLabel.foreground = JBColor(0x57606A, 0x8B949E)
        } else {
            bodyCounterLabel.text = "$bodyLen / 16 字符"
            bodyCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        }

        val loggedIn = environment.isLoggedIn
        isOKActionEnabled = bodyValid && loggedIn && draftReady && !draftBusy && !draftBlocked && !draftSession.conflicted && draftSession.version == SessionEpoch.current

        if (!loggedIn) {
            statusLabel.text = "请先完成登录验证"
        } else if (!bodyValid) {
            statusLabel.text = "⚪ 回复内容至少需要 16 个字符 (当前: $bodyLen)"
        } else {
            statusLabel.text = "⚪ 就绪 (${PlatformShortcuts.submitLabel} 发送回复)"
        }
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

    private fun insertQuote(quote: String) {
        if (!draftReady || publishing) { pendingQuotes.add(quote); return }
        textArea.insert(quote, textArea.caretPosition)
        textArea.requestFocusInWindow()
    }

    private fun showTarget() {
        targetLabel.text = "Issue #$topicId · " + (target.floor?.let { "回复 #$it 楼 ${target.author.takeIf(String::isNotBlank)?.let { name -> "@$name" }.orEmpty()}" } ?: "回复话题")
    }

    private fun <T> draftWork(work: () -> T, complete: (T) -> Unit) {
        if (draftBusy || draftBlocked || draftSession.version != SessionEpoch.current) return
        draftBusy = true
        draftRetry.isVisible = false
        updateValidation()
        backgroundTasks.submit {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                draftBusy = false
                if (rejectChangedSession(draftSession.version)) {
                    draftBlocked = true
                    pendingAction = null
                    draftStatus.text = "账号已切换，已停止同步；正文仅保留在此窗口"
                    return@invokeLater
                }
                result.onSuccess {
                    complete(it)
                    val next = pendingAction
                    pendingAction = null
                    next?.invoke()
                }.onFailure {
                    pendingAction = null
                    textArea.isEnabled = true
                    draftStatus.text = if (draftSession.conflicted) "草稿冲突，自动同步已暂停；请选择保留版本" else "同步失败，正文已保留在窗口；请重试"
                    draftStatus.toolTipText = it.message
                    draftRetry.text = if (draftSession.conflicted) "选择版本" else "重试同步"
                    draftRetry.isVisible = true
                }
                updateValidation()
            }, ModalityState.any())
        }
    }

    private fun restoreTarget(draft: ForumDraft): ReplyTarget {
        val restored = draft.target()
        if (draft.supported && restored.floor == null && restored.postId != null) {
            val post = environment.getPost(restored.postId)
            require(post.topicId == topicId) { "草稿回复对象不属于此话题" }
            return ReplyTarget(post.postNumber, post.username, post.id)
        }
        return restored
    }

    private fun loadDraft() {
        if (loadBaseline == null) loadBaseline = textArea.text
        textArea.isEnabled = false
        draftStatus.text = "正在读取论坛草稿…"
        draftWork({
            val draft = draftSession.load()
            draft to restoreTarget(draft)
        }) { (draft, restored) ->
            if (!draft.supported) {
                draftBlocked = true
                draftStatus.text = "此草稿为编辑、私信或其他类型，请在浏览器继续"
                draftRetry.text = "在浏览器打开"
                draftRetry.actionListeners.forEach { draftRetry.removeActionListener(it) }
                draftRetry.addActionListener { com.intellij.ide.BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/t/$topicId") }
                draftRetry.isVisible = true
                return@draftWork
            }
            var useServer = draft.data != null
            if (useServer && textArea.text != loadBaseline) {
                val choice = ReplyDraftConflictDialog(project, textArea.text, draft.body, target.floor, restored.floor)
                choice.show()
                when (choice.exitCode) {
                    ReplyDraftConflictDialog.LOCAL -> useServer = false
                    ReplyDraftConflictDialog.SERVER -> Unit
                    else -> {
                        textArea.isEnabled = true
                        draftStatus.text = "正文已保留，请选择版本后再同步"
                        draftRetry.isVisible = true
                        return@draftWork
                    }
                }
            }
            if (useServer) {
                suppressDraftChanges = true
                textArea.text = draft.body
                textArea.caretPosition = textArea.text.length
                suppressDraftChanges = false
                target = restored
            }
            draftReady = true
            textArea.isEnabled = true
            showTarget()
            draftStatus.text = if (draft.data != null) "已恢复论坛回复草稿" else "尚无论坛草稿"
            pendingQuotes.toList().also { pendingQuotes.clear() }.forEach(::insertQuote)
            if (textArea.text != draft.body) {
                draftAlarm.addRequest({ saveDraft() }, 2000, ModalityState.any())
                draftStatus.text = "尚未同步；停止输入 2 秒后保存"
            }
        }
    }

    private fun saveDraft(after: (() -> Unit)? = null) {
        draftAlarm.cancelAllRequests()
        if (draftBusy) { if (after != null) pendingAction = { saveDraft(after) }; return }
        if (!draftReady || draftBlocked || draftSession.conflicted) return
        val body = textArea.text
        val replyTarget = target
        draftStatus.text = "正在同步论坛草稿…"
        draftWork({ draftSession.save(body, replyTarget) }) {
            draftStatus.text = if (textArea.text == body) "已同步到论坛" else "尚有未同步修改"
            if (textArea.text != body) {
                draftAlarm.addRequest({ saveDraft(after) }, 2000, ModalityState.any())
            } else after?.invoke()
        }
    }

    private fun resolveConflict() {
        draftAlarm.cancelAllRequests()
        val local = textArea.text
        draftWork({ draftSession.load().let { it to restoreTarget(it) } }) { (server, serverTarget) ->
            if (!server.supported) {
                draftBlocked = true
                draftStatus.text = "服务器草稿类型已改变，请在浏览器继续；本地正文已保留"
                return@draftWork
            }
            val dialog = ReplyDraftConflictDialog(project, local, server.body, target.floor, serverTarget.floor)
            dialog.show()
            when (dialog.exitCode) {
                ReplyDraftConflictDialog.LOCAL -> {
                    draftSession.choose(server)
                    draftStatus.text = "已选择本地版本，等待同步"
                    pendingAction = { saveDraft() }
                }
                ReplyDraftConflictDialog.SERVER -> {
                    draftSession.choose(server)
                    suppressDraftChanges = true
                    textArea.text = server.body
                    textArea.caretPosition = textArea.text.length
                    suppressDraftChanges = false
                    target = serverTarget
                    showTarget()
                    draftStatus.text = "已采用服务器版本"
                }
                else -> {
                    draftStatus.text = "草稿冲突，自动同步仍暂停"
                    draftRetry.isVisible = true
                }
            }
        }
    }

    override fun doCancelAction() {
        if (publishing) { statusLabel.text = "正在发送，请等待提交结果"; return }
        if (draftBlocked) {
            if ((textArea.text.isNotBlank() || pendingQuotes.isNotEmpty()) && Messages.showYesNoDialog(project,
                    "同步已停止。本机未保存正文，关闭将舍弃此窗口内容；论坛草稿会保留。", "关闭回复窗口", Messages.getQuestionIcon()) != Messages.YES) return
            super.doCancelAction()
            return
        }
        if (draftBusy) { pendingAction = { doCancelAction() }; draftStatus.text = "正在等待当前草稿请求结束…"; return }
        val choice = Messages.showDialog(project, "正文仅在窗口中暂存；重启依赖已同步的论坛草稿。", "关闭回复窗口",
            arrayOf("保存草稿并关闭", "舍弃草稿", "继续编辑"), 2, Messages.getQuestionIcon())
        when (choice) {
            0 -> {
                if (draftSession.conflicted) { resolveConflict(); return }
                if (!draftReady) { loadDraft(); return }
                textArea.isEnabled = false
                saveDraft { close(CANCEL_EXIT_CODE) }
            }
            1 -> {
                if (draftSession.conflicted) { resolveConflict(); return }
                if (!draftReady) { loadDraft(); return }
                draftAlarm.cancelAllRequests()
                textArea.isEnabled = false
                draftWork({ draftSession.clearOwned() }) { cleanup ->
                    if (cleanup == ReplyDraftSession.Cleanup.OTHER_CLIENT) Messages.showInfoMessage(project,
                        "服务器草稿已由其他客户端修改，已保留该版本；此窗口内容将舍弃。", "草稿已保留")
                    close(CANCEL_EXIT_CODE)
                }
            }
        }
    }

    override fun doOKAction() {
        val session = draftSession.version
        if (rejectChangedSession(session) || !draftReady || draftBlocked || draftSession.conflicted) return
        if (draftBusy) { pendingAction = { doOKAction() }; return }
        if (!environment.isLoggedIn) {
            statusLabel.text = "请先完成登录验证，内容已保留"
            return
        }
        val content = textArea.text.trim()
        if (content.isBlank()) {
            statusLabel.text = "❌ 回复内容不能为空"
            return
        }

        if (content.length < 16) {
            statusLabel.text = "❌ 内容过短 (Discourse 要求正文至少 16 个字符)"
            return
        }

        statusLabel.text = "⏳ 正在提交回复并同步至论坛..."
        isOKActionEnabled = false
        textArea.isEnabled = false

        draftAlarm.cancelAllRequests()
        draftBusy = true
        publishing = true
        val replyTarget = target
        backgroundTasks.submit {
            try {
                val replyTo = replyTarget.floor
                LinuxDoLog.info("Submitting reply to topic #$topicId (replyTo=$replyTo)...")
                val result = runCatching {
                    draftSession.publish(content, replyTarget) {
                        environment.createReply(topicId, content, replyTo, session)
                    }
                }

                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    draftBusy = false
                    publishing = false
                    if (rejectChangedSession(session)) { draftBlocked = true; return@invokeLater }
                    result.onSuccess { (post, cleanup) ->
                        LinuxDoLog.info("Reply successfully posted to topic #$topicId")
                        statusLabel.text = "🟢 回复发送成功！正在更新..."
                        if (cleanup.isFailure) Messages.showInfoMessage(project,
                            "回复已发送；草稿清理未能确认，请到论坛检查。请勿重复发送回复。", "回复已发送")
                        else if (cleanup.getOrNull() == ReplyDraftSession.Cleanup.OTHER_CLIENT) Messages.showInfoMessage(project,
                            "回复已发送；其他客户端的新草稿已保留。", "回复已发送")
                        close(OK_EXIT_CODE)
                        if (pendingQuotes.isNotEmpty()) {
                            val quoted = pendingQuotes.joinToString("")
                            pendingQuotes.clear()
                            open(project, topicId, replyTarget.floor ?: 1, replyTarget.author, replyTarget.postId, quoted, onReplySuccess)
                        }
                        // Navigation failure must never turn a confirmed write into a retryable submission.
                        runCatching { onReplySuccess?.invoke(post) }.onFailure {
                            LinuxDoLog.warn("Reply published but navigation failed: ${it.javaClass.simpleName}")
                        }
                    }.onFailure { err ->
                        textArea.isEnabled = true
                        pendingQuotes.toList().also { pendingQuotes.clear() }.forEach(::insertQuote)
                        if (draftSession.conflicted) {
                            draftStatus.text = "草稿冲突，未发送回复；请选择保留版本"
                            draftRetry.text = "选择版本"
                            draftRetry.isVisible = true
                        }
                        updateValidation()
                        LinuxDoLog.warn("Failed to post reply: ${err.message}", err)
                        val errorMsg = ComposerErrors.parse(err.message)
                        val shortError = if (errorMsg.length > 36) errorMsg.take(36) + "..." else errorMsg
                        statusLabel.text = "🔴 发送失败: $shortError"
                        statusLabel.toolTipText = errorMsg
                        val formattedError = ComposerErrors.format(errorMsg)
                        Messages.showErrorDialog(project, "回复提交失败:\n$formattedError", "Commit Failed")
                    }
                }, ModalityState.any())
            } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    draftBusy = false
                    publishing = false
                    if (rejectChangedSession(session)) { draftBlocked = true; return@invokeLater }
                    textArea.isEnabled = true
                    updateValidation()
                    LinuxDoLog.error("Exception in commit reply", t)
                    val errorMsg = t.message ?: "未知异常"
                    val shortError = if (errorMsg.length > 36) errorMsg.take(36) + "..." else errorMsg
                    statusLabel.text = "🔴 异常: $shortError"
                    statusLabel.toolTipText = errorMsg
                    val formattedError = ComposerErrors.format(errorMsg)
                    Messages.showErrorDialog(project, "回复提交异常:\n$formattedError", "Commit Exception")
                }, ModalityState.any())
            }
        }
    }

    private fun rejectChangedSession(session: Long): Boolean {
        if (session == SessionEpoch.current) return false
        textArea.isEnabled = true
        isOKActionEnabled = false
        statusLabel.text = "账号已切换，内容已保留；请确认账号后再提交"
        return true
    }

    override fun dispose() {
        registryKey?.let { if (editors[it] === this) editors.remove(it) }
        draftAlarm.cancelAllRequests()
        backgroundTasks.dispose()
        previewAlarm.cancelAllRequests()
        super.dispose()
    }
}
