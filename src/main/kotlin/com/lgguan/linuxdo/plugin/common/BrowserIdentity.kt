package com.lgguan.linuxdo.plugin.common

internal object BrowserIdentity {
    fun defaultUserAgent(platform: HostPlatform = HostPlatform.detect()): String {
        val system = when (platform) {
            HostPlatform.WINDOWS -> "Windows NT 10.0; Win64; x64"
            HostPlatform.MAC -> "Macintosh; Intel Mac OS X 10_15_7"
            HostPlatform.LINUX -> "X11; Linux x86_64"
        }
        // Chromium's reduced desktop UA uses these tokens on both x64 and ARM64.
        return "Mozilla/5.0 ($system) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    }

    /** Derive hints from the effective UA, including user overrides and the login browser's captured UA. */
    fun clientHintPlatform(userAgent: String): String? = when {
        "Android" in userAgent -> "Android"
        "iPhone" in userAgent || "iPad" in userAgent -> "iOS"
        "Windows" in userAgent -> "Windows"
        "Macintosh" in userAgent || "Mac OS X" in userAgent -> "macOS"
        "CrOS" in userAgent -> "Chrome OS"
        "Linux" in userAgent || "X11" in userAgent -> "Linux"
        else -> null
    }
}
