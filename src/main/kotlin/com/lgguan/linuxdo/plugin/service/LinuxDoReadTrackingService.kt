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
) : PersistentStateComponent<LinuxDoReadTrackingService.State> {
    class State {
        // Legacy unscoped data cannot safely be assigned to any account.
        var readTopicIds: MutableSet<Long> = mutableSetOf()
        var lastReadPostNumbers: MutableMap<String, Int> = mutableMapOf()
        var readFloorSets: MutableMap<String, String> = mutableMapOf()
        var positions: MutableMap<String, Int> = mutableMapOf()
        var floors: MutableMap<String, String> = mutableMapOf()
    }
    private var data = State()
    private fun key(topicId: Long) = "${account()}:$topicId"
    @Synchronized override fun getState(): State = State().also {
        it.positions.putAll(data.positions)
        it.floors.putAll(data.floors)
    }
    @Synchronized override fun loadState(state: State) {
        data = State().also { it.positions.putAll(state.positions); it.floors.putAll(state.floors) }
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

    fun submitTimings(topicId: Long, timings: Map<Int, Long>, version: Long) {
        if (timings.isEmpty() || !LinuxDoSettingsState.getInstance().autoReportReadTimings ||
            !LinuxDoAuthService.getInstance().isLoggedIn || version != SessionEpoch.current) return
        ApplicationManager.getApplication()?.executeOnPooledThread {
            if (version == SessionEpoch.current && LinuxDoSettingsState.getInstance().autoReportReadTimings) {
                DiscourseApiClient.reportTimings(topicId, timings.values.sum(), timings, version)
            }
        }
    }
    companion object {
        private val fallback by lazy { LinuxDoReadTrackingService() }
        fun getInstance(): LinuxDoReadTrackingService = ApplicationManager.getApplication()
            ?.getService(LinuxDoReadTrackingService::class.java) ?: fallback
    }
}
