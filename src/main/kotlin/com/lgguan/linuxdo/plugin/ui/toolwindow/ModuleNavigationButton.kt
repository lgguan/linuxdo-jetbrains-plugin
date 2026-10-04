package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JToggleButton

/** Icon-only module navigation with a persistent selection indicator in either IDE theme. */
internal class ModuleNavigationButton(label: String, icon: Icon) : JToggleButton(icon) {
    init {
        name = "module-$label"
        toolTipText = label
        getAccessibleContext().accessibleName = label
        isOpaque = false
        isContentAreaFilled = false
        isBorderPainted = false
        isRolloverEnabled = true
        border = JBUI.Borders.empty(4)
        preferredSize = JBUI.size(28, 28)
        minimumSize = preferredSize
        maximumSize = preferredSize
        addItemListener {
            toolTipText = if (isSelected) "$label（当前模块）" else label
            repaint()
        }
    }

    override fun paintComponent(g: Graphics) {
        val copy = g.create() as Graphics2D
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isSelected || model.isRollover || model.isPressed) {
                copy.color = if (isSelected) {
                    JBColor.namedColor("ActionButton.pressedBackground", JBColor(0xDDEBFA, 0x334C66))
                } else {
                    JBColor.namedColor("ActionButton.hoverBackground", JBColor(0xEAECEF, 0x3C3F43))
                }
                copy.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            }
            if (isSelected) {
                copy.color = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x6B9BFA))
                copy.fillRoundRect(JBUI.scale(6), height - JBUI.scale(3), width - JBUI.scale(12),
                    JBUI.scale(2), JBUI.scale(2), JBUI.scale(2))
            }
            if (hasFocus()) {
                copy.color = JBColor.namedColor("Component.focusColor", JBColor(0x3574F0, 0x6B9BFA))
                copy.drawRoundRect(1, 1, width - 3, height - 3, JBUI.scale(6), JBUI.scale(6))
            }
        } finally {
            copy.dispose()
        }
        super.paintComponent(g)
    }
}
