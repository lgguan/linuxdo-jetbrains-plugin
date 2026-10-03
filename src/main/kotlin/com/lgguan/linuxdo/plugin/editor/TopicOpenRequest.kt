package com.lgguan.linuxdo.plugin.editor

enum class TopicOpenResult { SUCCESS, FAILURE, CANCELLED }

/** Single-use acknowledgement bound to a navigation request and account, never just an HTTP success. */
class TopicOpenRequest(val version: Long, val floor: Int?, private val callback: (TopicOpenResult) -> Unit) {
    val id: String = java.util.UUID.randomUUID().toString()
    private var completed = false
    @Synchronized fun finish(result: TopicOpenResult) {
        if (completed) return
        completed = true
        callback(result)
    }
    fun acknowledge(requestId: String, accountVersion: Long, displayedFloor: Int, shown: Boolean, success: Boolean) {
        if (requestId != id) return
        finish(when {
            version != accountVersion || !shown -> TopicOpenResult.CANCELLED
            !success || (floor != null && floor != displayedFloor) || displayedFloor < 1 -> TopicOpenResult.FAILURE
            else -> TopicOpenResult.SUCCESS
        })
    }
}
