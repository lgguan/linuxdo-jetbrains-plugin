package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.border.AbstractBorder

internal object ComposerAppearance {
    fun editor(text: JTextArea, toolbar: JComponent, counter: JLabel): JPanel {
        text.margin = JBUI.insets(14)
        text.font = text.font.deriveFont(text.font.size2D.coerceAtLeast(14f))
        toolbar.isOpaque = false
        toolbar.border = javax.swing.BorderFactory.createCompoundBorder(
            JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(5, 6))
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false; border = JBUI.Borders.empty(5, 14, 8, 14)
            add(JLabel("Markdown").apply { foreground = UIUtil.getContextHelpForeground(); font = font.deriveFont(11f) }, BorderLayout.WEST)
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
