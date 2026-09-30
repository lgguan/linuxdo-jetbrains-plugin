package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReadTrackingServiceTest {

    private lateinit var service: LinuxDoReadTrackingService

    @BeforeEach
    fun setUp() {
        service = LinuxDoReadTrackingService()
    }

    @Test
    fun testMarkReadLocallyAndLastReadFloor() {
        assertNull(service.getLastReadPostNumber(1001L))
        assertFalse(service.isRead(1001L))

        service.markReadLocally(1001L, 5)

        assertTrue(service.isRead(1001L))
        assertEquals(5, service.getLastReadPostNumber(1001L))

        // Visiting a floor updates only the position; it proves no reading.
        for (f in 1..5) {
            assertFalse(service.isPostRead(1001L, f, serverPostRead = false, topicServerLastRead = null))
        }

        // Floor 6 is unread when serverPostRead is false
        assertFalse(service.isPostRead(1001L, 6, serverPostRead = false, topicServerLastRead = null), "Floor 6 must be unread")
    }

    @Test
    fun testMarkFloorRead() {
        service.markFloorRead(2002L, 8)

        assertTrue(service.isRead(2002L))
        assertEquals(8, service.getLastReadPostNumber(2002L))
        assertTrue(service.isPostRead(2002L, 8, serverPostRead = false, topicServerLastRead = null))

        // Last position follows the reader, independently of the set of read floors.
        service.markFloorRead(2002L, 3)
        assertEquals(3, service.getLastReadPostNumber(2002L))
        assertTrue(service.isPostRead(2002L, 3, serverPostRead = false, topicServerLastRead = null))
    }

    @Test
    fun testMarkAllRead() {
        service.markAllRead(3003L, 20)

        assertEquals(20, service.getLastReadPostNumber(3003L))
        assertTrue(service.isRead(3003L))
        for (f in 1..20) {
            assertTrue(service.isPostRead(3003L, f, serverPostRead = false, topicServerLastRead = null))
        }
        // Floor 21 is unread
        assertFalse(service.isPostRead(3003L, 21, serverPostRead = false, topicServerLastRead = null))
    }

    @Test
    fun testLocalReadPrecedenceOverServerFalse() {
        // Even if server returns post.read = false (e.g. before timings synced or guest/token delay),
        // local read tracking takes precedence so unread blue dot NEVER reappears!
        service.markFloorRead(4004L, 12)

        assertTrue(service.isPostRead(4004L, 12, serverPostRead = false, topicServerLastRead = 5))
    }

    @Test
    fun testServerReadFallbackWhenNoLocalRecord() {
        // 1. Server post.read == true
        assertTrue(service.isPostRead(5005L, 3, serverPostRead = true, topicServerLastRead = null))

        // 2. Server topic-level lastReadPostNumber
        assertTrue(service.isPostRead(5005L, 4, serverPostRead = null, topicServerLastRead = 5))
        assertFalse(service.isPostRead(5005L, 6, serverPostRead = null, topicServerLastRead = 5))

        // Missing server signals are not evidence that an anonymous reader viewed a floor.
        assertFalse(service.isPostRead(5005L, 10, serverPostRead = null, topicServerLastRead = null))
    }

    @Test
    fun testStateSerializationPersistenceRoundTrip() {
        service.markReadLocally(8888L, 15)
        service.markFloorRead(9999L, 42)

        val state = service.state
        assertNotNull(state)
        assertEquals(15, state.positions["guest:8888"])
        assertEquals(42, state.positions["guest:9999"])
        assertNull(state.floors["guest:8888"])
        assertEquals("42", state.floors["guest:9999"])

        // Create new fresh service instance and load state (simulates IDE restart)
        val restoredService = LinuxDoReadTrackingService()
        restoredService.loadState(state)

        assertTrue(restoredService.isRead(8888L))
        assertTrue(restoredService.isRead(9999L))
        assertEquals(15, restoredService.getLastReadPostNumber(8888L))
        assertEquals(42, restoredService.getLastReadPostNumber(9999L))
        assertFalse(restoredService.isPostRead(8888L, 15, serverPostRead = false, topicServerLastRead = null))
        assertTrue(restoredService.isPostRead(9999L, 42, serverPostRead = false, topicServerLastRead = null))
    }

    @Test
    fun testIsTopicRead() {
        val topic = Topic(
            id = 7007L,
            title = "Testing isTopicRead",
            highestPostNumber = 10,
            unreadPosts = 0,
            lastReadPostNumber = 10
        )

        // Initially read according to server unreadPosts = 0 and lastReadPostNumber >= highestPostNumber
        assertTrue(service.isTopicRead(topic))

        val unreadTopic = Topic(
            id = 7008L,
            title = "Unread Topic",
            highestPostNumber = 10,
            unreadPosts = 3,
            lastReadPostNumber = 7
        )
        assertFalse(service.isTopicRead(unreadTopic))

        // After local read up to 10
        service.markReadLocally(7008L, 10)
        assertFalse(service.isTopicRead(unreadTopic))
        (8..10).forEach { service.markFloorRead(7008L, it) }
        assertTrue(service.isTopicRead(unreadTopic))
        assertFalse(service.isTopicRead(unreadTopic.copy(highestPostNumber = 11, unreadPosts = 4)))
    }

    @Test
    fun testAutoJumpSettingDefault() {
        val settings = LinuxDoSettingsState()
        assertTrue(settings.autoJumpToLastReadFloor)
    }
}
