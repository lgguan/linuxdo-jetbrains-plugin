package com.lgguan.linuxdo.plugin.net

import java.io.IOException

/** One version for account credentials, requests and account-owned caches. */
object SessionEpoch {
    @Volatile var current: Long = 0
        private set
    @Synchronized fun advance(): Long = (++current)
    @Synchronized fun <T> ifCurrent(version: Long, action: () -> T): T? =
        if (version == current) action() else null
    fun requireCurrent(version: Long) { if (version != current) throw StaleSessionException() }
    data class Stamp(val version: Long = current)
}

class StaleSessionException : IOException("账号凭据已更改，请重新操作")

internal class SessionCache<T> {
    @Volatile private var value: Pair<Long, T>? = null
    fun get(): T? = value?.takeIf { it.first == SessionEpoch.current }?.second
    fun put(version: Long, data: T) { SessionEpoch.ifCurrent(version) { value = version to data } }
    fun clear() { value = null }
}
