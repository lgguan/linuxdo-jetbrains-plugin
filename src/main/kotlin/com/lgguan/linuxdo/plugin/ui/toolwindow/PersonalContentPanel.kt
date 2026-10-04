package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.ui.dialog.*
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PersonalContentPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = PersonalContentService.getInstance()
    private val lifetime = Disposer.newDisposable()
    private data class View(var query: String = "", var selected: String? = null, var anchor: String? = null, var delta: Int = 0,
        var remoteQuery: String = "", var allBookmarks: Boolean = true)
    private val views = PersonalContentKind.entries.associateWith { View() }
    private val tabs = JTabbedPane()
    private val filter = JBTextField()
    private val searchScope = JComboBox(arrayOf("全部书签", "已加载内容"))
    private val search = JButton("搜索")
    private val searchOptions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { add(searchScope); add(search) }
    private val model = DefaultListModel<PersonalContentItem>()
    private val list = JBList(model)
    private val scroll = JBScrollPane(list)
    private val status = JTextArea().apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; font = JBUI.Fonts.label() }
    private val more = JButton("加载更多")
    private val refresh = JButton("刷新")
    private val web = JButton("在网页打开")
    private val editBookmark = JButton("编辑")
    private val deleteBookmark = JButton("删除")
    private val verifyBookmark = JButton("核对服务器")
    private val bookmarkActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { add(editBookmark); add(deleteBookmark); add(verifyBookmark) }
    private var bookmarkBusy = false
    private var pendingBookmark: BookmarkMetadata? = null
    private var changing = false
    private var active = false
    private var disposed = false
    private var openGeneration = 0L
    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()
    private fun currentIdentity() = Triple(DiscourseApiClient.getBaseUrl(), LinuxDoAuthService.getInstance().currentUser?.id,
        com.lgguan.linuxdo.plugin.net.SessionEpoch.current)
    private var displayedAccount = currentIdentity()
    var kind = PersonalContentKind.TOPICS
        private set
    init {
        border = JBUI.Borders.empty(4, 6)
        PersonalContentKind.entries.forEach { tabs.addTab(it.label, JPanel()) }
        tabs.preferredSize = Dimension(0, JBUI.scale(32))
        filter.emptyText.text = "筛选已加载内容"
        val controls = JPanel(BorderLayout(0, JBUI.scale(4))).apply { add(tabs, BorderLayout.NORTH); add(filter, BorderLayout.CENTER); add(searchOptions, BorderLayout.SOUTH) }
        add(controls, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(status, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { add(refresh); add(more); add(web) }, BorderLayout.CENTER)
            add(bookmarkActions, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ListCellRenderer { _, item, index, selected, _ ->
            val theme = com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter.getCurrentThemeColors()
            fun color(hex: String) = Color.decode(hex)
            val fg = color(if(selected) theme.selectionFgHex else theme.fgHex)
            JPanel(BorderLayout(0, 3)).apply {
                border = JBUI.Borders.empty(6, 8)
                background = color(if(selected) theme.selectionBgHex else if(index % 2 == 0) theme.bgHex else theme.codeBlockBgHex)
                fun label(value: String) = JBLabel(value).apply { putClientProperty("html.disable", true); foreground = fg }
                add(label(item.title).apply { font = JBUI.Fonts.label().deriveFont(Font.PLAIN, (theme.fontSize + 1).toFloat()) }, BorderLayout.NORTH)
                if(item.summary.isNotBlank()) add(JTextArea(item.summary).apply {
                    isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; rows = 2
                    font = JBUI.Fonts.label().deriveFont(Font.PLAIN, theme.fontSize.toFloat()); foreground = fg
                    preferredSize = Dimension((scroll.viewport.width - JBUI.scale(24)).coerceAtLeast(80), getFontMetrics(font).height * 2)
                }, BorderLayout.CENTER)
                add(label(listOf(item.detail, item.time).filter { it.isNotBlank() }.joinToString(" · ")).apply {
                    foreground = if(selected) fg else color(theme.commentHex)
                    font = JBUI.Fonts.label().deriveFont(Font.PLAIN, (theme.fontSize * .88f).coerceAtLeast(11f))
                }, BorderLayout.SOUTH)
            }
        }
        tabs.addChangeListener { if(!changing) selectKind(PersonalContentKind.entries[tabs.selectedIndex]) }
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = update()
            override fun removeUpdate(e: DocumentEvent?) = update()
            override fun changedUpdate(e: DocumentEvent?) = update()
            private fun update() { if(!changing) {
                capture(); val view = views.getValue(kind); view.query = filter.text
                if(remoteSearch() && filter.text.isBlank() && view.remoteQuery.isNotEmpty()) {
                    view.remoteQuery = ""; if(canRead()) service.visit(kind)
                }
                render()
            } }
        })
        searchScope.addActionListener { if(!changing) {
            capture(); views.getValue(kind).allBookmarks = searchScope.selectedIndex == 0; render(); visitCurrent()
        } }
        filter.addActionListener { submitSearch() }; search.addActionListener { submitSearch() }
        list.addListSelectionListener { if(!changing && !it.valueIsAdjusting) { views.getValue(kind).selected = list.selectedValue?.key; updateBookmarkActions() } }
        list.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
            val index = list.locationToIndex(e.point)
            if(SwingUtilities.isLeftMouseButton(e) && index >= 0 && list.getCellBounds(index, index)?.contains(e.point) == true) open(model[index])
        }
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)
            private fun popup(e: MouseEvent) {
                if(!e.isPopupTrigger || kind != PersonalContentKind.BOOKMARKS) return
                val index = list.locationToIndex(e.point)
                if(index < 0 || list.getCellBounds(index,index)?.contains(e.point) != true) return
                list.selectedIndex = index; updateBookmarkActions()
                JPopupMenu().apply {
                    add(JMenuItem("编辑书签").apply { isEnabled = editBookmark.isEnabled; addActionListener { editSelectedBookmark() } })
                    add(JMenuItem("删除书签").apply { isEnabled = deleteBookmark.isEnabled; addActionListener { deleteSelectedBookmark() } })
                }.show(list, e.x, e.y)
            }
        })
        list.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-personal")
        list.actionMap.put("open-personal", object : AbstractAction() { override fun actionPerformed(e: ActionEvent?) { list.selectedValue?.let(::open) } })
        refresh.addActionListener { refreshCurrent() }
        more.addActionListener { if(canRead()) service.loadMore(kind, searchTerm()) }
        editBookmark.addActionListener { editSelectedBookmark() }
        deleteBookmark.addActionListener { deleteSelectedBookmark() }
        verifyBookmark.addActionListener { verifyPendingBookmark() }
        web.addActionListener { if(!hidden()) BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity/${kind.webPath}") }
        service.addListener(lifetime) { if(!disposed && !project.isDisposed) {
            capture(); render(); if(canRead() && service.state(kind, searchTerm()).dirty) visitCurrent()
        } }
        LinuxDoAuthService.getInstance().addAuthListener(lifetime) {
            if(!disposed && !project.isDisposed) {
                val changed = displayedAccount != currentIdentity()
                capture(); render()
                if(changed) visitCurrent()
            }
        }
        addHierarchyListener { event ->
            if(event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !disposed && !project.isDisposed) {
                capture(); render()
            }
        }
        render()
    }
    private fun hidden() = LinuxDoBossKeyService.getInstance(project).isHidden
    private fun canRead() = active && !disposed && !project.isDisposed && !hidden() && LinuxDoAuthService.getInstance().isLoggedIn
    private fun remoteSearch() = kind == PersonalContentKind.BOOKMARKS && views.getValue(kind).allBookmarks
    private fun searchTerm() = if(remoteSearch()) views.getValue(kind).remoteQuery else ""
    private fun visitCurrent() { if(canRead()) service.visit(kind, searchTerm()) }
    private fun submitSearch() {
        if(!canRead() || !remoteSearch()) return
        val term = filter.text.trim()
        if(term.length > 500) { status.text = "搜索词不能超过 500 个字符"; return }
        capture(); views.getValue(kind).remoteQuery = term; render(); service.refresh(kind, term)
    }
    fun setActive(value: Boolean) { openGeneration++; capture(); active = value; render(); visitCurrent() }
    fun selectKind(value: PersonalContentKind) {
        openGeneration++
        capture(); kind = value; changing = true
        tabs.selectedIndex = value.ordinal; filter.text = views.getValue(value).query
        searchScope.selectedIndex = if(views.getValue(value).allBookmarks) 0 else 1; changing = false
        render(); visitCurrent()
    }
    fun refreshCurrent() { if(canRead()) service.refresh(kind, searchTerm()) }
    private fun capture() {
        if(changing || model.isEmpty) return
        val view = views.getValue(kind); view.selected = list.selectedValue?.key
        val index = list.firstVisibleIndex
        if(index >= 0) { view.anchor = model[index].key; view.delta = scroll.viewport.viewPosition.y - (list.getCellBounds(index, index)?.y ?: 0) }
    }
    private fun render() {
        val auth = LinuxDoAuthService.getInstance()
        if(displayedAccount != currentIdentity() || !auth.isLoggedIn) {
            displayedAccount = currentIdentity()
            views.values.forEach { it.query = ""; it.remoteQuery = ""; it.allBookmarks = true; it.selected = null; it.anchor = null; it.delta = 0 }
            pendingBookmark = null
            changing = true; filter.text = ""; searchScope.selectedIndex = 0; changing = false
        }
        service.retainView(lifetime, kind, searchTerm())
        val state = service.state(kind, searchTerm()); val view = views.getValue(kind)
        val items = if(auth.isLoggedIn) state.items.filter { remoteSearch() || it.matches(view.query) } else emptyList()
        changing = true; model.clear(); items.forEach(model::addElement)
        list.selectedIndex = items.indexOfFirst { it.key == view.selected }
        val anchor = items.indexOfFirst { it.key == view.anchor }
        if(anchor >= 0) list.getCellBounds(anchor, anchor)?.let { scroll.viewport.viewPosition = Point(0, (it.y + view.delta).coerceAtLeast(0)) }
        changing = false
        status.text = when(auth.status) {
            LinuxDoAuthService.Status.SIGNED_OUT -> "登录后查看我的话题、回复、书签和草稿"
            LinuxDoAuthService.Status.CREDENTIALS_PENDING -> "正在验证账号凭据，确认后可读取个人内容"
            else -> listOf("已加载 ${state.items.size} 条", if(remoteSearch() && view.query.trim() != view.remoteQuery) "输入后按 Enter 搜索全部书签" else "", if(state.loading) "正在读取…" else "", state.error.orEmpty(), state.warning.orEmpty(),
                if(items.isEmpty() && state.loaded && !state.loading) { if(!remoteSearch() && state.next != null && view.query.isNotBlank()) "已加载内容中暂无匹配，可继续加载" else "暂无内容或匹配" } else "").filter { it.isNotBlank() }.joinToString("\n")
        }
        searchOptions.isVisible = kind == PersonalContentKind.BOOKMARKS
        searchScope.isEnabled = canRead(); search.isEnabled = canRead() && remoteSearch() && !state.loading
        filter.emptyText.text = if(remoteSearch()) "按名称、话题标题或正文搜索书签" else "筛选已加载内容"
        filter.isEnabled = auth.isLoggedIn; refresh.isEnabled = canRead() && !state.loading
        more.isEnabled = canRead() && !state.loading && (state.next != null || state.failedQuery != null)
        more.text = if(state.failedQuery != null) "重试" else "加载更多"
        updateBookmarkActions()
        revalidate(); repaint()
    }
    private fun updateBookmarkActions() {
        val bookmark = list.selectedValue?.bookmark
        bookmarkActions.isVisible = kind == PersonalContentKind.BOOKMARKS
        editBookmark.isEnabled = canRead() && !bookmarkBusy && bookmark != null
        editBookmark.text = if(bookmark?.editable == false) "网页编辑" else "编辑"
        deleteBookmark.isEnabled = canRead() && !bookmarkBusy && bookmark?.deletable == true && pendingBookmark?.id != bookmark.id
        verifyBookmark.isVisible = pendingBookmark != null
        verifyBookmark.isEnabled = canRead() && !bookmarkBusy
    }
    private fun editSelectedBookmark() {
        val bookmark = list.selectedValue?.bookmark ?: return
        if(!canRead() || bookmarkBusy) return
        if(!bookmark.editable) { BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity/bookmarks"); return }
        val identity = currentIdentity(); val generation = openGeneration
        bookmarkBusy = true; updateBookmarkActions(); status.text = "正在读取书签设置…"
        backgroundTasks.submit {
            val result = runCatching { BookmarkService.getInstance().read(bookmark, identity.third) }
            ApplicationManager.getApplication().invokeLater({
                bookmarkBusy = false
                if(disposed || project.isDisposed) return@invokeLater
                updateBookmarkActions()
                if(!canRead() || identity != currentIdentity() || generation != openGeneration) return@invokeLater
                result.fold({ BookmarkEditDialog(project, it, identity.third) {
                    canRead() && identity == currentIdentity() && generation == openGeneration
                }.show() }, { error ->
                    if(error is BookmarkWebRequiredException) BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity/bookmarks")
                    else status.text = error.message ?: "读取书签失败，请刷新或在网页核对"
                })
            }, ModalityState.any())
        }
    }
    private fun deleteSelectedBookmark() {
        val bookmark = list.selectedValue?.bookmark ?: return
        if(!canRead() || bookmarkBusy || !bookmark.deletable) return
        val identity = currentIdentity()
        if(Messages.showYesNoDialog(project, "确认删除选中的书签？\n该书签的所有提醒也会取消。", "删除书签", Messages.getQuestionIcon()) != Messages.YES) return
        if(!canRead() || identity != currentIdentity()) return
        bookmarkTask(bookmark, false)
    }
    private fun verifyPendingBookmark() { pendingBookmark?.let { bookmarkTask(it, true) } }
    private fun bookmarkTask(bookmark: BookmarkMetadata, verify: Boolean) {
        if(!canRead() || bookmarkBusy) return
        val identity = currentIdentity(); val generation = openGeneration
        bookmarkBusy = true; updateBookmarkActions(); status.text = if(verify) "正在核对服务器…" else "正在删除书签…"
        backgroundTasks.submit {
            val service = BookmarkService.getInstance()
            val result = if(verify) service.reconcile(bookmark, identity.third) else service.delete(bookmark, identity.third) {
                canRead() && identity == currentIdentity() && generation == openGeneration
            }
            ApplicationManager.getApplication().invokeLater({
                bookmarkBusy = false
                if(disposed || project.isDisposed) return@invokeLater
                if(identity != currentIdentity()) { render(); return@invokeLater }
                pendingBookmark = bookmark.takeIf { result.error is UnconfirmedOperationException }
                render()
                if(result.error != null) status.text = result.error.message ?: "操作失败，列表已保留"
                else { status.text = if(verify) "服务器状态已核对" else "书签已删除"; visitCurrent() }
            }, ModalityState.any())
        }
    }
    private fun open(item: PersonalContentItem) {
        if(!canRead()) return
        val generation = ++openGeneration
        if(item.topicId != null && item.floor == null && item.postId != null) {
            val identity = currentIdentity()
            status.text = "正在确认回复楼层…"
            backgroundTasks.submit {
                val result = runCatching {
                    com.lgguan.linuxdo.plugin.net.SessionEpoch.requireCurrent(identity.third)
                    val post = DiscourseApiClient.getPost(item.postId).getOrThrow()
                    com.lgguan.linuxdo.plugin.net.SessionEpoch.requireCurrent(identity.third)
                    require(post.id == item.postId && post.topicId == item.topicId && post.postNumber > 0) { "无法确认回复目标" }
                    post
                }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if(canRead() && generation == openGeneration && identity == currentIdentity()) result.fold({ post ->
                        LinuxDoEditorOpener.openTopic(project, item.topicId, item.title, post.postNumber)
                    }, { status.text = "无法确认回复楼层，列表已保留，请在网页核对" })
                }
            }
            return
        }
        if(item.draftKey != null) when(item.draftType) {
            PersonalDraftType.TOPIC -> CreateTopicDialog.openDraft(project, item.draftKey)
            PersonalDraftType.REPLY -> item.topicId?.let { CommitReplyDialog.openDraft(project, it) }
            else -> BrowserUtil.browse("${DiscourseApiClient.getBaseUrl()}/my/activity/drafts")
        } else if(item.topicId != null) LinuxDoEditorOpener.openTopic(project, item.topicId, item.title, item.floor)
        else item.webUrl?.let(BrowserUtil::browse) ?: run { status.text = "无法确认目标，请使用当前页签的网页入口" }
    }
    override fun dispose() { disposed = true; openGeneration++; backgroundTasks.dispose(); model.clear(); Disposer.dispose(lifetime) }
}
