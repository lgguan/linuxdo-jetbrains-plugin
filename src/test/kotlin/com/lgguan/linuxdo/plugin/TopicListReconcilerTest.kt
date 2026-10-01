package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.ui.toolwindow.TopicListReconciler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import javax.swing.*
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

class TopicListReconcilerTest {
    @Test fun `refresh preserves selected ID and scrolled row with changing row heights`() {
        SwingUtilities.invokeAndWait {
            val model = DefaultListModel<Topic>()
            (1L..30L).forEach { model.addElement(Topic(it, "topic $it")) }
            val list = JList(model).apply {
                cellRenderer = ListCellRenderer { _, topic, _, _, _ -> JLabel(topic.title).apply {
                    preferredSize = Dimension(250, if (topic.title.startsWith("tall")) 50 else 30)
                } }
            }
            val scroll = JScrollPane(list).apply { setSize(280, 200); doLayout() }
            list.setSize(250, 900)
            list.selectedIndex = 14
            scroll.viewport.viewPosition = Point(0, 307)
            assertEquals(10, list.firstVisibleIndex)
            var emptied = false
            model.addListDataListener(object : ListDataListener {
                override fun intervalAdded(e: ListDataEvent?) = Unit
                override fun contentsChanged(e: ListDataEvent?) = Unit
                override fun intervalRemoved(e: ListDataEvent?) { if (model.isEmpty) emptied = true }
            })
            val refreshed = listOf(Topic(99, "new")) + (1L..30L).map { Topic(it, if (it == 1L) "tall changed" else "topic $it") }
            TopicListReconciler.apply(list, model, scroll, refreshed, true)
            assertFalse(emptied)
            assertEquals(15L, list.selectedValue.id)
            val first = list.firstVisibleIndex
            assertEquals(11L, model[first].id)
            assertEquals(7, scroll.viewport.viewPosition.y - list.getCellBounds(first, first).y)
        }
    }

    @Test fun `switching conditions resets selection scroll and removes old rows`() {
        SwingUtilities.invokeAndWait {
            val model = DefaultListModel<Topic>()
            (1L..30L).forEach { model.addElement(Topic(it, "topic")) }
            val list = JList(model).apply { fixedCellHeight = 30; setSize(250, 900); selectedIndex = 15 }
            val scroll = JScrollPane(list).apply { setSize(280, 200); doLayout() }
            scroll.viewport.viewPosition = Point(0, 307)
            TopicListReconciler.apply(list, model, scroll, listOf(Topic(99, "different")), false)
            assertEquals(1, model.size())
            assertEquals(99L, model[0].id)
            assertEquals(-1, list.selectedIndex)
            assertEquals(0, scroll.viewport.viewPosition.y)
        }
    }
}
