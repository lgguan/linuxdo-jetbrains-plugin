package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.lgguan.linuxdo.plugin.model.Topic
import java.awt.Point
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JScrollPane

internal object TopicListReconciler {
    fun apply(list: JList<Topic>, model: DefaultListModel<Topic>, scroll: JScrollPane, next: List<Topic>, preserve: Boolean) {
        val selectedId = list.selectedValue?.id
        val first = list.firstVisibleIndex
        val anchorId = if (first >= 0) model[first].id else null
        val offset = if (first >= 0) scroll.viewport.viewPosition.y - (list.getCellBounds(first, first)?.y ?: 0) else 0
        // Move or update rows by ID so the model and selection survive a refresh.
        next.forEachIndexed { index, topic ->
            if (index < model.size() && model[index].id == topic.id) {
                if (model[index] != topic) model.set(index, topic)
            } else {
                val existing = (index until model.size()).firstOrNull { model[it].id == topic.id }
                existing?.let { model.remove(it) }
                model.add(index, topic)
            }
        }
        while (model.size() > next.size) model.remove(model.size() - 1)
        if (preserve) {
            list.selectedIndex = next.indexOfFirst { it.id == selectedId }
            val anchor = next.indexOfFirst { it.id == anchorId }
            if (anchor >= 0) {
                val y = (list.getCellBounds(anchor, anchor)?.y ?: 0) + offset
                scroll.viewport.viewPosition = Point(0, y.coerceAtLeast(0))
            }
        } else {
            list.clearSelection()
            scroll.viewport.viewPosition = Point(0, 0)
        }
    }
}
