package com.lgguan.linuxdo.plugin

import com.google.gson.*
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.*

class BoostFeatureTest {
    private val mine = PostBoost(id=41,user=BoostUser(id=7,username="reader"),cooked="<p>赞👍</p>",canDelete=true)
    private val post = Post(id=10,topicId=1,postNumber=2,username="author",cooked="<p>body</p>",canBoost=true,boosts=emptyList())
    private class Transport(var current: Post, val boost: PostBoost) : TopicOperationTransport {
        val writes = mutableListOf<OperationRequest>()
        var failure: Throwable? = null
        var mutate = true
        var reads = 0
        var onRead: (() -> Unit)? = null
        override fun post(id: Long, version: Long): Result<Post> { reads++; onRead?.invoke(); return Result.success(current) }
        override fun write(request: OperationRequest, version: Long): Result<JsonElement> {
            writes.add(request)
            if (mutate) current = if(request.method=="POST")current.copy(canBoost=false,boosts=listOf(boost)) else current.copy(canBoost=true,boosts=emptyList())
            return failure?.let { Result.failure(it) } ?: Result.success(Gson().toJsonTree(boost))
        }
    }
    private fun service(transport: TopicOperationTransport, session: (Long) -> Unit = {}) = BoostService(transport,{7},session,ReaderWriteGate({0},{}))

    @Test fun `visible graphemes and valid shortcodes match forum boundaries`() {
        assertEquals(BoostText.Stats(1,0),BoostText.stats("e\u0301"))
        for(symbol in listOf("👨‍👩‍👧‍👦","👍🏻","🇨🇳","1️⃣","❤️")) assertEquals(BoostText.Stats(1,1),BoostText.stats(symbol),symbol)
        assertEquals(BoostText.Stats(2,2),BoostText.stats(":tada::+1:t2:"))
        assertEquals(BoostText.Stats(11,0),BoostText.stats(":not_emoji:"))
        assertEquals(BoostText.Stats(1,1),BoostText.stats(":custom_1:",setOf("custom_1")))
        BoostText.validate("中".repeat(16));BoostText.validate("👍🏻".repeat(5)+"中".repeat(11))
        assertThrows(IllegalArgumentException::class.java){BoostText.validate("中".repeat(17))}
        assertThrows(IllegalArgumentException::class.java){BoostText.validate("👍🏻".repeat(6))}
        assertThrows(IllegalArgumentException::class.java){BoostText.validate(":tada:".repeat(6))}
        assertEquals(0,BoostText.stats("👍🏻",denied=setOf("+1")).emoji)
    }
    @Test fun `missing permission denies writes and payload is only raw`() {
        val transport=Transport(post.copy(canBoost=null),mine)
        assertNotNull(service(transport).perform(1,10,"赞",null,0).error);assertTrue(transport.writes.isEmpty())
        transport.current=post
        val outcome=service(transport).perform(1,10,"赞👍",null,0)
        assertNull(outcome.error);assertEquals(listOf(mine),outcome.post?.boosts);assertFalse(outcome.post!!.canBoost!!)
        assertEquals(setOf("raw"),transport.writes.single().data.keySet())
        assertEquals("/discourse-boosts/posts/10/boosts",transport.writes.single().path)
        assertNotNull(service(transport).perform(1,10,"再赞",null,0).error);assertEquals(1,transport.writes.size)
    }
    @Test fun `only own permitted boost can be withdrawn and capability is reloaded`() {
        val transport=Transport(post.copy(canBoost=false,boosts=listOf(mine)),mine)
        val outcome=service(transport).perform(1,10,null,41,1)
        assertNull(outcome.error);assertTrue(outcome.post!!.canBoost!!);assertTrue(outcome.post!!.boosts!!.isEmpty())
        assertEquals("DELETE",transport.writes.single().method);assertEquals("/discourse-boosts/boosts/41",transport.writes.single().path)
        for(boost in listOf(mine.copy(canDelete=null),mine.copy(user=BoostUser(id=8,username="other")))) {
            transport.current=post.copy(boosts=listOf(boost));assertNotNull(service(transport).perform(1,10,null,41,1).error)
        }
        assertEquals(1,transport.writes.size)
    }
    @Test fun `lost response gets one read reconciliation and no write replay`() {
        val transport=Transport(post,mine);transport.failure=IOException("lost")
        val outcome=service(transport).perform(1,10,"赞",null,2)
        assertNull(outcome.error);assertEquals(1,transport.writes.size);assertEquals(2,transport.reads)
        val uncertain=Transport(post.copy(id=12),mine);uncertain.failure=IOException("lost");uncertain.mutate=false
        val pending=service(uncertain).perform(1,12,"赞",null,2)
        assertTrue(pending.error is UnconfirmedOperationException)
        assertNotNull(service(uncertain).perform(1,12,"赞",null,2).error);assertEquals(1,uncertain.writes.size)
    }
    @Test fun `two windows cannot overlap a boost submission`() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val transport=Transport(post.copy(id=13),mine)
        transport.onRead={entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS))}
        val pool=Executors.newSingleThreadExecutor()
        try {
            val first=pool.submit<OperationResult>{service(transport).perform(1,13,"赞",null,3)}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val second=service(transport).perform(1,13,"赞",null,3)
            assertNotNull(second.error);release.countDown();assertNull(first.get(5,TimeUnit.SECONDS).error)
            assertEquals(1,transport.writes.size)
        } finally { release.countDown();pool.shutdownNow() }
    }
    @Test fun `account switch closed page and distinct 429 forms preserve failure`() {
        val transport=Transport(post,mine)
        assertTrue(service(transport,{throw StaleSessionException()}).perform(1,10,"赞",null,4).error is StaleSessionException)
        assertNotNull(service(transport).perform(1,10,"赞",null,4,active={false}).error);assertTrue(transport.writes.isEmpty())
        for(error in listOf(RateLimitException(30),CloudflareChallengeException("verification"),ForumValidationException(422,listOf("不能发送")))) {
            transport.current=post;transport.failure=error;transport.mutate=false
            assertSame(error,service(transport).perform(1,10,"赞",null,4).error)
        }
    }
    @Test fun `bubble cooked is inert preserves emoji and entrance follows can boost`() {
        val topic=TopicDetailResponse(1,"Test",postStream=PostStream(listOf(post)))
        val unsafe=mine.copy(cooked="<p>赞<img class='emoji' src='/emoji/tada.png' alt=':tada:' onerror='bad()'><script>bad()</script><iframe src='https://bad.test'></iframe></p>")
        val doc=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(topic,listOf(post.copy(boosts=listOf(unsafe))),LinuxDoSettingsState(),"reader"))
        assertTrue(doc.select("script,iframe,[onerror]").isEmpty());assertEquals(1,doc.select(".boost-content img.emoji").size)
        assertEquals(1,doc.select(".boost-container [data-boost-open]").size);assertTrue(doc.select(".floor-actions [data-boost-open]").isEmpty())
        assertEquals(1,doc.select("[data-boost-delete]").size);assertEquals(1,doc.select(".boost-user[data-reader-author=reader]").size)
        val unknown=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(topic,listOf(post.copy(canBoost=null)),LinuxDoSettingsState(),"reader"))
        assertTrue(unknown.select("[data-boost-open]").isEmpty())
    }
}
