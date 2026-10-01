package com.lgguan.linuxdo.plugin.action

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.lgguan.linuxdo.plugin.ui.dialog.CreateTopicDialog
import com.lgguan.linuxdo.plugin.ui.dialog.LoginAuthDialog
import com.lgguan.linuxdo.plugin.ui.toolwindow.LinuxDoDocMainPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class CreateTopicAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val authService = LinuxDoAuthService.getInstance()

        if (!authService.isLoggedIn) {
            val choice = Messages.showYesNoDialog(
                project,
                "发布话题需要登录 Linux Do 社区账号。\n是否立即打开登录与人机验证窗口？",
                "需要登录",
                Messages.getQuestionIcon()
            )
            if (choice == Messages.YES) {
                LoginAuthDialog(project) {
                    if (authService.isLoggedIn) {
                        openCreateDialog(project)
                    }
                }.show()
            }
            return
        }

        openCreateDialog(project)
    }

    private fun openCreateDialog(project: com.intellij.openapi.project.Project) {
        CreateTopicDialog.open(project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Constants.TOOL_WINDOW_ID)
            val content = toolWindow?.contentManager?.contents?.firstOrNull()
            val mainPanel = content?.component as? LinuxDoDocMainPanel
            mainPanel?.refreshAll()
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}
