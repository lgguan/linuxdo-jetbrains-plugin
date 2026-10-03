package com.lgguan.linuxdo.plugin.editor

import com.lgguan.linuxdo.plugin.ui.toolwindow.DocViewerPanel
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class LinuxDoTopicFileEditor(
    private val project: Project,
    val topicFile: com.intellij.openapi.vfs.VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val viewerPanel = DocViewerPanel(project)
    val currentPostNumber: Int? get() = viewerPanel.currentPostNumber

    init {
        com.intellij.openapi.util.Disposer.register(this, viewerPanel)
        viewerPanel.loadTopic(LinuxDoTopicVirtualFile.topicId(topicFile), LinuxDoTopicVirtualFile.targetPostNumber(topicFile))
    }

    fun jumpToFloor(postNumber: Int, onComplete: ((TopicOpenResult) -> Unit)? = null) {
        viewerPanel.jumpToPostNumber(postNumber, onComplete)
    }

    fun openTarget(postNumber: Int?, onComplete: (TopicOpenResult) -> Unit) = viewerPanel.openTarget(postNumber, onComplete)

    override fun getComponent(): JComponent = viewerPanel

    override fun getPreferredFocusedComponent(): JComponent = viewerPanel.preferredFocusedComponent()

    override fun getName(): String = LinuxDoTopicVirtualFile.topicTitle(topicFile)

    override fun setState(state: FileEditorState) {}

    override fun selectNotify() { viewerPanel.setSelected(true) }
    override fun deselectNotify() { viewerPanel.setSelected(false) }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): com.intellij.openapi.vfs.VirtualFile = topicFile

    override fun dispose() {
        com.intellij.openapi.util.Disposer.dispose(viewerPanel)
    }
}
