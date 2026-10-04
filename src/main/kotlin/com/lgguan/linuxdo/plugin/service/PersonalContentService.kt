package com.lgguan.linuxdo.plugin.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import java.util.concurrent.CopyOnWriteArrayList

data class PersonalContentAccount(val forum: String, val id: Long, val username: String, val epoch: Long)

/** Application cache; a project stores only its own UI selection and viewport. No polling or disk storage. */
class PersonalContentService(
    private val account: () -> PersonalContentAccount? = {
        LinuxDoAuthService.getInstance().currentUser?.let { PersonalContentAccount(DiscourseApiClient.getBaseUrl(), it.id, it.username, SessionEpoch.current) }
    },
    private val fetch: (PersonalContentAccount, PersonalContentQuery) -> Result<PersonalContentPage> = { user, query ->
        DiscourseApiClient.getPersonalContent(query, user.username, user.epoch)
    },
    private val background: (() -> Unit) -> Unit = { work -> ApplicationManager.getApplication()?.executeOnPooledThread { work() } ?: work() },
    private val dispatch: (() -> Unit) -> Unit = { work -> ApplicationManager.getApplication()?.invokeLater { work() } ?: work() },
    initialize: Boolean = true
) : Disposable {
    private val lock = Any()
    private var owner: PersonalContentAccount? = null
    private val states = linkedMapOf<PersonalContentQuery, PersonalContentState>()
    private val flights = mutableMapOf<Pair<PersonalContentAccount, PersonalContentQuery>, Long>()
    private var requestSerial = 0L
    private var bookmarkRevision = 0L
    private val retainedViews = mutableMapOf<Disposable, PersonalContentQuery>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val lifetime = Disposer.newDisposable()
    private var disposed = false
    init { if(initialize) {
        LinuxDoAuthService.getInstance().addAuthListener(lifetime) { synchronized(lock) { ensureAccount() }; notifyChanged() }
        com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState.getInstance().addSettingsListener(lifetime) { synchronized(lock) { ensureAccount() }; notifyChanged() }
    } }
    private fun ensureAccount(): PersonalContentAccount? {
        val current = account()
        if(current != owner) { owner = current; states.clear(); flights.clear() }
        return current
    }
    @JvmOverloads fun state(kind: PersonalContentKind, searchTerm: String = ""): PersonalContentState = synchronized(lock) {
        ensureAccount(); states[PersonalContentQuery(kind, searchTerm = searchTerm)] ?: PersonalContentState()
    }
    fun addListener(owner: Disposable, listener: () -> Unit) {
        listeners.add(listener); Disposer.register(owner, Disposable { listeners.remove(listener) })
    }
    fun retainView(owner: Disposable, kind: PersonalContentKind, searchTerm: String) = synchronized(lock) {
        if(disposed) return@synchronized
        if(owner !in retainedViews) Disposer.register(owner, Disposable { synchronized(lock) { retainedViews.remove(owner) } })
        retainedViews[owner] = PersonalContentQuery(kind, searchTerm = searchTerm)
    }
    private fun notifyChanged() { dispatch { if(!disposed) { synchronized(lock) { ensureAccount() }; listeners.forEach { it() } } } }
    @JvmOverloads fun visit(kind: PersonalContentKind, searchTerm: String = "") { val state = state(kind, searchTerm); if((!state.loaded && state.error == null) || state.dirty) refresh(kind, searchTerm) }
    @JvmOverloads fun refresh(kind: PersonalContentKind, searchTerm: String = "") = request(PersonalContentQuery(kind, searchTerm = searchTerm))
    @JvmOverloads fun loadMore(kind: PersonalContentKind, searchTerm: String = "") { val state = state(kind, searchTerm); (state.failedQuery ?: state.next ?: if(!state.loaded) PersonalContentQuery(kind, searchTerm = searchTerm) else null)?.let(::request) }
    fun invalidate(kind: PersonalContentKind, epoch: Long = SessionEpoch.current) {
        synchronized(lock) { if(ensureAccount()?.epoch != epoch) return
            if(kind == PersonalContentKind.BOOKMARKS) invalidateBookmarks(null) else {
                val key = PersonalContentQuery(kind); states[key] = state(kind).copy(dirty = true)
            }
        }
        notifyChanged()
    }
    /** Drop old read generations before applying a confirmed bookmark delta. */
    fun bookmarkChanged(id: Long?, deleted: Boolean, epoch: Long) {
        synchronized(lock) { if(ensureAccount()?.epoch != epoch) return; invalidateBookmarks(id.takeIf { deleted }) }
        notifyChanged()
    }
    private fun invalidateBookmarks(deletedId: Long?) {
        bookmarkRevision++
        flights.keys.removeAll { it.second.kind == PersonalContentKind.BOOKMARKS }
        val keys = states.keys.filter { it.kind == PersonalContentKind.BOOKMARKS }.ifEmpty { listOf(PersonalContentQuery(PersonalContentKind.BOOKMARKS)) }
        keys.forEach { key -> val old = states[key] ?: PersonalContentState()
            states[key] = old.copy(items = old.items.filterNot { deletedId != null && it.bookmark?.id == deletedId },
                dirty = true, loading = false, failedQuery = null)
        }
    }
    internal fun draftSaved(key: String, draft: ForumDraft, epoch: Long) {
        synchronized(lock) {
            if(ensureAccount()?.epoch != epoch) return
            val old = state(PersonalContentKind.DRAFTS)
            states[PersonalContentQuery(PersonalContentKind.DRAFTS)] = old.copy(items = old.items.map {
                if(it.draftKey != key) it else it.copy(title = PersonalContentParser.text(PersonalContentParser.string(draft.data ?: com.google.gson.JsonObject(), "title")).ifBlank { it.title }, summary = PersonalContentParser.summary(draft.body))
            }, dirty = true)
        }; notifyChanged()
    }
    fun draftCleared(key: String, epoch: Long) {
        synchronized(lock) { if(ensureAccount()?.epoch != epoch) return
            val old = state(PersonalContentKind.DRAFTS); states[PersonalContentQuery(PersonalContentKind.DRAFTS)] = old.copy(items = old.items.filterNot { it.draftKey == key }, dirty = true)
        }; notifyChanged()
    }
    private fun request(query: PersonalContentQuery) {
        val key = query.copy(offset = 0)
        var ticket = 0L
        var revision = 0L
        val user = synchronized(lock) {
            if(disposed) return
            val user = ensureAccount() ?: return
            if(state(query.kind, query.searchTerm).loading || flights.containsKey(user to query)) return
            ticket = ++requestSerial; revision = bookmarkRevision; flights[user to query] = ticket
            states[key] = state(query.kind, query.searchTerm).copy(loading = true, error = null, dirty = false)
            // Keep a bounded set of recent searches; unfiltered tabs are always retained.
            states.keys.filter { it.searchTerm.isNotEmpty() && it != key && it !in retainedViews.values && states[it]?.loading != true }
                .dropLast(7).forEach(states::remove)
            user
        }
        notifyChanged()
        background {
            val result = runCatching {
                check(account() == user) { "账号已切换" }
                fetch(user, query).getOrThrow()
            }
            synchronized(lock) {
                if(flights[user to query] != ticket) return@synchronized
                flights.remove(user to query)
                if(disposed || ensureAccount() != user) return@synchronized
                if(query.kind == PersonalContentKind.BOOKMARKS && revision != bookmarkRevision) return@synchronized
                val old = state(query.kind, query.searchTerm)
                states[key] = result.fold({ page ->
                    val incoming = page.items.associateBy { it.key }
                    val keys = old.items.map { it.key }.toSet()
                    val merged = if(query.kind == PersonalContentKind.BOOKMARKS && query.offset == 0) page.items
                        else if(query.offset == 0) page.items.filterNot { it.key in keys } + old.items.map { incoming[it.key] ?: it }
                        else old.items.map { incoming[it.key] ?: it } + page.items.filterNot { it.key in keys }
                    // A completely shifted first page restarts the continuation to fill the gap.
                    val continuation = if(query.kind != PersonalContentKind.BOOKMARKS && query.offset == 0 && old.loaded && page.items.any { it.key in keys }) old.next else page.next
                    old.copy(items = merged, next = continuation, loaded = true, loading = false, error = null, failedQuery = null, warning = page.warning)
                }, { error -> old.copy(loading = false, error = when(error) {
                    is CloudflareChallengeException -> "需要 Cloudflare 验证，请通过账号按钮验证后重试"
                    is RateLimitException -> "HTTP 429：读取冷却中，请稍后手动重试"
                    is HttpStatusException -> "HTTP ${error.status}：个人接口不可用或访问受限，请在网页核对"
                    else -> "读取失败（${error.javaClass.simpleName}），可手动重试或在网页核对"
                }, failedQuery = query) })
            }
            notifyChanged()
        }
    }
    override fun dispose() { synchronized(lock) { disposed = true; states.clear(); flights.clear(); retainedViews.clear() }; listeners.clear(); Disposer.dispose(lifetime) }
    companion object {
        fun getInstance(): PersonalContentService = ApplicationManager.getApplication()?.getService(PersonalContentService::class.java) ?: Holder.instance
        private object Holder { val instance = PersonalContentService() }
    }
}
