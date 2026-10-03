package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class HttpStatusException(val status: Int) : IOException("HTTP $status")
class CsrfRejectedException : IOException("HTTP 403: CSRF validation failed")
/** Validation text is for the editor only; exception/log messages never contain forum content. */
internal class ForumValidationException(val status: Int, val errors: List<String>) : IOException("HTTP $status")

/** Shared classification; response bodies never become log/exception messages. */
object HttpFailure {
    fun classify(status: Int, headers: Map<String, String>, body: String): IOException? {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
        if (status in 200..299) return null
        if (status in setOf(403, 429) && (header("cf-mitigated").equals("challenge", true) ||
                (status == 429 && body.contains("cloudflare", true)) ||
                (header("Content-Type").contains("text/html", true) &&
                    listOf("cf-chl-opt", "challenge-platform", "Just a moment", "cf-turnstile").any { body.contains(it, true) }))) {
            return CloudflareChallengeException("Cloudflare 人机验证未通过 (HTTP $status)")
        }
        if (status == 429) {
            val bodyWait = runCatching {
                com.google.gson.JsonParser.parseString(body).asJsonObject.getAsJsonObject("extras")?.get("wait_seconds")?.asLong
            }.getOrNull()?.coerceIn(0,86400)
            val headerWait = header("Retry-After").takeIf { it.isNotBlank() }?.let { retryAfter(it) }
            return RateLimitException(listOfNotNull(bodyWait,headerWait).maxOrNull() ?: retryAfter(""))
        }
        if (status == 403 && listOf("invalid_csrf", "CSRF", "BAD CSRF").any { body.contains(it, true) }) {
            return CsrfRejectedException()
        }
        if (status in setOf(400, 422)) {
            val errors = runCatching {
                com.google.gson.JsonParser.parseString(body).asJsonObject.get("errors")?.takeIf { it.isJsonArray }
                    ?.asJsonArray?.take(20)?.map { org.jsoup.Jsoup.parse(it.asString).text().take(2000) }
            }.getOrNull()
            if (!errors.isNullOrEmpty()) return ForumValidationException(status, errors)
        }
        return HttpStatusException(status)
    }

    fun retryAfter(value: String, nowMillis: Long = System.currentTimeMillis()): Long =
        (value.trim().toLongOrNull() ?: runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis + 999) / 1000
        }.getOrNull() ?: Constants.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS).coerceIn(0, 86400)
}
