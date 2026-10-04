package com.lgguan.linuxdo.plugin

import com.google.gson.*
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.net.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Instant

class BookmarkServiceTest {
    private class Harness {
        var post=Post(id=11,topicId=1,bookmarked=true,bookmarkId=77,bookmarkName="old",bookmarkAutoDeletePreference=0)
        var pinned=false
        var failure:Throwable?=null
        var apply=true
        var unreadable=false
        var afterOverride:Post?=null
        var reads=0
        var session: (Long)->Unit={}
        var writable: (Long)->Unit={}
        val writes=mutableListOf<OperationRequest>()
        val events=mutableListOf<BookmarkChange>()
        val transport=object:TopicOperationTransport {
            override fun post(id:Long,version:Long):Result<Post> {
                reads++;return if(unreadable)Result.failure(IOException()) else Result.success(post)
            }
            override fun write(request:OperationRequest,version:Long):Result<JsonElement> {
                writes.add(request)
                if(apply)post=if(request.method=="DELETE")post.copy(bookmarked=false,bookmarkId=null,bookmarkName=null,bookmarkReminderAt=null)
                    else post.copy(bookmarked=true,bookmarkId=77,bookmarkName=request.data["name"].asString,
                        bookmarkReminderAt=request.data["reminder_at"].takeUnless{it.isJsonNull}?.asString,
                        bookmarkAutoDeletePreference=request.data["auto_delete_preference"]?.asInt ?: 3)
                afterOverride?.let { post=it }
                return failure?.let{Result.failure(it)} ?: Result.success(JsonObject().apply{addProperty("id",77)})
            }
        }
        val service=BookmarkService(transport,{p,_->BookmarkMetadata(requireNotNull(p.bookmarkId),"Post",p.id,p.bookmarkName.orEmpty(),p.bookmarkReminderAt,pinned)},
            {session(it)},{writable(it)},ReaderWriteGate({0},{}),events::add,{it()})
        fun bookmark()=BookmarkMetadata(77,"Post",11,post.bookmarkName.orEmpty(),post.bookmarkReminderAt,pinned)
        fun read()=service.read(bookmark(),1)
    }
    @Test fun `editing and clearing reminders preserve every original server policy`() {
        for(policy in 0..3){
            val h=Harness();h.post=h.post.copy(bookmarkAutoDeletePreference=policy)
            val reminder=Instant.now().plusSeconds(86400).toString()
            assertNull(h.service.save(h.read(),"new",reminder,1,{true}).error)
            assertEquals(policy,h.writes.single().data["auto_delete_preference"].asInt)
            assertEquals(reminder,h.post.bookmarkReminderAt)
            assertNull(h.service.save(h.read(),"new",null,1,{true}).error)
            assertTrue(h.writes.last().data["reminder_at"].isJsonNull)
            assertEquals(2,h.events.size)
        }
    }
    @Test fun `concurrent edits and pinned or unknown metadata require rechecking instead of mutation`() {
        val h=Harness();val baseline=h.read();h.post=h.post.copy(bookmarkName="other window")
        assertNotNull(h.service.save(baseline,"local",null,1,{true}).error);assertTrue(h.writes.isEmpty())
        h.pinned=true;assertThrows(BookmarkWebRequiredException::class.java){h.read()}
        h.pinned=false;h.post=h.post.copy(bookmarkAutoDeletePreference=null)
        assertThrows(BookmarkWebRequiredException::class.java){h.read()}
    }
    @Test fun `ambiguous applied mutation is read back without retry and unconfirmed writes block other windows`() {
        val h=Harness();h.failure=IOException("lost response")
        assertNull(h.service.save(h.read(),"saved",null,1,{true}).error)
        assertEquals(1,h.writes.size);assertEquals(1,h.events.size)
        val baseline=h.read();h.apply=false
        assertInstanceOf(UnconfirmedOperationException::class.java,h.service.save(baseline,"pending",null,1,{true}).error)
        assertNotNull(h.service.reader(h.post,"again",null,false,1,{true}).error)
        assertEquals(2,h.writes.size)
        h.post=h.post.copy(bookmarkName="pending")
        assertNull(h.service.reconcile(h.bookmark(),1).error);assertEquals(2,h.writes.size)
        h.failure=null;h.apply=true
        assertNull(h.service.save(h.read(),"after verification",null,1,{true}).error)
    }
    @Test fun `deletion uses bookmark id and publishes removal without affecting another bookmark`() {
        val h=Harness();assertNull(h.service.delete(h.bookmark(),1,{true}).error)
        assertEquals("/bookmarks/77.json",h.writes.single().path);assertEquals("DELETE",h.writes.single().method)
        assertTrue(h.events.single().deleted);assertEquals(77L,h.events.single().id);assertEquals(11L,h.events.single().postId)
        assertFalse(h.post.bookmarked!!)
    }
    @Test fun `reader creation uses post target and ambiguous delete can be explicitly verified`() {
        val h=Harness();h.post=h.post.copy(bookmarked=false,bookmarkId=null,bookmarkName=null,bookmarkAutoDeletePreference=null)
        assertNull(h.service.reader(h.post,"created",null,false,1,{true}).error)
        assertEquals("POST",h.writes.single().method);assertEquals("/bookmarks.json",h.writes.single().path)
        assertEquals(11L,h.writes.single().data["bookmarkable_id"].asLong)
        h.failure=IOException();h.apply=false
        val bookmark=h.bookmark();assertInstanceOf(UnconfirmedOperationException::class.java,h.service.delete(bookmark,1,{true}).error)
        h.post=h.post.copy(bookmarked=false,bookmarkId=null)
        assertNull(h.service.reconcilePost(h.post,1).error);assertEquals(2,h.writes.size)
    }
    @Test fun `topic bookmarks delete without guessing a post id and uncertain topic deletes never replay`() {
        val h=Harness();h.failure=IOException();h.apply=false
        val topic=BookmarkMetadata(9,"Topic",11)
        assertInstanceOf(UnconfirmedOperationException::class.java,h.service.delete(topic,1,{true}).error)
        assertEquals(0,h.reads)
        assertNotNull(h.service.delete(topic,1,{true}).error);assertEquals(1,h.writes.size)
        // A post with the same numeric target remains independent of the Topic bookmark.
        h.failure=null;h.apply=true;assertNull(h.service.save(h.read(),"post",null,1,{true}).error)
    }
    @Test fun `inactive stale readonly and rejected operations do not publish success`() {
        val h=Harness();val seed=h.read()
        assertNotNull(h.service.save(seed,"new",null,1,{false}).error);assertTrue(h.writes.isEmpty())
        h.writable={error("readonly")};assertNotNull(h.service.save(seed,"new",null,1,{true}).error);assertTrue(h.writes.isEmpty())
        h.writable={};h.session={throw StaleSessionException()}
        assertNotNull(h.service.delete(h.bookmark(),1,{true}).error);assertTrue(h.writes.isEmpty())
        h.session={};h.failure=HttpStatusException(403);h.apply=false
        assertNotNull(h.service.save(seed,"new",null,1,{true}).error);assertTrue(h.events.isEmpty())
    }
    @Test fun `partial post metadata cannot falsely confirm an ambiguous deletion`() {
        val h=Harness();h.apply=false;h.failure=IOException()
        h.afterOverride=h.post.copy(bookmarked=null,bookmarkId=null)
        assertInstanceOf(UnconfirmedOperationException::class.java,h.service.delete(h.bookmark(),1,{true}).error)
        assertTrue(h.events.isEmpty())
    }
    @Test fun `name and time validation runs before writes and unchanged expired reminders are preserved`() {
        val h=Harness();val seed=h.read()
        assertNotNull(h.service.save(seed,"x".repeat(101),null,1,{true}).error)
        assertNotNull(h.service.save(seed,"new",Instant.now().minusSeconds(60).toString(),1,{true}).error)
        assertTrue(h.writes.isEmpty())
        val expired=Instant.now().minusSeconds(60).toString();h.post=h.post.copy(bookmarkReminderAt=expired)
        assertNull(h.service.save(h.read(),"renamed",expired,1,{true}).error)
        assertTrue(BookmarkService.sameTime("2026-10-07T08:00:00.000Z","2026-10-07T08:00:00Z"))
    }
}
