package com.lgguan.linuxdo.plugin.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

object LinuxDoEditorOpener {

    fun openTopic(
        project: Project,
        topicId: Long,
        topicTitle: String = "Issue #$topicId",
        postNumber: Int? = null,
        onComplete: ((TopicOpenResult) -> Unit)? = null
    ) {
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || version != com.lgguan.linuxdo.plugin.net.SessionEpoch.current ||
                com.lgguan.linuxdo.plugin.service.LinuxDoBossKeyService.getInstance(project).isHidden) {
                onComplete?.invoke(TopicOpenResult.CANCELLED)
                return@invokeLater
            }
            try {
                val manager = FileEditorManager.getInstance(project)
                val file = manager.openFiles.firstOrNull {
                    it.isValid && LinuxDoTopicVirtualFile.accepts(it) && LinuxDoTopicVirtualFile.topicId(it) == topicId
                } ?: LinuxDoTopicVirtualFile.create(topicId, topicTitle, postNumber)
                LinuxDoTopicVirtualFile.setTargetPostNumber(file, postNumber)
                val editors = manager.openFile(file, true)
                if (onComplete != null) {
                    val editor = editors.filterIsInstance<LinuxDoTopicFileEditor>().firstOrNull()
                    if (editor == null) onComplete(TopicOpenResult.FAILURE) else editor.openTarget(postNumber, onComplete)
                } else if (postNumber != null && postNumber > 0) {
                    for (editor in editors) {
                        if (editor is LinuxDoTopicFileEditor) {
                            editor.jumpToFloor(postNumber)
                        }
                    }
                }
            } catch (_: Exception) { onComplete?.invoke(TopicOpenResult.FAILURE) }
        }
    }
}
