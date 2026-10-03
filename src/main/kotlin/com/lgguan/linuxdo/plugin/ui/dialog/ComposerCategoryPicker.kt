package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.model.Category
import org.jsoup.Jsoup
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal data class CategoryItem(val id: Int?, val name: String, val slug: String?, val color: String?,
    val path: String = name, val description: String = "", val parentColor: String? = null) {
    override fun toString() = name
    fun matches(query: String): Boolean {
        val text = "$path ${slug.orEmpty()} $description"
        return query.trim().split(Regex("\\s+")).all { text.contains(it, ignoreCase = true) }
    }
}

internal object ComposerCategories {
    fun flatten(categories: List<Category>): List<Category> {
        val all = linkedMapOf<Int, Category>()
        fun visit(category: Category, parent: Int? = null) {
            if (all.containsKey(category.id)) return
            val current = if (category.parentCategoryId == null && parent != null) category.copy(parentCategoryId = parent) else category
            all[current.id] = current
            (current.subcategoryList.orEmpty() + current.subcategories.orEmpty()).forEach { visit(it, current.id) }
        }
        categories.forEach { visit(it) }
        return all.values.toList()
    }
    fun options(categories: List<Category>, postingOnly: Boolean = true): List<CategoryItem> {
        val all = flatten(categories).associateBy { it.id }
        return all.values.filter { !postingOnly || it.permission == 1 }.map { category ->
            val names = mutableListOf(category.name)
            val visited = mutableSetOf(category.id)
            var parent = category.parentCategoryId?.let(all::get)
            val parentColor = parent?.color
            while (parent != null && visited.add(parent.id)) {
                names.add(0, parent.name)
                parent = parent.parentCategoryId?.let(all::get)
            }
            CategoryItem(category.id, category.name, category.slug, category.color, names.joinToString(" › "),
                Jsoup.parse(category.descriptionText ?: category.description.orEmpty()).text(), parentColor)
        }
    }
}

/** A searchable category menu; the combo model remains the source of the chosen ID. */
internal class ComposerCategoryPicker : ComboBox<CategoryItem>() {
    private var popup: JBPopup? = null
    private val searchField = JBTextField().apply {
        name = "composer-category-search"
        emptyText.text = "搜索板块名称或描述…"
    }
    private val results = DefaultListModel<CategoryItem>()
    private val resultList = JBList(results)
    private val emptyLabel = JBLabel("没有匹配的可发帖板块")
    init {
        name = "composer-category-picker"
        getAccessibleContext().accessibleName = "选择板块"
        val chosenLabel = JBLabel().apply { border = JBUI.Borders.empty(0, 5) }
        renderer = ListCellRenderer { _, value, _, _, _ ->
            chosenLabel.text = value?.path.orEmpty(); chosenLabel.icon = badge(value)
            chosenLabel.foreground = foreground
            chosenLabel.font = font
            chosenLabel
        }
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        val titleLabel = JBLabel()
        val descriptionLabel = JBLabel().apply { font = font.deriveFont(11f) }
        val row = JPanel(BorderLayout(0, 3)).apply {
            border = JBUI.Borders.empty(7, 10)
            add(titleLabel, BorderLayout.NORTH); add(descriptionLabel, BorderLayout.CENTER)
        }
        resultList.cellRenderer = ListCellRenderer { list, value, _, selected, _ ->
            row.background = if (selected) list.selectionBackground else list.background
            titleLabel.text = value.path; titleLabel.icon = badge(value)
            titleLabel.foreground = if (selected) list.selectionForeground else list.foreground
            descriptionLabel.text = value.description
            descriptionLabel.isVisible = value.description.isNotBlank()
            descriptionLabel.foreground = if (selected) list.selectionForeground else com.intellij.util.ui.UIUtil.getContextHelpForeground()
            row.toolTipText = value.description.ifBlank { value.path }
            row
        }
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = filter()
            override fun removeUpdate(e: DocumentEvent?) = filter()
            override fun changedUpdate(e: DocumentEvent?) = filter()
        })
        val keys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> move(1)
                    KeyEvent.VK_UP -> move(-1)
                    KeyEvent.VK_ENTER -> choose()
                    KeyEvent.VK_ESCAPE -> hidePopup()
                    else -> return
                }
                e.consume()
            }
        }
        searchField.addKeyListener(keys)
        resultList.addKeyListener(keys)
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val index = resultList.locationToIndex(e.point)
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && resultList.getCellBounds(index, index)?.contains(e.point) == true) {
                    resultList.selectedIndex = index
                    choose()
                }
            }
        })
    }
    private fun move(delta: Int) {
        if (results.isEmpty) return
        resultList.selectedIndex = (resultList.selectedIndex + delta).coerceIn(0, results.size - 1)
        resultList.ensureIndexIsVisible(resultList.selectedIndex)
    }
    private fun choose() {
        val option = resultList.selectedValue ?: return
        if (!isEnabled) return
        hidePopup()
        selectedItem = option
        requestFocusInWindow()
    }
    private fun filter() {
        val selected = (selectedItem as? CategoryItem)?.id
        results.clear()
        results.addAll((0 until itemCount).map { getItemAt(it) }.filter { it.matches(searchField.text) })
        resultList.selectedIndex = (0 until results.size).firstOrNull { results[it].id == selected } ?: if (results.isEmpty) -1 else 0
        emptyLabel.isVisible = results.isEmpty
    }
    override fun showPopup() {
        if (!isEnabled || !isShowing || popup?.isVisible == true) return
        searchField.text = ""
        filter()
        val popupWidth = width.coerceAtLeast(JBUI.scale(350))
        val content = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8)
            add(searchField, BorderLayout.NORTH)
            add(JBScrollPane(resultList).apply {
                preferredSize = Dimension(popupWidth, JBUI.scale(260))
            })
            add(emptyLabel, BorderLayout.SOUTH)
        }
        DialogTheme.refresh(content, includeWindow = false)
        val opened = JBPopupFactory.getInstance().createComponentPopupBuilder(content, searchField)
            .setFocusable(true).setRequestFocus(true).setCancelOnClickOutside(true).createPopup()
        popup = opened
        opened.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (popup === opened) popup = null
            }
        })
        opened.show(RelativePoint(this, Point(0, height)))
    }
    override fun hidePopup() {
        val opened = popup
        popup = null
        opened?.cancel()
        val comboUi = getUI()
        if (comboUi?.isPopupVisible(this) == true) comboUi.setPopupVisible(this, false)
    }
    override fun isPopupVisible(): Boolean = popup?.isVisible == true
    override fun setPopupVisible(visible: Boolean) { if (visible) showPopup() else hidePopup() }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); if (!enabled) hidePopup() }
    private fun badge(item: CategoryItem?): Icon? {
        if (item?.id == null) return null
        val colors = listOfNotNull(item.parentColor, item.color).ifEmpty { listOf("808080") }
        return object : Icon {
            override fun getIconWidth() = JBUI.scale(colors.size * 12)
            override fun getIconHeight() = JBUI.scale(10)
            override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
                colors.forEachIndexed { index, hex ->
                    g.color = runCatching { Color.decode("#${hex.removePrefix("#")}") }.getOrDefault(JBColor.GRAY)
                    g.fillRect(x + JBUI.scale(index * 12), y, JBUI.scale(8), JBUI.scale(10))
                }
            }
        }
    }
}
