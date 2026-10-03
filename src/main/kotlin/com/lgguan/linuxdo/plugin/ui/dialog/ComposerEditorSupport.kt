package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.common.HostPlatform
import java.awt.*
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.*
import javax.swing.undo.UndoManager
import javax.swing.undo.CompoundEdit
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener

/** Shared formatting, undo/redo, upload and preview controls for both composers. */
internal class ComposerEditorSupport(private val project: Project, val text: JTextArea, val preview: ComposerPreviewView,
    status: JBLabel, tasks: BackgroundTasks, disposed: () -> Boolean, private val changed: () -> Unit,
    owner: Disposable, private val toggle: () -> Boolean, private val showPreview: () -> Unit) {
    private val undo = UndoManager()
    private var composing = false
    private val bindings = mutableListOf<Triple<KeyStroke, Any?, String>>()
    private val inputListener = object : InputMethodListener {
        override fun inputMethodTextChanged(event: InputMethodEvent) {
            composing = (event.text?.endIndex ?: 0) > event.committedCharacterCount
        }
        override fun caretPositionChanged(event: InputMethodEvent) = Unit
    }
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
        var detachedSelection: Pair<Int, Int>? = null
        val caretListener = java.beans.PropertyChangeListener { event ->
            val old = event.oldValue as? javax.swing.text.Caret
            val fresh = event.newValue as? javax.swing.text.Caret
            // updateUI removes the old caret before installing its replacement. Keep only offsets,
            // so queued IDE theme updates cannot observe and retain a transient empty selection.
            if (fresh == null && old != null) detachedSelection = old.mark to old.dot
            if (fresh != null) detachedSelection?.let { (mark, dot) ->
                detachedSelection = null
                fresh.setDot(mark.coerceAtMost(text.document.length))
                fresh.moveDot(dot.coerceAtMost(text.document.length))
            }
        }
        text.addPropertyChangeListener("caret", caretListener)
        val editableListener = java.beans.PropertyChangeListener { event ->
            if (event.propertyName == "enabled" || event.propertyName == "editable") {
                toolbar.components.filterIsInstance<JButton>().forEach { it.isEnabled = editable() }
            }
        }
        text.addPropertyChangeListener(editableListener)
        text.document.addUndoableEditListener(undoListener)
        text.addInputMethodListener(inputListener)
        Disposer.register(owner, Disposable {
            text.document.removeUndoableEditListener(undoListener)
            text.removeInputMethodListener(inputListener)
            text.removePropertyChangeListener(editableListener)
            text.removePropertyChangeListener("caret", caretListener)
            bindings.forEach { (stroke, previous, key) ->
                if (previous == null) text.inputMap.remove(stroke) else text.inputMap.put(stroke, previous)
                text.actionMap.remove(key)
            }
        })
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
            isEnabled = editable()
            toolTipText = help; isFocusable = false; isRolloverEnabled = true
            isContentAreaFilled = false; isBorderPainted = false
            preferredSize = JBUI.size(30, 30); border = JBUI.Borders.empty(6)
            addActionListener { if (editable()) action() }
            toolbar.add(this)
        }
        fun separator() = toolbar.add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = JBUI.size(1, 20) })
        fun item(menu: JPopupMenu, label: String, action: () -> Unit) {
            menu.add(JMenuItem(label).apply { addActionListener { if (editable()) action() } })
        }
        button("bold", "粗体 · Ctrl/Cmd+B") { inline("**", "粗体文本") }
        button("italic", "斜体 · Ctrl/Cmd+I") { inline("*", "斜体文本") }
        button("link", "插入链接 · Ctrl/Cmd+K", ::link)
        button("quote", "切换引用文本") { block("> ") }
        button("code", "代码 · Ctrl/Cmd+E", ::code)
        separator()
        button("upload", "上传图片", ::chooseImage)
        button("list", "切换无序列表") { block("- ") }
        lateinit var emoji: JButton
        emoji = button("emoji", "插入表情") {
            JPopupMenu().apply {
                listOf("👍", "🎉", "🔥", "💡", "❤️", "😂", "🤝", "☕").forEach { symbol ->
                    item(this, symbol) { edit { text.replaceSelection(symbol) } }
                }
            }.show(emoji, 0, emoji.height)
        }
        lateinit var more: JButton
        more = button("more", "更多格式：标题、列表、缩进、代码块、折叠和隐藏") {
            JPopupMenu().apply {
                val headings = JMenu("标题")
                (1..6).forEach { level -> headings.add(JMenuItem("${level} 级标题").apply {
                    addActionListener { if (editable()) block("#".repeat(level) + " ") }
                }) }
                add(headings)
                item(this, "有序列表") { block("1. ") }
                item(this, "任务列表") { block("- [ ] ") }
                item(this, "增加缩进") { apply(MarkdownEditing.indent(snapshot(), false)) }
                item(this, "减少缩进") { apply(MarkdownEditing.indent(snapshot(), true)) }
                item(this, "代码块…", ::codeBlock)
                addSeparator()
                item(this, "折叠详情") { wrap("[details=点击展开]\n", "\n[/details]", "详情内容") }
                item(this, "隐藏内容") { wrap("[spoiler]", "[/spoiler]", "隐藏内容") }
            }.show(more, 0, more.height)
        }
        separator()
        button("undo", "撤销 · Ctrl/Cmd+Z", ::undoEdit)
        button("redo", "重做 · Ctrl/Cmd+Shift+Z", ::redoEdit)
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
        fun bind(stroke: KeyStroke, key: String, action: () -> Unit) {
            bindings += Triple(stroke, text.inputMap.get(stroke), key)
            text.inputMap.put(stroke, key)
            text.actionMap.put(key, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) { if (editable()) action() }
            })
        }
        fun shortcut(code: Int, extra: Int = 0, action: () -> Unit) {
            val key = "composer-$code-$extra"
            bind(KeyStroke.getKeyStroke(code, modifier or extra), key, action)
        }
        shortcut(KeyEvent.VK_B) { inline("**", "粗体文本") }
        shortcut(KeyEvent.VK_I) { inline("*", "斜体文本") }
        shortcut(KeyEvent.VK_K, action = ::link)
        shortcut(KeyEvent.VK_E, action = ::code)
        shortcut(KeyEvent.VK_Z, action = ::undoEdit)
        shortcut(KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK, ::redoEdit)
        val enterStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)
        val ordinaryEnter = text.actionMap.get(text.inputMap.get(enterStroke))
        bind(enterStroke, "composer-enter") {
            val operation = if (composing) null else MarkdownEditing.enter(snapshot())
            if (operation != null) apply(operation) else if (!composing) ordinaryEnter?.actionPerformed(java.awt.event.ActionEvent(text, 0, "insert-break"))
        }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "composer-plain-enter") {
            if (!composing) ordinaryEnter?.actionPerformed(java.awt.event.ActionEvent(text, 0, "insert-break"))
        }
        // JTextArea normally inserts tabs. Here ordinary text explicitly follows focus navigation.
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "composer-indent") { tab(false) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK), "composer-outdent") { tab(true) }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK), "composer-focus-next") { text.transferFocus() }
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK), "composer-focus-previous") { text.transferFocusBackward() }
    }
    private fun editable() = text.isEnabled && text.isEditable
    private fun snapshot() = MarkdownEditing.Snapshot(text.text, text.caret.mark, text.caret.dot)
    private fun select(mark: Int, dot: Int) { text.caret.setDot(mark); text.caret.moveDot(dot) }
    private fun undoEdit() { if (undo.canUndo()) undo.undo() }
    private fun redoEdit() { if (undo.canRedo()) undo.redo() }
    private fun tab(backwards: Boolean) {
        if (composing) return
        val operation = MarkdownEditing.indent(snapshot(), backwards)
        // A valid outdent with nothing to remove must not move focus.
        if (operation != null) apply(operation)
        else if (MarkdownEditing.indent(snapshot(), false) == null) {
            if (backwards) text.transferFocusBackward() else text.transferFocus()
        }
    }
    fun resetUndo() = undo.discardAllEdits()
    private fun edit(action: () -> Unit) {
        val before = snapshot()
        val group = object : CompoundEdit() {
            var after: MarkdownEditing.Snapshot? = null
            override fun undo() { super.undo(); select(before.mark, before.dot) }
            override fun redo() { super.redo(); after?.let { select(it.mark, it.dot) } }
        }
        compound = group
        try { action() } finally { compound = null; group.after = snapshot(); group.end(); if (group.canUndo()) undo.addEdit(group) }
    }
    private fun apply(operation: MarkdownEditing.Edit?) {
        if (operation == null || !editable()) return
        edit { text.replaceRange(operation.replacement, operation.start, operation.end); select(operation.mark, operation.dot) }
        text.requestFocusInWindow()
    }
    private fun inline(marker: String, placeholder: String) = apply(MarkdownEditing.inline(snapshot(), marker, placeholder))
    private fun block(prefix: String) {
        val operation = MarkdownEditing.block(snapshot(), prefix)
        if (operation == null && MarkdownEditing.codeContext(snapshot())) {
            Messages.showInfoMessage(project, "代码块内请使用“代码块…”操作，正文已保留。", "格式操作")
        }
        apply(operation)
    }
    private fun wrap(prefix: String, suffix: String, placeholder: String) {
        val start = text.selectionStart
        val selected = text.selectedText ?: placeholder
        edit { text.replaceSelection(prefix + selected + suffix); text.select(start + prefix.length, start + prefix.length + selected.length) }
        text.requestFocusInWindow()
    }
    private fun link() {
        val captured = snapshot(); val target = MarkdownEditing.link(captured)
        val dialog = ComposerLinkDialog(project, target?.label ?: text.selectedText ?: "链接文本", target?.url.orEmpty())
        if (dialog.showAndGet() && editable() && snapshot() == captured) apply(MarkdownEditing.replaceLink(captured, dialog.label, dialog.url))
    }
    private fun code() = if (text.selectedText?.contains('\n') == true) apply(MarkdownEditing.codeBlock(snapshot(), "")) else inline("`", "代码")
    private fun codeBlock() {
        val captured = snapshot(); val existing = MarkdownEditing.fence(captured)
        if (existing == null && MarkdownEditing.codeContext(captured)) {
            Messages.showInfoMessage(project, "无法确认完整代码围栏，请补齐围栏或直接编辑；正文已保留。", "代码块")
            return
        }
        val dialog = ComposerCodeBlockDialog(project, existing?.language.orEmpty(), existing != null)
        if (dialog.showAndGet() && editable() && snapshot() == captured) apply(MarkdownEditing.codeBlock(captured, dialog.language, dialog.remove))
    }
    fun chooseImage() {
        val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptor(true, false, false, false, false, true)
        descriptor.title = "选择图片"
        com.intellij.openapi.fileChooser.FileChooser.chooseFiles(descriptor, project, null) { files ->
            files.forEach { upload.file(java.io.File(it.path)) }
        }
    }
}
