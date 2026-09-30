package com.lgguan.linuxdo.plugin.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class LinuxDoTopicEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return LinuxDoTopicVirtualFile.accepts(file)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        require(LinuxDoTopicVirtualFile.accepts(file))
        return LinuxDoTopicFileEditor(project, file)
    }

    override fun getEditorTypeId(): String = "LinuxDoTopicEditor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
