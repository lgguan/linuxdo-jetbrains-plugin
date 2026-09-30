package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.api.TopicSearch
import com.lgguan.linuxdo.plugin.model.SearchResultResponse
import com.lgguan.linuxdo.plugin.model.Topic
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class TopicSearchTest {
    @Test
    fun `numeric IDs bypass full text search and retain topic metadata`() {
        val topic = Gson().fromJson("""{"id":123456,"title":"Exact match","posts_count":42,"category_id":7,"views":100,"post_stream":{"posts":[]}}""", Topic::class.java)
        for (query in listOf("123456", " #123456 ", "00123456")) {
            val result = TopicSearch.search(query,
                byId = { id -> assertEquals(123456L, id); Result.success(topic) },
                byText = { error("ID must not be sent to full text search") })
            assertEquals(listOf(topic), result.getOrThrow().topics)
            assertEquals(42, result.getOrThrow().topics!!.single().postsCount)
        }
    }

    @Test
    fun `keywords and advanced queries retain their original search semantics`() {
        for (query in listOf("kotlin", "123456 in:title", "#kotlin", "\"123456\"", "after:2026-01-01 tags:java")) {
            val expected = SearchResultResponse(topics = emptyList())
            val actual = TopicSearch.search(" $query ",
                byId = { error("Text queries must not use the ID lookup") },
                byText = { text -> assertEquals(query, text); Result.success(expected) })
            assertSame(expected, actual.getOrThrow())
        }
    }

    @Test
    fun `missing and restricted IDs preserve the lookup error`() {
        for (code in listOf(403, 404, 429)) {
            val error = IOException("HTTP $code")
            val result = TopicSearch.search("123456", { Result.failure(error) }, { error("Unexpected fallback") })
            assertSame(error, result.exceptionOrNull())
        }
    }

    @Test
    fun `invalid numeric IDs never issue network requests`() {
        for (query in listOf("0", "#0", "99999999999999999999999999")) {
            val result = TopicSearch.search(query, { error("Invalid ID") }, { error("Invalid ID") })
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
