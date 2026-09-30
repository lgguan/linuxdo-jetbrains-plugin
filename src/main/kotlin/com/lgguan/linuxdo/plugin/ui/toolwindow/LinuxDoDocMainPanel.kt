package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JPanel

class LinuxDoDocMainPanel(private val project: Project) : JPanel(BorderLayout()) {

    val issueListPanel = IssueListPanel(
        project = project,
        onTopicSelected = { topic ->
            LinuxDoEditorOpener.openTopic(project, topic.id, topic.title)
        },
        onBossKeyTriggered = {
            toggleBossKey()
        }
    )

    init {
        border = JBUI.Borders.empty()
        add(issueListPanel, BorderLayout.CENTER)
    }

    fun toggleBossKey() {
        com.lgguan.linuxdo.plugin.service.LinuxDoBossKeyService.getInstance(project).toggle()
    }

    fun refreshAll() {
        issueListPanel.loadInitialData()
    }
}
