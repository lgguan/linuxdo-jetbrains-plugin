package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class HttpStatusException(val status: Int) : IOException("HTTP $status")
class CsrfRejectedException : IOException("HTTP 403: CSRF validation failed")

/** Shared classification; response bodies never become log/exception messages. */
object HttpFailure {
    fun classify(status: Int, headers: Map<String, String>, body: String): IOException? {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
        if (status in 200..299) return null
        if (status == 429) return RateLimitException(retryAfter(header("Retry-After")))
        if (status == 403 && (header("cf-mitigated").equals("challenge", true) ||
                (header("Content-Type").contains("text/html", true) &&
                    listOf("cf-chl-opt", "challenge-platform", "Just a moment", "cf-turnstile").any { body.contains(it, true) }))) {
            return CloudflareChallengeException()
        }
        if (status == 403 && listOf("invalid_csrf", "CSRF", "BAD CSRF").any { body.contains(it, true) }) {
            return CsrfRejectedException()
        }
        return HttpStatusException(status)
    }

    fun retryAfter(value: String, nowMillis: Long = System.currentTimeMillis()): Long =
        (value.trim().toLongOrNull() ?: runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis + 999) / 1000
        }.getOrNull() ?: Constants.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS).coerceIn(0, 86400)
}
