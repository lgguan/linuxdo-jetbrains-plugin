package com.lgguan.linuxdo.plugin.ui.dialog

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.*
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/** Editing has no draft key and stays in this window until the server confirms it. */
internal class EditPostDialog(
    private val project: Project, private val topic: TopicDetailResponse, initial: Post,
    private val service: TopicOperationService, private val version: Long,
    private val pageCurrent: () -> Boolean, private val updated: (OperationResult) -> Unit
) : DialogWrapper(project) {
    private val tasks = BackgroundTasks()
    private var baseline = initial
    private val text = JBTextArea(initial.raw.orEmpty(), 20, 70)
    private val reason = JBTextField()
    private val status = JBLabel("编辑 #${initial.postNumber} · 版本 ${initial.version ?: "未知"}")
    private val preview = ComposerPreviewView(tasks) { isDisposed || !pageCurrent() }
    private var previewVisible = false
    private val previewPanel = JPanel(BorderLayout()).apply { add(preview.component); isVisible = false }
    private val editor = ComposerEditorSupport(project, text, preview, status, tasks, { isDisposed || !pageCurrent() },
        { if (previewVisible) preview.local(text.text) }, disposable, { previewVisible = !previewVisible; previewPanel.isVisible = previewVisible; if(previewVisible)preview.local(text.text); previewVisible },
        { previewVisible = true; previewPanel.isVisible = true },)
    private var busy = false
    private var uncertain = false
    private lateinit var editorPanel: JPanel
    init {
        title = "编辑帖子 #${initial.postNumber}"
        setOKButtonText("保存编辑")
        init()
        editor.resetUndo()
        ComposerAppearance.followTheme(disposable, contentPanel, text, editorPanel) { preview.refreshTheme() }
        Disposer.register(disposable, tasks); Disposer.register(disposable, preview)
        text.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = changed()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = changed()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = changed()
            private fun changed() { if (previewVisible) preview.local(text.text) }
        })
    }
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(8,8)).apply {
        preferredSize = Dimension(820,600)
        add(JPanel(BorderLayout(6,0)).apply { add(JBLabel("编辑原因"),BorderLayout.WEST); add(reason) },BorderLayout.NORTH)
        val counter = JBLabel("Markdown")
        editorPanel = ComposerAppearance.editor(text, editor.toolbar, counter)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorPanel, previewPanel).apply { resizeWeight = .55 }
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(event: java.awt.event.ComponentEvent?) {
                split.orientation = if (width < com.intellij.util.ui.JBUI.scale(760)) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
            }
        })
        add(split,BorderLayout.CENTER)
        add(status,BorderLayout.SOUTH)
    }
    override fun doOKAction() {
        if (busy || text.text.isBlank() || !pageCurrent() || version != SessionEpoch.current) return
        if (uncertain) {
            Messages.showInfoMessage(contentPanel,"上次保存结果尚未确认，请关闭前检查服务器版本，正文仍保留在当前窗口。","保存结果未确认")
            return
        }
        val input = JsonObject().apply { addProperty("raw",text.text); addProperty("original",baseline.raw); addProperty("reason",reason.text) }
        busy = true; isOKActionEnabled = false; text.isEnabled = false; status.text = "正在保存…"
        tasks.submit {
            val result = service.perform(topic,baseline.id,"edit",input,version,active=pageCurrent)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed || !pageCurrent() || version != SessionEpoch.current) return@invokeLater
                busy = false; isOKActionEnabled = true; text.isEnabled = true
                updated(result)
                if (result.error == null) { close(OK_EXIT_CODE); return@invokeLater }
                if ((result.error as? HttpStatusException)?.status == 409 && result.post?.raw != null) {
                    val local = text.text; val server = result.post
                    var useServer = false
                    val choice = object : DialogWrapper(project) {
                        init { title="编辑冲突 · #${baseline.postNumber}"; setOKButtonText("保留本地正文继续编辑"); init() }
                        override fun createActions(): Array<Action> = arrayOf(okAction,object : AbstractAction("使用服务器正文继续编辑") {
                            override fun actionPerformed(event: java.awt.event.ActionEvent?) { useServer=true;close(OK_EXIT_CODE) }
                        },cancelAction)
                        override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
                            preferredSize=Dimension(900,500)
                            add(JBLabel("请选择继续编辑的正文；再次保存前会核对服务器版本。"),BorderLayout.NORTH)
                            fun versionPanel(label:String,body:String?)=JPanel(BorderLayout()).apply { add(JBLabel(label),BorderLayout.NORTH);add(JBScrollPane(JBTextArea(body).apply { isEditable=false })) }
                            add(JSplitPane(JSplitPane.HORIZONTAL_SPLIT,versionPanel("本地正文",local),versionPanel("服务器正文 · 版本 ${server.version ?: "未知"}",server.raw)))
                        }
                    }
                    if (choice.showAndGet()) { baseline=server;if(useServer){text.text=server.raw;reason.text="";editor.resetUndo()}; status.text="已更新冲突基线；请核对内容后再次点击保存" }
                    else status.text="编辑冲突，当前文本已保留"
                } else {
                    uncertain=result.error is UnconfirmedOperationException
                    status.text=result.error.message ?: "保存失败，正文已保留"
                    if (uncertain && result.post?.raw == text.text) { uncertain=false; baseline=result.post; status.text="服务器正文与本地一致，已核对保存结果"; close(OK_EXIT_CODE) }
                }
            },ModalityState.any())
        }
    }
}
