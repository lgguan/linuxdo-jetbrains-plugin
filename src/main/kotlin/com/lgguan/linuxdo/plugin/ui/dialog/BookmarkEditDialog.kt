package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.service.*
import java.awt.BorderLayout
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.swing.*

internal class BookmarkEditDialog(project: Project, private var baseline: BookmarkEditState,
    private val version: Long, private val active: () -> Boolean) : DialogWrapper(project) {
    private val service = BookmarkService.getInstance()
    private val tasks = BackgroundTasks()
    private val bookmarkName = JBTextField(baseline.bookmark.name, 30)
    private val reminder = JCheckBox("设置提醒", baseline.bookmark.reminderAt != null)
    private val zone = ZoneId.systemDefault()
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val originalText = baseline.bookmark.reminderAt?.let { format.format(Instant.parse(it).atZone(zone)) }
    private val time = JBTextField(originalText ?: format.format(LocalDateTime.now(zone).plusDays(1).withSecond(0)), 20)
    private val status = JTextArea().apply { isEditable = false; isOpaque = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
    private var busy = false
    private var uncertain = false
    private fun policyText() = "提醒后：${listOf("保留书签", "删除书签", "回复后删除书签", "保留书签并清除提醒")[baseline.policy]}"
    private val policyLabel = JBLabel(policyText())
    private val verify = object : AbstractAction("核对服务器") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = reconcile()
    }
    init {
        bookmarkName.name = "bookmark-name"; time.name = "bookmark-reminder-time"; reminder.name = "bookmark-reminder-enabled"
        title = "编辑书签"; setOKButtonText("保存"); init()
        Disposer.register(disposable, tasks)
        time.isEnabled = reminder.isSelected
        reminder.addActionListener { time.isEnabled = reminder.isSelected && !busy }
        okAction.putValue(DEFAULT_ACTION, null)
    }
    override fun getPreferredFocusedComponent(): JComponent = bookmarkName
    override fun createActions(): Array<Action> = arrayOf(okAction, verify, cancelAction)
    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(4); preferredSize = java.awt.Dimension(460, 220)
        add(JPanel(BorderLayout()).apply { add(JBLabel("书签名称（最多 100 字符）"), BorderLayout.NORTH); add(bookmarkName) }, BorderLayout.NORTH)
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); add(reminder); add(time)
            add(JBLabel("时间格式：yyyy-MM-dd HH:mm · 时区：$zone"))
            add(policyLabel)
        }, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }
    private fun current() = !isDisposed && active() && version == SessionEpoch.current
    private fun inputReminder(): String? {
        if(!reminder.isSelected) return null
        if(baseline.bookmark.reminderAt?.let { format.format(Instant.parse(it).atZone(zone)) } == time.text.trim()) return baseline.bookmark.reminderAt
        val local = LocalDateTime.parse(time.text.trim(), format)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) { "此时间处于夏令时切换区间，请选择其他时间" }
        return local.toInstant(offsets.single()).toString()
    }
    private fun setBusy(value: Boolean) {
        busy = value; bookmarkName.isEnabled = !value; reminder.isEnabled = !value; time.isEnabled = !value && reminder.isSelected
        isOKActionEnabled = !value && !uncertain; cancelAction.isEnabled = !value; verify.isEnabled = !value
    }
    override fun doCancelAction() { if(!busy) super.doCancelAction() }
    override fun doOKAction() {
        if(busy || uncertain || !current()) return
        val reminderAt = runCatching { inputReminder().also { BookmarkService.validate(bookmarkName.text, it, baseline.bookmark.reminderAt) } }
            .getOrElse { status.text = it.message ?: "名称或时间无效"; return }
        val value = bookmarkName.text
        setBusy(true); status.text = "正在保存…"
        tasks.submit {
            val result = service.save(baseline, value, reminderAt, version, ::current)
            ApplicationManager.getApplication().invokeLater({
                if(isDisposed) return@invokeLater
                uncertain = result.error is UnconfirmedOperationException; setBusy(false)
                if(!current()) { isOKActionEnabled = false; status.text = "账号或页面已变化，输入已保留，请重新打开编辑"; return@invokeLater }
                if(result.error == null) close(OK_EXIT_CODE) else status.text = result.error.message ?: "保存失败，输入已保留"
            }, ModalityState.any())
        }
    }
    private fun reconcile() {
        if(busy || !current()) return
        setBusy(true); status.text = "正在核对服务器…"
        tasks.submit {
            val result = service.reconcile(baseline.bookmark, version)
            val fresh = if(result.error == null) runCatching { service.read(baseline.bookmark, version) } else null
            ApplicationManager.getApplication().invokeLater({
                if(isDisposed) return@invokeLater
                setBusy(false)
                if(!current()) { isOKActionEnabled = false; status.text = "账号或页面已变化，请重新打开编辑"; return@invokeLater }
                if(result.error != null) { status.text = result.error.message; return@invokeLater }
                if(uncertain && result.post?.bookmarkName.orEmpty() == bookmarkName.text &&
                    runCatching { BookmarkService.sameTime(result.post?.bookmarkReminderAt, inputReminder()) }.getOrDefault(false)) {
                    uncertain = false; close(OK_EXIT_CODE); return@invokeLater
                }
                uncertain = false; setBusy(false)
                fresh?.fold({ baseline = it; policyLabel.text = policyText(); status.text = "已核对服务器，输入已保留；再次保存将使用当前输入" }, { status.text = it.message })
            }, ModalityState.any())
        }
    }
}
