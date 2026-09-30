package com.lgguan.linuxdo.plugin.common

import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.keymap.KeymapUtil
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

internal object PlatformShortcuts {
    val submitLabel: String get() = if (HostPlatform.detect() == HostPlatform.MAC) "Command+Enter" else "Ctrl+Enter"
    val pasteLabel: String get() = KeymapUtil.getShortcutsText(CommonShortcuts.getPaste().shortcuts)

    fun isSubmit(event: KeyEvent, platform: HostPlatform = HostPlatform.detect()): Boolean {
        val modifier = if (platform == HostPlatform.MAC) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
        val modifiers = event.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or
            InputEvent.ALT_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK or InputEvent.ALT_GRAPH_DOWN_MASK)
        return !event.isConsumed && event.keyCode == KeyEvent.VK_ENTER && modifiers == modifier
    }
}
