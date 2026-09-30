package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

class LinuxDoLogDialog(private val myProject: Project?) : DialogWrapper(myProject, true) {

    private val textArea = JBTextArea()

    init {
        title = "📋 Linux Do 插件运行日志 (Runtime Logs)"
        setOKButtonText("关闭")
        init()
        refreshLogs()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8))
        mainPanel.border = JBUI.Borders.empty(8)
        mainPanel.preferredSize = Dimension(720, 460)

        // Monospace font with CJK fallback
        val baseFont = Font(Font.MONOSPACED, Font.PLAIN, 12)
        textArea.font = UIUtil.getFontWithFallback(baseFont)
        textArea.isEditable = false
        textArea.lineWrap = false

        val scrollPane = JBScrollPane(textArea)
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        // Top area: Toolbar + Persistent Log Path
        val topContainer = JPanel(BorderLayout(0, 4))

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        val refreshBtn = JButton("🔄 刷新").apply {
            addActionListener { refreshLogs() }
        }
        val copyBtn = JButton("📋 复制全部").apply {
            addActionListener {
                val text = textArea.text
                if (text.isNotBlank()) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    Messages.showInfoMessage("日志内容已复制到剪贴板", "复制成功")
                }
            }
        }
        val clearBtn = JButton("🗑️ 清空").apply {
            addActionListener {
                LinuxDoLog.clear()
                refreshLogs()
            }
        }
        val openFileBtn = JButton("📄 打开日志文件").apply {
            addActionListener {
                openLogFile()
            }
        }
        val revealBtn = JButton("📁 浏览所在目录").apply {
            addActionListener {
                revealLogDirectory()
            }
        }

        toolbar.add(refreshBtn)
        toolbar.add(copyBtn)
        toolbar.add(clearBtn)
        toolbar.add(openFileBtn)
        toolbar.add(revealBtn)

        val pathLabel = JBLabel("持久化存储路径: ${LinuxDoLog.logFilePath}").apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(4, 4, 0, 0)
        }

        topContainer.add(toolbar, BorderLayout.NORTH)
        topContainer.add(pathLabel, BorderLayout.SOUTH)

        mainPanel.add(topContainer, BorderLayout.NORTH)

        return mainPanel
    }

    private fun openLogFile() {
        val file = LinuxDoLog.getLogFile()
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        if (virtualFile != null && myProject != null) {
            FileEditorManager.getInstance(myProject).openFile(virtualFile, true)
            close(OK_EXIT_CODE)
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
        } else {
            Messages.showInfoMessage("日志文件路径: ${file.absolutePath}", "日志文件")
        }
    }

    private fun revealLogDirectory() {
        val file = LinuxDoLog.getLogFile()
        val dir = file.parentFile ?: file
        if (!dir.exists()) dir.mkdirs()
        try {
            RevealFileAction.openDirectory(dir)
        } catch (t: Throwable) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir)
            } else {
                Messages.showInfoMessage("日志目录: ${dir.absolutePath}", "日志目录")
            }
        }
    }

    private fun refreshLogs() {
        val logs = LinuxDoLog.getLogsText()
        textArea.text = if (logs.isBlank()) "暂无日志记录。" else logs
        textArea.caretPosition = textArea.document.length
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
