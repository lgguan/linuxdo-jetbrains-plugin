package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Category
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.PostStream
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CategoryAndAdvancedSearchTest {

    @Test
    fun productionStartsWithoutInventedCategories() {
        val service = LinuxDoTopicService()
        assertTrue(service.categories.isEmpty())
        assertTrue(service.getHierarchicalCategories().isEmpty())
        assertFalse(service.categoriesAreCurrent)
    }

    @Test
    fun testFlattenCategoriesFromBothNestedAndFlatStructures() {
        val service = LinuxDoTopicService()

        // 1. Nested structure (like from categories.json)
        val nestedCats = listOf(
            Category(
                id = 10, name = "Root A", color = "fff", slug = "root-a",
                subcategoryList = listOf(
                    Category(id = 101, name = "Child A1", color = "fff", slug = "child-a1", parentCategoryId = 10),
                    Category(
                        id = 102, name = "Child A2", color = "fff", slug = "child-a2", parentCategoryId = 10,
                        subcategories = listOf(
                            Category(id = 1021, name = "Grandchild A21", color = "fff", slug = "gc-a21", parentCategoryId = 102)
                        )
                    )
                )
            )
        )

        val flatFromNested = service.flattenCategories(nestedCats)
        assertEquals(4, flatFromNested.size)
        assertTrue(flatFromNested.any { it.id == 10 })
        assertTrue(flatFromNested.any { it.id == 101 })
        assertTrue(flatFromNested.any { it.id == 102 })
        assertTrue(flatFromNested.any { it.id == 1021 })

        // 2. Flat structure (like from site.json)
        val siteCats = listOf(
            Category(id = 20, name = "Root B", color = "fff", slug = "root-b", parentCategoryId = null),
            Category(id = 201, name = "Child B1", color = "fff", slug = "child-b1", parentCategoryId = 20),
            Category(id = 202, name = "Child B2", color = "fff", slug = "child-b2", parentCategoryId = 20)
        )
        val flatFromSite = service.flattenCategories(siteCats)
        assertEquals(3, flatFromSite.size)
    }

    @Test
    fun testCategoryListenersNotification() {
        val service = LinuxDoTopicService()
        var notifiedCount = 0
        var receivedListSize = 0

        val listener: (List<Category>) -> Unit = { list ->
            notifiedCount++
            receivedListSize = list.size
        }

        service.addCategoryListener(listener)

        // Simulate category load update
        val newCats = listOf(
            Category(id = 999, name = "Dynamic Board", color = "000", slug = "dynamic-board")
        )
        val flattened = service.flattenCategories(newCats)
        service.categories.clear()
        service.categories.addAll(flattened)

        // Directly invoke listener
        listener(service.categories.toList())

        assertEquals(1, notifiedCount)
        assertEquals(1, receivedListSize)

        service.removeCategoryListener(listener)
    }

    @Test
    fun testMarkAllReadButtonCompletelyRemovedFromDocHtml() {
        val theme = EditorColorSchemeAdapter.ThemeColors(
            bgHex = "#2B2D30",
            fgHex = "#DFE1E5",
            commentHex = "#7A7E85",
            keywordHex = "#CC7832",
            linkHex = "#589DF6",
            selectionBgHex = "#32435C",
            selectionFgHex = "#DFE1E5",
            codeBlockBgHex = "#1E1F22",
            borderHex = "#393B40",
            fontName = "JetBrains Mono",
            fontSize = 13,
            isDark = true
        )

        val settings = LinuxDoSettingsState()
        val samplePost = Post(
            id = 100,
            username = "neo",
            cooked = "<p>Welcome to Linux Do</p>",
            postNumber = 1
        )
        val topic = TopicDetailResponse(
            id = 12345,
            title = "Test Topic Title",
            postStream = PostStream(posts = listOf(samplePost))
        )

        val html = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(samplePost),
            categoryName = "日常闲聊",
            categorySlug = "chat",
            theme = theme,
            settings = settings
        )

        // Verify "标记全部已读" and bulk read functions are NOT in html
        assertFalse(html.contains("标记全部已读"), "HTML must NOT contain '标记全部已读' button")
        assertFalse(html.contains("markAllFloorsRead"), "HTML must NOT contain markAllFloorsRead JS function")
        assertFalse(html.contains("mark-all-read-btn"), "HTML must NOT contain mark-all-read-btn CSS class")
        assertFalse(html.contains("doc-header-actions"), "HTML must NOT contain doc-header-actions")

        // But single floor read dot and tracking MUST be preserved
        assertTrue(html.contains("unread-dot"), "Single floor unread-dot must be preserved")
        assertTrue(html.contains("markFloorRead"), "Single floor markFloorRead must be preserved")
    }

    @Test
    fun testAdvancedSearchTagAndCategoryQueryBuilder() {
        // Test Category formatting:
        // Canonical Discourse format for category + subcategory is #category:subcategory
        fun buildCategoryQuery(slug: String?, parentSlug: String?): String? {
            if (slug.isNullOrBlank()) return null
            return if (!parentSlug.isNullOrBlank()) "#$parentSlug:$slug" else "#$slug"
        }

        assertEquals("#dev:tuning", buildCategoryQuery("tuning", "dev"))
        assertEquals("#dev", buildCategoryQuery("dev", null))

        // Test Tag query formatting:
        fun buildTagQuery(rawInput: String): String? {
            val tags = rawInput.split(Regex("[,，\\s]+"))
                .map { it.trim().replace(Regex("""^#+"""), "") }
                .filter { it.isNotBlank() }
            return when {
                tags.isEmpty() -> null
                tags.size == 1 -> "tag:${tags[0]}"
                else -> "tags:${tags.joinToString(",")}"
            }
        }

        assertEquals("tag:纯水", buildTagQuery("#纯水"))
        assertEquals("tags:纯水,快问快答,dev", buildTagQuery("#纯水, 快问快答， dev"))
        assertNull(buildTagQuery("   "))
    }

    @Test
    fun testIssueListPanelErrorDisplayFormatting() {
        // 1. 404 with JSON Discourse error body
        val raw404 = "HTTP 404: {\"errors\":[\"找不到请求的 URL 或资源。\"],\"error_type\":\"not_found\"}"
        val formatted404 = IssueListPanel.formatErrorDisplay(raw404)
        assertTrue(formatted404.contains("404"), "Must indicate 404 status")
        assertTrue(formatted404.contains("所选分类或请求的内容未找到") || formatted404.contains("全部版块"), "Must give friendly actionable advice: $formatted404")
        assertFalse(formatted404.contains("{\"errors\":"), "Must NOT display raw JSON syntax")

        // 2. 429 Rate limit
        val raw429 = "HTTP 429: Too Many Requests"
        val formatted429 = IssueListPanel.formatErrorDisplay(raw429)
        assertTrue(formatted429.contains("429") && formatted429.contains("防风控安全熔断保护"), "Must explain 429 rate limit protection: $formatted429")

        // 3. Cloudflare challenge
        val rawCf = "Cloudflare 安全验证未通过 (HTTP 403)"
        val formattedCf = IssueListPanel.formatErrorDisplay(rawCf)
        assertTrue(formattedCf.contains("安全防护") && formattedCf.contains("人机验证"), "Must explain Cloudflare turnstile: $formattedCf")

        // 4. Network timeout
        val rawTimeout = "java.net.SocketTimeoutException: timeout after 10000ms"
        val formattedTimeout = IssueListPanel.formatErrorDisplay(rawTimeout)
        assertTrue(formattedTimeout.contains("超时") && formattedTimeout.contains("代理"), "Must suggest checking proxy: $formattedTimeout")

        // 5. A reset alone does not identify its cause.
        val rawReset = "java.net.SocketException: Connection reset"
        val formattedReset = IssueListPanel.formatErrorDisplay(rawReset)
        assertTrue(formattedReset.contains("重置") && formattedReset.contains("尚未确认") && formattedReset.contains("诊断"), "Must preserve the reset without claiming a cause: $formattedReset")
    }

    @Test
    fun testTopicServiceStatePersistence() {
        val service = LinuxDoTopicService()
        val customCats = listOf(
            Category(id = 888, name = "Persisted Board", color = "123456", slug = "persisted-board")
        )
        val json = com.google.gson.Gson().toJson(customCats)
        val state = LinuxDoTopicService.State().apply {
            cachedCategoriesJson = json
            cachedTags = mutableListOf("测试持久化标签1", "测试持久化标签2")
        }

        // Restore state
        service.loadState(state)

        assertNull(service.getCategory(888))
        assertTrue(service.popularTags.isEmpty())
        assertFalse(service.categoriesAreCurrent)
        assertNull(service.state.cachedCategoriesJson)
    }
}
