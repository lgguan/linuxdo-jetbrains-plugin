package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.theme.NamespaceFormatter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NamespaceFormatterTest {

    @Test
    fun testPresetCategoryMappings() {
        assertEquals("dev.tuning", NamespaceFormatter.format("开发调优", "dev"))
        assertEquals("ai.core", NamespaceFormatter.format("人工智能", "ai"))
        assertEquals("res.share", NamespaceFormatter.format("资源荟萃", "resource"))
        assertEquals("ops.meta", NamespaceFormatter.format("运营反馈", "feedback"))
        assertEquals("general.misc", NamespaceFormatter.format("日常闲聊", "chat"))
        assertEquals("infra.news", NamespaceFormatter.format("前沿快讯", "news"))
    }

    @Test
    fun testSlugFallback() {
        assertEquals("cloud.native", NamespaceFormatter.format("云原生技术", "cloud-native"))
        assertEquals("jvm.internals", NamespaceFormatter.format("JVM深度剖析", "jvm-internals"))
    }

    @Test
    fun testNullOrBlankHandling() {
        assertEquals("common.core", NamespaceFormatter.format(null, null))
        assertEquals("common.core", NamespaceFormatter.format("", ""))
    }
}
