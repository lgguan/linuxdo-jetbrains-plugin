package com.lgguan.linuxdo.plugin.net

/** Share server-directed read cooldown across topic tabs and background readers. */
internal class ForumReadCooldown(private val now: () -> Long = System::currentTimeMillis) {
    private var until = 0L

    @Synchronized
    fun remainingSeconds(): Long = ((until - now() + 999) / 1000).coerceAtLeast(0)

    @Synchronized
    private fun record(seconds: Long) {
        until = maxOf(until, now() + seconds.coerceIn(1, 86400) * 1000)
    }

    fun <T> read(action: () -> Result<T>): Result<T> {
        val remaining = remainingSeconds()
        if (remaining > 0) return Result.failure(RateLimitException(remaining))
        val result = action()
        (result.exceptionOrNull() as? RateLimitException)?.let { record(it.retryAfterSeconds) }
        return result
    }
}
