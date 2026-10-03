package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.net.ForumReadCooldown
import com.lgguan.linuxdo.plugin.net.RateLimitException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class ForumReadCooldownTest {
    @Test fun `forum JSON 429 cooldown and Cloudflare challenge stay distinct`() {
        val failure=com.lgguan.linuxdo.plugin.net.HttpFailure
        assertEquals(75,(failure.classify(429,emptyMap(),"""{"error_type":"rate_limit","extras":{"wait_seconds":75}}""") as RateLimitException).retryAfterSeconds)
        assertEquals(90,(failure.classify(429,mapOf("Retry-After" to "90"),"""{"extras":{"wait_seconds":75}}""") as RateLimitException).retryAfterSeconds)
        assertTrue(failure.classify(429,mapOf("cf-mitigated" to "challenge"),"Cloudflare") is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException)
    }
    @Test fun `429 blocks subsequent readers until Retry-After without extending it`() {
        var now = 10_000L
        val cooldown = ForumReadCooldown { now }
        var requests = 0
        cooldown.read<String> { requests++; Result.failure(RateLimitException(30)) }
        now += 1001
        val blocked = cooldown.read { requests++; Result.success("second tab") }
        assertEquals(29, (blocked.exceptionOrNull() as RateLimitException).retryAfterSeconds)
        assertEquals(1, requests)
        now = 40_000
        assertEquals("retry", cooldown.read { requests++; Result.success("retry") }.getOrThrow())
        assertEquals(2, requests)
    }

    @Test fun `ordinary network failures do not become a server cooldown`() {
        val cooldown = ForumReadCooldown { 0L }
        cooldown.read<String> { Result.failure(IOException("offline")) }
        assertEquals("cached or retried", cooldown.read { Result.success("cached or retried") }.getOrThrow())
    }
}
