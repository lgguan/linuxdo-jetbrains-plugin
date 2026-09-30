package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.DiscourseUrls
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.TagListResponse
import com.lgguan.linuxdo.plugin.model.UploadResponse
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CreateTopicFeatureTest {

    private val gson = Gson()

    @Test
    fun testPostDeserializationWithTopicId() {
        val json = """
            {
                "id": 8888,
                "name": "Linus",
                "username": "linus",
                "cooked": "<p>Hello Linux Do community!</p>",
                "raw": "Hello Linux Do community!",
                "post_number": 1,
                "topic_id": 9999,
                "topic_slug": "hello-linux-do-community"
            }
        """.trimIndent()

        val post = gson.fromJson(json, Post::class.java)
        assertNotNull(post)
        assertEquals(8888L, post.id)
        assertEquals("linus", post.username)
        assertEquals(9999L, post.topicId)
        assertEquals("hello-linux-do-community", post.topicSlug)
    }

    @Test
    fun testUploadResponseDeserialization() {
        val json = """
            {
                "id": 12345,
                "url": "https://linux.do/uploads/default/original/2X/a/abc123.png",
                "original_filename": "screenshot.png",
                "filesize": 45678,
                "width": 1024,
                "height": 768,
                "short_url": "upload://abc123xyz.png",
                "short_path": "/uploads/short-url/abc123xyz.png",
                "extension": "png"
            }
        """.trimIndent()

        val upload = gson.fromJson(json, UploadResponse::class.java)
        assertNotNull(upload)
        assertEquals(12345L, upload.id)
        assertEquals("screenshot.png", upload.originalFilename)
        assertEquals("upload://abc123xyz.png", upload.shortUrl)
        assertEquals(1024, upload.width)
        assertEquals(768, upload.height)
        assertEquals("png", upload.extension)
    }

    @Test
    fun testTagListResponseDeserialization() {
        val json = """
            {
                "tags": [
                    { "id": "linux", "text": "linux", "count": 120 },
                    { "id": "jetbrains", "text": "jetbrains", "count": 45 }
                ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, TagListResponse::class.java)
        assertNotNull(response)
        assertEquals(2, response.tags.size)
        assertEquals("linux", response.tags[0].text)
        assertEquals(120, response.tags[0].count)
        assertEquals("jetbrains", response.tags[1].id)
    }

    @Test
    fun testDiscourseUrlsForTopicCreationAndUploads() {
        val baseUrl = "https://linux.do"
        assertEquals("https://linux.do/posts.json", DiscourseUrls.createPost(baseUrl))
        assertEquals("https://linux.do/uploads.json", DiscourseUrls.upload(baseUrl))
        assertEquals("https://linux.do/tags.json", DiscourseUrls.tags(baseUrl))
    }

    @Test
    fun testCreateTopicPayloadStructure() {
        val title = "这是一个测试话题标题"
        val raw = "这是话题详细正文内容，必须达到二十个字符以上以满足规范。"
        val categoryId = 4
        val tags = listOf("dev", "plugin")

        val json = JsonObject().apply {
            addProperty("title", title)
            addProperty("raw", raw)
            addProperty("category", categoryId)
            val tagsArray = com.google.gson.JsonArray()
            tags.forEach { tagsArray.add(it) }
            add("tags", tagsArray)
            addProperty("archetype", "regular")
        }

        assertEquals(title, json.get("title").asString)
        assertEquals(raw, json.get("raw").asString)
        assertEquals(categoryId, json.get("category").asInt)
        assertEquals(2, json.getAsJsonArray("tags").size())
        assertEquals("dev", json.getAsJsonArray("tags").get(0).asString)
        assertEquals("regular", json.get("archetype").asString)
    }

    @Test
    fun testErrorParsingLogic() {
        val errorJson = """
            {
                "action": "create_post",
                "errors": [
                    "标题太短（至少为 6 个字符）",
                    "正文太短（至少为 20 个字符）"
                ]
            }
        """.trimIndent()

        val obj = JsonParser.parseString(errorJson).asJsonObject
        val errorsArray = obj.getAsJsonArray("errors")
        assertNotNull(errorsArray)
        val errorMsg = errorsArray.joinToString("; ") { it.asString }
        assertTrue(errorMsg.contains("标题太短"))
        assertTrue(errorMsg.contains("正文太短"))
    }

    @Test
    fun testTagSearchResultResponseDeserialization() {
        val json = """
            {
                "results": [
                    { "id": "linux", "text": "linux", "count": 250 },
                    { "id": "jetbrains", "text": "jetbrains", "count": 80 }
                ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, com.lgguan.linuxdo.plugin.model.TagSearchResultResponse::class.java)
        assertNotNull(response)
        assertEquals(2, response.results.size)
        assertEquals("linux", response.results[0].text)
        assertEquals(250, response.results[0].count)
    }

    @Test
    fun testCategorySubcategoryListDeserialization() {
        val json = """
            {
                "id": 1,
                "name": "开发调优",
                "slug": "dev",
                "color": "0088CC",
                "description": "开发与调优",
                "parent_category_id": null,
                "subcategory_list": [
                    {
                        "id": 10,
                        "name": "前端开发",
                        "slug": "frontend",
                        "color": "E45735",
                        "description": "前端相关",
                        "parent_category_id": 1
                    }
                ]
            }
        """.trimIndent()

        val cat = gson.fromJson(json, com.lgguan.linuxdo.plugin.model.Category::class.java)
        assertNotNull(cat)
        assertEquals(1, cat.id)
        assertNotNull(cat.subcategoryList)
        assertEquals(1, cat.subcategoryList!!.size)
        val sub = cat.subcategoryList!![0]
        assertEquals(10, sub.id)
        assertEquals("前端开发", sub.name)
        assertEquals(1, sub.parentCategoryId)
    }

    @Test
    fun testSiteResponseDeserialization() {
        val json = """
            {
                "categories": [
                    { "id": 1, "name": "开发调优", "slug": "dev", "color": "0088CC", "parent_category_id": null },
                    { "id": 101, "name": "开发调优, Lv1", "slug": "dev-lv1", "color": "0088CC", "parent_category_id": 1, "read_restricted": true }
                ]
            }
        """.trimIndent()

        val site = gson.fromJson(json, com.lgguan.linuxdo.plugin.model.SiteResponse::class.java)
        assertNotNull(site)
        assertNotNull(site.categories)
        assertEquals(2, site.categories!!.size)
        val parent = site.categories!![0]
        val child = site.categories!![1]
        assertEquals("开发调优", parent.name)
        assertEquals(1, child.parentCategoryId)
        assertEquals(true, child.readRestricted)
        assertEquals("开发调优, Lv1", child.name)
    }

    @Test
    fun serverCategoriesRetainHierarchy() {
        val service = com.lgguan.linuxdo.plugin.service.LinuxDoTopicService()
        service.categories.addAll(listOf(
            com.lgguan.linuxdo.plugin.model.Category(10, "Development", "123456", "dev"),
            com.lgguan.linuxdo.plugin.model.Category(20, "Private", "123456", "private", parentCategoryId = 10, readRestricted = true)))
        val child = service.getHierarchicalCategories().single { it.category.id == 20 }
        assertEquals(1, child.depth)
        assertEquals(10, child.parent?.id)
        assertFalse(service.categoriesAreCurrent, "Only a current server response authorizes publishing")
    }

    @Test
    fun testUpdatedDiscourseEndpoints() {
        val baseUrl = "https://linux.do"
        assertEquals("https://linux.do/site.json", DiscourseUrls.site(baseUrl))
        assertEquals("https://linux.do/categories.json?include_subcategories=true", DiscourseUrls.categories(baseUrl))
    }

    @Test
    fun testDefaultSystemTagsContainsOfficialTopicGroupTags() {
        val tags = com.lgguan.linuxdo.plugin.service.LinuxDoTopicService.DEFAULT_SYSTEM_TAGS
        val requiredTags = listOf("纯水", "快问快答", "软件开发", "人工智能", "VPS", "抽奖", "病友", "求资源", "配置优化", "树洞", "转载")
        for (req in requiredTags) {
            assertTrue(tags.contains(req), "DEFAULT_SYSTEM_TAGS must contain $req")
        }
        assertEquals(tags.size, tags.distinct().size, "DEFAULT_SYSTEM_TAGS must not contain duplicates")
    }

    @Test
    fun testTagSanitization() {
        fun sanitizeTag(raw: String): String {
            var t = raw.trim()
            t = t.removePrefix("name:").removePrefix("name：").removePrefix("-").trim()
            t = t.trim('"', '\'', '`', '#', ',', '，', '“', '”', '‘', '’', ' ')
            return t
        }

        assertEquals("纯水", sanitizeTag("#纯水"))
        assertEquals("快问快答", sanitizeTag("“快问快答”"))
        assertEquals("软件开发", sanitizeTag("‘软件开发’"))
        assertEquals("人工智能", sanitizeTag(" #人工智能 "))
        assertEquals("VPS", sanitizeTag("name:VPS"))
    }

    @Test
    fun testTagsQueryParamConstruction() {
        val cleanTags = listOf("纯水", "dev")
        val basePostUrl = "https://linux.do/posts.json"
        val fullUrl = if (cleanTags.isNotEmpty()) {
            val query = cleanTags.joinToString("&") { "tags[]=" + java.net.URLEncoder.encode(it, "UTF-8") }
            if (basePostUrl.contains("?")) "$basePostUrl&$query" else "$basePostUrl?$query"
        } else {
            basePostUrl
        }
        assertTrue(fullUrl.contains("tags[]=%E7%BA%AF%E6%B0%B4"))
        assertTrue(fullUrl.contains("tags[]=dev"))
    }

    @Test
    fun testFormatDialogErrorMessageLineWrapping() {
        val longDiscourseError = "您必须至少包含 1 个类版块组标签。此组中的标签包括: 抽奖, 病友, 数据库, 订阅节点, 动漫, 音乐, VPS, 游戏, 二次元, 人工智能, 影视, 摄影, 服务器, 算法, 职场, 嵌入式, 快问快答, 拼车, 网络安全, 赏金任务, 健身, 旅行, 美食, 金融经济, 软件开发, 软件测试, 软件调试, 配置优化, AI, 硬件开发, 硬件测试, 硬件调试, 计算机网络, 纯水, 求资源, 树洞, 转载。"
        val formatted = com.lgguan.linuxdo.plugin.ui.dialog.ComposerErrors.format(longDiscourseError)
        val lines = formatted.split("\n")
        assertTrue(lines.size >= 4, "Long error message must be broken into multiple lines")
        for (line in lines) {
            assertTrue(line.length <= 45, "Each line must not exceed 45 characters to avoid stretching dialogs")
        }
    }

    @Test
    fun testReplyPayloadStructure() {
        val topicId = 2939709L
        val raw = "这是一条测试回复内容，测试图片粘贴与预览"
        val replyToPostNumber = 3

        val json = JsonObject().apply {
            addProperty("topic_id", topicId)
            addProperty("raw", raw)
            addProperty("reply_to_post_number", replyToPostNumber)
            addProperty("nested_post", true)
        }

        assertEquals(topicId, json.get("topic_id").asLong)
        assertEquals(raw, json.get("raw").asString)
        assertEquals(3, json.get("reply_to_post_number").asInt)
        assertTrue(json.get("nested_post").asBoolean)
    }
}
