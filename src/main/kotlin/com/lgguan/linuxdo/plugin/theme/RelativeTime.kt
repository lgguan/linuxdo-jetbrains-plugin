package com.lgguan.linuxdo.plugin.theme

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object RelativeTime {
    fun format(value: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return "时间未知"
        val seconds = Duration.between(instant, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "刚刚"
            seconds < 3600 -> "${seconds / 60}分钟前"
            seconds < 86400 -> "${seconds / 3600}个小时前"
            seconds < 604800 -> "${seconds / 86400}天前"
            else -> DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(instant)
        }
    }
}
