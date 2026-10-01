package com.lgguan.linuxdo.plugin.ui.dialog

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout

/** FlowLayout wraps children, but its default preferred size still demands one wide row. */
internal class ComposerWrapLayout : FlowLayout(LEFT, 4, 3) {
    override fun minimumLayoutSize(target: Container) = Dimension(0, 28)
    override fun preferredLayoutSize(target: Container): Dimension {
        val width = target.width.takeIf { it > 0 } ?: target.parent?.width?.takeIf { it > 0 } ?: 800
        val insets = target.insets
        val available = (width - insets.left - insets.right - hgap * 2).coerceAtLeast(1)
        var rowWidth = 0; var rowHeight = 0; var height = vgap * 2
        target.components.filter { it.isVisible }.forEach { child ->
            val size = child.preferredSize
            if (rowWidth > 0 && rowWidth + hgap + size.width > available) {
                height += rowHeight + vgap; rowWidth = 0; rowHeight = 0
            }
            rowWidth += size.width + if (rowWidth > 0) hgap else 0
            rowHeight = maxOf(rowHeight, size.height)
        }
        return Dimension(width, height + rowHeight + insets.top + insets.bottom)
    }
}
