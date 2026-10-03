package com.lgguan.linuxdo.plugin.service

internal data class ReadingBatch(val topicTimeMs: Long = 0, val timings: Map<Int, Long> = emptyMap()) {
    fun isEmpty() = topicTimeMs == 0L && timings.isEmpty()
}

/** Consecutive foreground samples; each visible body owns its dwell time. */
internal class ReadingClock(private val now: () -> Long = System::nanoTime) {
    private var last = now()
    private var lastFlush = last
    private var lastScrolled = last
    private var visible = emptySet<Int>()
    private var wasForeground = false
    private var topicTime = 0L
    private var rush = false
    private val seen = mutableSetOf<Int>()
    private val total = mutableMapOf<Int, Long>()
    private val pending = mutableMapOf<Int, Long>()

    fun sample(floors: Set<Int>, foreground: Boolean, scrolled: Boolean = false, bodyVisible: Boolean = floors.isNotEmpty()): Map<Int, Long> {
        val time = now()
        val elapsed = (time - last) / 1_000_000
        last = time
        val active = foreground && bodyVisible
        if (scrolled || active && !wasForeground) lastScrolled = time
        val current = if (active) floors.filter { it > 0 }.toSet() else emptySet()
        val stable = current.intersect(visible)
        visible = current
        val continuous = active && wasForeground
        wasForeground = active
        // Background, sleep, delayed rendering and the first resumed sample give no credit.
        if (!continuous || elapsed !in 1..2500 || time - lastScrolled > 180_000_000_000L) return emptyMap()
        topicTime += elapsed
        val sample = stable.mapNotNull { floor ->
            val ms = elapsed.coerceAtMost(360_000L - (total[floor] ?: 0))
            if (ms <= 0) null else floor to ms
        }.toMap()
        sample.forEach { (floor, ms) ->
            total.merge(floor, ms, Long::plus); pending.merge(floor, ms, Long::plus)
            if (seen.add(floor)) rush = true
        }
        return sample
    }

    fun due(): Boolean = rush || now() - lastFlush >= 60_000_000_000L
    fun drain(): ReadingBatch = ReadingBatch(topicTime, pending.toMap()).also {
        pending.clear(); topicTime = 0; rush = false; lastFlush = now()
    }
}
