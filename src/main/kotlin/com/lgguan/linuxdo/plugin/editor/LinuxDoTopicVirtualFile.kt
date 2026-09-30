package com.lgguan.linuxdo.plugin.editor

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/** Closed-tab history may outlive the plugin. Never put plugin-owned objects in it. */
object LinuxDoTopicVirtualFile {
    private val TOPIC_ID = Key.create<Long>("linuxdo.topic.id")
    private val TITLE = Key.create<String>("linuxdo.topic.title")
    private val TARGET = Key.create<Int>("linuxdo.topic.targetFloor")

    fun create(topicId: Long, topicTitle: String, targetPostNumber: Int? = null): LightVirtualFile {
        require(topicId > 0)
        return LightVirtualFile("[$topicId] ${sanitizeFileName(topicTitle)}.doc", PlainTextFileType.INSTANCE, "").apply {
            isWritable = false
            putUserData(TOPIC_ID, topicId)
            putUserData(TITLE, topicTitle)
            setTargetPostNumber(this, targetPostNumber)
        }
    }

    fun accepts(file: VirtualFile): Boolean = file is LightVirtualFile && file.getUserData(TOPIC_ID) != null
    fun topicId(file: VirtualFile): Long = requireNotNull(file.getUserData(TOPIC_ID))
    fun topicTitle(file: VirtualFile): String = file.getUserData(TITLE) ?: file.name
    fun targetPostNumber(file: VirtualFile): Int? = file.getUserData(TARGET)

    fun setTargetPostNumber(file: VirtualFile, floor: Int?) {
        file.putUserData(TARGET, floor?.takeIf { it > 0 })
    }

    fun sanitizeFileName(title: String): String {
        val clean = title.replace(Regex("[/\\\\:*?\"<>|\\r\\n]"), "_").trim()
        return if (clean.length > 35) clean.take(35) + "..." else clean
    }
}
