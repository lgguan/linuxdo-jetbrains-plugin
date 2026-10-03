package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.lgguan.linuxdo.plugin.theme.NamespaceFormatter
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

class TopicCardCellRenderer : ListCellRenderer<Topic> {

    private val panel = JPanel(BorderLayout(0, 4))
    private val titleLabel = CustomLabel()
    private val metaLabel = CustomLabel()
    private val summaryLabel = CustomLabel()
    private val tagPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply { isOpaque = false }
    private val footer = JPanel(BorderLayout()).apply { isOpaque = false; add(metaLabel, BorderLayout.NORTH); add(tagPanel, BorderLayout.SOUTH) }

    init {
        panel.border = JBUI.Borders.empty(6, 10)
        panel.isOpaque = true

        titleLabel.isOpaque = false
        metaLabel.isOpaque = false

        panel.add(titleLabel, BorderLayout.NORTH)
        panel.add(footer, BorderLayout.SOUTH)
        panel.add(summaryLabel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out Topic>?,
        value: Topic?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return panel

        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val settings = LinuxDoSettingsState.getInstance()
        val isRead = LinuxDoReadTrackingService.getInstance().isTopicRead(value)

        // Background color
        if (isSelected) {
            panel.background = Color(
                Integer.valueOf(theme.selectionBgHex.removePrefix("#"), 16)
            )
        } else {
            panel.background = if (index % 2 == 0) {
                Color(Integer.valueOf(theme.bgHex.removePrefix("#"), 16))
            } else {
                Color(Integer.valueOf(theme.codeBlockBgHex.removePrefix("#"), 16))
            }
        }

        // Font with native CJK fallback support from IntelliJ UI
        val baseFont = com.intellij.util.ui.JBFont.label()
        val editorFont = baseFont.deriveFont(if (isRead) Font.PLAIN else Font.BOLD, (theme.fontSize + 1).toFloat())
        val metaFont = baseFont.deriveFont(Font.PLAIN, (theme.fontSize * 0.88f).coerceAtLeast(11f))

        titleLabel.font = editorFont
        metaLabel.font = metaFont

        // Category & Namespace
        val category = LinuxDoTopicService.getInstance().getCategory(value.categoryId)
        val namespace = if (settings.categoryNamespaceFormat) {
            NamespaceFormatter.format(category?.name, category?.slug)
        } else {
            category?.name ?: "General"
        }

        // Title formatted like an Issue / Commit / RFC with unescaped HTML entities
        val prefix = if (value.pinned) "[PIN] " else ""
        val readMarker = if (isRead) "" else "• "
        val cleanTitle = unescapeHtml(value.title)
        val displayTitle = "$readMarker$prefix$cleanTitle"
        titleLabel.text = displayTitle
        titleLabel.foreground = if (isSelected) {
            Color(Integer.valueOf(theme.selectionFgHex.removePrefix("#"), 16))
        } else if (isRead) {
            Color(Integer.valueOf(theme.commentHex.removePrefix("#"), 16))
        } else {
            Color(Integer.valueOf(theme.fgHex.removePrefix("#"), 16))
        }

        // Meta info line: namespace • replies • views • time
        val timeText = com.lgguan.linuxdo.plugin.theme.RelativeTime.format(value.lastPostedAt ?: value.bumpedAt ?: value.createdAt)
        val unread = if (value.unreadPosts > 0) "${value.unreadPosts} 未读" else if (value.unseen) "新话题" else ""
        val metaText = listOf(namespace, unread, "${value.replyCount} replies", timeText).filter { it.isNotBlank() }.joinToString(" | ")
        metaLabel.text = metaText
        metaLabel.foreground = if (isSelected) {
            Color(Integer.valueOf(theme.selectionFgHex.removePrefix("#"), 16))
        } else {
            Color(Integer.valueOf(theme.commentHex.removePrefix("#"), 16))
        }
        summaryLabel.text = value.searchBlurb?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty()
        summaryLabel.isVisible = summaryLabel.text.isNotBlank()
        summaryLabel.font = metaFont
        summaryLabel.foreground = metaLabel.foreground
        tagPanel.removeAll()
        value.tags.orEmpty().forEach { tag -> tagPanel.add(CustomLabel().apply {
            text = "#${tag.name}"; font = metaFont
            foreground = if (isSelected) metaLabel.foreground else Color.decode(theme.linkHex)
            toolTipText = "按标签「${tag.name}」筛选"
        }) }
        tagPanel.isVisible = !value.tags.isNullOrEmpty()

        return panel
    }

    fun tagAt(list: JList<out Topic>, topic: Topic, index: Int, point: Point, size: Dimension): String? {
        getListCellRendererComponent(list, topic, index, list.selectedIndex == index, false)
        panel.size = size; panel.doLayout(); footer.doLayout(); tagPanel.doLayout()
        val origin = javax.swing.SwingUtilities.convertPoint(tagPanel, 0, 0, panel)
        return tagPanel.components.mapIndexedNotNull { i, component ->
            if (Rectangle(origin.x + component.x, origin.y + component.y, component.width, component.height).contains(point)) topic.tags?.getOrNull(i)?.name else null
        }.firstOrNull()
    }

    private fun unescapeHtml(text: String): String {
        return text.replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&hellip;", "…")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&nbsp;", " ")
    }

    private class CustomLabel : javax.swing.JLabel() {
        init {
            border = JBUI.Borders.empty()
            putClientProperty("html.disable", true)
        }
    }
}
