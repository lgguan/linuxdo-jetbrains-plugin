package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.google.gson.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.ui.dialog.EditPostDialog

/** Native boundary for reader requests. All callbacks are bound to page and account epoch. */
internal class TopicReaderController(
    private val project: Project, private val tasks: BackgroundTasks,
    private val topic: () -> TopicDetailResponse?, private val update: (TopicDetailResponse) -> Unit,
    private val active: (String, Long) -> Boolean, private val respond: (String, String, JsonObject) -> Unit
) {
    private val operations = TopicOperationService()
    private val boosts = BoostService()
    private val boostModeration = BoostModerationService()
    private val siteCache = SessionCache<JsonObject>()
    private fun site(version: Long) = siteCache.get() ?: DiscourseApiClient.readerGet("/site.json",version)
        .getOrThrow().asJsonObject.also { siteCache.put(version,it) }
    private val boostFlagsCache = SessionCache<List<BoostFlagType>>()
    private fun boostFlags(version: Long) = boostFlagsCache.get() ?: BoostFlagType.parse(site(version)).also { boostFlagsCache.put(version, it) }
    private val boostRulesCache = SessionCache<BoostEmojiRules>()
    private val rulesCache = SessionCache<ReaderRules>()
    private val uncertain = java.util.concurrent.ConcurrentHashMap.newKeySet<Triple<String,Long,Long>>()
    @Volatile private var filterState: Triple<String,String,Boolean>? = null
    fun readAround(key:String,id:Long,floor:Int,version:Long): Result<TopicDetailResponse> =
        filterState?.takeIf { it.first==key && (it.second.isNotBlank() || it.third) }?.let { TopicReadingService.filtered(id,it.second,it.third,version,floor) }
            ?: DiscourseApiClient.getTopicAroundPost(id,floor)
    private fun rules(version: Long): ReaderRules = rulesCache.get() ?: ReaderRules.parse(
        DiscourseApiClient.readerBootstrap(version).getOrThrow(), site(version)
    ).also { rulesCache.put(version,it) }
    fun handle(key: String, requestId: String, action: String, postId: Long, input: JsonObject, version: Long) {
        if (!active(key,version) || requestId.length !in 1..64 || input.toString().length > 200000) return
        val current = topic() ?: return
        if(action == "openBookmarks") {
            if(LinuxDoBossKeyService.getInstance(project).isHidden || !LinuxDoAuthService.getInstance().isLoggedIn) {
                respond(key, requestId, json("error" to "请先登录并显示插件窗口")); return
            }
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(com.lgguan.linuxdo.plugin.common.Constants.TOOL_WINDOW_ID)?.let { window ->
                window.show {
                    if(!active(key, version) || LinuxDoBossKeyService.getInstance(project).isHidden) return@show
                    val main = window.contentManager.contents.firstOrNull()?.component as? LinuxDoDocMainPanel
                    main?.issueListPanel?.let { list -> list.personalContentPanel.selectKind(PersonalContentKind.BOOKMARKS); list.selectPersonalView(true) }
                }
                respond(key, requestId, json("message" to "已打开我的书签"))
            } ?: respond(key, requestId, json("error" to "个人中心暂不可用，请打开 API Docs 工具窗口"))
            return
        }
        if (action == "retryReadSync") {
            if (!LinuxDoSettingsState.getInstance().autoReportReadTimings) {
                respond(key,requestId,json("message" to "自动同步已关闭；待同步数据已保留，请在设置中开启后补传"))
                return
            }
            LinuxDoReadTrackingService.getInstance().retrySync()
            respond(key,requestId,json("message" to "已恢复补传；论坛冷却结束后继续"))
            return
        }
        val loaded = current.postStream.posts.firstOrNull { it.id == postId }
        if (postId != 0L && loaded == null) { respond(key,requestId,json("error" to "该楼层已从缓存移出，请重新定位后操作")); return }
        if (action == "appearance") {
            runCatching {
                val font=input.get("font").asInt;val line=input.get("line").asDouble;val width=input.get("width").asInt
                require(font == 0 || font in 12..32);require(line.isFinite() && line in 1.2..2.5);require(width in 480..1600)
                LinuxDoSettingsState.getInstance().apply { readingFontSize=font;readingLineHeight=line;readingWidth=width }
                respond(key,requestId,json("message" to "阅读设置已保存"))
            }.onFailure { respond(key,requestId,json("error" to "阅读设置范围无效")) }
            return
        }
        if(action == "boostFlag") {
            val boost = loaded?.boosts?.firstOrNull { it.id == input.get("boostId")?.asLong }
            if(boost == null) { respond(key,requestId,json("error" to "Boost 已不存在，请刷新楼层")); return }
            val reason = boostFlagsCache.get()?.firstOrNull { it.id == input.get("type")?.asInt }?.name
            if(reason == null) { respond(key,requestId,json("error" to "请先读取 Boost 举报原因")); return }
            val preview = org.jsoup.Jsoup.parse(boost.cooked ?: boost.raw ?: boost.content.orEmpty()).text().take(200)
            val message = "确认举报 #${loaded?.postNumber} 楼中 @${boost.getDisplayUsername()} 的 Boost？\nBoost：$preview\n原因：$reason\n说明：${input.get("message")?.asString.orEmpty().take(4000)}"
            if(Messages.showYesNoDialog(project,message,"举报 Boost",Messages.getQuestionIcon()) != Messages.YES) {
                respond(key,requestId,json("cancelled" to true,"message" to "已取消")); return
            }
            if(!active(key,version)) return
        }
        if (action == "delete" || action == "recover" || action == "flag") {
            val target = requireNotNull(loaded)
            val message = when(action) {
                "delete" -> "确认软删除 #${target.postNumber} 楼（@${target.username}）？" + if(target.postNumber==1) "首楼删除可能影响整个话题；实际影响由服务器决定。" else "正文将按论坛规则隐藏。"
                "recover" -> "确认恢复 #${target.postNumber} 楼（@${target.username}）？"
                else -> "确认举报 #${target.postNumber} 楼（@${target.username}）？\n原因：${rulesCache.get()?.flags?.firstOrNull { it.id==input.get("type")?.asInt }?.name ?: input.get("type")}\n说明：${input.get("message")?.asString.orEmpty()}"
            }
            if (Messages.showYesNoDialog(project,message,"确认帖子操作",Messages.getQuestionIcon()) != Messages.YES) {
                respond(key,requestId,json("message" to "已取消"));return
            }
            if (!active(key,version)) return
        }
        tasks.submit {
            if (!active(key,version)) return@submit
            var changed: Post? = null
            var relatedChanges: List<Post> = emptyList()
            var replacement: TopicDetailResponse? = null
            var editing: Post? = null
            var filtering: Pair<String,Boolean>? = null
            val result = runCatching {
                when(action) {
                    "boostActionsInfo" -> {
                        check(LinuxDoAuthService.getInstance().isLoggedIn) { "请先登录" }
                        val state = boostModeration.readLoaded(current.id,requireNotNull(loaded),input.get("boostId").asLong,version,boostFlags(version))
                        json("username" to state.boost.getDisplayUsername(),"types" to state.types,
                            "canFlag" to boostModeration.canReport(state.boost,version),
                            "flagged" to (state.boost.userFlagStatus == 0),"readOnly" to rules(version).readOnly)
                    }
                    "boostFlag" -> {
                        val operation = boostModeration.flag(current.id,postId,input.get("boostId").asLong,input.get("type").asInt,
                            input.get("message")?.asString.orEmpty(),version,boostFlags(version),rules(version).readOnly) { active(key,version) }
                        changed = operation.post; operation.error?.let { throw it }
                        json("message" to "Boost 举报已提交，等待论坛处理")
                    }
                    "boostInfo" -> {
                        val post = ForumOperationTransport.post(postId,version).getOrThrow();require(post.topicId==current.id);changed=post
                        val rules = boostRulesCache.get() ?: BoostEmojiRules.parse(DiscourseApiClient.readerBootstrap(version).getOrThrow()).also { boostRulesCache.put(version,it) }
                        json("allowed" to (post.canBoost==true),"emojiNames" to (BoostText.names+rules.custom),"emojiUnicode" to BoostText.unicode,"tonedEmoji" to BoostText.toned,"deniedEmoji" to rules.denied)
                    }
                    "boostSend", "boostDelete" -> {
                        val rules = if(action=="boostSend") boostRulesCache.get() ?: BoostEmojiRules.parse(DiscourseApiClient.readerBootstrap(version).getOrThrow()).also { boostRulesCache.put(version,it) } else BoostEmojiRules()
                        val operation = boosts.perform(current.id,postId,input.get("raw")?.asString,
                            if(action=="boostDelete")input.get("boostId").asLong else null,version,rules) { active(key,version) }
                        changed = operation.post
                        operation.error?.let { throw it }
                        json("message" to if(action=="boostDelete")"Boost 已撤回" else "Boost 已发送")
                    }
                    "search" -> TopicReadingService.search(current.id,input.get("query").asString,input.get("page").asInt,version).getOrThrow()
                    "profile" -> TopicReadingService.profile(input.get("username").asString,version).getOrThrow()
                    "context" -> {
                        val floor=input.get("floor").asInt; val id=input.get("topic").asLong;require(floor>0 && id>0)
                        val around=DiscourseApiClient.getTopicAroundPost(id,floor).getOrThrow();SessionEpoch.requireCurrent(version)
                        json("items" to around.postStream.posts.filter { kotlin.math.abs(it.postNumber-floor)<=2 }.map(::item))
                    }
                    "filter" -> {
                        val author=input.get("author")?.asString.orEmpty(); val popular=input.get("popular")?.asBoolean==true
                        val filtered=if(author.isBlank() && !popular) DiscourseApiClient.getTopicAroundPost(current.id,1).getOrThrow()
                            else TopicReadingService.filtered(current.id,author,popular,version).getOrThrow()
                        SessionEpoch.requireCurrent(version);replacement=filtered.copy(postStream=filtered.postStream.copy(posts=PostCache.bound(filtered.postStream.posts,1)));filtering=author to popular
                        json("ids" to filtered.postStream.stream.orEmpty().map { it.toString() },"fragment" to TopicDocumentRenderer.buildPostFragment(filtered,replacement!!.postStream.posts,LinuxDoSettingsState.getInstance()))
                    }
                    "edge" -> {
                        val ids=current.postStream.stream.orEmpty();val id=if(input.get("last")?.asBoolean==true)ids.lastOrNull() else ids.firstOrNull()
                        val post=Gson().fromJson(DiscourseApiClient.readerGet("/t/${current.id}/posts.json?post_ids[]=${requireNotNull(id)}",version).getOrThrow(),PostStreamResponse::class.java).postStream.posts.firstOrNull { it.id==id } ?: error("筛选结果中没有可定位楼层")
                        json("floor" to post.postNumber)
                    }
                    "subscription", "topicVote" -> ReaderWriteGate.shared.serialized {
                        val identity = Triple(key,version,0L)
                        check(identity !in uncertain) { "上次话题操作结果未确认，请核对服务器状态后重新打开话题" }
                        check(LinuxDoAuthService.getInstance().isLoggedIn) { "请先登录" };check(!rules(version).readOnly) { "论坛处于只读状态" }
                        fun readTopic() = Gson().fromJson(DiscourseApiClient.readerGet("/t/${current.id}.json?track_visit=false",version).getOrThrow(),TopicDetailResponse::class.java)
                        val fresh=readTopic()
                        val body=JsonObject();val path:String
                        if(action=="subscription") { require(fresh.details?.notificationLevel != null);val level=input.get("level").asInt;require(level in 0..3);body.addProperty("notification_level",level);path="/t/${current.id}/notifications.json" }
                        else { require((fresh.canVote==true || fresh.userVoted==true) && fresh.closed!=true && fresh.archived!=true);body.addProperty("topic_id",current.id);path="/voting/${if(fresh.userVoted==true) "unvote" else "vote"}.json" }
                        ReaderWriteGate.shared.beforeWrite()
                        check(active(key,version));SessionEpoch.requireCurrent(version)
                        val written=DiscourseApiClient.readerWrite(path,"POST",body,version)
                        ReaderWriteGate.shared.failure(written.exceptionOrNull())
                        val checked=runCatching { readTopic() }.getOrElse { error ->
                            val failure=written.exceptionOrNull()
                            if(failure!=null && TopicOperationService.definite(failure))throw failure
                            uncertain.add(identity);throw UnconfirmedOperationException(error)
                        }
                        replacement=current.copy(details=checked.details,canVote=checked.canVote,userVoted=checked.userVoted,voteCount=checked.voteCount,votesLeft=checked.votesLeft,closed=checked.closed,archived=checked.archived)
                        written.exceptionOrNull()?.let { error ->
                            val confirmed=if(action=="subscription")checked.details?.notificationLevel==input.get("level")?.asInt else checked.userVoted!=fresh.userVoted
                            if(TopicOperationService.definite(error))throw error
                            if(!confirmed){uncertain.add(identity);throw UnconfirmedOperationException(error)}
                        }
                        val response=written.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                        json("message" to "服务器状态已同步", "topic" to json("notificationLevel" to checked.details?.notificationLevel,"voteCount" to checked.voteCount,"canVote" to checked.canVote,"userVoted" to checked.userVoted,"votesLeft" to (response?.get("votes_left")?.asInt ?: checked.votesLeft)))
                    }
                    "postInfo", "bookmarkInfo", "flagInfo", "reactionInfo", "historyInfo", "repliesInfo", "reactionUsersInfo", "pollInfo", "edit" -> {
                        val post=ForumOperationTransport.post(postId,version).getOrThrow();require(post.topicId==current.id);changed=post
                        when(action) {
                            "postInfo" -> json("message" to "帖子已更新")
                            "bookmarkInfo" -> {
                                val checked = BookmarkService.getInstance().reconcilePost(post, version)
                                checked.error?.let { throw it }
                                changed = checked.post ?: post
                                json("bookmarked" to changed?.bookmarked,"name" to changed?.bookmarkName,"reminder" to changed?.bookmarkReminderAt)
                            }
                            "flagInfo" -> json("types" to rules(version).flags.filter { PostCapabilities.flag(post,it.id) })
                            "reactionInfo" -> json("current" to post.currentUserReaction?.id,"reactions" to (current.validReactions ?: rules(version).reactions))
                            "pollInfo" -> rules(version).let { json("undo" to it.pollUndo,"groups" to it.groups,"readOnly" to it.readOnly) }
                            "historyInfo" -> {
                                require(post.canViewEditHistory==true)
                                val revision=input.get("revision")?.takeUnless { it.isJsonNull }?.asInt
                                require(revision==null || revision in 1..100000)
                                val path=revision?.let { "/posts/$postId/revisions/$it.json" } ?: "/posts/$postId/latest_revision.json"
                                val data=DiscourseApiClient.readerGet(path,version).getOrThrow().asJsonObject
                                val diff=data.get("body_changes")?.takeIf { it.isJsonObject }?.asJsonObject
                                val bodies=TopicReadingService.revisionBodies(diff)
                                json("version" to data.get("current_version"),"previous" to data.get("previous_revision"),"next" to data.get("next_revision"),"reason" to data.get("edit_reason")?.takeUnless { it.isJsonNull }?.asString,"before" to bodies.first,"after" to bodies.second,"unavailable" to (diff==null || data.get("diff_error")?.asBoolean==true))
                            }
                            "repliesInfo" -> { val posts=TopicReadingService.replies(postId,input.get("after")?.asInt ?: 1,version).getOrThrow();json("items" to posts.map(::item),"more" to (posts.size==20)) }
                            "reactionUsersInfo" -> {
                                val page=input.get("page")?.asInt ?: 0;require(page in 0..10000)
                                val data=DiscourseApiClient.readerGet("/discourse-reactions/posts/$postId/reactions-users-list.json?page=$page&limit=30",version).getOrThrow().asJsonObject
                                json("items" to data.getAsJsonArray("users"),"more" to ((data.get("total_rows")?.asInt ?: 0)>(page+1)*30))
                            }
                            else -> { require(post.canEdit==true && !post.raw.isNullOrBlank()); editing=post;json("message" to "编辑窗口已打开") }
                        }
                    }
                    else -> {
                        check(LinuxDoAuthService.getInstance().isLoggedIn) { "请先登录" }
                        val identity=Triple(key,version,postId)
                        if(identity in uncertain) error("上次操作结果未确认，请在浏览器核对后重新打开话题；当前正文已保留")
                        val operation=operations.perform(current,postId,action,input,version,
                            if(action in setOf("flag","reaction","poll","unpoll"))rules(version).let { if(action=="reaction")it.copy(reactions=current.validReactions ?: it.reactions) else it } else null,active={active(key,version)})
                        changed=operation.post
                        if(operation.error==null && action=="delete" && changed==null){
                            changed=loaded?.copy(cooked="<p>服务器已删除此楼层。</p>",raw=null,canEdit=false,canDelete=false,canRecover=false,canViewEditHistory=false,actionsSummary=emptyList(),reactions=null,polls=null,userDeleted=true)
                        }
                        if(operation.error is UnconfirmedOperationException && action !in setOf("bookmark", "unbookmark")) uncertain.add(identity)
                        operation.error?.let { throw it }
                        if(action in setOf("accept","unaccept")){
                            // A newly accepted answer can change another loaded post's solved status.
                            relatedChanges=current.postStream.posts.filter { it.id!=postId && it.acceptedAnswer==true }
                                .mapNotNull { ForumOperationTransport.post(it.id,version).getOrNull() }
                        }
                        json("message" to if(action=="flag") "举报已提交，等待论坛处理" else "服务器状态已同步").apply {
                            if(action=="delete" && loaded?.postNumber==1){
                                val checked=DiscourseApiClient.getTopicDetail(current.id,false)
                                checked.getOrNull()?.let { fresh ->replacement=current.copy(details=fresh.details,closed=fresh.closed,archived=fresh.archived) }
                                if((checked.exceptionOrNull() as? HttpStatusException)?.status in setOf(403,404,410)){addProperty("topicUnavailable",true);addProperty("message","首楼已删除，服务器返回话题不可用，当前内容保留供查看")}
                            }
                        }
                    }
                }
            }
            val payload=result.getOrElse { error -> json("error" to errorMessage(error),"unconfirmed" to (error is UnconfirmedOperationException)) }
            val post=changed
            val changes=listOfNotNull(post)+relatedChanges
            if(changes.isNotEmpty()) payload.addProperty("html",TopicDocumentRenderer.buildPostFragment(replacement ?: current,changes,LinuxDoSettingsState.getInstance()))
            ApplicationManager.getApplication().invokeLater({
                if(!active(key,version))return@invokeLater
                filtering?.let { filterState=Triple(key,it.first,it.second) }
                replacement?.let(update)
                if(changes.isNotEmpty())topic()?.let { detail -> val ids=changes.map { it.id }.toSet();update(detail.copy(postStream=detail.postStream.copy(posts=PostCache.bound(detail.postStream.posts.filterNot { it.id in ids }+changes,post?.postNumber ?: 1)))) }
                respond(key,requestId,payload)
                editing?.let { baseline -> EditPostDialog(project,current,baseline,operations,version,{active(key,version)}) { outcome ->
                    if(!active(key,version))return@EditPostDialog
                    outcome.post?.let { edited ->
                        topic()?.let { detail -> update(detail.copy(postStream=detail.postStream.copy(posts=PostCache.bound(detail.postStream.posts.filterNot { it.id==edited.id }+edited,edited.postNumber)))) }
                        respond(key,"edit-update",json("html" to TopicDocumentRenderer.buildPostFragment(current,listOf(edited),LinuxDoSettingsState.getInstance())))
                    }
                }.show() }
            },ModalityState.any())
        }
    }
    private fun item(post: Post) = json("floor" to post.postNumber,"author" to post.username,"text" to org.jsoup.Jsoup.parse(post.cooked).text().take(3000))
    private fun errorMessage(error: Throwable): String = when(error) {
        is RateLimitException -> "论坛请求频率限制，请等待 ${error.retryAfterSeconds} 秒后手动重试"
        is CloudflareChallengeException -> "需要完成 Cloudflare 验证，请使用登录入口"
        is ForumValidationException -> error.errors.joinToString("；")
        is HttpStatusException -> "论坛拒绝操作（HTTP ${error.status}）"
        else -> error.message ?: "操作失败，正文已保留"
    }
    companion object { fun json(vararg pairs: Pair<String,Any?>) = JsonObject().apply { pairs.forEach { (key,value)->add(key,Gson().toJsonTree(value)) } } }
}
