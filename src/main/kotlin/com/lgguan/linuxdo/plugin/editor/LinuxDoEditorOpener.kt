package com.lgguan.linuxdo.plugin.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

object LinuxDoEditorOpener {

    fun openTopic(
        project: Project,
        topicId: Long,
        topicTitle: String = "Issue #$topicId",
        postNumber: Int? = null
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || com.lgguan.linuxdo.plugin.service.LinuxDoBossKeyService.getInstance(project).isHidden) return@invokeLater
            val manager = FileEditorManager.getInstance(project)
            val file = manager.openFiles.firstOrNull {
                it.isValid && LinuxDoTopicVirtualFile.accepts(it) && LinuxDoTopicVirtualFile.topicId(it) == topicId
            } ?: LinuxDoTopicVirtualFile.create(topicId, topicTitle, postNumber)
            LinuxDoTopicVirtualFile.setTargetPostNumber(file, postNumber)
            val editors = manager.openFile(file, true)
            if (postNumber != null && postNumber > 0) {
                for (editor in editors) {
                    if (editor is LinuxDoTopicFileEditor) {
                        editor.jumpToFloor(postNumber)
                    }
                }
            }
        }
    }
}
