package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.net.LinuxDoDiagnostics
import com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig
import com.lgguan.linuxdo.plugin.net.ProxyPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxDoCefDohTest {

    @Test
    fun testIpLiteralValidation() {
        // Valid IPv4
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("1.1.1.1"))
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("223.5.5.5"))
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("192.168.1.1"))
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("0.0.0.0"))

        // Invalid IPv4
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("256.0.0.1"))
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("1.2.3.4.5"))
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("1.2.3"))
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("1.2.3.a"))

        // Hostnames must be rejected
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("resolver.example"))
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("doh.example"))
        assertFalse(LinuxDoNetworkConfig.isValidIpLiteral("linux.do"))

        // Valid IPv6
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("2001:4860:4860::8888"))
        assertTrue(LinuxDoNetworkConfig.isValidIpLiteral("::1"))
    }

    @Test
    fun testDohUrlValidationAndTemplatePreservation() {
        val rawUrl = "https://neil.ddd.oaifree.com/query-dns"
        val config = LinuxDoNetworkConfig(
            dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = rawUrl
        )

        // Must keep raw URL intact without arbitrarily appending {?dns}
        assertEquals(rawUrl, config.effectiveDohUrl)
        assertTrue(LinuxDoNetworkConfig.isValidDohUrl(config.effectiveDohUrl))

        val templateWithQuery = "https://doh.example/dns-query{?dns}"
        val configWithTemplate = LinuxDoNetworkConfig(
            dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = templateWithQuery
        )
        assertEquals(templateWithQuery, configWithTemplate.effectiveDohUrl)
    }

    @Test
    fun testDiagnosticsSanitization() {
        val config = LinuxDoNetworkConfig(
            baseUrl = "https://linux.do",
            dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = "https://neil.ddd.oaifree.com/query-dns",
            customBootstrapIp = "1.2.3.4",
            proxyPolicy = ProxyPolicy.DIRECT
        )

        val report = LinuxDoDiagnostics.DiagnosticReport(
            ideBuild = "IC-241.14494.240",
            jbrVersion = "17.0.11",
            isJcefSupported = false,
            isJcefStarted = false,
            cacheDirPath = "C:/test/jcef_cache",
            localStateExists = true,
            localStateStatus = "磁盘配置匹配，运行时未验证",
            localStateDohMode = "secure",
            localStateDohTemplate = "https://neil.ddd.oaifree.com/query-dns",
            networkConfigSnapshot = config,
            dnsTestSuccess = true,
            dnsTestMessage = "Success (35ms)",
            jcefTestSuccess = null,
            jcefTestMessage = null
        )

        val summary = report.formatSanitizedSummary()
        assertTrue(summary.contains("IC-241.14494.240"))
        assertTrue(summary.contains("DIRECT"))
        assertTrue(summary.contains("期望代理策略"))
        assertTrue(summary.contains("JCEF 实际代理状态: 插件独立实例"))
        assertTrue(summary.contains("Local State 磁盘状态"))
        assertTrue(summary.contains("neil.ddd.oaifree.com"))
        // Strict privacy check: no cookies, tokens or passwords
        assertFalse(summary.contains("_t="))
        assertFalse(summary.contains("cf_clearance="))
        assertFalse(summary.contains("csrf"))
    }
}
