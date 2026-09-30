package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.HostPlatform
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

internal object BrowserGeometry {
    fun scale(value: Double): Double = value.takeIf { it.isFinite() && it > 0 } ?: 1.0

    /** CEF uses Cocoa screen coordinates on macOS and physical pixels on Windows/Linux. */
    fun screenPoint(platform: HostPlatform, origin: Point, viewPoint: Point, screen: Rectangle, density: Double): Point {
        val x = origin.x + viewPoint.x
        val y = origin.y + viewPoint.y
        return if (platform == HostPlatform.MAC) Point(x, screen.height - y)
        else Point((x * scale(density)).roundToInt(), (y * scale(density)).roundToInt())
    }
}
