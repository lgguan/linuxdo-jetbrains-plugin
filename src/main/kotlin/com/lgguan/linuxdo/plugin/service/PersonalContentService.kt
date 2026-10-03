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
    private val states = mutableMapOf<PersonalContentKind, PersonalContentState>()
    private val flights = mutableSetOf<Pair<PersonalContentAccount, PersonalContentQuery>>()
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
    fun state(kind: PersonalContentKind): PersonalContentState = synchronized(lock) { ensureAccount(); states[kind] ?: PersonalContentState() }
    fun addListener(owner: Disposable, listener: () -> Unit) {
        listeners.add(listener); Disposer.register(owner, Disposable { listeners.remove(listener) })
    }
    private fun notifyChanged() { dispatch { if(!disposed) { synchronized(lock) { ensureAccount() }; listeners.forEach { it() } } } }
    fun visit(kind: PersonalContentKind) { val state = state(kind); if((!state.loaded && state.error == null) || state.dirty) refresh(kind) }
    fun refresh(kind: PersonalContentKind) = request(PersonalContentQuery(kind))
    fun loadMore(kind: PersonalContentKind) { val state = state(kind); (state.failedQuery ?: state.next ?: if(!state.loaded) PersonalContentQuery(kind) else null)?.let(::request) }
    fun invalidate(kind: PersonalContentKind, epoch: Long = SessionEpoch.current) {
        synchronized(lock) { if(ensureAccount()?.epoch == epoch) states[kind] = state(kind).copy(dirty = true) }
        notifyChanged()
    }
    internal fun draftSaved(key: String, draft: ForumDraft, epoch: Long) {
        synchronized(lock) {
            if(ensureAccount()?.epoch != epoch) return
            val old = state(PersonalContentKind.DRAFTS)
            states[PersonalContentKind.DRAFTS] = old.copy(items = old.items.map {
                if(it.draftKey != key) it else it.copy(title = PersonalContentParser.text(PersonalContentParser.string(draft.data ?: com.google.gson.JsonObject(), "title")).ifBlank { it.title }, summary = PersonalContentParser.summary(draft.body))
            }, dirty = true)
        }; notifyChanged()
    }
    fun draftCleared(key: String, epoch: Long) {
        synchronized(lock) { if(ensureAccount()?.epoch != epoch) return
            val old = state(PersonalContentKind.DRAFTS); states[PersonalContentKind.DRAFTS] = old.copy(items = old.items.filterNot { it.draftKey == key }, dirty = true)
        }; notifyChanged()
    }
    private fun request(query: PersonalContentQuery) {
        val user = synchronized(lock) {
            if(disposed) return
            val user = ensureAccount() ?: return
            if(state(query.kind).loading || !flights.add(user to query)) return
            states[query.kind] = state(query.kind).copy(loading = true, error = null, dirty = false)
            user
        }
        notifyChanged()
        background {
            val result = runCatching {
                check(account() == user) { "账号已切换" }
                fetch(user, query).getOrThrow()
            }
            synchronized(lock) {
                flights.remove(user to query)
                if(disposed || ensureAccount() != user) return@synchronized
                val old = state(query.kind)
                states[query.kind] = result.fold({ page ->
                    val incoming = page.items.associateBy { it.key }
                    val keys = old.items.map { it.key }.toSet()
                    val merged = if(query.offset == 0) page.items.filterNot { it.key in keys } + old.items.map { incoming[it.key] ?: it }
                        else old.items.map { incoming[it.key] ?: it } + page.items.filterNot { it.key in keys }
                    // A completely shifted first page restarts the continuation to fill the gap.
                    val continuation = if(query.offset == 0 && old.loaded && page.items.any { it.key in keys }) old.next else page.next
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
    override fun dispose() { synchronized(lock) { disposed = true; states.clear(); flights.clear() }; listeners.clear(); Disposer.dispose(lifetime) }
    companion object {
        fun getInstance(): PersonalContentService = ApplicationManager.getApplication()?.getService(PersonalContentService::class.java) ?: Holder.instance
        private object Holder { val instance = PersonalContentService() }
    }
}
