package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import okhttp3.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class NetworkDiagnosticsTest {
    @TempDir lateinit var dir: File

    @Test fun `arming before first JCEF use still waits for a cold IDE restart`() {
        assertEquals("", NetworkCapture.startupToken("armed-token", "session1", "session1"))
        assertEquals("armed-token", NetworkCapture.startupToken("armed-token", "session1", "session2"))
    }

    @Test fun `disposed observer drops late callbacks and cleans up once`() {
        val lifetime = DiagnosticLifetime()
        var events = 0
        var cleanups = 0
        lifetime.whileOpen { events++ }
        lifetime.close { cleanups++ }
        lifetime.whileOpen { events++ }
        lifetime.close { cleanups++ }
        assertEquals(1, events)
        assertEquals(1, cleanups)
    }

    @Test fun `netlog is one shot durable and repeatable within startup`() {
        val token = "00000000-0000-0000-0000-000000000001"
        val capture = OneShotNetLog()
        val first = capture.prepare(token, dir, 144)
        assertTrue(first.contains("--net-log-duration=300"))
        assertTrue(first.contains("--net-log-max-size-mb=50"))
        assertFalse(first.any { it.contains("IncludeSensitive") || it.contains("Everything") })
        assertEquals(first, capture.prepare("", dir, 144))
        assertTrue(OneShotNetLog().prepare(token, dir, 144).isEmpty())
        assertFalse(capture.file!!.exists(), "Prepared is not the same as captured")
    }

    @Test fun `late arming waits for another startup and old runtime has no unsupported flags`() {
        val capture = OneShotNetLog()
        assertTrue(capture.prepare("", dir, 144).isEmpty())
        assertTrue(capture.prepare("00000000-0000-0000-0000-000000000002", dir, 144).isEmpty())
        val old = OneShotNetLog().prepare("00000000-0000-0000-0000-000000000002", dir, 116)
        assertEquals(1, old.size)
        assertTrue(old.single().startsWith("--log-net-log="))
        assertTrue(OneShotNetLog().prepare("../../escape", dir, 144).isEmpty())
    }

    @Test fun `URLs and legacy log messages cannot expose credentials or queries`() {
        val url = "https://user:secret@example.org/login?token=secret#secret"
        assertEquals("https://example.org/login", NetworkTrace.safeUrl(url))
        assertFalse(LinuxDoLog.sanitize("request=$url").contains("secret"))
        assertFalse(LinuxDoLog.sanitize("Authorization: Bearer secret").contains("secret"))
        assertFalse(LinuxDoLog.sanitize("HTTP 403: secret response body").contains("secret"))
        assertFalse(NetworkTrace.safeUrl("data:text/plain,secret").contains("secret"))
    }

    @Test fun `CDP metadata selection excludes content and represents missing ECH as unknown`() {
        val event = """{"requestId":"1","response":{"url":"https://linux.do/?token=secret","status":403,"remoteIPAddress":"192.0.2.1","headers":{"Set-Cookie":"secret"},"body":"secret","securityDetails":{"protocol":"TLS 1.3"},"timing":{"connectStart":2,"connectEnd":7}}} """
        val result = JcefNetworkTrace.selectEvent("Network.responseReceived", event)!!
        assertEquals("unknown", result["ech"])
        assertEquals("192.0.2.1", result["remoteIPAddress"])
        assertEquals(7.0, result["connectEnd"])
        assertFalse(result.toString().contains("secret"))
        assertNull(JcefNetworkTrace.selectEvent("Network.responseReceivedExtraInfo", "not json"))
        val success = event.replace("\"protocol\":\"TLS 1.3\"", "\"protocol\":\"TLS 1.3\",\"encryptedClientHello\":true")
        assertEquals("true", JcefNetworkTrace.selectEvent("Network.responseReceived", success)!!["ech"])
        assertEquals("net::ERR_CONNECTION_TIMED_OUT", JcefNetworkTrace.selectEvent("Network.loadingFailed", """{"requestId":"1","errorText":"net::ERR_CONNECTION_TIMED_OUT secret"}""")!!["error"])
    }

    @Test fun `Java failures preserve DNS TCP and TLS phase without exception content`() {
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("https://example.org").build())
        val address = InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 443)
        for ((phase, error) in listOf("dns" to UnknownHostException("secret"), "connect" to SocketTimeoutException("secret"), "tls" to SSLHandshakeException("secret"))) {
            val events = mutableListOf<Pair<String, Map<String, Any?>>>()
            val listener = NetworkEventListener("shared-id", "JAVA") { event, values -> events.add(event to values) }
            listener.callStart(call)
            listener.dnsStart(call, "example.org")
            if (phase != "dns") {
                listener.dnsEnd(call, "example.org", listOf(address.address))
                listener.connectStart(call, address, Proxy.NO_PROXY)
            }
            if (phase == "tls") listener.secureConnectStart(call)
            listener.callFailed(call, error)
            assertEquals(phase, events.last().second["phase"])
            assertFalse(events.toString().contains("secret"))
        }
    }

    @Test fun `reused connections and HTTP errors do not imply TLS failure`() {
        val client = OkHttpClient()
        val call = client.newCall(Request.Builder().url("https://example.org").build())
        val address = Address("example.org", 443, Dns.SYSTEM, javax.net.SocketFactory.getDefault(), null, null, null,
            Authenticator.NONE, Proxy.NO_PROXY, listOf(Protocol.HTTP_1_1), listOf(ConnectionSpec.CLEARTEXT), java.net.ProxySelector.getDefault())
        val route = Route(address, Proxy.NO_PROXY, InetSocketAddress("127.0.0.1", 443))
        val connection = object : Connection {
            override fun route() = route
            override fun socket() = java.net.Socket()
            override fun handshake(): Handshake? = null
            override fun protocol() = Protocol.HTTP_1_1
        }
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val listener = NetworkEventListener("id", "JAVA") { event, values -> events.add(event to values) }
        listener.connectionAcquired(call, connection)
        assertEquals(true, events.last().second["reused"])
        listener.responseHeadersEnd(call, Response.Builder().request(call.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden").build())
        assertEquals(403, events.last().second["status"])
        listener.callEnd(call)
        assertEquals("call_end", events.last().first)
    }
}
