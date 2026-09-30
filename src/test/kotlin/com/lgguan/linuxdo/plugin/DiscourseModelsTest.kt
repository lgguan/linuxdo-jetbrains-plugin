package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.model.CategoryListResponse
import com.lgguan.linuxdo.plugin.model.CurrentUserResponse
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.model.TopicListResponse
import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DiscourseModelsTest {

    private val gson = Gson()

    @Test
    fun testCategoryListDeserialization() {
        val json = """
            {
                "category_list": {
                    "categories": [
                        {
                            "id": 1,
                            "name": "开发调优",
                            "color": "0088CC",
                            "slug": "dev",
                            "description": "技术开发与调优经验讨论",
                            "topic_count": 128
                        }
                    ]
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, CategoryListResponse::class.java)
        assertNotNull(response)
        assertEquals(1, response.categoryList.categories.size)
        val cat = response.categoryList.categories[0]
        assertEquals(1, cat.id)
        assertEquals("开发调优", cat.name)
        assertEquals("dev", cat.slug)
    }

    @Test
    fun testTopicListDeserialization() {
        val json = """
            {
                "topic_list": {
                    "more_topics_url": "/latest?page=1",
                    "topics": [
                        {
                            "id": 1001,
                            "title": "DeepSeek 本地部署实践",
                            "posts_count": 15,
                            "reply_count": 14,
                            "views": 320,
                            "like_count": 42,
                            "pinned": false,
                            "category_id": 2
                        }
                    ]
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, TopicListResponse::class.java)
        assertNotNull(response)
        assertEquals(1, response.topicList.topics.size)
        val topic = response.topicList.topics[0]
        assertEquals(1001L, topic.id)
        assertEquals("DeepSeek 本地部署实践", topic.title)
        assertEquals(14, topic.replyCount)
        assertEquals(42, topic.likeCount)
        assertEquals("/latest?page=1", response.topicList.moreTopicsUrl)
    }

    @Test
    fun testTopicDetailAndPostsDeserialization() {
        val json = """
            {
                "id": 2002,
                "title": "Linux Do 论坛客户端架构设计",
                "category_id": 5,
                "views": 500,
                "like_count": 88,
                "post_stream": {
                    "stream": [1, 2, 3],
                    "posts": [
                        {
                            "id": 101,
                            "username": "neo",
                            "cooked": "<p>欢迎大家测试此插件！<img src=\"https://example.com/logo.png\" /></p>",
                            "post_number": 1,
                            "reads": 200,
                            "actions_summary": [
                                {
                                    "id": 2,
                                    "count": 25,
                                    "acted": true
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, TopicDetailResponse::class.java)
        assertNotNull(response)
        assertEquals(2002L, response.id)
        assertEquals(1, response.postStream.posts.size)
        val post = response.postStream.posts[0]
        assertEquals("neo", post.username)
        assertEquals(1, post.postNumber)
        assertEquals(25, post.getLikeCount())
        assertTrue(post.isLiked())
    }

    @Test
    fun testCurrentUserDeserialization() {
        val json = """
            {
                "current_user": {
                    "id": 888,
                    "username": "developer",
                    "name": "Linux Geek",
                    "trust_level": 3,
                    "admin": false,
                    "unread_notifications": 2
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, CurrentUserResponse::class.java)
        assertNotNull(response.currentUser)
        assertEquals("developer", response.currentUser?.username)
        assertEquals(3, response.currentUser?.trustLevel)
        assertEquals(2, response.currentUser?.unreadNotifications)
    }

    @Test
    fun testBoostsDeserialization() {
        val json = """
            {
                "id": 1001,
                "username": "neo",
                "cooked": "<p>测试内容</p>",
                "post_number": 1,
                "boosts": [
                    {
                        "id": 12,
                        "cooked": "<p>写得很好！</p>",
                        "raw": "写得很好！",
                        "user": {
                            "id": 88,
                            "username": "tester",
                            "name": "Test User"
                        }
                    }
                ]
            }
        """.trimIndent()

        val post = gson.fromJson(json, com.lgguan.linuxdo.plugin.model.Post::class.java)
        assertNotNull(post.boosts)
        assertEquals(1, post.boosts?.size)
        val boost = post.boosts!![0]
        assertEquals("tester", boost.getDisplayUsername())
        assertEquals("写得很好！", boost.getDisplayContent())
    }
}
