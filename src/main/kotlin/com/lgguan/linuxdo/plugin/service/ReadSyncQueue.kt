package com.lgguan.linuxdo.plugin.service

import com.lgguan.linuxdo.plugin.net.*
import java.util.UUID

/** XML beans contain timing evidence only, never post bodies or credentials. */
class PendingReadBatch {
    var id: String = UUID.randomUUID().toString()
    var site: String = ""
    var accountId: String = ""
    var topicId: Long = 0
    var topicTimeMs: Long = 0
    var timings: MutableMap<Int, Long> = mutableMapOf()
    var failures: Int = 0
    var retryAt: Long = 0
    var pauseReason: String = ""
    var rateLimitedUntil: Long = 0
    fun copy() = PendingReadBatch().also {
        it.id = id; it.site = site; it.accountId = accountId; it.topicId = topicId
        it.topicTimeMs = topicTimeMs; it.timings.putAll(timings)
        it.failures = failures; it.retryAt = retryAt; it.pauseReason = pauseReason
        it.rateLimitedUntil = rateLimitedUntil
    }
}

internal data class ReadIdentity(val site: String, val accountId: String)

/** An in-flight snapshot is immutable to new samples. */
internal class ReadSyncQueue(private val now: () -> Long = System::currentTimeMillis) {
    private val batches = mutableListOf<PendingReadBatch>()
    private var inFlight: String? = null
    private var nextRequest = 0L
    @Synchronized fun snapshot() = batches.map { it.copy() }.toMutableList()
    @Synchronized fun restore(saved: List<PendingReadBatch>) {
        batches.clear(); inFlight = null
        batches.addAll(saved.filter { it.id.isNotBlank() && it.topicId > 0 && it.accountId != "guest" && it.accountId.isNotBlank() &&
            it.topicTimeMs in 0..60_000 && it.timings.all { (floor, ms) -> floor > 0 && ms in 1..60_000 } }.map { it.copy() })
    }
    @Synchronized fun enqueue(identity: ReadIdentity, topic: Long, batch: ReadingBatch) {
        if (topic <= 0 || batch.isEmpty() || identity.accountId == "guest") return
        var topicRemaining = batch.topicTimeMs.coerceAtLeast(0)
        val accountPause = batches.firstOrNull { it.site == identity.site && it.accountId == identity.accountId && it.pauseReason.isNotEmpty() }?.pauseReason.orEmpty()
        val remaining = batch.timings.filter { it.key > 0 && it.value > 0 }.toMutableMap()
        while (topicRemaining > 0 || remaining.isNotEmpty()) {
            val target = batches.lastOrNull { it.id != inFlight && it.site == identity.site && it.accountId == identity.accountId && it.topicId == topic &&
                it.failures == 0 && it.topicTimeMs < 60_000 &&
                remaining.keys.all { floor -> (it.timings[floor] ?: 0) < 60_000 } }
                ?: PendingReadBatch().also { it.site = identity.site; it.accountId = identity.accountId; it.topicId = topic; it.pauseReason = accountPause; batches.add(it) }
            val topicPart = topicRemaining.coerceAtMost(60_000 - target.topicTimeMs)
            target.topicTimeMs += topicPart; topicRemaining -= topicPart
            remaining.toMap().forEach { (floor, ms) ->
                val part = ms.coerceAtMost(60_000 - (target.timings[floor] ?: 0))
                target.timings.merge(floor, part, Long::plus)
                if (part == ms) remaining.remove(floor) else remaining[floor] = ms - part
            }
        }
    }
    @Synchronized fun take(identity: ReadIdentity): PendingReadBatch? {
        if (inFlight != null || now() < nextRequest) return null
        if (batches.any { it.site == identity.site && it.accountId == identity.accountId && it.pauseReason.isNotEmpty() }) return null
        if (batches.any { it.site == identity.site && it.accountId == identity.accountId && it.rateLimitedUntil > now() }) return null
        val batch = batches.firstOrNull { it.site == identity.site && it.accountId == identity.accountId && it.pauseReason.isEmpty() } ?: return null
        if (now() < batch.retryAt) return null
        inFlight = batch.id; nextRequest = now() + 5000
        return batch.copy()
    }
    @Synchronized fun complete(id: String, result: Result<Boolean>) {
        if (inFlight != id) return
        inFlight = null
        val batch = batches.firstOrNull { it.id == id } ?: return
        if (result.getOrNull() == true) { batches.remove(batch); return }
        val error = result.exceptionOrNull()
        batch.pauseReason = when (error) {
            is CloudflareChallengeException -> "需要完成 Cloudflare 验证"
            is StaleSessionException -> "等待对应账号登录"
            is CsrfRejectedException -> "需要重新验证登录"
            is HttpStatusException -> if (error.status !in setOf(408, 405, 500, 501, 502, 503, 504)) "论坛拒绝同步（HTTP ${error.status}）" else ""
            is ForumValidationException -> "论坛拒绝同步（HTTP ${error.status}）"
            else -> ""
        }
        if (batch.pauseReason.isNotEmpty()) batches.filter { it.site == batch.site && it.accountId == batch.accountId }
            .forEach { it.pauseReason = batch.pauseReason }
        val delay = if (error is RateLimitException) error.retryAfterSeconds * 1000
            else listOf(5000L, 10000L, 20000L, 40000L)[batch.failures.coerceAtMost(3)]
        batch.failures = (batch.failures + 1).coerceAtMost(4)
        batch.retryAt = now() + delay
        batch.rateLimitedUntil = if (error is RateLimitException) batch.retryAt else 0
    }
    @Synchronized fun resume(identity: ReadIdentity) {
        batches.filter { it.site == identity.site && it.accountId == identity.accountId }.forEach { it.pauseReason = "" }
    }
    @Synchronized fun status(identity: ReadIdentity, topic: Long): String? {
        val pending = batches.filter { it.site == identity.site && it.accountId == identity.accountId && it.topicId == topic }
        if (pending.isEmpty()) return null
        val reason = pending.firstNotNullOfOrNull { it.pauseReason.takeIf(String::isNotBlank) }
        return "待同步 ${pending.size} 批" + (reason?.let { " · $it" } ?: pending.firstOrNull { it.failures > 0 }?.let {
            " · ${((it.retryAt - now()).coerceAtLeast(0) + 999) / 1000} 秒后重试"
        }.orEmpty())
    }
}
