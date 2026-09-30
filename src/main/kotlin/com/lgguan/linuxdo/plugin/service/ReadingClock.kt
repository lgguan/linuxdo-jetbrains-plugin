package com.lgguan.linuxdo.plugin.service

/** Monotonic dwell samples, shared across visible floors without multiplying elapsed time. */
internal class ReadingClock(private val now: () -> Long = System::nanoTime) {
    private var last = now()
    private var lastFlush = last
    private var visible = emptySet<Int>()
    private val pending = mutableMapOf<Int, Long>()

    fun sample(floors: Set<Int>, foreground: Boolean): Map<Int, Long> {
        val time = now()
        val elapsed = (time - last) / 1_000_000
        last = time
        val current = if (foreground) floors.filter { it > 0 }.toSet() else emptySet()
        val stable = current.intersect(visible)
        visible = current
        // A suspended renderer or blocked EDT must not count as reading time.
        if (elapsed !in 1..2500 || stable.isEmpty()) return emptyMap()
        val sample = stable.associateWith { elapsed / stable.size }
        sample.forEach { (floor, ms) -> pending.merge(floor, ms, Long::plus) }
        return sample
    }

    fun due(): Boolean = now() - lastFlush >= 15_000_000_000L
    fun drain(): Map<Int, Long> = pending.toMap().also { pending.clear(); lastFlush = now() }
}
