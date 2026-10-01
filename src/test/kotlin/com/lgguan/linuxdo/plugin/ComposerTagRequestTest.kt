package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.DiscourseUrls
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ComposerTagRequestTest {
    @Test fun `empty composer search delegates result limit to forum configuration`() {
        val request = DiscourseUrls.composerTags("https://linux.do", "", 4, emptyList()).toHttpUrl()
        assertEquals("/tags/filter/search.json", request.encodedPath)
        assertEquals("", request.queryParameter("q"))
        assertNull(request.queryParameter("limit"), "Discourse rejects client limits above the site's maximum")
        assertEquals("true", request.queryParameter("filterForInput"))
        assertEquals("4", request.queryParameter("categoryId"))
    }
    @Test fun `keyword and selection context survive encoding without overriding the site limit`() {
        val request = DiscourseUrls.composerTags("https://linux.do", " 中文 & C++ ", 8, listOf("1451", "129", "软件开发")).toHttpUrl()
        assertEquals("中文 & C++", request.queryParameter("q"))
        assertEquals(listOf("1451", "129"), request.queryParameterValues("selected_tag_ids[]"))
        assertEquals("8", request.queryParameter("categoryId"))
        assertNull(request.queryParameter("limit"))
    }
    @Test fun `unscoped tag searches omit category instead of sending null`() {
        val request = DiscourseUrls.composerTags("https://linux.do", "工具", null, emptyList()).toHttpUrl()
        assertNull(request.queryParameter("categoryId"))
        assertNull(request.queryParameter("selected_tag_ids[]"))
    }
}
