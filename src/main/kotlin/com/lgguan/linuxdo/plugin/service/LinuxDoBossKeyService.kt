package com.lgguan.linuxdo.plugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.editor.LinuxDoTopicFileEditor
import com.lgguan.linuxdo.plugin.editor.LinuxDoTopicVirtualFile

/** A separate session per project; the shortcut works even when the tool window is closed. */
@Service(Service.Level.PROJECT)
class LinuxDoBossKeyService(private val project: Project) {
    private data class Session(
        val topics: List<VirtualFile>,
        val selected: VirtualFile?,
        val listVisible: Boolean
    )

    private var session: Session? = null
    val isHidden: Boolean get() = session != null

    fun toggle() {
        if (project.isDisposed) return
        val manager = FileEditorManagerEx.getInstanceEx(project)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(Constants.TOOL_WINDOW_ID)
        val previous = session
        if (previous != null) {
            session = null
            previous.topics.filter { it.isValid }.forEach { manager.openFile(it, false) }
            previous.selected?.takeIf { it.isValid && manager.isFileOpen(it) }?.let { manager.openFile(it, true) }
            if (previous.listVisible) toolWindow?.show()
            return
        }

        val topics = manager.openFiles.filter(LinuxDoTopicVirtualFile::accepts)
        if (topics.isEmpty() && toolWindow?.isVisible != true) return
        session = Session(topics, manager.selectedFiles.firstOrNull(), toolWindow?.isVisible == true)
        toolWindow?.hide()
        topics.forEach { file ->
            manager.getEditors(file).filterIsInstance<LinuxDoTopicFileEditor>().firstOrNull()?.let {
                LinuxDoTopicVirtualFile.setTargetPostNumber(file, it.currentPostNumber)
            }
            // Close all copies, including editor splits, so no forum content or tab title remains.
            manager.windows.toList().forEach { window -> manager.closeFile(file, window) }
        }
    }

    companion object {
        fun getInstance(project: Project): LinuxDoBossKeyService = project.getService(LinuxDoBossKeyService::class.java)
    }
}
