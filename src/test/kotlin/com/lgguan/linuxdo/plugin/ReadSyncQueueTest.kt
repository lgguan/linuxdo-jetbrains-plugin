package com.lgguan.linuxdo.plugin

import com.intellij.util.xmlb.XmlSerializer
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class ReadSyncQueueTest {
    private val first=ReadIdentity("https://linux.do","7")
    private val second=ReadIdentity("https://linux.do","8")
    @Test fun `each visible floor gets full dwell and topic time is independent`() {
        var nanos=0L;val clock=ReadingClock{nanos}
        clock.sample(setOf(1,2),true)
        nanos+=1_000_000_000;assertEquals(mapOf(1 to 1000L,2 to 1000L),clock.sample(setOf(1,2),true))
        assertTrue(clock.due());assertEquals(ReadingBatch(1000,mapOf(1 to 1000L,2 to 1000L)),clock.drain())
        repeat(59){nanos+=1_000_000_000;clock.sample(setOf(1,2),true)}
        assertFalse(clock.due());nanos+=1_000_000_000;clock.sample(setOf(1,2),true);assertTrue(clock.due())
    }
    @Test fun `quick scroll background resume and sleep never get retroactive credit`() {
        var nanos=0L;val clock=ReadingClock{nanos}
        clock.sample(setOf(1),true)
        nanos+=1_000_000_000;assertTrue(clock.sample(setOf(2),true,scrolled=true).isEmpty())
        nanos+=1_000_000_000;clock.sample(emptySet(),false)
        nanos+=1_000_000_000;assertTrue(clock.sample(setOf(2),true).isEmpty())
        nanos+=60_000_000_000;assertTrue(clock.sample(setOf(2),true).isEmpty())
        assertEquals(ReadingBatch(1000),clock.drain())
        nanos+=1_000_000_000;assertEquals(mapOf(2 to 1000L),clock.sample(setOf(2),true))
    }
    @Test fun `boost only viewport gets no time and a partially visible body only counts topic dwell`() {
        var nanos=0L;val clock=ReadingClock{nanos}
        clock.sample(emptySet(),true,bodyVisible=false)
        nanos+=1_000_000_000;clock.sample(emptySet(),true,bodyVisible=false);assertTrue(clock.drain().isEmpty())
        clock.sample(emptySet(),true,bodyVisible=true)
        nanos+=1_000_000_000;clock.sample(emptySet(),true,bodyVisible=true)
        assertEquals(ReadingBatch(1000),clock.drain())
    }
    @Test fun `three minutes idle pauses and six minutes caps each floor in a visit`() {
        var nanos=0L;val clock=ReadingClock{nanos};clock.sample(setOf(1),true)
        repeat(180){nanos+=1_000_000_000;clock.sample(setOf(1),true)}
        assertEquals(180_000L,clock.drain().timings[1])
        nanos+=1_000_000_000;assertTrue(clock.sample(setOf(1),true).isEmpty())
        repeat(181){nanos+=1_000_000_000;clock.sample(setOf(1),true,scrolled=true)}
        val remaining=clock.drain();assertEquals(180_000L,remaining.timings[1]);assertEquals(181_000L,remaining.topicTimeMs)
        nanos+=1_000_000_000;assertTrue(clock.sample(setOf(1),true,scrolled=true).isEmpty())
    }
    @Test fun `backlog splits without dropping any topic or floor milliseconds`() {
        val queue=ReadSyncQueue{0}
        queue.enqueue(first,1,ReadingBatch(145_000,mapOf(1 to 180_000L,2 to 70_001L)))
        val batches=queue.snapshot();assertEquals(3,batches.size)
        assertEquals(145_000L,batches.sumOf{it.topicTimeMs});assertEquals(180_000L,batches.sumOf{it.timings[1]?:0});assertEquals(70_001L,batches.sumOf{it.timings[2]?:0})
        assertTrue(batches.all{it.topicTimeMs<=60_000&&it.timings.values.all{ms->ms in 1..60_000}})
        assertEquals(3,batches.map{it.id}.distinct().size)
    }
    @Test fun `in flight snapshot stays separate from new evidence and only confirmed success dequeues`() {
        var time=0L;val queue=ReadSyncQueue{time}
        queue.enqueue(first,1,ReadingBatch(1000,mapOf(1 to 1000L)))
        val sending=queue.take(first)!!
        queue.enqueue(first,1,ReadingBatch(2000,mapOf(1 to 2000L)))
        assertEquals(2,queue.snapshot().size);assertNull(queue.take(first));assertEquals(1000L,sending.topicTimeMs)
        queue.complete(sending.id,Result.success(true));assertEquals(2000L,queue.snapshot().single().topicTimeMs)
        assertNull(queue.take(first));time=5000
        val next=queue.take(first)!!;queue.complete(next.id,Result.failure(IOException()))
        assertEquals(next.id,queue.snapshot().single().id);assertNull(queue.take(first));time=10_000
        assertNotNull(queue.take(first))
    }
    @Test fun `failure delays follow five ten twenty forty and rate limit is authoritative`() {
        var time=0L;val queue=ReadSyncQueue{time};queue.enqueue(first,1,ReadingBatch(1000,mapOf(1 to 1000L)))
        for(delay in listOf(5000L,10000L,20000L,40000L,40000L)) {
            val sending=queue.take(first)!!;queue.complete(sending.id,Result.failure(HttpStatusException(503)))
            assertEquals(time+delay,queue.snapshot().single().retryAt);time+=delay
        }
        val sending=queue.take(first)!!;queue.complete(sending.id,Result.failure(RateLimitException(90)))
        queue.enqueue(first,2,ReadingBatch(1000,mapOf(1 to 1000L)));queue.resume(first)
        time+=5000;assertNull(queue.take(first));time+=85_000;assertNotNull(queue.take(first))
    }
    @Test fun `Cloudflare and permissions pause while another account can synchronize`() {
        var time=0L;val queue=ReadSyncQueue{time};queue.enqueue(first,1,ReadingBatch(1000,mapOf(1 to 1000L)))
        val sending=queue.take(first)!!;queue.complete(sending.id,Result.failure(CloudflareChallengeException("verify")))
        queue.enqueue(first,2,ReadingBatch(1000,mapOf(1 to 1000L)))
        queue.enqueue(second,1,ReadingBatch(2000,mapOf(1 to 2000L)));time=5000
        assertNull(queue.take(first));val other=queue.take(second)!!;queue.complete(other.id,Result.success(true))
        assertTrue(queue.snapshot().all { it.accountId == first.accountId && it.pauseReason.isNotEmpty() })
        queue.resume(first);time=10_000;val retry=queue.take(first)!!;queue.complete(retry.id,Result.failure(HttpStatusException(403)))
        time=60_000;assertNull(queue.take(first));assertTrue(queue.status(first,1)!!.contains("HTTP 403"))
    }
    @Test fun `XML restart restores snapshot IDs retries and account isolation without content`() {
        var time=0L;val queue=ReadSyncQueue{time};queue.enqueue(first,1,ReadingBatch(5000,mapOf(2 to 5000L)))
        val sending=queue.take(first)!!;queue.complete(sending.id,Result.failure(IOException()))
        val state=LinuxDoReadTrackingService.State().apply{pendingReadBatches=queue.snapshot()}
        val xml=XmlSerializer.serialize(state)
        val restored=XmlSerializer.deserialize(xml,LinuxDoReadTrackingService.State::class.java)
        val fresh=ReadSyncQueue{time};fresh.restore(restored.pendingReadBatches)
        assertEquals(sending.id,fresh.snapshot().single().id);assertEquals(1,fresh.snapshot().single().failures)
        assertNull(fresh.take(second));assertNull(fresh.take(first));time=5000
        val retry=fresh.take(first)!!;fresh.complete(retry.id,Result.success(true));assertTrue(fresh.snapshot().isEmpty())
        assertFalse(xml.toString().contains("cooked"));assertFalse(xml.toString().contains("raw"))
        assertTrue(LinuxDoReadTrackingService.State().pendingReadBatches.isEmpty())
    }
    @Test fun `forum address separates queues and snapshots cannot mutate retained data`() {
        val queue=ReadSyncQueue{0};queue.enqueue(first,1,ReadingBatch(1000,mapOf(1 to 1000L)))
        queue.snapshot().single().timings[1]=60_000
        assertEquals(1000L,queue.snapshot().single().timings[1])
        assertNull(queue.take(ReadIdentity("https://other.test","7")))
    }
}
