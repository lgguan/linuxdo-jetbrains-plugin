package com.lgguan.linuxdo.plugin.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Topic
import com.lgguan.linuxdo.plugin.net.SessionEpoch

@State(name = "com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService", storages = [Storage("LinuxDoReadTracking.xml")])
class LinuxDoReadTrackingService(
    private val account: () -> String = { LinuxDoAuthService.getInstance().currentUser?.id?.toString() ?: "guest" }
) : PersistentStateComponent<LinuxDoReadTrackingService.State>, com.intellij.openapi.Disposable {
    class State {
        // Legacy unscoped data cannot safely be assigned to any account.
        var readTopicIds: MutableSet<Long> = mutableSetOf()
        var lastReadPostNumbers: MutableMap<String, Int> = mutableMapOf()
        var readFloorSets: MutableMap<String, String> = mutableMapOf()
        var positions: MutableMap<String, Int> = mutableMapOf()
        var floors: MutableMap<String, String> = mutableMapOf()
        var pendingReadBatches: MutableList<PendingReadBatch> = mutableListOf()
    }
    private var data = State()
    private val queue = ReadSyncQueue()
    private val tasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()
    private val pumping = java.util.concurrent.atomic.AtomicBoolean()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var disposed = false
    private var scheduled: java.util.concurrent.ScheduledFuture<*>? = null
    private var authListener: ((com.lgguan.linuxdo.plugin.model.UserInfo?) -> Unit)? = null
    internal fun identity() = ReadIdentity(LinuxDoSettingsState.getInstance().baseUrl.trimEnd('/'), account())
    init {
        // Defer obtaining AuthService until this persistent component has been constructed.
        ApplicationManager.getApplication()?.invokeLater {
            if (!disposed) {
                val listener: (com.lgguan.linuxdo.plugin.model.UserInfo?) -> Unit = { user ->
                    if (user != null) { queue.resume(identity()); pump() }
                }
                authListener = listener
                LinuxDoAuthService.getInstance().addAuthListener(listener)
                if (LinuxDoAuthService.getInstance().isLoggedIn) queue.resume(identity())
                scheduled = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay({ pump() }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
    private fun key(topicId: Long) = "${account()}:$topicId"
    @Synchronized override fun getState(): State = State().also {
        it.positions.putAll(data.positions)
        it.floors.putAll(data.floors)
        it.pendingReadBatches = queue.snapshot()
    }
    @Synchronized override fun loadState(state: State) {
        data = State().also { it.positions.putAll(state.positions); it.floors.putAll(state.floors) }
        queue.restore(state.pendingReadBatches)
    }
    private fun floors(topicId: Long): Set<Int> = data.floors[key(topicId)].orEmpty().split(',').mapNotNull(String::toIntOrNull).toSet()
    @Synchronized fun isRead(topicId: Long): Boolean = key(topicId) in data.positions
    @Synchronized fun isTopicRead(topic: Topic): Boolean {
        if (topic.highestPostNumber <= 0) return false
        val server = (topic.lastReadPostNumber ?: 0).coerceAtLeast(0)
        if (topic.unreadPosts == 0 && server >= topic.highestPostNumber) return true
        val read = floors(topic.id)
        return (server + 1..topic.highestPostNumber).all { it in read } &&
            (server < topic.highestPostNumber || topic.unreadPosts == 0)
    }
    /** Visit/scroll position is separate from evidence that a floor was actually viewed. */
    @Synchronized fun markReadLocally(topicId: Long, postNumber: Int = 1) {
        if (postNumber > 0) data.positions[key(topicId)] = postNumber
    }
    @Synchronized fun markFloorRead(topicId: Long, postNumber: Int) {
        if (postNumber <= 0) return
        markReadLocally(topicId, postNumber)
        data.floors[key(topicId)] = (floors(topicId) + postNumber).sorted().joinToString(",")
    }
    @Synchronized fun markAllRead(topicId: Long, highestPostNumber: Int) {
        val highest = highestPostNumber.coerceIn(1, 100000)
        markReadLocally(topicId, highest)
        data.floors[key(topicId)] = (1..highest).joinToString(",")
    }
    @Synchronized fun isPostRead(topicId: Long, postNumber: Int, serverPostRead: Boolean?, topicServerLastRead: Int?): Boolean {
        if (postNumber in floors(topicId)) return true
        if (serverPostRead != null) return serverPostRead
        if (topicServerLastRead != null) return postNumber <= topicServerLastRead
        return false
    }
    @Synchronized fun getLastReadPostNumber(topicId: Long): Int? = data.positions[key(topicId)]

    internal fun submitTimings(topicId: Long, batch: ReadingBatch, identity: ReadIdentity) {
        if (batch.isEmpty() || identity.accountId == "guest") return
        // The reader captured this identity before sampling; closing an old account's page
        // still preserves its final evidence, without assigning it to the newly signed-in user.
        queue.enqueue(identity, topicId, batch)
        pump()
    }
    fun syncStatus(topicId: Long): String? = queue.status(identity(), topicId)?.let {
        it + if (LinuxDoSettingsState.getInstance().autoReportReadTimings) "" else " · 自动同步已关闭"
    }
    fun retrySync() { queue.resume(identity()); pump() }
    fun addSyncListener(owner: com.intellij.openapi.Disposable, changed: () -> Unit) {
        listeners.add(changed)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { listeners.remove(changed) })
    }
    private fun pump() {
        if (disposed || !LinuxDoSettingsState.getInstance().autoReportReadTimings ||
            !LinuxDoAuthService.getInstance().isLoggedIn || !pumping.compareAndSet(false, true)) return
        tasks.submit {
            try {
                val version = SessionEpoch.current
                val account = identity()
                if (!LinuxDoSettingsState.getInstance().autoReportReadTimings) return@submit
                val batch = queue.take(account) ?: return@submit
                val result = runCatching {
                    SessionEpoch.requireCurrent(version)
                    check(account == identity())
                    if (!LinuxDoSettingsState.getInstance().autoReportReadTimings) throw com.lgguan.linuxdo.plugin.net.StaleSessionException()
                    DiscourseApiClient.reportTimings(batch.topicId, batch.topicTimeMs, batch.timings, version).getOrThrow()
                }
                queue.complete(batch.id, result)
                if (result.getOrNull() == true) ApplicationManager.getApplication()?.invokeLater {
                    if (!disposed && version == SessionEpoch.current && account == identity()) listeners.forEach { it() }
                }
            } finally {
                pumping.set(false)
            }
        }
    }
    override fun dispose() {
        disposed = true; scheduled?.cancel(false); tasks.dispose(); listeners.clear()
        authListener?.let { LinuxDoAuthService.getInstance().removeAuthListener(it) }
    }
    companion object {
        private val fallback by lazy { LinuxDoReadTrackingService() }
        fun getInstance(): LinuxDoReadTrackingService = ApplicationManager.getApplication()
            ?.getService(LinuxDoReadTrackingService::class.java) ?: fallback
    }
}
