package com.lgguan.linuxdo.plugin.net

import java.io.IOException

/** Both transports retry once, and only after the server explicitly rejected CSRF. */
internal object CsrfRecovery {
    fun <T> execute(
        token: String?, version: Long,
        send: (String?) -> T,
        failure: (T) -> IOException?,
        close: (T) -> Unit,
        refresh: () -> String?
    ): T {
        fun inspect(response: T): IOException? = try { failure(response) } catch (error: Throwable) {
            close(response)
            throw error
        }
        SessionEpoch.requireCurrent(version)
        var response = send(token) // A timeout/connection exception is never retried.
        val initial = inspect(response)
        if (initial is CsrfRejectedException) {
            close(response)
            SessionEpoch.requireCurrent(version)
            val fresh = refresh()?.takeIf(String::isNotBlank) ?: throw initial
            SessionEpoch.requireCurrent(version)
            response = send(fresh)
        }
        if (version != SessionEpoch.current) { close(response); throw StaleSessionException() }
        inspect(response)?.let { close(response); throw it }
        return response
    }
}
