package com.lgguan.linuxdo.plugin.service

import com.google.gson.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

internal data class BookmarkEditState(val bookmark: BookmarkMetadata, val post: Post, val policy: Int)
internal data class BookmarkChange(val epoch: Long, val id: Long?, val postId: Long?, val post: Post?, val deleted: Boolean)
internal class BookmarkWebRequiredException : IllegalStateException("该书签需要在网页编辑，以保留原有设置")

/** One mutation path for personal lists and reader windows. Never retries a write. */
internal class BookmarkService(
    private val transport: TopicOperationTransport = ForumOperationTransport,
    private val lookup: (Post, Long) -> BookmarkMetadata? = ::lookupBookmark,
    private val session: (Long) -> Unit = SessionEpoch::requireCurrent,
    private val writable: (Long) -> Unit = { version ->
        check(LinuxDoAuthService.getInstance().isLoggedIn) { "请先登录" }
        check(!ReaderRules.parse(DiscourseApiClient.readerBootstrap(version).getOrThrow(), JsonObject()).readOnly) { "论坛处于只读状态" }
    },
    private val gate: ReaderWriteGate = ReaderWriteGate.shared,
    private val changed: (BookmarkChange) -> Unit = { event ->
        PersonalContentService.getInstance().bookmarkChanged(event.id, event.deleted, event.epoch)
    },
    private val dispatch: (() -> Unit) -> Unit = { work -> ApplicationManager.getApplication()?.invokeLater { work() } ?: work() }
) : Disposable {
    private val listeners = CopyOnWriteArrayList<(BookmarkChange) -> Unit>()
    private data class Desired(val bookmark: BookmarkMetadata, val name: String, val reminder: String?, val policy: Int?, val delete: Boolean)
    private val uncertain = mutableMapOf<Triple<Long, String, Long>, Desired>()
    @Volatile private var disposed = false
    fun addListener(owner: Disposable, listener: (BookmarkChange) -> Unit) {
        listeners.add(listener); Disposer.register(owner, Disposable { listeners.remove(listener) })
    }
    private fun publish(event: BookmarkChange) {
        changed(event)
        dispatch { if(!disposed) listeners.forEach { it(event) } }
    }
    fun read(bookmark: BookmarkMetadata, version: Long): BookmarkEditState {
        session(version)
        if(!bookmark.editable) throw BookmarkWebRequiredException()
        val post = transport.post(requireNotNull(bookmark.targetId), version).getOrThrow()
        session(version)
        require(post.bookmarked == true && post.bookmarkId == bookmark.id) { "书签已变化，请刷新列表" }
        val fresh = lookup(post, version) ?: throw BookmarkWebRequiredException()
        if(!fresh.editable || fresh.id != bookmark.id || fresh.targetId != post.id) throw BookmarkWebRequiredException()
        val policy = post.bookmarkAutoDeletePreference?.takeIf { it in 0..3 } ?: throw BookmarkWebRequiredException()
        return BookmarkEditState(fresh.copy(name = post.bookmarkName.orEmpty(), reminderAt = post.bookmarkReminderAt), post, policy)
    }
    fun save(baseline: BookmarkEditState, name: String, reminder: String?, version: Long, active: () -> Boolean): OperationResult =
        perform(baseline.bookmark, baseline.post, name, reminder, false, version, active, baseline)
    fun delete(bookmark: BookmarkMetadata, version: Long, active: () -> Boolean): OperationResult =
        perform(bookmark, null, "", null, true, version, active)
    fun reader(post: Post, name: String, reminder: String?, delete: Boolean, version: Long, active: () -> Boolean): OperationResult {
        val bookmark = post.bookmarkId?.let { BookmarkMetadata(it, "Post", post.id, post.bookmarkName.orEmpty(), post.bookmarkReminderAt) }
        return perform(bookmark, post, name, reminder, delete, version, active)
    }
    fun reconcilePost(post: Post, version: Long): OperationResult = reconcile(
        BookmarkMetadata(post.bookmarkId ?: 0, "Post", post.id, post.bookmarkName.orEmpty(), post.bookmarkReminderAt), version)
    private fun perform(bookmark: BookmarkMetadata?, initial: Post?, name: String, reminder: String?, delete: Boolean,
        version: Long, active: () -> Boolean, baseline: BookmarkEditState? = null): OperationResult = gate.serialized {
        var observed = initial
        try {
            check(!disposed); session(version); check(active()) { "页面已关闭或切换" }
            val target = bookmark?.targetId ?: initial?.id ?: error("无法确认书签目标")
            val identity = Triple(version, bookmark?.targetType ?: "Post", target)
            check(identity !in uncertain) { "上次书签操作结果未确认，请先核对服务器" }
            uncertain.keys.removeAll { it.first != version }
            if(delete) require(bookmark?.deletable == true) { "请在网页管理该类型书签" }
            val before = if(bookmark?.targetType == "Topic") null else transport.post(target, version).getOrThrow().also { observed = it }
            require(before == null || before.id == target) { "书签目标不匹配" }
            session(version)
            if(bookmark != null && before != null) require(before.bookmarkId == bookmark.id) { "书签已变化，请刷新列表" }
            var policy: Int? = null
            var freshBookmark = bookmark
            if(!delete && bookmark != null) {
                val fresh = read(bookmark, version); observed = fresh.post; policy = fresh.policy; freshBookmark = fresh.bookmark
                if(baseline != null) require(fresh.bookmark.name == baseline.bookmark.name &&
                    sameTime(fresh.bookmark.reminderAt, baseline.bookmark.reminderAt) && fresh.policy == baseline.policy) {
                    "书签已在其他窗口修改；输入已保留，请核对服务器后重新编辑"
                }
            }
            if(!delete && bookmark == null) require(before?.bookmarked == false && before.bookmarkId == null) { "书签已创建，请重新读取" }
            validate(name, reminder, freshBookmark?.reminderAt)
            writable(version)
            val desired = Desired(freshBookmark ?: BookmarkMetadata(0, "Post", target), name, reminder, policy, delete)
            val request = if(delete) OperationRequest("/bookmarks/${requireNotNull(bookmark).id}.json", "DELETE", JsonObject())
                else OperationRequest(bookmark?.id?.let { "/bookmarks/$it.json" } ?: "/bookmarks.json", if(bookmark == null) "POST" else "PUT",
                    JsonObject().apply {
                        addProperty("bookmarkable_id", target); addProperty("bookmarkable_type", "Post"); addProperty("name", name)
                        add("reminder_at", reminder?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                        policy?.let { addProperty("auto_delete_preference", it) }
                    })
            gate.beforeWrite(); session(version); check(active()) { "页面已关闭或切换" }
            val response = transport.write(request, version); gate.failure(response.exceptionOrNull()); session(version)
            val after = before?.let { transport.post(target, version).getOrNull() }; session(version)
            val failure = response.exceptionOrNull()
            if(failure != null && (TopicOperationService.definite(failure) || !matches(desired, after))) {
                val error = if(TopicOperationService.definite(failure)) failure else UnconfirmedOperationException(failure).also { uncertain[identity] = desired }
                return@serialized OperationResult(after ?: observed, response.getOrNull(), error)
            }
            if(after != null && !matches(desired, after)) {
                uncertain[identity] = desired
                return@serialized OperationResult(after, response.getOrNull(), UnconfirmedOperationException())
            }
            val id = bookmark?.id ?: after?.bookmarkId ?: response.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asLong
            if(!delete && id == null) {
                uncertain[identity] = desired
                return@serialized OperationResult(after ?: observed, response.getOrNull(), UnconfirmedOperationException())
            }
            val snapshot = after ?: before?.copy(bookmarked = !delete, bookmarkId = if(delete) null else id,
                bookmarkName = if(delete) null else name, bookmarkReminderAt = if(delete) null else reminder,
                bookmarkAutoDeletePreference = if(delete) null else policy)
            publish(BookmarkChange(version, id, before?.id, snapshot, delete))
            OperationResult(snapshot, response.getOrNull(), null)
        } catch(error: Throwable) { OperationResult(observed, null, error) }
    }
    /** Explicit read-only reconciliation of an ambiguous write, shared across all windows. */
    fun reconcile(bookmark: BookmarkMetadata, version: Long): OperationResult = gate.serialized {
        try {
            session(version)
            val target = requireNotNull(bookmark.targetId)
            val identity = Triple(version, bookmark.targetType, target)
            val desired = uncertain[identity] ?: return@serialized OperationResult(
                if(bookmark.targetType == "Post") transport.post(target, version).getOrThrow() else null, null, null)
            val post = if(bookmark.targetType == "Post") transport.post(target, version).getOrThrow() else null
            session(version)
            val confirmed = if(desired.delete && bookmark.targetType == "Topic") topicDeletionConfirmed(desired.bookmark, version) else matches(desired, post)
            if(!confirmed) return@serialized OperationResult(post, null, UnconfirmedOperationException())
            uncertain.remove(identity)
            publish(BookmarkChange(version, post?.bookmarkId ?: desired.bookmark.id, post?.id, post, desired.delete))
            OperationResult(post, null, null)
        } catch(error: Throwable) { OperationResult(null, null, error) }
    }
    override fun dispose() { disposed = true; listeners.clear(); gate.serialized { uncertain.clear() } }
    companion object {
        fun getInstance(): BookmarkService = ApplicationManager.getApplication()?.getService(BookmarkService::class.java) ?: Holder.instance
        private object Holder { val instance = BookmarkService() }
        private fun lookupBookmark(post: Post, version: Long): BookmarkMetadata? {
            val username = LinuxDoAuthService.getInstance().currentUser?.username ?: error("请先登录")
            var query: PersonalContentQuery? = PersonalContentQuery(PersonalContentKind.BOOKMARKS, searchTerm = post.bookmarkName.orEmpty())
            // Bounded fallback for reader bookmarks outside the first page; never guesses pinned state.
            repeat(10) {
                val current = query ?: return null
                SessionEpoch.requireCurrent(version)
                val page = DiscourseApiClient.getPersonalContent(current, username, version).getOrThrow()
                page.items.firstOrNull { it.bookmark?.id == post.bookmarkId }?.bookmark?.let { return it }
                query = page.next
            }
            return null
        }
        private fun topicDeletionConfirmed(bookmark: BookmarkMetadata, version: Long): Boolean {
            val username = LinuxDoAuthService.getInstance().currentUser?.username ?: error("请先登录")
            var query: PersonalContentQuery? = PersonalContentQuery(PersonalContentKind.BOOKMARKS)
            repeat(10) {
                val current = query ?: return true
                SessionEpoch.requireCurrent(version)
                val page = DiscourseApiClient.getPersonalContent(current, username, version).getOrThrow()
                if(page.warning != null || page.items.size != page.rawCount || page.items.any { it.bookmark?.id == bookmark.id }) return false
                query = page.next
            }
            return query == null
        }
        fun sameTime(a: String?, b: String?): Boolean = runCatching {
            a?.takeIf(String::isNotBlank)?.let(Instant::parse) == b?.takeIf(String::isNotBlank)?.let(Instant::parse)
        }.getOrDefault(false)
        fun validate(name: String, reminder: String?, original: String? = null) {
            require(name.length <= 100) { "书签名称不能超过 100 个字符" }
            if(!reminder.isNullOrBlank() && !sameTime(reminder, original)) {
                val instant = Instant.parse(reminder); val now = Instant.now()
                require(instant.isAfter(now) && instant.isBefore(now.plusSeconds(10L * 366 * 86400))) { "提醒时间必须在未来十年内" }
            }
        }
        private fun matches(desired: Desired, post: Post?): Boolean = post != null && if(desired.delete)
            post.bookmarked == false || post.bookmarked == true && post.bookmarkId != null && post.bookmarkId != desired.bookmark.id
            else post.bookmarked == true && post.bookmarkId != null && post.bookmarkName.orEmpty() == desired.name && sameTime(post.bookmarkReminderAt, desired.reminder) &&
                (desired.bookmark.id == 0L || post.bookmarkId == desired.bookmark.id) && (desired.policy == null || post.bookmarkAutoDeletePreference == desired.policy)
    }
}
