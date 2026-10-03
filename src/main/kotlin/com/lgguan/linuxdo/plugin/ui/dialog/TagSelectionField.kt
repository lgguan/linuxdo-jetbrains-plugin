package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.popup.*
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.TagItem
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Native combo outside; searchable choices and removable selections inside the popup. */
internal class TagSelectionField(val mode: Mode, private val remove: (String) -> Unit) : JPanel(BorderLayout()) {
    enum class Mode { BROWSE, SEARCH, COMPOSE }
    val chips = JPanel(ComposerWrapLayout()).apply { isOpaque = false }
    var openPopup: () -> Unit = {}
    var currentPopup: () -> JBPopup? = { null }
    val picker = object : ComboBox<String>() {
        override fun processMouseEvent(event: MouseEvent) {
            if (event.id == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event) && isEnabled) {
                requestFocusInWindow()
                if (isPopupVisible) hidePopup() else showPopup()
                event.consume()
                return
            }
            super.processMouseEvent(event)
        }
        override fun showPopup() { if (isEnabled && isShowing) openPopup() }
        override fun hidePopup() { currentPopup()?.cancel() }
        override fun isPopupVisible(): Boolean = currentPopup()?.isVisible == true
        override fun setPopupVisible(visible: Boolean) { if (visible) showPopup() else hidePopup() }
        override fun getPreferredSize() = super.getPreferredSize().apply { width = JBUI.scale(120); height = JBUI.scale(28) }
    }.apply {
        name = if (mode == Mode.COMPOSE) "composer-tag-picker" else "${mode.name.lowercase()}-tag-picker"
        toolTipText = "搜索并选择标签"
        accessibleContext.accessibleName = "选择标签"
        minimumSize = Dimension(0, JBUI.scale(28))
        renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    }
    init {
        minimumSize = Dimension(JBUI.scale(100), JBUI.scale(28))
        isOpaque = false
        add(picker, BorderLayout.CENTER)
        render(emptyList())
    }
    fun render(selected: Collection<String>, errors: Map<String, String> = emptyMap()) {
        chips.removeAll()
        chips.isVisible = selected.isNotEmpty()
        selected.forEach { tag ->
            val chip = JPanel(BorderLayout(3, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(1, 5))
                toolTipText = errors[tag] ?: tag
            }
            val label = JBLabel(if (tag.length > 15) tag.take(14) + "…" else tag).apply {
                putClientProperty("html.disable", true)
                toolTipText = errors[tag] ?: tag
                if (tag in errors) foreground = JBColor.RED
            }
            val close = JButton("×").apply {
                name = "${if (mode == Mode.COMPOSE) "composer" else mode.name.lowercase()}-remove-tag-$tag"
                accessibleContext.accessibleName = "移除标签 $tag"
                toolTipText = "移除标签 $tag"
                margin = JBUI.emptyInsets(); border = JBUI.Borders.empty(0, 2)
                preferredSize = Dimension(JBUI.scale(16), JBUI.scale(18)); minimumSize = preferredSize
                isContentAreaFilled = false; isEnabled = this@TagSelectionField.isEnabled
                addActionListener { remove(tag) }
                addFocusListener(object : FocusAdapter() {
                    override fun focusGained(e: FocusEvent) { chip.border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.BLUE), JBUI.Borders.empty(1, 5)) }
                    override fun focusLost(e: FocusEvent) { chip.border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(1, 5)) }
                })
            }
            chip.add(label, BorderLayout.CENTER); chip.add(close, BorderLayout.EAST); chips.add(chip)
        }
        picker.toolTipText = if (selected.isEmpty()) "搜索并选择标签" else selected.joinToString("、")
        val summary = if (selected.isEmpty()) { if (mode == Mode.BROWSE) "全部标签" else "选择标签" }
            else selected.joinToString("、")
        picker.model = DefaultComboBoxModel(arrayOf(summary))
        picker.foreground = if (errors.isEmpty()) javax.swing.UIManager.getColor("ComboBox.foreground") else JBColor.RED
        chips.revalidate(); revalidate(); repaint()
    }
    fun followPopup(current: () -> JBPopup?) {
        fun reposition() {
            ApplicationManager.getApplication().invokeLater({
                val popup = current() ?: return@invokeLater
                if (!isShowing || !popup.isVisible) return@invokeLater
                val point = locationOnScreen.apply { y += height }
                val screen = graphicsConfiguration.bounds
                point.x = point.x.coerceIn(screen.x, maxOf(screen.x, screen.x + screen.width - popup.content.width))
                point.y = point.y.coerceIn(screen.y, maxOf(screen.y, screen.y + screen.height - popup.content.height))
                popup.setLocation(point)
            }, ModalityState.any())
        }
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = reposition()
            override fun componentMoved(e: ComponentEvent) = reposition()
        })
    }
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // Swing invokes this during construction, before Kotlin properties are initialized.
        components.forEach { component ->
            fun enable(c: Component) { c.isEnabled = enabled; if (c is Container) c.components.forEach(::enable) }
            enable(component)
        }
    }
}

/** Read-only browsing candidates come from /tags.json, including visible groups and counts. */
internal class BrowseTagSelector(owner: Disposable, mode: TagSelectionField.Mode, private val changed: (List<String>) -> Unit) : Disposable {
    private val selected = linkedSetOf<String>()
    val field = TagSelectionField(mode) { selected.remove(it); redraw(); changed(selected.toList()) }
    private val tasks = BackgroundTasks()
    private var popup: JBPopup? = null
    private var disposed = false
    private var generation = 0L
    private val input = JBTextField().apply { emptyText.text = "搜索标签名称或分组…" }
    private val model = DefaultListModel<TagItem>()
    private val list = JBList(model)
    private val status = JBLabel()
    private var candidates: List<TagItem> = emptyList()
    private var groups: Map<String, List<String>> = emptyMap()
    private val retry = JButton("重试").apply { addActionListener { load() } }
    init {
        com.intellij.openapi.util.Disposer.register(owner, this)
        field.followPopup { popup }
        field.currentPopup = { popup }
        field.openPopup = { show() }
        com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().addAuthListener(owner) {
            if (!disposed) { generation++; candidates = emptyList(); groups = emptyMap(); popup?.cancel() }
        }
        list.cellRenderer = DefaultListCellRenderer().let { renderer -> ListCellRenderer { source, value, index, highlight, focus ->
            val row = renderer.getListCellRendererComponent(source, value, index, highlight, focus) as JLabel
            row.putClientProperty("html.disable", true)
            row.text = "${if (value.text in selected) "✓ " else ""}${value.text} (${value.count})${groups[value.text]?.joinToString(" / ", " · ").orEmpty()}"
            row.toolTipText = value.text; row
        } }
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = filter()
            override fun removeUpdate(e: DocumentEvent) = filter()
            override fun changedUpdate(e: DocumentEvent) = filter()
        })
        val keys = object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN, KeyEvent.VK_UP -> if (!model.isEmpty) {
                        list.selectedIndex = (list.selectedIndex + if (e.keyCode == KeyEvent.VK_DOWN) 1 else -1).coerceIn(0, model.size - 1)
                        list.ensureIndexIsVisible(list.selectedIndex)
                    }
                    KeyEvent.VK_ENTER -> choose()
                    KeyEvent.VK_ESCAPE -> popup?.cancel()
                    else -> return
                }
                e.consume()
            }
        }
        input.addKeyListener(keys); list.addKeyListener(keys)
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (SwingUtilities.isLeftMouseButton(e) && index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) {
                    list.selectedIndex = index; choose()
                }
            }
        })
    }
    fun selection() = selected.toList()
    fun setSelection(tags: Collection<String>, notify: Boolean = false) {
        selected.clear(); selected.addAll(if (field.mode == TagSelectionField.Mode.BROWSE) tags.take(1) else tags)
        redraw(); if (notify) changed(selection())
    }
    private fun redraw() { field.render(selected); list.repaint() }
    private fun choose() {
        val tag = list.selectedValue?.text ?: return
        if (field.mode == TagSelectionField.Mode.BROWSE) { selected.clear(); selected.add(tag) }
        else if (!selected.add(tag)) selected.remove(tag)
        redraw(); changed(selection())
        if (field.mode == TagSelectionField.Mode.BROWSE) popup?.cancel()
    }
    private fun filter() {
        val query = input.text.trim()
        model.clear(); model.addAll(candidates.filter { it.text.contains(query, true) || groups[it.text].orEmpty().any { group -> group.contains(query, true) } })
        list.selectedIndex = if (model.isEmpty) -1 else 0
        status.text = if (model.isEmpty) "没有匹配的标签" else "${model.size} 个标签 · 使用数量由论坛提供"
    }
    private fun load() {
        val request = ++generation
        val version = SessionEpoch.current
        candidates = emptyList(); model.clear(); status.text = "正在读取标签…"; retry.isVisible = false
        tasks.submit {
            val result = runCatching { DiscourseApiClient.browseTags().getOrThrow() }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || popup?.isVisible != true || request != generation || version != SessionEpoch.current) return@invokeLater
                result.onSuccess { response ->
                    groups = response.extras?.groups.orEmpty().flatMap { group -> group.tags.map { it.text to group.name } }
                        .groupBy({ it.first }, { it.second })
                    candidates = (response.tags + response.extras?.groups.orEmpty().flatMap { it.tags }).distinctBy { it.text }
                        .sortedWith(compareByDescending<TagItem> { it.count }.thenBy { it.text })
                    filter()
                }.onFailure { error -> status.text = IssueListPanel.formatErrorDisplay(error.message.orEmpty()); retry.isVisible = true }
            }, ModalityState.any())
        }
    }
    private fun show() {
        if (disposed || !field.isShowing || !field.isEnabled || popup?.isVisible == true) return
        input.text = ""
        val footer = JPanel(BorderLayout()).apply { add(status); add(retry, BorderLayout.EAST) }
        val heading = JPanel(BorderLayout(0, 6)).apply {
            add(input, BorderLayout.NORTH)
            if (field.mode == TagSelectionField.Mode.BROWSE) add(JButton("全部标签").apply {
                addActionListener { setSelection(emptyList(), notify = true); popup?.cancel() }
            }, BorderLayout.CENTER) else add(field.chips, BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.empty(8); add(heading, BorderLayout.NORTH)
            add(JBScrollPane(list).apply { preferredSize = Dimension(JBUI.scale(360), JBUI.scale(240)) })
            add(footer, BorderLayout.SOUTH)
        }
        DialogTheme.refresh(content, includeWindow = false)
        val opened = JBPopupFactory.getInstance().createComponentPopupBuilder(content, input)
            .setFocusable(true).setRequestFocus(true).setCancelOnClickOutside(true).createPopup()
        popup = opened
        opened.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { if (popup === opened) { popup = null; generation++; field.picker.repaint() } }
        })
        opened.show(RelativePoint(field, Point(0, field.height))); load()
    }
    override fun dispose() { disposed = true; generation++; popup?.cancel(); tasks.dispose() }
}

/** Keep the tag selector beside its category; move list type to the next row if needed. */
internal class TopicFilterRow(category: JComponent, tags: JComponent, private val listType: JComponent) : JPanel(null) {
    private val choices = ResponsiveFilterRow(category, tags, 300)
    init {
        add(choices); add(listType)
        addComponentListener(object : ComponentAdapter() { override fun componentResized(e: ComponentEvent) { revalidate() } })
    }
    override fun getPreferredSize(): Dimension {
        val w = width.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: JBUI.scale(600)
        val stacked = w < JBUI.scale(560)
        choices.setSize(if (stacked) w else (w * .72).toInt(), JBUI.scale(28))
        val h = choices.preferredSize.height
        return Dimension(w, if (stacked) h + JBUI.scale(6) + JBUI.scale(28) else h)
    }
    override fun getMinimumSize() = Dimension(0, JBUI.scale(28))
    override fun doLayout() {
        val gap = JBUI.scale(6)
        val stacked = width < JBUI.scale(560)
        val w = if (stacked) width else ((width - gap) * .72).toInt()
        choices.setSize(w, JBUI.scale(28))
        val h = choices.preferredSize.height
        choices.setBounds(0, 0, w, h); choices.doLayout()
        listType.setBounds(if (stacked) 0 else w + gap, if (stacked) h + gap else 0,
            if (stacked) width else width - w - gap, JBUI.scale(28))
    }
}

/** Labels and hints belong to their column; at narrow widths columns stack. */
internal class ResponsiveFilterRow(private val first: JComponent, private val second: JComponent, private val breakpoint: Int = 520) : JPanel(null) {
    init {
        add(first); add(second)
        addComponentListener(object : ComponentAdapter() { override fun componentResized(e: ComponentEvent) { revalidate() } })
    }
    private fun measure(component: JComponent, width: Int): Int {
        component.setSize(width.coerceAtLeast(1), component.height.coerceAtLeast(JBUI.scale(28)))
        fun layout(container: Container) { container.doLayout(); container.components.filterIsInstance<Container>().forEach(::layout) }
        layout(component)
        return component.preferredSize.height.coerceAtLeast(JBUI.scale(28))
    }
    override fun getPreferredSize(): Dimension {
        val w = width.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: JBUI.scale(600)
        val gap = JBUI.scale(6)
        val narrow = w < JBUI.scale(breakpoint)
        val leftWidth = if (narrow) w else ((w - gap) * .4).toInt()
        val leftHeight = measure(first, leftWidth)
        val rightHeight = measure(second, if (narrow) w else w - gap - leftWidth)
        return Dimension(w, if (narrow) leftHeight + gap + rightHeight else maxOf(leftHeight, rightHeight))
    }
    override fun getMinimumSize() = Dimension(0, JBUI.scale(28))
    override fun doLayout() {
        val gap = JBUI.scale(6)
        val narrow = width < JBUI.scale(breakpoint)
        val leftWidth = if (narrow) width else ((width - gap) * .4).toInt()
        val firstHeight = measure(first, leftWidth)
        first.setBounds(0, 0, leftWidth, firstHeight)
        val rightWidth = if (narrow) width else width - gap - leftWidth
        second.setBounds(if (narrow) 0 else leftWidth + gap, if (narrow) firstHeight + gap else 0, rightWidth, measure(second, rightWidth))
    }
}
