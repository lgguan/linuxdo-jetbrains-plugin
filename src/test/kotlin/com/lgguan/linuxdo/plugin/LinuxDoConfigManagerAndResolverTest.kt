package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.net.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.file.Path
import java.util.Base64

class LinuxDoConfigManagerAndResolverTest {
    private fun dns(lookup: () -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = lookup()
    }
    private fun config(url: String) = LinuxDoNetworkConfig(dohProvider = Constants.DohProvider.CUSTOM, customDohUrl = url)

    @Test fun `configuration status never modifies the IDE shared cache`(@TempDir dir: Path) {
        val file = dir.resolve("Local State").toFile()
        val original = "{broken shared browser configuration"
        file.writeText(original)
        val old = System.getProperty("ide.browser.jcef.cache.path")
        try {
            System.setProperty("ide.browser.jcef.cache.path", dir.toString())
            LinuxDoCefConfigManager.getConfigStatus(config("https://doh.example/query"))
            assertEquals(original, file.readText())
            assertNotEquals(dir.toFile(), LinuxDoCefConfigManager.getJcefCacheDir())
        } finally {
            if (old == null) System.clearProperty("ide.browser.jcef.cache.path") else System.setProperty("ide.browser.jcef.cache.path", old)
        }
    }

    @Test fun `malformed or empty strict DoH never falls back to system DNS`() {
        for (url in listOf("", "https://", "http://resolver.test/query", "https://resolver.test/{bad}")) {
            var usedSystem = false
            val resolver = DohDnsResolver(dns { usedSystem = true; listOf(InetAddress.getLoopbackAddress()) }, { OkHttpClient.Builder() })
            resolver.updateConfiguration(config(url))
            assertThrows(UnknownHostException::class.java) { resolver.lookup("forum.test") }
            assertFalse(usedSystem)
        }
    }

    @Test fun `disabled DoH uses the supplied system resolver`() {
        val answer = listOf(InetAddress.getLoopbackAddress())
        val resolver = DohDnsResolver(dns { answer }, { OkHttpClient.Builder() })
        resolver.updateConfiguration(LinuxDoNetworkConfig(dohProvider = Constants.DohProvider.DISABLED))
        assertEquals(answer, resolver.lookup("forum.test"))
    }

    @Test fun `RFC8484 POST and GET templates send valid DNS messages`() {
        val cases = listOf(
            "https://ldh.ddd.oaifree.com/query-dns" to "POST",
            "https://doh.example/query?key=example" to "POST",
            "https://doh.example/query{?dns}" to "GET",
            "https://doh.example/query?key=example{&dns}" to "GET",
            "https://doh.example/query?dns={dns}" to "GET",
            "https://doh.example/query/{dns}" to "GET"
        )
        for ((url, method) in cases) {
            var captured: Request? = null
            var bootstrap: OkHttpClient.Builder? = null
            val resolver = DohDnsResolver(dns { error("Unexpected fallback") }) {
                OkHttpClient.Builder().also { bootstrap = it }.addInterceptor { chain ->
                    val request = chain.request()
                    captured = request
                    val query = if (method == "POST") {
                        val buffer = Buffer(); request.body!!.writeTo(buffer); buffer.readByteArray()
                    } else Base64.getUrlDecoder().decode(request.url.queryParameter("dns") ?: request.url.pathSegments.last())
                    assertTrue(query.size > 12)
                    val response = query.copyOf().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
                        byteArrayOf(0xc0.toByte(), 0x0c, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 203.toByte(), 0, 113, 7)
                    Response.Builder().request(request).protocol(Protocol.HTTP_2).code(200).message("OK")
                        .body(response.toResponseBody("application/dns-message".toMediaType())).build()
                }
            }
            resolver.updateConfiguration(if (url == LinuxDoNetworkConfig().effectiveDohUrl) LinuxDoNetworkConfig() else config(url))
            assertEquals(Proxy.NO_PROXY, bootstrap!!.build().proxy)
            assertEquals("203.0.113.7", resolver.lookup("forum.test").single().hostAddress)
            assertEquals(method, captured!!.method)
            assertFalse(captured!!.url.toString().contains("%7B"))
            if ("key=" in url) assertEquals("example", captured!!.url.queryParameter("key"))
        }
    }

    @Test fun `bootstrap hostnames and malformed IPv6 are rejected`() {
        for (ip in listOf("another-host.test", "1::2::3", "::::", "12345::1", "01.2.3.4")) {
            assertFalse(LinuxDoNetworkConfig.isValidIpLiteral(ip), ip)
        }
        val result = DohDnsResolver.testDoH(config("https://doh.example/query").copy(customBootstrapIp = "hostname.test"))
        assertFalse(result.first)
    }

    @Test fun `runtime identity ignores user agent and timeout changes but includes DNS`() {
        val original = config("https://doh.example/query")
        assertEquals(original.runtimeKey(), original.copy(userAgent = "new UA", requestTimeoutSeconds = 40).runtimeKey())
        assertNotEquals(original.runtimeKey(), original.copy(customDohUrl = "https://other.example/query").runtimeKey())
    }
}
