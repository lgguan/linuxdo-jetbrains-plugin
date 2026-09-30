package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TopicRefreshTest {
    private val loaded = Post(id = 80, postNumber = 40, cooked = "already loaded", read = true)
    private val topic = TopicDetailResponse(id = 10, title = "topic", postStream = PostStream(listOf(loaded), listOf(1, 80)))

    @Test
    fun `jump merges target neighborhood and retains previously loaded posts`() {
        val target = Post(id = 300, topicId = 10, postNumber = 150)
        val around = topic.copy(postStream = PostStream(listOf(target), listOf(1, 80, 300)))
        val merged = TopicRefresh.mergeAround(topic, around, 150)
        assertEquals(listOf(loaded, target), merged.postStream.posts)
        assertEquals(listOf(1L, 80L, 300L), merged.postStream.stream)
        assertThrows(IllegalArgumentException::class.java) { TopicRefresh.mergeAround(topic, around, 149) }
        assertThrows(IllegalArgumentException::class.java) { TopicRefresh.mergeAround(topic, around.copy(id = 11), 150) }
    }

    @Test
    fun `published reply can be revealed directly without loading intervening floors`() {
        val reply = Post(id = 300, topicId = 10, postNumber = 150, cooked = "my reply")
        val updated = TopicRefresh.addReply(topic, reply)
        assertEquals(listOf(loaded, reply), updated.postStream.posts)
        assertEquals(listOf(1L, 80L, 300L), updated.postStream.stream)
        assertEquals(150, updated.highestPostNumber)
        assertEquals(topic.replyCount + 1, updated.replyCount)
        assertEquals(updated, TopicRefresh.addReply(updated, reply))
    }

    @Test
    fun `pending or unrelated submission cannot be treated as a published floor`() {
        for (post in listOf(Post(id = 0), Post(id = 300, postNumber = 1), Post(id = 300, postNumber = 150, topicId = 20))) {
            assertThrows(IllegalArgumentException::class.java) { TopicRefresh.addReply(topic, post) }
        }
    }

    @Test
    fun `late index can retain a confirmed reply until the server index catches up`() {
        val reply = Post(id = 300, topicId = 10, postNumber = 150)
        val updated = TopicRefresh.addReply(topic, reply)
        val late = TopicRefresh.merge(updated, topic)
        val retained = TopicRefresh.addReply(late, reply)
        assertEquals(updated, retained)
        val fresh = topic.copy(postStream = PostStream(emptyList(), listOf(1, 80, 300)))
        assertTrue(TopicRefresh.merge(updated, fresh).postStream.posts.contains(reply))
    }

    @Test
    fun `fresh index admits new replies without discarding loaded middle floors`() {
        val first = Post(id = 1, postNumber = 1)
        val fresh = topic.copy(postsCount = 43, highestPostNumber = 43,
            postStream = PostStream(listOf(first), listOf(1, 80, 81, 82, 82)))
        val merged = TopicRefresh.merge(topic, fresh)
        assertEquals(listOf(1L, 80L, 81L, 82L), merged.postStream.stream)
        assertEquals(listOf(loaded), merged.postStream.posts)
        assertEquals(43, merged.highestPostNumber)
        assertEquals(43, merged.postsCount)
    }

    @Test
    fun `removed posts are no longer eligible for actions or new requests`() {
        val merged = TopicRefresh.merge(topic, topic.copy(postStream = PostStream(emptyList(), listOf(1, 81))))
        assertTrue(merged.postStream.posts.isEmpty())
        assertEquals(listOf(1L, 81L), merged.postStream.stream)
    }

    @Test
    fun `wrong topic or incomplete index cannot replace current document`() {
        assertThrows(IllegalArgumentException::class.java) { TopicRefresh.merge(topic, topic.copy(id = 20)) }
        assertThrows(IllegalArgumentException::class.java) {
            TopicRefresh.merge(topic, topic.copy(postStream = PostStream(emptyList())))
        }
        assertEquals(listOf(loaded), topic.postStream.posts)
    }
}
