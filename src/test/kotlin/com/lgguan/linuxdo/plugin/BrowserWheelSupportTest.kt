package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.net.BrowserWheelSupport
import java.awt.event.InputEvent
import java.awt.event.MouseWheelEvent
import javax.swing.JPanel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowserWheelSupportTest {
    private fun event(rotation: Double, type: Int = MouseWheelEvent.WHEEL_UNIT_SCROLL) = MouseWheelEvent(
        JPanel(), MouseWheelEvent.MOUSE_WHEEL, 123L, InputEvent.SHIFT_DOWN_MASK,
        20, 30, 120, 130, 0, false, type, 3, rotation.toInt(), rotation
    )

    @Test fun `notches use native units and retain targeting and horizontal modifier`() {
        val input = event(1.0)
        val output = BrowserWheelSupport.convert(input, 40, false)
        assertEquals(40, output.wheelRotation)
        assertEquals(120, output.unitsToScroll)
        assertEquals(input.point, output.point)
        assertEquals(input.locationOnScreen, output.locationOnScreen)
        assertEquals(input.`when`, output.`when`)
        assertTrue(output.isShiftDown)
        assertFalse(input.isConsumed)
    }

    @Test fun `fractional rotation is scaled before integer conversion`() {
        assertEquals(10, BrowserWheelSupport.convert(event(0.25), 40, false).wheelRotation)
        assertEquals(-10, BrowserWheelSupport.convert(event(-0.25), 40, false).wheelRotation)
        assertEquals(10.0, BrowserWheelSupport.convert(event(0.25), 40, false).preciseWheelRotation)
    }

    @Test fun `platform direction and block scrolling match IDE adapter`() {
        val output = BrowserWheelSupport.convert(event(1.0, MouseWheelEvent.WHEEL_BLOCK_SCROLL), 40, true)
        assertEquals(-40, output.wheelRotation)
        assertEquals(MouseWheelEvent.WHEEL_BLOCK_SCROLL, output.scrollType)
        assertEquals(1, output.scrollAmount)
    }
}
