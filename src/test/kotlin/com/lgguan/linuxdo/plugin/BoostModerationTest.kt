package com.lgguan.linuxdo.plugin

import com.google.gson.*
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class BoostModerationTest {
    private val types = listOf(BoostFlagType(6,"notify_moderators","其他","说明",true),BoostFlagType(4,"spam","垃圾信息","垃圾信息",false))
    private class Harness(val id: Long = 80) {
        var boost = PostBoost(id=id,user=BoostUser(id=9,username="other"),cooked="<p>Boost</p>",canFlag=true,availableFlags=listOf("notify_moderators","spam"))
        var post = Post(id=10,topicId=1,postNumber=2,username="author",boosts=listOf(boost))
        var failure: Throwable? = null
        var apply = true
        var unreadable = false
        val writes = mutableListOf<OperationRequest>()
        var postReads = 0
        var boostReads = 0
        val transport = object : TopicOperationTransport {
            override fun post(id:Long,version:Long):Result<Post> { postReads++;return Result.success(post) }
            override fun write(request:OperationRequest,version:Long):Result<JsonElement> {
                writes.add(request)
                if(apply)boost=boost.copy(userFlagStatus=0,canFlag=false)
                return failure?.let { Result.failure(it) } ?: Result.success(JsonObject().apply { addProperty("success","OK") })
            }
        }
        val service = BoostModerationService(transport,{_,_->boostReads++;if(unreadable)Result.failure(IOException()) else Result.success(boost)}, {}, {7}, ReaderWriteGate({0},{}))
    }
    @Test fun `loaded permissions need one Boost read and report still refreshes floor`() {
        val h=Harness(86)
        val state=h.service.readLoaded(1,h.post,86,6)
        assertEquals(0,h.postReads);assertEquals(1,h.boostReads)
        assertTrue(h.service.canReport(state.boost,6))
        assertThrows(IllegalArgumentException::class.java){h.service.readLoaded(2,h.post,86,6)}
        assertEquals(1,h.boostReads)
        assertNull(h.service.flag(1,10,86,6,"explanation",6,types,false,{true}).error)
        assertEquals(1,h.postReads);assertFalse(h.service.canReport(state.boost,6))
    }
    @Test fun `catalog intersects enabled Boost target keys and fresh permissions`() {
        val catalog=JsonParser.parseString("""{"post_action_types":[
            {"id":6,"name_key":"notify_moderators","name":"<b>其他</b>","description":"<p>说明</p>","require_message":true,"enabled":true,"is_flag":true,"applies_to":["DiscourseBoosts::Boost"]},
            {"id":4,"name_key":"spam","enabled":false,"is_flag":true,"applies_to":["DiscourseBoosts::Boost"]},
            {"id":9,"enabled":true,"is_flag":true,"applies_to":["Post"]}]}""").asJsonObject
        assertEquals(listOf(types[0]),BoostFlagType.parse(catalog))
        val h=Harness();h.boost=h.boost.copy(availableFlags=listOf("spam"))
        assertEquals(listOf(types[1]),h.service.read(1,10,80,0,types).types)
        for(boost in listOf(h.boost.copy(canFlag=null),h.boost.copy(user=BoostUser(id=7)),h.boost.copy(userFlagStatus=0))) {
            h.boost=boost;assertTrue(h.service.read(1,10,80,0,types).types.isEmpty())
        }
    }
    @Test fun `report targets boost ID with no moderation side effects and confirms pending status`() {
        val h=Harness();val result=h.service.flag(1,10,80,6,"  explanation  ",1,types,false,{true})
        assertNull(result.error);assertEquals(0,result.post?.boosts?.single()?.userFlagStatus)
        val request=h.writes.single();assertEquals("/discourse-boosts/boosts/80/flags",request.path)
        assertEquals("POST",request.method);assertEquals(6,request.data["flag_type_id"].asInt)
        assertEquals("explanation",request.data["message"].asString)
        assertFalse(request.data["take_action"].asBoolean);assertFalse(request.data["queue_for_review"].asBoolean)
        assertNotNull(h.service.flag(1,10,80,6,"explanation",1,types,false,{true}).error);assertEquals(1,h.writes.size)
    }
    @Test fun `read only wrong target missing permission invalid reason and required message prevent writes`() {
        val h=Harness()
        assertNotNull(h.service.flag(1,10,80,6,"",2,types,false,{true}).error)
        assertNotNull(h.service.flag(1,10,80,4,"",2,types,true,{true}).error)
        assertNotNull(h.service.flag(1,10,80,90,"",2,types,false,{true}).error)
        assertNotNull(h.service.flag(1,10,80,4,"x".repeat(4001),2,types,false,{true}).error)
        assertNotNull(h.service.flag(1,10,81,4,"",2,types,false,{true}).error)
        assertNotNull(h.service.flag(2,10,80,4,"",2,types,false,{true}).error)
        assertNotNull(h.service.flag(1,10,80,4,"",2,types,false,{false}).error)
        h.boost=h.boost.copy(canFlag=false)
        assertNotNull(h.service.flag(1,10,80,4,"",2,types,false,{true}).error);assertTrue(h.writes.isEmpty())
    }
    @Test fun `ambiguous flag gets read reconciliation without replay and blocks other instances`() {
        val h=Harness(82);h.failure=IOException("lost")
        assertNull(h.service.flag(1,10,82,4,"",3,types,false,{true}).error);assertEquals(1,h.writes.size)
        val unknown=Harness(83);unknown.failure=IOException("lost");unknown.apply=false
        assertTrue(unknown.service.flag(1,10,83,4,"",3,types,false,{true}).error is UnconfirmedOperationException)
        val other=BoostModerationService(unknown.transport,{_,_->Result.success(unknown.boost)}, {}, {7}, ReaderWriteGate({0},{}))
        assertNotNull(other.flag(1,10,83,4,"",3,types,false,{true}).error);assertEquals(1,unknown.writes.size)
    }
    @Test fun `definite rejection keeps input retry possible and success survives unreadable readback`() {
        val h=Harness(84);h.apply=false;h.failure=ForumValidationException(422,listOf("rejected"))
        assertSame(h.failure,h.service.flag(1,10,84,4,"",4,types,false,{true}).error)
        h.failure=null;h.apply=true
        assertNull(h.service.flag(1,10,84,4,"",4,types,false,{true}).error);assertEquals(2,h.writes.size)
    }
    @Test fun `confirmed report blocks a second window even when server reads lag behind`() {
        val h=Harness(85);h.apply=false
        assertNull(h.service.flag(1,10,85,4,"",5,types,false,{true}).error)
        assertTrue(h.service.read(1,10,85,5,types).types.isEmpty())
        val other=BoostModerationService(h.transport,{_,_->Result.success(h.boost)}, {}, {7}, ReaderWriteGate({0},{}))
        assertNotNull(other.flag(1,10,85,4,"",5,types,false,{true}).error);assertEquals(1,h.writes.size)
    }
    @Test fun `public user card excludes credentials and renders untrusted biography as plain text`() {
        val response=JsonParser.parseString("""{"user":{"username":"other","name":"<b>Name</b>","bio_cooked":"<p>Hello</p><script>bad()</script>","trust_level":2,"email":"private","auth_token":"secret","avatar_template":"javascript:bad()"}}""").asJsonObject
        val card=TopicReadingService.profileCard(response,"other","https://linux.do")
        assertEquals("Name",card["name"].asString);assertEquals("Hello",card["bio_cooked"].asString)
        assertFalse(card.has("email"));assertFalse(card.has("auth_token"));assertFalse(card.has("avatarUrl"))
        assertEquals("https://linux.do/u/other",card["profileUrl"].asString)
        assertThrows(IllegalArgumentException::class.java){TopicReadingService.profileCard(response,"different","https://linux.do")}
    }
}
