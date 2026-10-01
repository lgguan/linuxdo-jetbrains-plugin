package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.common.HostPlatform
import com.lgguan.linuxdo.plugin.net.DocumentTrust
import java.awt.*
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.undo.UndoManager

/** Shared formatting, undo/redo, upload and preview controls for both composers. */
internal class ComposerEditorSupport(private val project: Project, val text: JTextArea, val preview: ComposerPreviewView,
    status: JBLabel, tasks: BackgroundTasks, disposed: () -> Boolean, private val changed: () -> Unit,
    owner: Disposable, private val toggle: () -> Boolean, private val showPreview: () -> Unit) {
    private val undo = UndoManager()
    private var retryButton: JButton? = null
    private var discardButton: JButton? = null
    private lateinit var previewButton: JButton
    private var compound: javax.swing.undo.CompoundEdit? = null
    private val undoListener = javax.swing.event.UndoableEditListener { event ->
        compound?.addEdit(event.edit) ?: undo.addEdit(event.edit)
    }
    val upload: ComposerImageUpload = ComposerImageUpload(project, text, status, tasks, disposed) {
        retryButton?.isVisible = upload.failures > 0
        discardButton?.isVisible = upload.failures > 0
        toolbar.revalidate()
        changed()
    }
    val toolbar = JPanel(ComposerWrapLayout()).apply {
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) { parent?.revalidate() }
        })
    }
    init {
        text.document.addUndoableEditListener(undoListener)
        Disposer.register(owner, Disposable { text.document.removeUndoableEditListener(undoListener) })
        fun button(id: String, help: String, action: () -> Unit): JButton = object : JButton(ComposerIcon(id)) {
            override fun paintComponent(g: Graphics) {
                if (model.isRollover || model.isPressed || isSelected) {
                    val copy = g.create() as Graphics2D
                    copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    copy.color = if (isSelected) JBColor(0xDDEBFA, 0x334C66) else JBColor(0xEAECEF, 0x3C3F43)
                    copy.fillRoundRect(0, 0, width, height, JBUI.scale(5), JBUI.scale(5)); copy.dispose()
                }
                super.paintComponent(g)
            }
        }.apply {
            name = "composer-$id"; accessibleContext.accessibleName = help.substringBefore(" ·")
            toolTipText = help; isFocusable = false; isRolloverEnabled = true
            isContentAreaFilled = false; isBorderPainted = false
            preferredSize = JBUI.size(30, 30); border = JBUI.Borders.empty(6)
            addActionListener { if (this@ComposerEditorSupport.text.isEnabled) action() }
            toolbar.add(this)
        }
        fun separator() = toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = JBUI.size(1, 20) })
        fun item(menu: JPopupMenu, label: String, action: () -> Unit) {
            menu.add(JMenuItem(label).apply { addActionListener { if (this@ComposerEditorSupport.text.isEnabled) action() } })
        }
        button("bold", "粗体 · Ctrl/Cmd+B") { wrap("**", "**", "粗体文本") }
        button("italic", "斜体 · Ctrl/Cmd+I") { wrap("*", "*", "斜体文本") }
        button("link", "插入链接 · Ctrl/Cmd+K", ::link)
        button("quote", "引用文本") { lines("> ") }
        button("code", "代码 · Ctrl/Cmd+E", ::code)
        separator()
        button("upload", "上传图片", ::chooseImage)
        button("list", "无序列表") { lines("- ") }
        lateinit var emoji: JButton
        emoji = button("emoji", "插入表情") {
            JPopupMenu().apply {
                listOf("👍", "🎉", "🔥", "💡", "❤️", "😂", "🤝", "☕").forEach { symbol ->
                    item(this, symbol) { text.replaceSelection(symbol); changed() }
                }
            }.show(emoji, 0, emoji.height)
        }
        lateinit var more: JButton
        more = button("more", "更多格式：标题、有序列表、任务、折叠和隐藏") {
            JPopupMenu().apply {
                item(this, "标题") { lines("### ") }
                item(this, "有序列表") { lines("1. ") }
                item(this, "任务列表") { lines("- [ ] ") }
                addSeparator()
                item(this, "折叠详情") { wrap("[details=点击展开]\n", "\n[/details]", "详情内容") }
                item(this, "隐藏内容") { wrap("[spoiler]", "[/spoiler]", "隐藏内容") }
            }.show(more, 0, more.height)
        }
        separator()
        button("undo", "撤销 · Ctrl/Cmd+Z") { if (undo.canUndo()) undo.undo() }
        button("redo", "重做 · Ctrl/Cmd+Shift+Z") { if (undo.canRedo()) undo.redo() }
        separator()
        previewButton = button("preview", "切换实时预览 · 按需打开") { previewButton.isSelected = toggle() }
        lateinit var previewMenu: JButton
        previewMenu = button("dropdown", "选择预览方式") {
            JPopupMenu().apply {
                item(this, "本地预览") { showPreview(); previewButton.isSelected = true; preview.local(text.text) }
                item(this, "论坛引擎预览") { showPreview(); previewButton.isSelected = true; preview.forum(text.text) }
            }.show(previewMenu, 0, previewMenu.height)
        }
        retryButton = button("retry", "重试失败的图片上传", upload::retry).apply { isVisible = false }
        discardButton = button("discard", "舍弃失败的图片任务，保留正文", upload::discardFailures).apply { isVisible = false }
        val modifier = if (HostPlatform.detect() == HostPlatform.MAC) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        fun shortcut(code: Int, extra: Int = 0, action: () -> Unit) {
            val key = "composer-$code-$extra"
            text.inputMap.put(KeyStroke.getKeyStroke(code, modifier or extra), key)
            text.actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: java.awt.event.ActionEvent?) { if (this@ComposerEditorSupport.text.isEnabled) action() } })
        }
        shortcut(KeyEvent.VK_B) { wrap("**", "**", "粗体文本") }
        shortcut(KeyEvent.VK_I) { wrap("*", "*", "斜体文本") }
        shortcut(KeyEvent.VK_K, action = ::link)
        shortcut(KeyEvent.VK_E, action = ::code)
        shortcut(KeyEvent.VK_Z) { if (undo.canUndo()) undo.undo() }
        shortcut(KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK) { if (undo.canRedo()) undo.redo() }
    }
    fun resetUndo() = undo.discardAllEdits()
    private fun edit(action: () -> Unit) {
        val group = javax.swing.undo.CompoundEdit()
        compound = group
        try { action() } finally { compound = null; group.end(); if (group.canUndo()) undo.addEdit(group) }
    }
    private fun wrap(prefix: String, suffix: String, placeholder: String) {
        val start = text.selectionStart
        val selected = text.selectedText ?: placeholder
        edit { text.replaceSelection(prefix + selected + suffix) }
        text.select(start + prefix.length, start + prefix.length + selected.length)
        text.requestFocusInWindow()
    }
    private fun lines(prefix: String) {
        val source = text.text
        val start = source.lastIndexOf('\n', text.selectionStart - 1) + 1
        val end = source.indexOf('\n', text.selectionEnd).let { if (it < 0) source.length else it }
        val block = source.substring(start, end).split('\n').joinToString("\n") { prefix + it }
        edit { text.replaceRange(block, start, end) }
        text.select(start, start + block.length)
    }
    private fun link() {
        val value = Messages.showInputDialog(project, "链接 URL", "插入链接", Messages.getQuestionIcon()) ?: return
        if (!DocumentTrust.isWebLink(value)) { Messages.showErrorDialog(project, "请输入 http 或 https 链接", "链接无效"); return }
        wrap("[", "]($value)", "链接文本")
    }
    private fun code() = if (text.selectedText?.contains('\n') == true) wrap("```\n", "\n```\n", "代码") else wrap("`", "`", "代码")
    fun chooseImage() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
        descriptor.title = "选择图片"
        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null) { files ->
            files.forEach { upload.file(java.io.File(it.path)) }
        }
    }
}
