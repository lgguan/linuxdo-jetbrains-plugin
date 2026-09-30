package com.lgguan.linuxdo.plugin

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge
import com.lgguan.linuxdo.plugin.ui.toolwindow.IssueListPanel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketException
import java.util.Base64
import javax.net.ssl.SSLException

class JcefNetworkBridgeTest {

    private val gson = Gson()

    @Test
    fun testNetworkModeEnum() {
        val autoMode = LinuxDoSettingsState.NetworkMode.AUTO
        assertEquals("自动切换 (推荐)", autoMode.label)
        assertTrue(autoMode.description.contains("连接失败"))
        assertFalse(autoMode.description.contains("SNI 审查阻断"))

        val forceJcef = LinuxDoSettingsState.NetworkMode.FORCE_JCEF
        assertTrue(forceJcef.label.contains("JCEF"))
        assertTrue(forceJcef.description.contains("Chromium"))

        val javaOnly = LinuxDoSettingsState.NetworkMode.JAVA_ONLY
        assertTrue(javaOnly.label.contains("标准 Java"))
    }

    @Test
    fun testIsConnectionResetOrSniBlock() {
        // Direct SocketException
        val e1 = SocketException("Connection reset")
        assertTrue(DiscourseApiClient.isConnectionResetOrSniBlock(e1))

        // Nested in IOException
        val e2 = IOException("HTTP failed", SocketException("Connection reset by peer"))
        assertTrue(DiscourseApiClient.isConnectionResetOrSniBlock(e2))

        // SSL Handshake reset
        val e3 = SSLException("Connection reset")
        assertTrue(DiscourseApiClient.isConnectionResetOrSniBlock(e3))

        // Software abort
        val e4 = SocketException("Software caused connection abort: recv failed")
        assertTrue(DiscourseApiClient.isConnectionResetOrSniBlock(e4))

        // Connect timed out
        val e5 = java.net.SocketTimeoutException("Connect timed out")
        assertTrue(DiscourseApiClient.isConnectionResetOrSniBlock(e5))

        // Read timed out should not trigger SNI block
        val e6 = java.net.SocketTimeoutException("Read timed out")
        assertFalse(DiscourseApiClient.isConnectionResetOrSniBlock(e6))

        // Unrelated exceptions
        val normal404 = IOException("HTTP 404: Not Found")
        assertFalse(DiscourseApiClient.isConnectionResetOrSniBlock(normal404))

        val normal500 = IOException("HTTP 500: Internal Server Error")
        assertFalse(DiscourseApiClient.isConnectionResetOrSniBlock(normal500))
    }

    @Test
    fun testBridgeRequestSerialization() {
        val req = LinuxDoJcefBridge.BridgeRequest(
            requestId = "req-123",
            url = "https://linux.do/site.json",
            method = "GET",
            headers = mapOf("Accept" to "application/json")
        )
        val json = gson.toJson(req)
        assertTrue(json.contains("\"requestId\":\"req-123\""))
        assertTrue(json.contains("\"url\":\"https://linux.do/site.json\""))
        assertTrue(json.contains("\"method\":\"GET\""))
        assertTrue(json.contains("\"Accept\":\"application/json\""))
    }

    @Test
    fun testBridgeUploadRequestSerialization() {
        val dummyBytes = "fake image content".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(dummyBytes)
        val uploadReq = LinuxDoJcefBridge.BridgeRequest(
            requestId = "upload-999",
            url = "https://linux.do/uploads.json",
            method = "POST",
            headers = mapOf("X-CSRF-Token" to "test-csrf"),
            isUpload = true,
            uploadFileName = "screenshot.png",
            uploadMimeType = "image/png",
            uploadBase64 = base64
        )
        val json = gson.toJson(uploadReq)
        assertTrue(json.contains("\"isUpload\":true"))
        assertTrue(json.contains("\"uploadFileName\":\"screenshot.png\""))
        assertTrue(json.contains(base64))

        val decoded = gson.fromJson(json, LinuxDoJcefBridge.BridgeRequest::class.java)
        assertEquals("screenshot.png", decoded.uploadFileName)
        assertEquals(base64, decoded.uploadBase64)
    }

    @Test
    fun testBridgeResponseDeserialization() {
        val json = """
            {
                "requestId": "resp-001",
                "success": true,
                "status": 200,
                "statusText": "OK",
                "headers": { "content-type": "application/json" },
                "body": "{\"title\":\"Hello Linux Do\"}"
            }
        """.trimIndent()

        val resp = gson.fromJson(json, LinuxDoJcefBridge.BridgeResponse::class.java)
        assertEquals("resp-001", resp.requestId)
        assertTrue(resp.success)
        assertEquals(200, resp.status)
        assertEquals("OK", resp.statusText)
        assertEquals("{\"title\":\"Hello Linux Do\"}", resp.body)
    }

    @Test
    fun testBridgeBinaryResponseDeserialization() {
        val binaryData = "binary image test data".toByteArray()
        val base64 = Base64.getEncoder().encodeToString(binaryData)
        val json = """
            {
                "requestId": "binary-001",
                "success": true,
                "status": 200,
                "statusText": "OK",
                "binaryBase64": "$base64"
            }
        """.trimIndent()

        val resp = gson.fromJson(json, LinuxDoJcefBridge.BridgeResponse::class.java)
        assertNotNull(resp.binaryBase64)
        val decodedBytes = Base64.getDecoder().decode(resp.binaryBase64)
        assertArrayEquals(binaryData, decodedBytes)
    }

    @Test
    fun testHeadlessEnvironmentSafety() {
        // In Gradle JVM test environment without JBR native CEF libraries loaded,
        // isSupported() must return false cleanly and must never throw UnsatisfiedLinkError.
        val supported = LinuxDoJcefBridge.isSupported()
        // If false, execute() must safely return failure without crashing
        if (!supported) {
            val req = LinuxDoJcefBridge.BridgeRequest(url = "https://linux.do/site.json")
            val res = LinuxDoJcefBridge.execute(req)
            assertTrue(res.isFailure)
            assertTrue(res.exceptionOrNull()?.message?.contains("JCEF") == true)
        }
    }

    @Test
    fun testFormatErrorDisplayConnectionReset() {
        val raw = "java.net.SocketException: Connection reset"
        val formatted = IssueListPanel.formatErrorDisplay(raw)
        assertTrue(formatted.contains("重置"))
        assertTrue(formatted.contains("尚未确认"))
        assertTrue(formatted.contains("DNS") && formatted.contains("TLS"))
        assertFalse(formatted.contains("SNI 审查阻断"))
    }

    @Test
    fun testBridgeStateTransitions() {
        LinuxDoJcefBridge.resetBridge()
        assertEquals(LinuxDoJcefBridge.BridgeState.NEW, LinuxDoJcefBridge.getState())
        LinuxDoJcefBridge.dispose()
        assertEquals(LinuxDoJcefBridge.BridgeState.DISPOSED, LinuxDoJcefBridge.getState())
        LinuxDoJcefBridge.resetBridge()
        assertEquals(LinuxDoJcefBridge.BridgeState.NEW, LinuxDoJcefBridge.getState())
    }

    @Test
    fun testStrictOriginVerification() {
        val base = "https://linux.do"

        // 1. Same origin variations
        assertTrue(LinuxDoJcefBridge.isSameOrigin("https://linux.do", base))
        assertTrue(LinuxDoJcefBridge.isSameOrigin("https://linux.do/", base))
        assertTrue(LinuxDoJcefBridge.isSameOrigin("https://linux.do:443/t/12345", base))
        assertTrue(LinuxDoJcefBridge.isSameOrigin("https://linux.do/site.json?param=1", base))

        // 2. Different schemes
        assertFalse(LinuxDoJcefBridge.isSameOrigin("http://linux.do", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("http://linux.do:80", base))

        // 3. Different ports
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://linux.do:8443", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://linux.do:8080", base))

        // 4. Host prefix / suffix phishing attacks
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://linux.do.attacker.com", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://attacker-linux.do", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://linux.do-fake.com", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://fake-linux.do.com", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("https://evil.com", base))

        // 5. Malformed URLs
        assertFalse(LinuxDoJcefBridge.isSameOrigin("not-a-valid-url", base))
        assertFalse(LinuxDoJcefBridge.isSameOrigin("about:blank", base))
    }

    @Test
    fun testBridgeInitSessionLifecycleAndTimeoutDescription() {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        val startNano = System.nanoTime()
        val deadlineNano = startNano + 30_000_000_000L
        val session = LinuxDoJcefBridge.BridgeInitSession(
            generation = 99L,
            future = future,
            startNano = startNano,
            deadlineNano = deadlineNano,
            timeoutSeconds = 30L
        )

        // Stage begins at WAITING_NATIVE_BROWSER
        assertEquals(LinuxDoJcefBridge.InitStage.WAITING_NATIVE_BROWSER, session.stage)
        assertFalse(session.completed.get())
        assertTrue(session.remainingMillis() in 1..30000)

        // Stage transitions to LOADING_CARRIER
        session.stage = LinuxDoJcefBridge.InitStage.LOADING_CARRIER
        assertEquals(LinuxDoJcefBridge.InitStage.LOADING_CARRIER, session.stage)

        // Stage transitions to WAITING_HANDSHAKE
        session.stage = LinuxDoJcefBridge.InitStage.WAITING_HANDSHAKE
        assertEquals(LinuxDoJcefBridge.InitStage.WAITING_HANDSHAKE, session.stage)

        // Timeout / failure finishes session
        val finishResult = session.tryFinish(false, LinuxDoJcefBridge.BridgeState.FAILED, "原生浏览器创建超时 (30s)")
        assertTrue(finishResult)
        assertTrue(session.completed.get())
        assertTrue(future.isDone)
        assertEquals(false, future.get())

        // Late callbacks cannot revive the session
        val lateResult = session.tryFinish(true, LinuxDoJcefBridge.BridgeState.READY)
        assertFalse(lateResult, "Late callbacks after session completed must be rejected")
    }

    @Test
    fun testSessionSingleReloadEnforcement() {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        val startNano = System.nanoTime()
        val session = LinuxDoJcefBridge.BridgeInitSession(
            generation = 100L,
            future = future,
            startNano = startNano,
            deadlineNano = startNano + 30_000_000_000L,
            timeoutSeconds = 30L
        )

        assertFalse(session.reloadAttempted)
        session.reloadAttempted = true
        assertTrue(session.reloadAttempted, "Reload attempt flag must prevent repeated reload loops")
    }

    @Test
    fun testSessionLastLoadErrorAndRetryProtection() {
        val future = java.util.concurrent.CompletableFuture<Boolean>()
        val startNano = System.nanoTime()
        val session = LinuxDoJcefBridge.BridgeInitSession(
            generation = 101L,
            future = future,
            startNano = startNano,
            deadlineNano = startNano + 30_000_000_000L,
            timeoutSeconds = 30L
        )

        assertNull(session.lastLoadError)
        session.lastLoadError = "ERR_CONNECTION_TIMED_OUT (-118)"
        assertEquals("ERR_CONNECTION_TIMED_OUT (-118)", session.lastLoadError)

        val timer = javax.swing.Timer(5000) {}
        session.retryTimer = timer
        assertNotNull(session.retryTimer)
        assertFalse(session.completed.get())
        timer.stop()
    }

    @Test
    fun completedHandshakeStillDeliversApiResponsesButRejectsOldGenerations() {
        val bridge = LinuxDoJcefBridge
        bridge.resetBridge()
        val generation = ++bridge.currentGeneration
        val now = System.nanoTime()
        val session = LinuxDoJcefBridge.BridgeInitSession(generation, java.util.concurrent.CompletableFuture(), now, now + 30_000_000_000L, 30)
        session.tryFinish(true, LinuxDoJcefBridge.BridgeState.READY)
        val pendingField = bridge.javaClass.getDeclaredField("pendingRequests").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val pending = pendingField.get(null) as java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<LinuxDoJcefBridge.BridgeResponse>>
        val receive = bridge.javaClass.getDeclaredMethod("handleJsMessage", String::class.java, session.javaClass).apply { isAccessible = true }
        try {
            val response = java.util.concurrent.CompletableFuture<LinuxDoJcefBridge.BridgeResponse>()
            pending["public-api"] = response
            receive.invoke(bridge, """{"requestId":"public-api","success":true,"status":200,"body":"{}"}""", session)
            assertTrue(response.isDone, "Finishing initialization must not discard subsequent fetch responses")
            assertEquals(200, response.get().status)
            val next = java.util.concurrent.CompletableFuture<LinuxDoJcefBridge.BridgeResponse>()
            pending["new-generation"] = next
            bridge.currentGeneration++
            receive.invoke(bridge, """{"requestId":"new-generation","success":true,"status":200}""", session)
            assertFalse(next.isDone, "Old browser messages must not complete a new generation's requests")
        } finally { bridge.resetBridge() }
    }

    @Test
    fun testCefConfigStatusEnumAndDescriptions() {
        assertEquals(
            "插件独立浏览器配置已应用，连接待验证",
            com.lgguan.linuxdo.plugin.net.LinuxDoCefConfigManager.CefConfigStatus.RUNTIME_MATCH_UNVERIFIED.description
        )
        assertEquals(
            "配置已保存，将用于插件独立浏览器",
            com.lgguan.linuxdo.plugin.net.LinuxDoCefConfigManager.CefConfigStatus.PREPARED.description
        )
        assertEquals(
            "配置已保存，下次连接将重建插件独立浏览器",
            com.lgguan.linuxdo.plugin.net.LinuxDoCefConfigManager.CefConfigStatus.RECREATE_ON_NEXT_CONNECTION.description
        )
    }
}
