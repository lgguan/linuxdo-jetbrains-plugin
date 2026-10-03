package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.*
import com.lgguan.linuxdo.plugin.net.DocumentTrust
import java.awt.GridLayout
import javax.swing.*

internal class ComposerLinkDialog(project: Project, label: String, url: String) : DialogWrapper(project) {
    private val labelField = JBTextField(label).apply { name = "composer-link-label" }
    private val urlField = JBTextField(url).apply { name = "composer-link-url" }
    val label get() = labelField.text
    val url get() = urlField.text.trim()
    init { title = "插入或编辑链接"; setOKButtonText("应用"); init() }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(2, 2, 8, 8)).apply {
        add(JBLabel("链接文字").apply { labelFor = labelField }); add(labelField)
        add(JBLabel("URL").apply { labelFor = urlField }); add(urlField)
    }
    override fun getPreferredFocusedComponent(): JComponent = urlField
    override fun doValidate(): ValidationInfo? = when {
        label.isBlank() -> ValidationInfo("请输入链接文字", labelField)
        !DocumentTrust.isWebLink(url) || url.any { it == '\r' || it == '\n' || it == '\t' } -> ValidationInfo("请输入 http 或 https 链接", urlField)
        else -> null
    }
}

internal class ComposerCodeBlockDialog(project: Project, language: String, existing: Boolean) : DialogWrapper(project) {
    private val languageField = JBTextField(language, 24).apply { name = "composer-code-language" }
    private val removeBox = JBCheckBox("移除围栏，保留代码正文").apply { isVisible = existing; name = "composer-code-remove" }
    val language get() = languageField.text.trim()
    val remove get() = removeBox.isSelected
    init { title = "代码块"; setOKButtonText("应用"); init(); removeBox.addActionListener { languageField.isEnabled = !remove } }
    override fun createCenterPanel(): JComponent = JPanel(GridLayout(3, 1, 0, 6)).apply {
        add(JBLabel("语言标识（可留空，例如 kotlin、c++）").apply { labelFor = languageField })
        add(languageField); add(removeBox)
    }
    override fun getPreferredFocusedComponent(): JComponent = languageField
    override fun doValidate(): ValidationInfo? = if (!remove && language.isNotEmpty() && !language.matches(Regex("[A-Za-z0-9_+.\\-]{1,64}")))
        ValidationInfo("语言标识最多 64 个字符，仅支持字母、数字及 _ + . -", languageField) else null
}
