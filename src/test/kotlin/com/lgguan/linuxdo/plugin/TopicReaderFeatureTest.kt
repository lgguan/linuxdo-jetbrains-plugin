package com.lgguan.linuxdo.plugin

import com.google.gson.*
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.theme.*
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.*
import java.util.concurrent.*

class TopicReaderFeatureTest {
    @Test fun `reader write response accepts native OK and preserves rejection explanations`() {
        val api=com.lgguan.linuxdo.plugin.api.DiscourseApiClient
        assertEquals("OK",api.parseReaderResponse("""{"success":"OK","errors":[]}""").asJsonObject.get("success").asString)
        assertTrue(api.parseReaderResponse("").isJsonObject)
        val error=assertThrows(ForumValidationException::class.java){api.parseReaderResponse("""{"success":false,"errors":["<b>投票已关闭</b>"]}""")}
        assertEquals(listOf("投票已关闭"),error.errors)
    }
    @Test fun `empty bookmarks and visible revision sides preserve server meaning`() {
        val empty=TopicReadingService.bookmarkPage(JsonParser.parseString("""{"bookmarks":[]}""").asJsonObject)
        assertEquals(0,empty.getAsJsonArray("items").size());assertFalse(empty.get("more").asBoolean)
        val diff=JsonObject().apply{addProperty("side_by_side_markdown","<table><tr><td class='--previous'>    old\n</td><td class='--current'>    new\n</td></tr></table>")}
        assertEquals("    old\n" to "    new\n",TopicReadingService.revisionBodies(diff))
    }
    private val post=Post(id=11,topicId=1,postNumber=2,username="author",cooked="<p>body</p>",raw="body",canEdit=true,canDelete=true,canRecover=true,canViewEditHistory=true,bookmarked=false,
        actionsSummary=listOf(ActionSummary(2,1,false,true,true),ActionSummary(99,0,false,true)), reactions=emptyList(),canAcceptAnswer=true,canBoost=true)
    private val topic=TopicDetailResponse(1,"Test",postStream=PostStream(listOf(post),listOf(11)),details=TopicPermissions(true,1))
    private val rules=ReaderRules(listOf("heart","tada"),listOf(FlagType(99,"自定义举报","社区配置",true)),setOf("members"),true,false)
    private fun input(vararg values:Pair<String,Any?>)=JsonObject().apply { values.forEach { (key,value)->add(key,Gson().toJsonTree(value)) } }
    private class Transport(var current:Post):TopicOperationTransport {
        val writes=CopyOnWriteArrayList<OperationRequest>()
        var next:Throwable?=null
        var reads=0
        override fun post(id:Long,version:Long)=Result.success(current).also { reads++ }
        override fun write(request:OperationRequest,version:Long):Result<JsonElement>{writes.add(request);return next?.let{Result.failure(it)}?:Result.success(JsonObject())}
    }
    @Test fun `preview TeX retains underscores backslashes and never converts code`() {
        val source="""公式 ${'$'}x_1=\frac{1}{2}${'$'} 和 \(a_b\)

${'$'}${'$'}
\int_0^1 x^2 dx
${'$'}${'$'}

```text
${'$'}not_math${'$'}
```

`${'$'}also_code${'$'}`"""
        val doc=Jsoup.parse(com.lgguan.linuxdo.plugin.ui.dialog.ComposerPreview.markdownToHtml(source))
        assertEquals(3,doc.select(".math").size)
        assertEquals("x_1=\\frac{1}{2}",doc.select(".math")[0].attr("data-math-source"))
        assertTrue(doc.select("pre").text().contains("not_math"));assertTrue(doc.select("code .math").isEmpty())
    }
    @Test fun `captured webpage structure is inert and preserves code and media topology`() {
        val source=javaClass.getResource("/reader/forum-structure.html")!!.readText()
        val doc=Jsoup.parse(ForumContent.render(source,false,"web-sample"))
        assertTrue(doc.select("script,svg,iframe,button,[onclick],[onerror]").isEmpty())
        assertTrue(doc.select("pre code.language-lua").isNotEmpty())
        assertTrue(doc.select(".lightbox-wrapper img[width][height]").isNotEmpty())
        assertFalse(doc.html().contains("linux.do/uploads"))
        assertTrue(doc.text().contains("脱敏样本"))
    }
    @Test fun `permissions never default to granted and do not depend on matching username`() {
        val missing=Gson().fromJson("""{"id":11,"username":"author","actions_summary":[{"id":2,"count":1}]}""",Post::class.java)
        assertFalse(PostCapabilities.like(missing,true));assertFalse(PostCapabilities.like(missing,false));assertFalse(PostCapabilities.reply(topic.copy(details=null)))
        assertFalse(PostCapabilities.reply(topic.copy(closed=true)));assertFalse(PostCapabilities.reply(topic.copy(archived=true)))
        val html=TopicDocumentRenderer.buildPostFragment(topic,listOf(post.copy(canEdit=false,canDelete=false,canRecover=false)),LinuxDoSettingsState(),currentUsername="author")
        val doc=Jsoup.parse(html)
        assertEquals(0,doc.select("[data-reader-action=edit],[data-reader-action=delete],[data-reader-action=recover]").size)
        assertTrue(doc.select("[onclick^=toggleLikeUi]").isNotEmpty())
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,missing,"like",input("like" to true),null)}
    }
    @Test fun `media sanitization keeps inert embed metadata and drops executable SVG`() {
        val doc=Jsoup.parse(ForumContent.render("""<iframe src="https://player.bilibili.com/player.html?bvid=BV1xx411c7mD" onload="bad()"></iframe><iframe src="javascript:bad()"></iframe><svg onload="bad()"><script>bad()</script></svg><video autoplay src="/uploads/demo.webm"></video><span class="math" data-math-source="x^2">x^2</span><h2>A</h2><h2>B</h2><img width="640" height="480" src="/image.png" onerror="bad()">""",false,"11"))
        assertTrue(doc.select("iframe,svg,script,[onload],[onerror]").isEmpty())
        assertEquals(2,doc.select(".forum-embed").size)
        assertEquals(1,doc.select(".forum-embed-source").size)
        assertEquals("x^2",doc.selectFirst(".math")!!.attr("data-math-source"))
        assertEquals(2,doc.select("h2[id]").map{it.id()}.distinct().size)
        assertEquals("lazy",doc.selectFirst("img")!!.attr("loading"));assertTrue(doc.selectFirst("img")!!.attr("style").contains("640/480"))
        assertFalse(RenderAssets.allowedPlayer("https://player.bilibili.com.evil.test/player.html"))
        assertFalse(RenderAssets.allowedPlayer("https://player.bilibili.com:444/player.html"))
        assertFalse(RenderAssets.allowedPlayer("https://evil@player.bilibili.com/player.html"))
        assertNotNull(RenderAssets.resource(RenderAssets.PATH+"mermaid.min.js"));assertNull(RenderAssets.resource(RenderAssets.PATH+"../forum-content.js"))
    }
    @Test fun `post actions only expose granted capabilities below body and group secondary actions`() {
        val unavailable=Post(id=22,postNumber=1,username="reader",cooked="<p>ordinary body</p>",yours=true,
            canEdit=false,canDelete=false,canRecover=false,canViewEditHistory=false,canAcceptAnswer=false,canUnacceptAnswer=false,
            reactions=emptyList(),actionsSummary=listOf(ActionSummary(2,0,false,false,false)))
        val closed=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(topic.copy(closed=true),listOf(unavailable),LinuxDoSettingsState(),currentUsername=null))
        assertEquals(listOf("分享"),closed.select(".floor-actions button").map{it.text()})
        assertTrue(closed.select(".action-disabled,.floor-actions > button,.floor-comment-header .floor-actions").isEmpty())
        assertEquals(1,closed.select(".post-actions-menu-items [data-post-command=share]").size)
        assertEquals(1,closed.select(".post-content + .floor-actions").size)
        val allowed=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(topic,listOf(post.copy(canRecover=false)),LinuxDoSettingsState(),currentUsername="reader"))
        assertEquals(listOf("点赞","Boost","回复"),allowed.select(".floor-actions > button").map{it.attr("aria-label")})
        assertEquals(3,allowed.select(".floor-actions > button > svg.reader-icon").size)
        assertTrue(allowed.select(".post-actions-menu-items [data-post-command=boost]").isEmpty())
        assertEquals(1,allowed.select(".post-actions-menu-items [data-post-command=share]").size)
        assertEquals(1,allowed.select(".post-actions-menu").size)
        assertEquals("收藏",allowed.selectFirst("[data-reader-action=bookmark] span")!!.text())
        assertEquals(1,allowed.select("[data-reader-action=bookmark] svg").size)
        val bookmarked=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(topic,listOf(post.copy(bookmarked=true)),LinuxDoSettingsState(),currentUsername="reader"))
        assertEquals("已收藏",bookmarked.selectFirst("[data-reader-action=bookmark] span")!!.text())
        assertFalse(bookmarked.selectFirst(".floor-number")!!.text().contains("Revision"))
        assertTrue(allowed.select(".post-actions-menu-items [data-reader-action=edit]").isNotEmpty())
        assertTrue(allowed.select("[data-reader-action=recover],[data-reader-action=reactionUsers]").isEmpty())
        val reactionsEnabled=topic.copy(validReactions=listOf("heart","tada"))
        val firstReaction=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(reactionsEnabled,listOf(post),LinuxDoSettingsState(),currentUsername="reader"))
        assertEquals(1,firstReaction.select(".post-actions-menu-items [data-reader-action=reaction]").size)
        assertTrue(firstReaction.select("[data-reader-action=reactionUsers]").isEmpty())
        val deniedReaction=Jsoup.parse(TopicDocumentRenderer.buildPostFragment(reactionsEnabled,listOf(unavailable),LinuxDoSettingsState(),currentUsername="reader"))
        assertTrue(deniedReaction.select("[data-reader-action=reaction],[data-reader-action=reactionUsers]").isEmpty())
    }
    @Test fun `edit sends initial text and 409 keeps latest server version for comparison`() {
        val transport=Transport(post.copy(raw="server change"));transport.next=HttpStatusException(409)
        val service=TopicOperationService(transport,{},pause={})
        val result=service.perform(topic,11,"edit",input("original" to "initial text","raw" to "my revision","reason" to "fix"),0)
        assertEquals(409,(result.error as HttpStatusException).status);assertEquals("server change",result.post?.raw)
        assertEquals("initial text",transport.writes.single().data.getAsJsonObject("post").get("original_text").asString)
        assertEquals(2,transport.reads);assertEquals(1,transport.writes.size)
    }
    @Test fun `uncertain write is reconciled once and never automatically replayed`() {
        val transport=Transport(post);transport.next=IOException("lost response")
        val result=TopicOperationService(transport,{},pause={}).perform(topic,11,"like",input("like" to true),0)
        assertTrue(result.error is UnconfirmedOperationException);assertEquals(post,result.post)
        assertEquals(1,transport.writes.size);assertEquals(2,transport.reads)
    }
    @Test fun `a lost response can be confirmed by an authoritative matching state`() {
        val after=post.copy(actionsSummary=listOf(ActionSummary(2,2,true,false,true)))
        assertTrue(TopicOperationService.confirmed(post,after,"like",input("like" to true)))
        assertTrue(TopicOperationService.confirmed(post,post.copy(raw="revision"),"edit",input("raw" to "revision")))
        assertFalse(TopicOperationService.confirmed(post,post,"flag",input("type" to 99)))
    }
    @Test fun `account change and closed page prevent writes after initial read`() {
        var checked=0
        val transport=Transport(post)
        val service=TopicOperationService(transport,{if(++checked>1)throw StaleSessionException()},pause={})
        assertTrue(service.perform(topic,11,"delete",JsonObject(),0).error is StaleSessionException)
        assertTrue(transport.writes.isEmpty())
        val second=TopicOperationService(transport,{},pause={}).perform(topic,11,"delete",JsonObject(),0,active={false})
        assertNotNull(second.error);assertTrue(transport.writes.isEmpty())
    }
    @Test fun `changed permissions return the fresh post without issuing a write`() {
        val denied=post.copy(canDelete=false)
        val transport=Transport(denied)
        val outcome=TopicOperationService(transport,{},pause={}).perform(topic,11,"delete",JsonObject(),0)
        assertNotNull(outcome.error);assertSame(denied,outcome.post);assertTrue(transport.writes.isEmpty())
    }
    @Test fun `like and reaction share one serialization gate with request spacing`() {
        val transport=Transport(post);var clock=0L;val pauses=mutableListOf<Long>()
        val service=TopicOperationService(transport,{},now={clock},pause={pauses.add(it);clock+=it})
        assertNull(service.perform(topic,11,"like",input("like" to true),0).error)
        assertNull(service.perform(topic,11,"reaction",input("reaction" to "tada"),0,rules).error)
        assertEquals(listOf(800L),pauses);assertEquals(2,transport.writes.size)
        assertTrue(transport.writes.last().path.contains("/tada/toggle.json"))
    }
    @Test fun `rate limit blocks next mutation and Cloudflare remains separately classified`() {
        val transport=Transport(post);transport.next=RateLimitException(30)
        val service=TopicOperationService(transport,{},now={0},pause={})
        assertTrue(service.perform(topic,11,"like",input("like" to true),0).error is RateLimitException)
        assertTrue(service.perform(topic,11,"like",input("like" to true),0).error is RateLimitException)
        assertEquals(1,transport.writes.size)
        assertTrue(HttpFailure.classify(429,mapOf("cf-mitigated" to "challenge"),"") is CloudflareChallengeException)
    }
    @Test fun `delete is soft bookmark edit does not recreate and custom flag requires explanation`() {
        assertFalse(TopicOperationService.request(topic,post,"delete",JsonObject(),null).data.has("force_destroy"))
        assertEquals("PUT",TopicOperationService.request(topic,post.copy(bookmarked=true,bookmarkId=22),"bookmark",input("name" to "later"),null).method)
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,post,"flag",input("type" to 99),rules)}
        val flag=TopicOperationService.request(topic,post,"flag",input("type" to 99,"message" to "说明"),rules)
        assertEquals(99,flag.data.get("post_action_type_id").asInt)
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,post,"reaction",input("reaction" to "evil"),rules)}
    }
    @Test fun `poll options counts ordering groups and removal follow server data`() {
        val poll=JsonParser.parseString("""{"name":"p","type":"multiple","status":"open","min":"2","max":"3","groups":"members","options":[{"id":"a"},{"id":"b"},{"id":"c"}]}""").asJsonObject
        val p=post.copy(polls=listOf(poll),pollsVotes=input("p" to listOf("a","b")))
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,p,"poll",input("name" to "p","options" to listOf("a")),rules)}
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,p,"poll",input("name" to "p","options" to listOf("a","bad")),rules)}
        assertThrows(IllegalArgumentException::class.java){TopicOperationService.request(topic,p,"poll",input("name" to "p","options" to listOf("a","b")),rules.copy(groups=emptySet()))}
        assertEquals("/polls/vote.json",TopicOperationService.request(topic,p,"poll",input("name" to "p","options" to listOf("a","b")),rules).path)
        assertEquals("/polls/remove_vote.json",TopicOperationService.request(topic,p,"unpoll",input("name" to "p"),rules).path)
        poll.addProperty("type","ranked_choice")
        assertEquals(listOf("c","a","b"),TopicOperationService.request(topic,p,"poll",input("name" to "p","options" to listOf("c","a","b")),rules).data.getAsJsonArray("options").map{it.asString})
    }
    @Test fun `question votes use server string directions and never vote on comments`() {
        val question=topic.copy(isPostVoting=true)
        val voted=post.copy(yours=false,postVotingDirection="up")
        assertEquals("DELETE",TopicOperationService.request(question,voted,"postVote",input("direction" to "up"),null).method)
        val switch=TopicOperationService.request(question,voted,"postVote",input("direction" to "down"),null)
        assertEquals("POST",switch.method);assertEquals("down",switch.data.get("direction").asString)
        assertFalse(PostCapabilities.postVote(question,voted.copy(replyToPostNumber=2)))
        assertEquals("up",Gson().fromJson("""{"post_voting_user_voted_direction":"up"}""",Post::class.java).postVotingDirection)
    }
    @Test fun `ten thousand posts obey both cache limits and prefer target neighborhood`() {
        val posts=(1..10000).map{post.copy(id=it.toLong(),postNumber=it)}
        val bounded=PostCache.bound(posts,6000)
        assertEquals(400,bounded.size);assertTrue(bounded.any{it.postNumber==6000});assertFalse(bounded.any{it.postNumber==1})
        val heavy=PostCache.bound(posts.take(50).map{it.copy(cooked="中".repeat(500000))},25)
        assertTrue(heavy.sumOf(PostCache::bytes)<=PostCache.MAX_BYTES);assertTrue(heavy.size<50)
        val fresh=topic.copy(postStream=PostStream(listOf(post.copy(cooked="updated")),listOf(11)))
        assertEquals("updated",TopicRefresh.merge(topic,fresh).postStream.posts.single().cooked)
    }
    @Test fun `configuration preserves community flags and enabled reactions`() {
        val html="""<script id="data-preloaded" type="application/json">{"siteSettings":{"discourse_reactions_enabled_reactions":"heart|tada","poll_enable_remove_vote":true},"currentUser":{"groups":[{"name":"members"}]}}</script>"""
        val site=input("post_action_types" to listOf(input("id" to 99,"name" to "社区自定义","description" to "<b>说明</b>","is_flag" to true,"enabled" to true,"require_message" to true)))
        val parsed=ReaderRules.parse(html,site)
        assertEquals(listOf("heart","tada"),parsed.reactions);assertEquals("说明",parsed.flags.single().description);assertTrue(parsed.flags.single().requireMessage)
    }
    @Test fun `generate production reader fixture with all offline media and server permissions`() {
        val cooked="""<h2>正文目录</h2><p>中文长帖、图片、代码、公式和图表。</p><span class="math" data-math-source="E=mc^2">E=mc^2</span><div class="math">\int_0^1 x^2 dx=\frac{1}{3}</div><pre><code class="language-kotlin">fun answer(): Int {\n    return 42\n}\n</code></pre><pre><code class="language-mermaid">graph LR; A[阅读] --> B[互动]</code></pre><details><summary>展开详情</summary><span class="spoiler">隐藏内容</span></details><aside class="quote" data-topic="1" data-post="2"><div class="title">引用</div><blockquote>上下文</blockquote></aside><img src="https://fixture.test/image.png" width="640" height="480"><img src="https://fixture.test/second.png" width="640" height="480"><iframe src="https://www.youtube.com/embed/dQw4w9WgXcQ"></iframe><iframe src="https://player.bilibili.com/player.html?bvid=BV1xx411c7mD"></iframe><audio src="https://fixture.test/audio.ogg"></audio><video src="https://fixture.test/video.webm"></video><table><tr><th>功能</th><th>状态</th></tr><tr><td>安全渲染</td><td>启用</td></tr></table>""".replace("\\n","\n")
        val pollData=JsonParser.parseString("""[{"name":"single","title":"单选问卷","type":"regular","status":"open","voters":0,"results":"on_vote","options":[{"id":"a","html":"方案 A"},{"id":"b","html":"方案 B"}]},{"name":"multi","title":"多选问卷","type":"multiple","status":"open","voters":2,"results":"always","min":"2","max":"2","options":[{"id":"a","html":"方案 A","votes":2},{"id":"b","html":"方案 B","votes":1},{"id":"c","html":"方案 C","votes":1}]},{"name":"number","title":"数字问卷","type":"number","status":"open","voters":0,"results":"on_close","options":[{"id":"one","html":"1"},{"id":"two","html":"2"}]},{"name":"ranked","title":"排序问卷","type":"ranked_choice","status":"open","voters":0,"results":"on_close","options":[{"id":"a","html":"方案 A"},{"id":"b","html":"方案 B"}]}]""").asJsonArray.map{it.asJsonObject}
        val first=post.copy(id=1,postNumber=1,cooked=cooked,raw=null,replyCount=2,polls=pollData,pollsVotes=input("multi" to listOf("a","b")))
        val posts=listOf(first,post.copy(cooked="<h3>回复标题</h3><p>第二楼内容，用于搜索和引用。</p>"))
        for(dark in listOf(true,false)){
            val theme=EditorColorSchemeAdapter.getCurrentThemeColors().copy(isDark=dark,bgHex=if(dark)"#202124" else "#ffffff",
                fgHex=if(dark)"#dfe1e5" else "#24292f",commentHex=if(dark)"#939ba5" else "#66707b",
                codeBlockBgHex=if(dark)"#26282e" else "#f6f8fa",borderHex=if(dark)"#383a40" else "#d8dee4")
            val config=mapOf("key" to "fixture","topic" to 1,"stream" to (1..10000).map{it.toString()},"highest" to 10000,"author" to "author","loggedIn" to true,"notificationLevel" to 1,"unreadFloor" to 3,"defaultFontSize" to 16)
            val html=TopicDocumentRenderer.buildFullDocHtml(topic.copy(postStream=PostStream(posts,(1L..10000L).toList())),posts,"开发","dev",theme,LinuxDoSettingsState().apply{foldImages=false},currentUsername="reader",paginationScript="window.linuxDoPage="+Gson().toJson(config)+";"+javaClass.getResource("/web/topic-pagination.js")!!.readText())
            val path=Path.of("build/reader-fixture-$dark.html");Files.createDirectories(path.parent);Files.writeString(path,html)
            assertTrue(html.contains("__linuxdo_plugin_assets/"));assertFalse(html.contains("cdn.jsdelivr.net"))
            val simplePosts=listOf(
                Post(id=1,postNumber=1,username="sample_author",yours=true,cooked="<p>整理了一份开发笔记，欢迎交流。</p>",bookmarked=false),
                Post(id=2,postNumber=2,username="community_member",yours=false,cooked="<p>感谢分享，期待后续更新。</p>",bookmarked=false,canBoost=true,actionsSummary=listOf(ActionSummary(2,0,false,true,true),ActionSummary(99,0,false,true))))
            val simpleTopic=topic.copy(title="开发笔记与交流",highestPostNumber=2,postStream=PostStream(simplePosts,listOf(1,2)))
            val simpleConfig=config + mapOf("stream" to listOf("1","2"),"highest" to 2,"unreadFloor" to null,"author" to "sample_author")
            Files.writeString(Path.of("build/layout-reader-$dark.html"),TopicDocumentRenderer.buildFullDocHtml(simpleTopic,simplePosts,"开发","dev",theme,LinuxDoSettingsState(),currentUsername="reader",paginationScript="window.linuxDoPage="+Gson().toJson(simpleConfig)+";"+javaClass.getResource("/web/topic-pagination.js")!!.readText()))
        }
    }
}
