package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Container
import java.awt.Window
import javax.swing.*

/** Some IDE delegates install plain Colors, so updateUI alone leaves their previous palette. */
internal object DialogTheme {
    fun refresh(root: JComponent, includeWindow: Boolean = true) {
        val window = if (includeWindow) SwingUtilities.getWindowAncestor(root) else null
        fun refreshTree(container: Container) {
            val selections = mutableListOf<Triple<javax.swing.text.JTextComponent, Int, Int>>()
            fun remember(c: java.awt.Component) {
                if (c is javax.swing.text.JTextComponent) selections.add(Triple(c, c.caret.dot, c.caret.mark))
                if (c is Container) c.components.forEach(::remember)
            }
            remember(container)
            SwingUtilities.updateComponentTreeUI(container)
            fun colors(c: java.awt.Component) {
                val key = when (c) {
                    is JPanel, is JViewport -> "Panel"
                    is JLabel -> "Label"
                    is JTextField -> "TextField"
                    is JTextArea -> "TextArea"
                    is JComboBox<*> -> "ComboBox"
                    is JCheckBox -> "CheckBox"
                    is JButton -> "Button"
                    is JList<*> -> "List"
                    else -> null
                }
                if (key != null) {
                    if (c.background !is JBColor) UIManager.getColor("$key.background")?.let { c.background = it }
                    if (c.foreground !is JBColor) UIManager.getColor("$key.foreground")?.let { c.foreground = it }
                }
                if (c is Container) c.components.forEach(::colors)
            }
            colors(container)
            selections.forEach { (field, dot, mark) ->
                field.caret.setDot(mark.coerceAtMost(field.document.length))
                field.caret.moveDot(dot.coerceAtMost(field.document.length))
            }
            if (container is Window) {
                container.background = UIUtil.getPanelBackground()
                container.ownedWindows.filter { it.isShowing }.forEach(::refreshTree)
            }
            container.invalidate(); container.validate(); container.repaint()
        }
        refreshTree(window ?: root)
    }

    fun follow(owner: Disposable, root: JComponent) {
        var closed = false
        Disposer.register(owner, Disposable { closed = true })
        ApplicationManager.getApplication().messageBus.connect(owner).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            ApplicationManager.getApplication().invokeLater({ if (!closed) refresh(root) }, ModalityState.any())
        })
    }
}
