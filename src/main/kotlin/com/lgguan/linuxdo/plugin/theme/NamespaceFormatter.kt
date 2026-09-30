package com.lgguan.linuxdo.plugin.theme

object NamespaceFormatter {

    private val PRESET_MAPPINGS = mapOf(
        "开发调优" to "dev.tuning",
        "人工智能" to "ai.core",
        "资源荟萃" to "res.share",
        "运营反馈" to "ops.meta",
        "日常闲聊" to "general.misc",
        "前沿快讯" to "infra.news",
        "福利羊毛" to "sec.bonus",
        "文档教程" to "docs.guide",
        "软件分享" to "pkg.dist",
        "网络安全" to "sec.audit"
    )

    fun format(categoryName: String?, slug: String?): String {
        if (categoryName.isNullOrBlank()) return "common.core"
        PRESET_MAPPINGS[categoryName.trim()]?.let { return it }

        if (!slug.isNullOrBlank()) {
            return slug.trim().lowercase().replace("-", ".")
        }

        return categoryName.trim()
    }
}
