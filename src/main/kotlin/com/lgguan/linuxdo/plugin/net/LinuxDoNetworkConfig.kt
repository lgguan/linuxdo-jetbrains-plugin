package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants

/**
 * Request execution engine choices.
 */
enum class EngineMode(val label: String, val description: String) {
    AUTO("自动切换 (推荐)", "优先使用标准网络通道；若受阻则通过内置 Chromium 网桥发送请求"),
    FORCE_JCEF("强制 JCEF 网桥", "始终通过内置 Chromium 网桥发送请求"),
    JAVA_ONLY("仅标准 Java 网络", "仅使用标准 Java/OkHttp 网络栈，不启动 JCEF 网桥")
}

/**
 * Proxy routing policy.
 */
enum class ProxyPolicy(val label: String, val description: String) {
    DIRECT("直连模式 (不使用代理)", "插件内的网络请求绕过 IDE/系统代理，配合自建 DoH 实现免代理直连"),
    FOLLOW_IDE("跟随 IDE 代理", "遵循 IDE 全局配置的 HTTP/SOCKS 代理设置")
}

/**
 * Immutable network configuration snapshot.
 * Used across settings, tests, JCEF initialization, and OkHttp client rebuilds.
 */
data class LinuxDoNetworkConfig(
    val baseUrl: String = Constants.DEFAULT_BASE_URL,
    val dohProvider: Constants.DohProvider = Constants.DohProvider.LINUXDO,
    val customDohUrl: String = "",
    val customBootstrapIp: String = "",
    val engineMode: EngineMode = EngineMode.AUTO,
    val proxyPolicy: ProxyPolicy = ProxyPolicy.DIRECT,
    val requestTimeoutSeconds: Int = 15,
    val userAgent: String = Constants.DEFAULT_USER_AGENT,
    val strictDoh: Boolean = true,
    val revision: Long = System.currentTimeMillis()
) {
    val effectiveDohUrl: String
        get() = if (dohProvider == Constants.DohProvider.CUSTOM) customDohUrl.trim() else dohProvider.url

    val effectiveBootstrapIp: String
        get() = if (dohProvider == Constants.DohProvider.CUSTOM) customBootstrapIp.trim() else dohProvider.bootstrapIp

    val isDohEnabled: Boolean
        get() = dohProvider != Constants.DohProvider.DISABLED

    fun validateDoh() {
        if (!isDohEnabled) return
        DohEndpoint.parse(effectiveDohUrl)
        require(effectiveBootstrapIp.isBlank() || isValidIpLiteral(effectiveBootstrapIp)) {
            "引导 IP 非法：必须是有效的 IPv4/IPv6 地址字面量"
        }
    }

    /** Settings that require replacing the private browser process. UA detection must not close the login page. */
    fun runtimeKey(): List<String> = listOf(dohProvider.name, effectiveDohUrl, effectiveBootstrapIp, proxyPolicy.name, baseUrl)

    companion object {
        private val IPV4_REGEX = Regex("^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")

        /**
         * Validates whether a string is a strict IPv4 or IPv6 literal.
         * Hostnames are explicitly rejected to prevent nested implicit DNS lookups.
         */
        fun isValidIpLiteral(ip: String?): Boolean {
            if (ip.isNullOrBlank()) return false
            val trimmed = ip.trim()
            if (IPV4_REGEX.matches(trimmed)) return trimmed.split('.').all { it.length == 1 || !it.startsWith('0') }
            val literal = if (trimmed.startsWith('[') && trimmed.endsWith(']')) trimmed.substring(1, trimmed.length - 1) else trimmed
            // This lexical gate prevents InetAddress from ever resolving a hostname.
            if (':' !in literal || literal.any { it !in "0123456789abcdefABCDEF:." }) return false
            return runCatching { java.net.InetAddress.getByName(literal) }.isSuccess
        }

        /**
         * Validates DoH URL format.
         */
        fun isValidDohUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return runCatching { DohEndpoint.parse(url) }.isSuccess
        }
    }
}
