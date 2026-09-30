package com.lgguan.linuxdo.plugin.config

import com.intellij.openapi.options.SearchableConfigurable
import javax.swing.JComponent

class LinuxDoSettingsConfigurable : SearchableConfigurable {

    private var settingsPanel: LinuxDoSettingsPanel? = null

    override fun getId(): String = "com.lgguan.linuxdo.plugin.config.LinuxDoSettingsConfigurable"

    override fun getDisplayName(): String = "Linux Do (API Docs)"

    override fun createComponent(): JComponent {
        val panel = LinuxDoSettingsPanel()
        settingsPanel = panel
        return panel.mainPanel
    }

    override fun isModified(): Boolean {
        val panel = settingsPanel ?: return false
        val state = LinuxDoSettingsState.getInstance()
        return panel.isModified(state)
    }

    override fun apply() {
        val panel = settingsPanel ?: return
        val state = LinuxDoSettingsState.getInstance()
        panel.applyTo(state)
    }

    override fun reset() {
        val panel = settingsPanel ?: return
        val state = LinuxDoSettingsState.getInstance()
        panel.resetFrom(state)
    }

    override fun disposeUIResources() {
        settingsPanel?.dispose()
        settingsPanel = null
    }
}
