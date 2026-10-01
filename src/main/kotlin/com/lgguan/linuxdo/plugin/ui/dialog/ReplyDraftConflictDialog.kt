package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

internal class ReplyDraftConflictDialog(project: Project, private val local: String, private val server: String,
                                        private val localFloor: Int?, private val serverFloor: Int?) : DialogWrapper(project) {
    companion object { const val LOCAL = OK_EXIT_CODE; const val SERVER = 2 }
    init { title = "论坛草稿冲突 · 选择保留版本"; init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(1, 2, 8, 0)).apply {
        preferredSize = Dimension(850, 460)
        listOf(Triple("本地正文", local, localFloor), Triple("服务器正文", server, serverFloor)).forEach { (label, body, floor) ->
            add(JPanel(BorderLayout()).apply {
                add(JLabel("$label · ${floor?.let { "回复 #$it 楼" } ?: "回复话题"}"), BorderLayout.NORTH)
                add(JBScrollPane(JBTextArea(body).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }), BorderLayout.CENTER)
            })
        }
    }
    override fun createActions(): Array<Action> = arrayOf(
        object : AbstractAction("保留本地版本") { override fun actionPerformed(e: ActionEvent?) = close(LOCAL) },
        object : AbstractAction("采用服务器版本") { override fun actionPerformed(e: ActionEvent?) = close(SERVER) },
        cancelAction)
}
