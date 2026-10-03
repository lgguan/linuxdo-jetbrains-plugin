package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.model.AdvancedSearchQuery
import com.lgguan.linuxdo.plugin.model.SearchCapabilities
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AdvancedSearchQueryTest {
    @Test fun `escaped and unfinished quotations are retained instead of losing punctuation`() {
        for (text in listOf("\"say \\\"hello\\\"\" -custom:\"a b\"", "text \"unfinished phrase", "custom:'unclosed value"))
            assertEquals(text, AdvancedSearchQuery.parse(text).query())
    }
    @Test fun `reopening and replacing filters preserves phrases exclusions and unknown syntax`() {
        val model = AdvancedSearchQuery.parse("\"hello world\" -tag:blocked -in:seen custom:\"a b\" #dev:tools tags:原创+开发 @neo in:title with:images order:latest")
        assertEquals("#dev:tools", model.filters["category"])
        assertEquals("原创+开发", model.filters["tags"])
        assertEquals("neo", model.filters["author"])
        model.filters["tags"] = "原创,开发"
        model.filters["order"] = "latest_topic"
        val query = model.query()
        assertTrue(query.contains("\"hello world\" -tag:blocked -in:seen custom:\"a b\""))
        assertTrue(query.contains("tags:原创,开发"))
        assertTrue(query.contains("order:latest_topic"))
        assertFalse(query.contains("order:latest "))
        assertEquals(model.filters, AdvancedSearchQuery.parse(query).filters)
        assertFalse(query.contains("in:title:title"))
    }
    @Test fun `messages alias scopes and expert extensions round trip`() {
        val model = AdvancedSearchQuery.parse("in:personal in:created in:watching is:category_expert_question with:category_expert_response without:category_expert_post status:solved order:votes")
        assertEquals("messages", model.filters["scope"])
        assertTrue(model.query().contains("in:messages"))
        assertEquals(model.filters, AdvancedSearchQuery.parse(model.query()).filters)
    }
    @Test fun `tag aliases and AND OR semantics are independent of category`() {
        assertEquals("dev", AdvancedSearchQuery.parse("#dev::tag").filters["tags"])
        assertEquals("a+b", AdvancedSearchQuery.parse("tags:a+b").filters["tags"])
        assertEquals("a,b", AdvancedSearchQuery.parse("tags:a,b").filters["tags"])
        assertEquals("a", AdvancedSearchQuery.parse("tag:a").filters["tags"])
        assertEquals("#dev:tools", AdvancedSearchQuery.parse("#dev:tools #a::tag").filters["category"])
    }
    @Test fun `explicit conditions supersede inherited filters including exclusions and quoted values`() {
        assertEquals("word category:7 tag:开发", AdvancedSearchQuery.inherit("word", 7, "开发"))
        assertEquals("word #dev tags:a+b", AdvancedSearchQuery.inherit("word #dev tags:a+b", 7, "开发"))
        assertEquals("word -category:7 -tag:开发", AdvancedSearchQuery.inherit("word -category:7 -tag:开发", 7, "开发"))
        assertEquals("word category:\"dev tools\" tags:\"tag name\"", AdvancedSearchQuery.inherit("word category:\"dev tools\" tags:\"tag name\"", 7, "开发"))
        assertEquals("#123", AdvancedSearchQuery.inherit("#123", 7, "开发"))
        assertEquals("", AdvancedSearchQuery.inherit("", 7, "开发"))
        val model = AdvancedSearchQuery.parse("word category:7 tag:old")
        model.text = "\"phrase\" category:9 tags:new"
        assertEquals("\"phrase\" category:9 tags:new", model.query())
    }
    @Test fun `date number and range errors identify the corresponding field`() {
        for (date in listOf("2025-02-29", "2026-13-01", "2026-02-31", "bad"))
            assertEquals("after", AdvancedSearchQuery.parse("after:$date").validate()?.field)
        assertNull(AdvancedSearchQuery.parse("after:2024-02-29 before:2026-10-03 min_posts:0 max_posts:12 min_views:1 max_views:100").validate())
        assertNull(AdvancedSearchQuery.parse("after:7 before:0").validate())
        assertEquals("before", AdvancedSearchQuery.parse("after:2026-10-03 before:2026-01-01").validate()?.field)
        for (number in listOf("-1", "1.5", "abc", "99999999999999999999999999"))
            assertEquals("min_posts", AdvancedSearchQuery.parse("min_posts:$number").validate()?.field)
        assertEquals("max_posts", AdvancedSearchQuery.parse("min_posts:12 max_posts:11").validate()?.field)
        assertEquals("max_views", AdvancedSearchQuery.parse("min_views:12 max_views:11").validate()?.field)
    }
    @Test fun `unknown orders are preserved and native sort values are correct`() {
        assertEquals("最新回复", AdvancedSearchQuery.orders["latest"])
        assertEquals("最新话题", AdvancedSearchQuery.orders["latest_topic"])
        assertEquals("最近阅读", AdvancedSearchQuery.orders["read"])
        assertEquals("text order:activity", AdvancedSearchQuery.parse("text order:activity").query())
    }
    @Test fun `feature flags are sourced from bootstrap and disabled extensions stay hidden`() {
        fun html(settings: String) = """<script type="application/json" id="data-preloaded">{"siteSettings":$settings}</script>"""
        assertEquals(SearchCapabilities(true, true, true, true, true), SearchCapabilities.parse(html("""{"tagging_enabled":true,"solved_enabled":true,"topic_voting_enabled":true,"enable_category_experts":true,"show_category_expert_advanced_search_filters":true}""")))
        assertEquals(SearchCapabilities(confirmed = true), SearchCapabilities.parse(html("{}")))
        assertThrows(Exception::class.java) { SearchCapabilities.parse("<html>challenge</html>") }
    }
}
