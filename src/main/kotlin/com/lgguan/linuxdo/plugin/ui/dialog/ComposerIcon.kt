package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.geom.Path2D
import javax.swing.Icon

/** Small, theme-aware vector glyphs following the forum's formatting controls. */
internal class ComposerIcon(private val kind: String) : Icon {
    override fun getIconWidth() = JBUI.scale(18)
    override fun getIconHeight() = JBUI.scale(18)
    override fun paintIcon(c: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.translate(x, y); g.scale(iconWidth / 18.0, iconHeight / 18.0)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = if (c.isEnabled) UIUtil.getLabelForeground() else UIUtil.getLabelDisabledForeground()
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            fun line(x1: Int, y1: Int, x2: Int, y2: Int) = g.drawLine(x1, y1, x2, y2)
            fun path(vararg points: Int) {
                val p = Path2D.Double(); p.moveTo(points[0].toDouble(), points[1].toDouble())
                for (i in 2 until points.size step 2) p.lineTo(points[i].toDouble(), points[i + 1].toDouble())
                g.draw(p)
            }
            when (kind) {
                "bold", "italic" -> {
                    g.font = Font(Font.SERIF, if (kind == "bold") Font.BOLD else Font.BOLD or Font.ITALIC, 18)
                    g.drawString(if (kind == "bold") "B" else "I", if (kind == "bold") 3 else 5, 15)
                }
                "link" -> {
                    g.rotate(-Math.PI / 4, 9.0, 9.0)
                    g.drawRoundRect(2, 6, 9, 6, 6, 6); g.drawRoundRect(7, 6, 9, 6, 6, 6)
                    line(6, 9, 12, 9)
                }
                "quote" -> {
                    for (left in listOf(3, 11)) { g.fillRoundRect(left, 3, 5, 6, 1, 1); path(left + 4, 9, left + 3, 12, left, 14) }
                }
                "code" -> { path(5, 5, 1, 9, 5, 13); path(13, 5, 17, 9, 13, 13); line(11, 3, 7, 15) }
                "upload" -> { path(5, 6, 9, 2, 13, 6); line(9, 2, 9, 12); path(2, 11, 2, 16, 16, 16, 16, 11) }
                "list" -> { for (row in listOf(4, 9, 14)) { g.fillOval(1, row - 1, 3, 3); line(7, row, 16, row) } }
                "emoji" -> { g.drawOval(2, 2, 14, 14); g.fillOval(5, 6, 2, 2); g.fillOval(11, 6, 2, 2); g.drawArc(5, 7, 8, 6, 190, 160) }
                "more" -> { line(3, 9, 15, 9); line(9, 3, 9, 15) }
                "undo", "redo" -> {
                    if (kind == "redo") { g.translate(18.0, 0.0); g.scale(-1.0, 1.0) }
                    path(6, 3, 2, 7, 6, 11); line(2, 7, 10, 7); g.drawArc(6, 7, 10, 8, -90, 180)
                }
                "preview" -> { val p = Path2D.Double(); p.moveTo(1.0, 9.0); p.curveTo(5.0, 1.0, 13.0, 1.0, 17.0, 9.0); p.curveTo(13.0, 17.0, 5.0, 17.0, 1.0, 9.0); g.draw(p); g.drawOval(6, 6, 6, 6) }
                "dropdown" -> path(5, 7, 9, 11, 13, 7)
                "retry" -> { g.drawArc(2, 2, 14, 14, 45, 280); path(11, 1, 15, 2, 15, 6) }
                "discard" -> { line(4, 4, 14, 14); line(14, 4, 4, 14) }
            }
        } finally { g.dispose() }
    }
}
