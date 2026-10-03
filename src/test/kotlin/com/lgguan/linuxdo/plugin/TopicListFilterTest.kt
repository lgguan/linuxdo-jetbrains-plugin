package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.Constants.TopicFilter
import com.lgguan.linuxdo.plugin.common.DiscourseUrls
import com.lgguan.linuxdo.plugin.model.*
import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TopicListFilterTest {
    @Test fun `account scoped tag caches reject late writes and old callbacks`() {
        val cache = com.lgguan.linuxdo.plugin.net.SessionCache<TagListResponse>()
        val oldVersion = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        val oldTags = TagListResponse(listOf(TagItem("1", "仅旧账号可见", 4)))
        cache.put(oldVersion, oldTags)
        assertEquals(oldTags, cache.get())
        val newVersion = com.lgguan.linuxdo.plugin.net.SessionEpoch.advance()
        assertNull(cache.get())
        cache.put(oldVersion, oldTags)
        assertNull(cache.get())
        var delivered = false
        com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(oldVersion) { delivered = true }
        assertFalse(delivered)
        val newTags = TagListResponse(listOf(TagItem("2", "新账号", 12)))
        cache.put(newVersion, newTags)
        assertEquals(newTags, cache.get())
    }
    @Test fun `every list type retains category tag and pagination`() {
        for (filter in TopicFilter.entries) {
            val name = if (filter in setOf(TopicFilter.TOP, TopicFilter.HOT)) "top" else filter.key
            for (category in listOf(null, 4)) for (tag in listOf(null, "中文 & C++ / #")) {
                val url = DiscourseUrls.topicList("https://linux.do", filter, "dev", category, 3, tag).toHttpUrl()
                assertEquals("3", url.queryParameter("page"))
                val expected = when {
                    tag != null && category != null -> listOf("tags", "c", "dev", "4", tag, "l", "$name.json")
                    tag != null -> listOf("tag", tag, "l", "$name.json")
                    category != null -> listOf("c", "dev", "4", "l", "$name.json")
                    else -> listOf("$name.json")
                }
                assertEquals(expected, url.pathSegments)
                assertEquals(when(filter) { TopicFilter.TOP -> "weekly"; TopicFilter.HOT -> "daily"; else -> null }, url.queryParameter("period"))
            }
        }
        assertEquals("/c/4/l/unread.json", DiscourseUrls.topicList("https://linux.do", TopicFilter.UNREAD, null, 4).toHttpUrl().encodedPath)
    }
    @Test fun `browse candidates are not restricted by composer permissions`() {
        val url = DiscourseUrls.browseTags("https://linux.do", "中文 & C++").toHttpUrl()
        assertEquals("false", url.queryParameter("filterForInput"))
        assertNull(url.queryParameter("categoryId"))
        assertNull(url.queryParameter("limit"))
        assertEquals("中文 & C++", url.queryParameter("q"))
        val response = Gson().fromJson("""{"tags":[{"id":1,"text":"开发","count":24}],"extras":{"tag_groups":[{"id":2,"name":"技术","tags":[{"id":1,"text":"开发","count":24}]}]}}""", TagListResponse::class.java)
        assertEquals("技术", response.extras!!.groups.first().name)
        assertEquals(24, response.tags.first().count)
    }
}
