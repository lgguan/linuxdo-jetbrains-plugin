package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.ui.JBColor
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.ide.ui.LafManagerListener
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder

internal object ComposerAppearance {
    /** Update colors without replacing documents, undo stacks, or selected text. */
    fun followTheme(owner: Disposable, root: JComponent, text: JTextArea, panel: JPanel, title: JTextField? = null,
                    refresh: () -> Unit) {
        fun update() {
            DialogTheme.refresh(root)
            val scheme = EditorColorsManager.getInstance().globalScheme
            val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
            text.background = scheme.defaultBackground
            text.foreground = scheme.defaultForeground
            text.caretColor = scheme.defaultForeground
            text.selectionColor = scheme.getColor(com.intellij.openapi.editor.colors.EditorColors.SELECTION_BACKGROUND_COLOR)
                ?: UIUtil.getListSelectionBackground(true)
            text.selectedTextColor = scheme.getColor(com.intellij.openapi.editor.colors.EditorColors.SELECTION_FOREGROUND_COLOR)
                ?: scheme.defaultForeground
            val font = UIUtil.getFontWithFallback(Font(theme.fontName, Font.PLAIN, theme.fontSize))
            text.font = font.deriveFont(font.size2D.coerceAtLeast(14f))
            title?.font = font
            panel.background = scheme.defaultBackground
            (panel.components.filterIsInstance<JScrollPane>().firstOrNull())?.viewport?.background = scheme.defaultBackground
            refresh()
            panel.revalidate(); panel.repaint()
        }
        val connection = ApplicationManager.getApplication().messageBus.connect(owner)
        var closed = false
        com.intellij.openapi.util.Disposer.register(owner, Disposable { closed = true })
        fun later() = ApplicationManager.getApplication().invokeLater({
            if (!closed) update()
        }, ModalityState.any())
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { later() })
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { later() })
        update()
    }
    fun editor(text: JTextArea, toolbar: JComponent, counter: JLabel): JPanel {
        text.margin = JBUI.insets(14)
        text.font = text.font.deriveFont(text.font.size2D.coerceAtLeast(14f))
        toolbar.isOpaque = false
        toolbar.border = javax.swing.BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(5, 6))
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(5, 14, 8, 14)
            add(JLabel("Markdown").apply { foreground = JBColor.namedColor("ContextHelp.foreground", UIUtil.getContextHelpForeground()); font = font.deriveFont(11f) }, BorderLayout.WEST)
            counter.font = counter.font.deriveFont(11f)
            add(counter, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            background = text.background
            border = object : AbstractBorder() {
                override fun getBorderInsets(c: Component) = JBUI.insets(1)
                override fun getBorderInsets(c: Component, insets: Insets): Insets = JBUI.insets(1).also {
                    insets.set(it.top, it.left, it.bottom, it.right)
                }.let { insets }
                override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
                    val copy = g.create() as Graphics2D
                    try {
                        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        copy.color = JBColor.border(); copy.drawRoundRect(x, y, width - 1, height - 1, JBUI.scale(8), JBUI.scale(8))
                    } finally { copy.dispose() }
                }
            }
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(text).apply { border = JBUI.Borders.empty(); viewport.background = text.background }, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }
    }
}
