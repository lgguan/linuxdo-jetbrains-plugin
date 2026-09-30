package com.lgguan.linuxdo.plugin

import com.intellij.credentialStore.Credentials
import com.lgguan.linuxdo.plugin.net.IdeProxySettings
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy

class IdeProxySettingsTest {
    enum class ProtocolKind { HTTP, SOCKS }
    interface StaticConfiguration {
        fun getHost(): String
        fun getPort(): Int
        fun getProtocol(): ProtocolKind
    }

    private fun configuration(host: String, port: Int, protocol: ProtocolKind) = object : StaticConfiguration {
        override fun getHost() = host
        override fun getPort() = port
        override fun getProtocol() = protocol
    }

    @Test fun `HTTP and SOCKS configurations keep Java and browser endpoints consistent`() {
        for ((protocol, javaType, browserScheme) in listOf(
            Triple(ProtocolKind.HTTP, Proxy.Type.HTTP, "http"),
            Triple(ProtocolKind.SOCKS, Proxy.Type.SOCKS, "socks5"))) {
            val proxy = IdeProxySettings.readConfiguration(configuration("127.0.0.1", 8080, protocol), StaticConfiguration::class.java)!!
            assertEquals(javaType, proxy.javaProxy().type())
            val address = proxy.javaProxy().address() as InetSocketAddress
            assertEquals("127.0.0.1", address.hostString)
            assertEquals(8080, address.port)
            assertEquals("--proxy-server=$browserScheme://127.0.0.1:8080", proxy.browserArgument())
        }
    }

    @Test fun `non-manual and invalid configurations do not force a proxy`() {
        assertNull(IdeProxySettings.readConfiguration(Any(), StaticConfiguration::class.java))
        for ((host, port) in listOf("" to 8080, "  " to 8080, "proxy.test" to 0, "proxy.test" to 65536)) {
            assertNull(IdeProxySettings.readConfiguration(configuration(host, port, ProtocolKind.HTTP), StaticConfiguration::class.java))
        }
    }

    @Test fun `IPv6 proxy addresses are bracketed for Chromium`() {
        val proxy = IdeProxySettings.readConfiguration(configuration("::1", 1080, ProtocolKind.SOCKS), StaticConfiguration::class.java)!!
        assertEquals("--proxy-server=socks5://[::1]:1080", proxy.browserArgument())
    }

    private fun challenge(request: Request = Request.Builder().url("https://linux.do").build()): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(407).message("Proxy Authentication Required").build()

    @Test fun `proxy authentication reads current credentials only after a challenge`() {
        var reads = 0
        var saved = Credentials("first", "password")
        val auth = IdeProxySettings.authenticator { reads++; saved }
        assertEquals(0, reads)
        assertEquals(okhttp3.Credentials.basic("first", "password"), auth.authenticate(null, challenge())!!.header("Proxy-Authorization"))
        saved = Credentials("second", "updated")
        assertEquals(okhttp3.Credentials.basic("second", "updated"), auth.authenticate(null, challenge())!!.header("Proxy-Authorization"))
        assertEquals(2, reads)
    }

    @Test fun `missing credentials and rejected credentials do not retry`() {
        assertNull(IdeProxySettings.authenticator { null }.authenticate(null, challenge()))
        assertNull(IdeProxySettings.authenticator { Credentials(null, "password") }.authenticate(null, challenge()))
        val rejected = Request.Builder().url("https://linux.do").header("Proxy-Authorization", "Basic rejected").build()
        assertNull(IdeProxySettings.authenticator { fail<Credentials>("Do not fetch a rejected credential again") }
            .authenticate(null, challenge(rejected)))
    }
}
