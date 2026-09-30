package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.net.DohDnsResolver
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DohDnsResolverTest {

    @Test
    fun closingResolverReleasesItsTransportResources() {
        val dispatcher = okhttp3.Dispatcher()
        val pool = okhttp3.ConnectionPool()
        val resolver = DohDnsResolver(okhttp3.Dns.SYSTEM) {
            okhttp3.OkHttpClient.Builder().dispatcher(dispatcher).connectionPool(pool)
        }
        resolver.updateConfiguration(com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig(
            dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = "https://resolver.invalid/dns-query",
            customBootstrapIp = "127.0.0.1"
        ))
        assertFalse(dispatcher.executorService.isShutdown)
        resolver.close()
        resolver.close()
        assertTrue(dispatcher.executorService.isShutdown)
        org.junit.jupiter.api.Assertions.assertEquals(0, pool.connectionCount())
    }

    @Test
    fun testInvalidDohUrlReturnsFailure() {
        val (success, message) = DohDnsResolver.testDoH(
            Constants.DohProvider.CUSTOM,
            customUrl = "https://invalid-nonexistent-domain-xyz-12345.com/dns-query",
            customBootstrapIp = "127.0.0.1",
            testHost = "linux.do"
        )
        assertFalse(success)
        assertTrue(message.contains("failed", ignoreCase = true) || message.contains("error", ignoreCase = true))
    }

    @Test
    fun testDohResolverConfigurationUpdate() {
        val resolver = DohDnsResolver()
        // Default update should not throw exceptions
        resolver.updateConfiguration()
    }
}
