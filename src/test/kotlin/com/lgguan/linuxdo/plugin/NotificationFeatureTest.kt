package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.service.LinuxDoNotificationService
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NotificationFeatureTest {

    private val gson = Gson()

    @Test
    fun testNotificationJsonParsingAndDisplayHelpers() {
        val json = """
            {
                "notifications": [
                    {
                        "id": 1001,
                        "notification_type": 5,
                        "read": false,
                        "created_at": "2026-09-23T03:00:00.000Z",
                        "topic_id": 8888,
                        "post_number": 4,
                        "slug": "linux-do-plugin-guide",
                        "data": {
                            "topic_title": "LINUX DO 一定要tz才能上么？国内能直接上么",
                            "original_username": "blindman2023",
                            "display_username": "blindman2023"
                        }
                    },
                    {
                        "id": 1002,
                        "notification_type": 2,
                        "read": true,
                        "created_at": "2026-09-23T02:30:00.000Z",
                        "topic_id": 9999,
                        "post_number": 7,
                        "slug": "antigravity-cli-feedback",
                        "data": {
                            "topic_title": "佬们觉得antigravity cli哪里用起来不舒服呢？",
                            "original_username": "jxms"
                        }
                    },
                    {
                        "id": 1003,
                        "notification_type": 12,
                        "read": false,
                        "created_at": "2026-09-22T10:00:00.000Z",
                        "topic_id": null,
                        "post_number": null,
                        "data": {
                            "badge_name": "首次链接",
                            "badge_id": 42
                        }
                    },
                    {
                        "id": 1004,
                        "notification_type": 34,
                        "read": false,
                        "created_at": "2026-09-23T03:10:00.000Z",
                        "topic_id": 7777,
                        "post_number": 3,
                        "data": {
                            "topic_title": "ArkDO一切为了审核通过",
                            "display_username": "JoeyYin",
                            "message": "我复现看看"
                        }
                    }
                ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, NotificationListResponse::class.java)
        assertEquals(4, response.notifications.size)

        // Item 1: Like
        val n1 = response.notifications[0]
        assertEquals("blindman2023", n1.getDisplayAuthor())
        assertEquals("LINUX DO 一定要tz才能上么？国内能直接上么", n1.getDisplayTitle())
        assertEquals("赞了你的帖子", n1.getTypeActionLabel())
        assertEquals(8888L, n1.topicId)
        assertEquals(4, n1.postNumber)
        assertFalse(n1.read)

        // Item 2: Reply
        val n2 = response.notifications[1]
        assertEquals("jxms", n2.getDisplayAuthor())
        assertEquals("佬们觉得antigravity cli哪里用起来不舒服呢？", n2.getDisplayTitle())
        assertEquals("回复了你", n2.getTypeActionLabel())
        assertEquals(9999L, n2.topicId)
        assertEquals(7, n2.postNumber)
        assertTrue(n2.read)

        // Item 3: Badge
        val n3 = response.notifications[2]
        assertEquals("获得了 '首次链接'", n3.getDisplayTitle())
        assertEquals("授予你新徽章", n3.getTypeActionLabel())
        assertNull(n3.topicId)

        // Item 4: Boost
        val n4 = response.notifications[3]
        assertEquals("JoeyYin", n4.getDisplayAuthor())
        assertEquals("我复现看看", n4.getDisplayTitle())
        assertEquals("发送了微回复", n4.getTypeActionLabel())
        assertEquals(7777L, n4.topicId)
        assertEquals(3, n4.postNumber)
    }

    @Test
    fun testNotificationCategoriesFilter() {
        val nLike = DiscourseNotification(id = 1, notificationType = 5, read = false)
        val nReply = DiscourseNotification(id = 2, notificationType = 2, read = false)
        val nMention = DiscourseNotification(id = 3, notificationType = 1, read = false)
        val nBoost = DiscourseNotification(id = 4, notificationType = 34, read = false)
        val nBadge = DiscourseNotification(id = 5, notificationType = 12, read = true)
        val nSystem = DiscourseNotification(id = 6, notificationType = 6, read = true)

        val all = listOf(nLike, nReply, nMention, nBoost, nBadge, nSystem)

        // Replies category
        val replies = all.filter { it.notificationType in listOf(1, 2, 3, 9, 15, 34) }
        assertEquals(3, replies.size)
        assertTrue(replies.contains(nReply))
        assertTrue(replies.contains(nMention))
        assertTrue(replies.contains(nBoost))

        // Likes category
        val likes = all.filter { it.notificationType in listOf(5, 19, 25) }
        assertEquals(1, likes.size)
        assertTrue(likes.contains(nLike))

        // System category
        val system = all.filter { it.notificationType !in listOf(1, 2, 3, 5, 9, 15, 19, 25, 34) }
        assertEquals(2, system.size)
        assertTrue(system.contains(nBadge))
        assertTrue(system.contains(nSystem))
    }

    @Test
    fun testDocCamouflageCssBuilderWithTargetPostNumber() {
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
        val post1 = Post(id = 1, username = "author1", cooked = "<p>Post 1</p>", postNumber = 1)
        val post5 = Post(id = 5, username = "author5", cooked = "<p>Post 5</p>", postNumber = 5)

        val topic = TopicDetailResponse(
            id = 5555,
            title = "Testing Jump to Floor from Notification",
            postStream = PostStream(posts = listOf(post1, post5))
        )

        // When targetPostNumber = 5, generated HTML must include jumpToFloor(5) script
        val html = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(post1, post5),
            categoryName = "开发",
            categorySlug = "dev",
            theme = theme,
            settings = settings,
            targetPostNumber = 5
        )

        assertTrue(html.contains("jumpToFloor(5)"))
        assertTrue(html.contains("id=\"floor-5\""))
    }

    @Test
    fun testCircuitBreakerTriggerAndRecovery() {
        val service = LinuxDoNotificationService()
        try {
            assertFalse(service.isCircuitBroken())
            assertEquals(0L, service.getRemainingCircuitBreakerSeconds())

            // Trigger circuit breaker with 600s
            service.triggerCircuitBreaker("HTTP 429 Test", 600L)
            assertTrue(service.isCircuitBroken())
            assertTrue(service.getRemainingCircuitBreakerSeconds() in 590L..600L)
            assertEquals("HTTP 429 Test", service.circuitBreakerReason)

            // Reset
            service.resetCircuitBreaker()
            assertFalse(service.isCircuitBroken())
            assertEquals(0L, service.getRemainingCircuitBreakerSeconds())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun testPollingDelayJitterAndInactiveReducedWeight() {
        val service = LinuxDoNotificationService()
        try {
            // Repeat multiple times to verify jitter range and reduced weight
            val activeDelays = (1..50).map { service.calculateNextDelaySeconds(isActive = true) }
            val inactiveDelays = (1..50).map { service.calculateNextDelaySeconds(isActive = false) }

            // Active delays: base 60s ± 20% (48s ~ 72s)
            for (d in activeDelays) {
                assertTrue(d in 48L..72L, "Active delay $d should be within 48s..72s")
            }

            // Inactive delays: base 300s ± 20% (240s ~ 360s)
            for (d in inactiveDelays) {
                assertTrue(d in 240L..360L, "Inactive delay $d should be within 240s..360s")
            }

            // Inactive polling should have significantly reduced weight (average delay ~5x longer)
            val avgActive = activeDelays.average()
            val avgInactive = inactiveDelays.average()
            assertTrue(avgInactive > avgActive * 3.5, "Inactive polling should be at least 3.5x slower than active")

            // Jitter test: delays should not all be identical
            val uniqueActive = activeDelays.toSet().size
            assertTrue(uniqueActive > 5, "Active delays should have jitter and not all be identical")
        } finally {
            service.dispose()
        }
    }
}
