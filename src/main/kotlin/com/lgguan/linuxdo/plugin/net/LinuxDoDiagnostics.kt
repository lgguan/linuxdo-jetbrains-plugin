package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.application.ApplicationInfo
import com.lgguan.linuxdo.plugin.common.LinuxDoLog

/**
 * Diagnostic utilities for inspecting the private JCEF runtime,
 * DoH resolution, and network connectivity without exposing sensitive credentials.
 */
object LinuxDoDiagnostics {

    data class DiagnosticReport(
        val ideBuild: String,
        val jbrVersion: String,
        val isJcefSupported: Boolean,
        val isJcefStarted: Boolean,
        val cacheDirPath: String,
        val localStateExists: Boolean,
        val localStateStatus: String,
        val localStateDohMode: String?,
        val localStateDohTemplate: String?,
        val networkConfigSnapshot: LinuxDoNetworkConfig,
        val dnsTestSuccess: Boolean,
        val dnsTestMessage: String,
        val jcefTestSuccess: Boolean?,
        val jcefTestMessage: String?,
        val operatingSystem: String = System.getProperty("os.name", "Unknown"),
        val architecture: String = System.getProperty("os.arch", "Unknown"),
        val jcefSupportFailure: String? = null
    ) {
        fun formatSanitizedSummary(): String {
            return buildString {
                appendLine("=== Linux Do Plugin Diagnostic Report ===")
                appendLine("Generated: ${java.time.Instant.now()}")
                appendLine("Trace Session: ${NetworkTrace.sessionId}")
                appendLine(NetworkCapture.status())
                appendLine("JCEF/Chromium: ${NetworkCapture.cefVersion().second}")
                appendLine("--- Environment ---")
                appendLine("IDE: $ideBuild")
                appendLine("JBR: $jbrVersion")
                appendLine("OS/Architecture: $operatingSystem / $architecture")
                appendLine("JCEF Supported: $isJcefSupported")
                jcefSupportFailure?.let { appendLine("JCEF Capability Failure: ${LinuxDoLog.sanitize(it)}") }
                appendLine("JCEF Started: $isJcefStarted")
                appendLine("Cache Directory: $cacheDirPath")
                appendLine("Local State Exists: $localStateExists")
                appendLine("Local State 磁盘状态: $localStateStatus")
                appendLine("Local State DoH Mode: ${localStateDohMode ?: "(none)"}")
                appendLine("Local State DoH Template: ${NetworkTrace.safeUrl(localStateDohTemplate)}")
                appendLine()
                appendLine("--- Network Configuration ---")
                appendLine("Base URL: ${NetworkTrace.safeUrl(networkConfigSnapshot.baseUrl)}")
                appendLine("DoH Provider: ${networkConfigSnapshot.dohProvider}")
                appendLine("Custom DoH URL: ${NetworkTrace.safeUrl(networkConfigSnapshot.customDohUrl)}")
                appendLine("Bootstrap IP: ${networkConfigSnapshot.customBootstrapIp.ifBlank { "(empty)" }}")
                appendLine("Engine Mode: ${networkConfigSnapshot.engineMode}")
                appendLine("期望代理策略 (Desired Proxy Policy): ${networkConfigSnapshot.proxyPolicy}")
                appendLine("JCEF 实际代理状态: 插件独立实例（DIRECT 使用 --no-proxy-server）；网络路径可用私有 NetLog 核实")
                appendLine("Strict DoH: ${networkConfigSnapshot.strictDoh}")
                appendLine("Timeout: ${networkConfigSnapshot.requestTimeoutSeconds}s")
                appendLine()
                appendLine("--- Diagnostic Tests ---")
                appendLine("DNS Test Result: [${if (dnsTestSuccess) "OK" else "FAIL"}] ${LinuxDoLog.sanitize(dnsTestMessage)}")
                if (jcefTestSuccess != null) {
                    appendLine("JCEF Test Result: [${if (jcefTestSuccess) "OK" else "FAIL"}] ${LinuxDoLog.sanitize(jcefTestMessage ?: "")}")
                } else {
                    appendLine("JCEF Test Result: (skipped)")
                }
                appendLine("=========================================")
            }
        }
    }

    /**
     * Executes end-to-end diagnostics safely on a background thread.
     */
    fun runFullDiagnostics(config: LinuxDoNetworkConfig): DiagnosticReport {
        val appInfo = try { ApplicationInfo.getInstance().build.asString() } catch (_: Throwable) { "Unknown" }
        val jbr = System.getProperty("java.version") ?: "Unknown"

        val jcefSupported = IsolatedCefRuntime.isSupported()
        val jcefStarted = IsolatedCefRuntime.currentOrNull() != null

        val cacheDir = LinuxDoCefConfigManager.getJcefCacheDir().absolutePath
        val localStateFile = LinuxDoCefConfigManager.getLocalStateFile()
        val localStateExists = localStateFile.exists()
        val (dohMode, dohTemplate) = LinuxDoCefConfigManager.getLocalStateDohInfo()

        val localStateStatus = "仅供参考；DoH 由插件独立策略配置，不写入 Local State，也不以此文件判断是否生效"

        // 1. Test DNS
        val (dnsSuccess, dnsMsg) = DohDnsResolver.testDoH(config, "linux.do")

        // 2. Test JCEF Bridge connection if supported
        var jcefSuccess: Boolean? = null
        var jcefMsg: String? = null

        if (jcefSupported) {
            try {
                val start = System.currentTimeMillis()
                val baseUrl = config.baseUrl.trim().removeSuffix("/")
                val testUrl = "$baseUrl/site.json"
                val req = LinuxDoJcefBridge.BridgeRequest(
                    url = testUrl,
                    method = "GET",
                    headers = mapOf("Accept" to "application/json")
                )
                val res = LinuxDoJcefBridge.execute(req, timeoutSeconds = 30)
                val elapsed = System.currentTimeMillis() - start
                res.onSuccess { resp ->
                    jcefSuccess = resp.success && resp.status in 200..399
                    jcefMsg = "HTTP ${resp.status} in ${elapsed}ms (Carrier State: ${LinuxDoJcefBridge.getState()})"
                }.onFailure { err ->
                    jcefSuccess = false
                    jcefMsg = "Failed in ${elapsed}ms: ${err.message} (Carrier State: ${LinuxDoJcefBridge.getState()}, Failure: ${LinuxDoJcefBridge.getLastFailureReason()})"
                }
            } catch (t: Throwable) {
                jcefSuccess = false
                jcefMsg = "Exception: ${t.message}"
            }
        }

        return DiagnosticReport(
            ideBuild = appInfo,
            jbrVersion = jbr,
            isJcefSupported = jcefSupported,
            isJcefStarted = jcefStarted,
            cacheDirPath = cacheDir,
            localStateExists = localStateExists,
            localStateStatus = localStateStatus,
            localStateDohMode = dohMode,
            localStateDohTemplate = dohTemplate,
            networkConfigSnapshot = config,
            dnsTestSuccess = dnsSuccess,
            dnsTestMessage = dnsMsg,
            jcefTestSuccess = jcefSuccess,
            jcefTestMessage = jcefMsg,
            jcefSupportFailure = if (jcefSupported) null else IsolatedCefRuntime.supportFailure()
        )
    }
}
