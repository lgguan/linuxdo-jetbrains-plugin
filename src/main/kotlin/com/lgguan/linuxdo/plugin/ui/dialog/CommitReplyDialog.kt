package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.model.ComposerCapabilities
import com.lgguan.linuxdo.plugin.model.PublishOutcome
import com.lgguan.linuxdo.plugin.model.UnconfirmedPublishException
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
    private val environment: ReplyComposerEnvironment = ForumReplyComposerEnvironment,
    private val requireExistingDraft: Boolean = false
) : DialogWrapper(project, true) {

    companion object {
        private val editors = mutableMapOf<String, CommitReplyDialog>()
        private fun editorKey(topicId: Long) = "${DiscourseApiClient.getBaseUrl()}:${com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().currentUser?.id}:${SessionEpoch.current}:topic_$topicId"
        fun openDraft(project: Project, topicId: Long) {
            val key = editorKey(topicId)
            editors[key]?.takeUnless { it.isDisposed }?.let { it.window?.toFront(); it.textArea.requestFocusInWindow(); return }
            val dialog = CommitReplyDialog(project, topicId, 1, "", null, null, requireExistingDraft = true)
            dialog.registryKey = key; editors[key] = dialog; dialog.show()
        }
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
    private var unconfirmed = false
    private var capabilities = ComposerCapabilities()
    private var draftBlocked = false
    private var suppressDraftChanges = false
    private var loadBaseline: String? = null
    private var pendingAction: (() -> Unit)? = null
    private val pendingQuotes = mutableListOf<String>()
    private val draftAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val draftStatus = JBLabel("正在读取论坛草稿…")
    private val targetLabel = JBLabel()
    private val publishCheck = JButton("检查发布结果").apply {
        isVisible = false
        addActionListener { checkPublishResult() }
    }
    private val draftRetry = JButton("重试同步").apply {
        isVisible = false
        addActionListener {
            if (draftSession.conflicted) resolveConflict()
            else if (!draftReady) loadDraft()
            else saveDraft()
        }
    }

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val previewView by lazy { ComposerPreviewView(backgroundTasks, { isDisposed }) }
    private val editorSupport by lazy {
        ComposerEditorSupport(project, textArea, previewView, statusLabel, backgroundTasks, { isDisposed }, ::onContentChanged,
            disposable, ::togglePreview, ::showPreview)
    }
    private val textArea = JBTextArea(12, 50)
    private val bodyCounterLabel = JBLabel("0 / 16 字符")

    // Live preview: High performance, styled JEditorPane honoring IDE theme
    private var previewPane: JEditorPane? = null
    private val previewAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    private val splitter = JBSplitter(false, 0.52f)
    private var isPreviewVisible = false
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
                    previewView.invalidate()
                    imageUpload.cancel()
                    draftBlocked = true
                    draftStatus.text = "账号已切换，已停止同步；正文仅保留在此窗口"
                }
                updateValidation()
            }
        }
        updateValidation()
        updatePreview()
        if (environment is ReplyPublishEnvironment) backgroundTasks.submit {
            val rules = environment.capabilities()
            ApplicationManager.getApplication().invokeLater({
                if (!isDisposed && draftSession.version == SessionEpoch.current) { capabilities = rules; updateValidation() }
            }, ModalityState.any())
        }
        initialQuote?.let { insertQuote(it) }
        loadDraft()
    }

    override fun beforeShowCallback() {
        super.beforeShowCallback()
        window?.minimumSize = Dimension(320, 460)
    }

    override fun createCenterPanel(): JComponent {
        val rootPanel = JPanel(BorderLayout(0, 8))
        rootPanel.border = JBUI.Borders.empty(8)
        rootPanel.preferredSize = Dimension(JBUI.scale(1000), JBUI.scale(640))

        val scheme = EditorColorsManager.getInstance().globalScheme
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val editorFont = UIUtil.getFontWithFallback(Font(theme.fontName, Font.PLAIN, theme.fontSize))

        // 1. Top IDE Native Information Banner
        val bannerPanel = createBannerPanel()
        rootPanel.add(bannerPanel, BorderLayout.NORTH)

        // 2. Middle Form Stack
        val formPanel = JPanel()
        formPanel.layout = BoxLayout(formPanel, BoxLayout.Y_AXIS)

        textArea.font = editorFont
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        textArea.background = scheme.defaultBackground
        textArea.foreground = scheme.defaultForeground
        textArea.emptyText.text = "输入回复，支持 Markdown；可拖放或粘贴图片。"

        if (floorNumber > 1 && replyToAuthor.isNotBlank()) {
            textArea.text = "@$replyToAuthor "
            textArea.caretPosition = textArea.text.length
        }

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
        rootPanel.add(JPanel(BorderLayout(6, 4)).apply {
            add(draftStatus, BorderLayout.CENTER)
            add(draftRetry, BorderLayout.EAST)
            add(JBLabel("草稿同步到论坛；本机不保存正文。重启仅恢复已同步内容。"), BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)

        setupEventListeners()
        ComposerAppearance.followTheme(disposable, rootPanel, textArea, editorPanel) {
            updateValidation()
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
        publishCheck.isVisible = unconfirmed
        val bodyText = textArea.text.trim()
        val bodyLen = bodyText.length
        val bodyValid = capabilities.bodyValid(bodyText, false)

        if (bodyValid) {
            bodyCounterLabel.text = "$bodyLen 字符"
            bodyCounterLabel.foreground = JBColor(0x57606A, 0x8B949E)
        } else {
            bodyCounterLabel.text = "$bodyLen / ${capabilities.minReplyBody}–${capabilities.maxBody} 字符"
            bodyCounterLabel.foreground = JBColor(0xCF222E, 0xF85149)
        }

        val loggedIn = environment.isLoggedIn
        isOKActionEnabled = bodyValid && loggedIn && draftReady && !draftBusy && !draftBlocked && !draftSession.conflicted && draftSession.version == SessionEpoch.current && !capabilities.readOnly && !publishing && !unconfirmed && imageUpload.pending == 0 && imageUpload.failures == 0

        if (!loggedIn) {
            statusLabel.text = "请先完成登录验证"
        } else if (!bodyValid) {
            statusLabel.text = "⚪ 回复内容需要 ${capabilities.minReplyBody}–${capabilities.maxBody} 个字符 (当前: $bodyLen)"
        } else if (imageUpload.failures > 0) {
            statusLabel.text = "图片上传失败，请重试或舍弃失败图片"
        } else if (imageUpload.pending > 0) {
            statusLabel.text = "还有 ${imageUpload.pending} 张图片正在上传"
        } else if (unconfirmed) {
            statusLabel.text = "发布结果未确认，请先在网页检查"
        } else if (capabilities.readOnly) {
            statusLabel.text = "论坛当前限制发帖"
        } else {
            statusLabel.text = "⚪ 就绪 (${PlatformShortcuts.submitLabel} 发送回复)"
        }
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
                    val verification = it is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
                    draftStatus.text = when {
                        draftSession.conflicted -> "草稿冲突，自动同步已暂停；请选择保留版本"
                        verification -> "请在侧边栏完成 Cloudflare 人机验证；正文已保留"
                        else -> "同步失败，正文已保留在窗口；请重试"
                    }
                    draftStatus.toolTipText = it.message
                    draftRetry.text = if (draftSession.conflicted) "选择版本" else if (verification) "验证后重试" else "重试同步"
                    draftRetry.isVisible = true
                }
                updateValidation()
            }, ModalityState.any())
        }
    }

    private fun restoreTarget(draft: ForumDraft): ReplyTarget {
        val restored = draft.target()
        if (draft.supported && (restored.floor == null || restored.author.isBlank()) && restored.postId != null) {
            val post = environment.getPost(restored.postId)
            require(post.topicId == topicId) { "草稿回复对象不属于此话题" }
            require(restored.floor == null || restored.floor == post.postNumber) { "草稿回复楼层无法确认" }
            return ReplyTarget(post.postNumber, post.username, post.id)
        }
        if(requireExistingDraft && restored.floor != null && restored.author.isBlank()) {
            val response = DiscourseApiClient.getTopicAroundPost(topicId, restored.floor).getOrThrow()
            require(response.id == topicId) { "草稿回复话题无法确认" }
            val post = response.postStream.posts.firstOrNull { it.postNumber == restored.floor } ?: error("草稿回复楼层已不可用")
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
            if(requireExistingDraft) {
                com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance().invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.DRAFTS, draftSession.version)
                if(draft.data == null) com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance().draftCleared(draftSession.key, draftSession.version)
                require(draft.data != null) { "草稿已消失，请刷新我的草稿核对" }
                require(draft.supported) { "草稿类型已改变，请在网页继续" }
                val topic = DiscourseApiClient.readerGet("/t/$topicId.json?track_visit=false", draftSession.version).getOrThrow().asJsonObject
                require(topic.get("id")?.asLong == topicId && topic.get("archetype")?.asString == "regular") { "无法确认普通话题，请在网页继续" }
            }
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
                editorSupport.resetUndo()
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
        if (imageUpload.pending > 0) { imageUpload.whenIdle { saveDraft(after) }; return }
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
                    editorSupport.resetUndo()
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
                if (imageUpload.pending > 0) {
                    draftStatus.text = "正在等待图片上传完成后保存…"
                    imageUpload.whenIdle {
                        if (imageUpload.failures == 0) saveDraft { close(CANCEL_EXIT_CODE) }
                        else { textArea.isEnabled = true; draftStatus.text = "图片上传失败，请重试或舍弃失败图片；窗口内容已保留" }
                    }
                } else saveDraft { close(CANCEL_EXIT_CODE) }
            }
            1 -> {
                if (draftSession.conflicted) { resolveConflict(); return }
                if (!draftReady) { loadDraft(); return }
                imageUpload.cancel()
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
        if (unconfirmed || publishing || imageUpload.pending > 0 || imageUpload.failures > 0 || capabilities.readOnly) return
        if (rejectChangedSession(session) || !draftReady || draftBlocked || draftSession.conflicted) return
        if (draftBusy) { pendingAction = { doOKAction() }; return }
        if (!environment.isLoggedIn) {
            statusLabel.text = "请先完成登录验证，内容已保留"
            return
        }
        val content = textArea.text
        if (content.isBlank()) {
            statusLabel.text = "❌ 回复内容不能为空"
            return
        }

        if (!capabilities.bodyValid(content, false)) {
            statusLabel.text = "❌ 内容过短 (要求正文 ${capabilities.minReplyBody}–${capabilities.maxBody} 个字符)"
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
                        if (environment is ReplyPublishEnvironment) environment.publishReply(topicId, content, replyTo, session, draftSession.key)
                        else PublishOutcome.Published(environment.createReply(topicId, content, replyTo, session))
                    }
                }

                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    draftBusy = false
                    publishing = false
                    if (rejectChangedSession(session)) { draftBlocked = true; return@invokeLater }
                    result.onSuccess { (outcome, cleanup) ->
                        val personal = com.lgguan.linuxdo.plugin.service.PersonalContentService.getInstance()
                        personal.invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.REPLIES, session)
                        personal.invalidate(com.lgguan.linuxdo.plugin.model.PersonalContentKind.DRAFTS, session)
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
                        runCatching {
                            when (outcome) {
                                is PublishOutcome.Published -> onReplySuccess?.invoke(outcome.post)
                                is PublishOutcome.Queued -> Messages.showInfoMessage(project, outcome.message, "已提交，等待审核")
                            }
                        }.onFailure {
                            LinuxDoLog.warn("Reply published but navigation failed: ${it.javaClass.simpleName}")
                        }
                    }.onFailure { err ->
                        unconfirmed = err is UnconfirmedPublishException
                        textArea.isEnabled = true
                        pendingQuotes.toList().also { pendingQuotes.clear() }.forEach(::insertQuote)
                        if (draftSession.conflicted) {
                            draftStatus.text = "草稿冲突，未发送回复；请选择保留版本"
                            draftRetry.text = "选择版本"
                            draftRetry.isVisible = true
                        }
                        updateValidation()
                        LinuxDoLog.warn("Failed to post reply: ${err.message}", err)
                        val errorMsg = ComposerErrors.parse(err)
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

    private fun checkPublishResult() {
        com.intellij.ide.BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity")
        if (unconfirmed && Messages.showYesNoDialog(project,
            "请先检查网页版。如果已确认没有发布或进入审核，可解除发送限制。是否已经确认？", "确认发布结果", Messages.getQuestionIcon()) == Messages.YES) {
            unconfirmed = false; updateValidation()
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
        imageUpload.cancel()
        previewView.dispose()
        backgroundTasks.dispose()
        previewAlarm.cancelAllRequests()
        super.dispose()
    }
}
