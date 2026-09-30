package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.registry.Registry
import java.awt.event.MouseWheelEvent

internal object BrowserWheelSupport {
    // Match JBCefOsrComponent: raw AWT notches are not native CEF wheel units.
    fun toCefEvent(event: MouseWheelEvent): MouseWheelEvent = convert(
        event,
        Registry.intValue("ide.browser.jcef.osr.wheelRotation.factor", 40),
        SystemInfoRt.isLinux || SystemInfoRt.isMac
    )

    internal fun convert(event: MouseWheelEvent, factor: Int, reverse: Boolean): MouseWheelEvent {
        val rotation = event.preciseWheelRotation * factor * if (reverse) -1 else 1
        return MouseWheelEvent(
            event.component, event.id, event.`when`, event.modifiersEx,
            event.x, event.y, event.xOnScreen, event.yOnScreen,
            event.clickCount, event.isPopupTrigger, event.scrollType,
            if (event.scrollType == MouseWheelEvent.WHEEL_UNIT_SCROLL) event.scrollAmount else 1,
            Math.round(rotation).toInt(), rotation
        )
    }
}
