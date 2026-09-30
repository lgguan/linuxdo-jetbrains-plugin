package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

class BoostQuickReplyDialog(
    private val project: Project?,
    private val postId: Long,
    private val postNumber: Int,
    private val author: String,
    private val onBoostSuccess: (() -> Unit)? = null
) : DialogWrapper(project, true) {

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()
    private val inputField = JBTextField(24)
    private val statusLabel = JBLabel(" ")

    init {
        title = "🚀 Boost 快捷回复 (微回复)"
        setOKButtonText("🚀 发送 Boost")
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8))
        mainPanel.border = JBUI.Borders.empty(12)
        mainPanel.preferredSize = Dimension(440, 130)

        // Top info
        val infoLabel = JBLabel(
            "<html><body>" +
            "对 <b>#$postNumber 楼 @$author</b> 发送 Boost 微回复：<br>" +
            "<span style='color:gray;font-size:11px;'>• 请输入您的简短表态（建议 ≤16 字符），直接附加在楼层下。</span>" +
            "</body></html>"
        )
        mainPanel.add(infoLabel, BorderLayout.NORTH)

        val safeFont = com.intellij.util.ui.UIUtil.getFontWithFallback(inputField.font)
        inputField.font = safeFont
        val inputContainer = JPanel(BorderLayout(0, 4))
        inputField.emptyText.text = "输入自定义 Boost 内容..."
        inputContainer.add(inputField, BorderLayout.CENTER)
        inputContainer.add(statusLabel, BorderLayout.SOUTH)

        mainPanel.add(inputContainer, BorderLayout.CENTER)

        return mainPanel
    }

    override fun doOKAction() {
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        if (!com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().isLoggedIn) {
            statusLabel.text = "请先完成登录验证"
            return
        }
        val text = inputField.text.trim()
        if (text.isBlank()) {
            statusLabel.text = "❌ 请输入回复内容"
            return
        }
        if (text.length > 50) {
            statusLabel.text = "❌ 内容过长，Boost 仅限简短微回复 (建议 ≤16 字)"
            return
        }

        statusLabel.text = "⏳ 正在提交 Boost..."
        isOKActionEnabled = false

        backgroundTasks.submit {
            try {
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Boost dialog submitting to post $postId")
                val result = DiscourseApiClient.boostPost(postId, text, expectedVersion = session)
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    if (session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) {
                        isOKActionEnabled = true
                        statusLabel.text = "账号已切换，内容已保留；请确认账号后再提交"
                        return@invokeLater
                    }
                    result.onSuccess {
                        com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("Boost succeeded for post $postId")
                        statusLabel.text = "🟢 Boost 发送成功！"
                        try {
                            onBoostSuccess?.invoke()
                        } catch (t: Throwable) {
                            com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("onBoostSuccess callback error", t)
                        }
                        close(OK_EXIT_CODE)
                    }.onFailure { err ->
                        isOKActionEnabled = true
                        com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Boost failed: ${err.message}")
                        statusLabel.text = "❌ 发送失败: ${err.message?.take(50)}"
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            } catch (t: Throwable) {
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.error("Boost exception", t)
                ApplicationManager.getApplication().invokeLater({
                    if (isDisposed) return@invokeLater
                    if (session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) {
                        isOKActionEnabled = true
                        statusLabel.text = "账号已切换，内容已保留；请确认账号后再提交"
                        return@invokeLater
                    }
                    isOKActionEnabled = true
                    statusLabel.text = "❌ 异常: ${t.message?.take(50)}"
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }
    override fun dispose() {
        backgroundTasks.dispose()
        super.dispose()
    }

}
