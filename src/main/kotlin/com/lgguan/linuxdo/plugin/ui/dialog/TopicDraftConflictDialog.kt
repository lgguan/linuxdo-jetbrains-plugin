package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import javax.swing.*

internal class TopicDraftConflictDialog(project: Project, private val local: String, private val server: String) : DialogWrapper(project) {
    companion object { const val LOCAL = OK_EXIT_CODE; const val SERVER = 2 }
    init { title = "新话题草稿冲突 · 选择完整版本"; init() }
    override fun createCenterPanel() = JPanel(GridLayout(1, 2, 8, 0)).apply {
        preferredSize = Dimension(850, 460)
        listOf("本地版本" to local, "服务器版本" to server).forEach { (label, content) ->
            add(JPanel(BorderLayout()).apply {
                add(JLabel(label), BorderLayout.NORTH)
                add(JBScrollPane(JBTextArea(content).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }))
            })
        }
    }
    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("保留本地版本") { override fun actionPerformed(e: java.awt.event.ActionEvent?) = close(LOCAL) },
        object : AbstractAction("采用服务器版本") { override fun actionPerformed(e: java.awt.event.ActionEvent?) = close(SERVER) }, cancelAction)
}
