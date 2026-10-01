package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.api.TopicSearch
import com.lgguan.linuxdo.plugin.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TopicBrowsingTest {
    @Test fun `real tag objects and legacy names parse with the default Gson`() {
        val topic = Gson().fromJson("""{"id":9,"title":"中文","tags":[{"id":17,"name":"开发","slug":"dev","extra":true},"旧标签",{"id":null,"name":"空编号","slug":null}]}""", Topic::class.java)
        assertEquals(listOf(TopicTag(17, "开发", "dev"), TopicTag(name = "旧标签"), TopicTag(name = "空编号")), topic.tags)
        assertEquals(topic.tags, Gson().fromJson(Gson().toJson(topic), Topic::class.java).tags)
    }

    @Test fun `search uses the first matching floor and full page flag`() {
        val response = Gson().fromJson("""{"topics":[{"id":8,"title":"标题"},{"id":8,"title":"重复"},{"id":9,"title":"无匹配"}],"posts":[{"id":1,"topic_id":8,"post_number":42,"blurb":"中文 <b>摘要</b>"},{"id":2,"topic_id":8,"post_number":55,"blurb":"第二项"}],"grouped_search_result":{"more":false,"more_full_page_results":true}}""", SearchResultResponse::class.java)
        val topics = TopicBrowsing.searchTopics(response)
        assertTrue(response.groupedSearchResult!!.moreFullPageResults)
        assertEquals(listOf(8L, 9L), topics.map { it.id })
        assertEquals(42, topics.first().searchPostNumber)
        assertEquals("中文 <b>摘要</b>", topics.first().searchBlurb)
        assertNull(topics.last().searchPostNumber)
    }

    @Test fun `append deduplicates topics and preserves the first match across pages`() {
        val old = Topic(8, "旧标题", searchPostNumber = 42, searchBlurb = "第一个摘要")
        val next = Topic(8, "新标题", searchPostNumber = 55, searchBlurb = "第二个摘要")
        val merged = TopicBrowsing.merge(listOf(old), listOf(next, Topic(9, "新增"), Topic(9, "重复")), false)
        assertEquals(listOf(8L, 9L), merged.map { it.id })
        assertEquals("新标题", merged.first().title)
        assertEquals(42, merged.first().searchPostNumber)
    }

    @Test fun `refresh updates IDs reorders the head and preserves loaded tail`() {
        val merged = TopicBrowsing.merge(listOf(Topic(8, "旧"), Topic(9, "已加载下一页")), listOf(Topic(10, "新增"), Topic(8, "新")), true)
        assertEquals(listOf(10L, 8L, 9L), merged.map { it.id })
        assertEquals("新", merged[1].title)
    }

    @Test fun `exact ID lookup cannot enable text pagination`() {
        val result = TopicSearch.search("#8", { Result.success(Topic(it, "精确")) }, { error("must not use text search") }).getOrThrow()
        assertNull(result.groupedSearchResult)
        assertNull(TopicBrowsing.searchTopics(result).first().searchPostNumber)
    }

    @Test fun `successful empty refresh clears stale results but empty append keeps loaded pages`() {
        val loaded = listOf(Topic(8, "已失效的搜索结果"))
        assertTrue(TopicBrowsing.merge(loaded, emptyList(), true).isEmpty())
        assertEquals(loaded, TopicBrowsing.merge(loaded, emptyList(), false))
    }
}
