package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class LinuxDoToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = LinuxDoDocMainPanel(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(mainPanel, "", false)
        content.setDisposer(mainPanel.issueListPanel)
        toolWindow.contentManager.addContent(content)

        val createTopicTitleAction = object : com.intellij.openapi.actionSystem.AnAction(
            "新建话题",
            "创建并发布新话题至社区",
            com.intellij.icons.AllIcons.General.Add
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                mainPanel.issueListPanel.openCreateTopicDialog()
            }
            override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
            }
        }
        val refreshTitleAction = object : com.intellij.openapi.actionSystem.AnAction(
            "刷新",
            "刷新话题与版块",
            com.intellij.icons.AllIcons.Actions.Refresh
        ) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                mainPanel.refreshAll()
            }
            override fun getActionUpdateThread(): com.intellij.openapi.actionSystem.ActionUpdateThread {
                return com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
            }
        }
        toolWindow.setTitleActions(listOf(createTopicTitleAction, refreshTitleAction))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
