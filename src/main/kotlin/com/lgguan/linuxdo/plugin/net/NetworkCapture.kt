package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.application.PathManager
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import java.io.File
import java.time.Instant

/** A durable consumption marker prevents a crash before settings save from re-arming a capture. */
class OneShotNetLog {
    private var decision: List<String>? = null
    var file: File? = null
        private set
    var startedAt: Instant? = null
        private set
    var autoStop = false
        private set
    var consumedToken: String? = null
        private set

    @Synchronized
    fun prepare(token: String, directory: File, major: Int?): List<String> {
        decision?.let { return it }
        if (!token.matches(Regex("[a-f0-9-]{36}"))) return emptyList<String>().also { decision = it }
        directory.mkdirs()
        val marker = File(directory, "linuxdo-netlog-$token.consumed")
        val fresh = marker.createNewFile()
        consumedToken = token
        if (!fresh) return emptyList<String>().also { decision = it }
        val target = File(directory, "linuxdo-netlog-$token.json")
        file = target
        startedAt = Instant.now()
        autoStop = major != null && major >= 137
        return buildList {
            add("--log-net-log=${target.absolutePath}")
            if (major != null && major >= 117) add("--net-log-max-size-mb=50")
            if (autoStop) add("--net-log-duration=300")
        }.also { decision = it }
    }
}

object NetworkCapture {
    private val capture = OneShotNetLog()
    internal fun startupToken(token: String, armedSession: String, currentSession: String): String =
        if (armedSession == currentSession) "" else token
    fun cefVersion(): Pair<Int?, String> = try {
        val version = Class.forName("com.jetbrains.cef.JCefAppConfig").getMethod("getVersionDetails").invoke(null)
        val cef = version.javaClass.getField("cefVersion").get(version)
        (cef.javaClass.getField("major").get(cef) as Int) to version.toString()
    } catch (_: Throwable) { null to "unknown" }

    fun startupOptions(): List<String> = try {
        val state = LinuxDoSettingsState.getInstance()
        val token = startupToken(state.networkDiagnosticToken, state.networkDiagnosticArmedSession, NetworkTrace.sessionId)
        capture.prepare(token, File(PathManager.getLogPath()), cefVersion().first).also {
            if (state.networkDiagnosticToken == capture.consumedToken) state.networkDiagnosticToken = ""
            if (it.isNotEmpty()) {
                NetworkTrace.event("startup", "JCEF", "netlog_prepared", "file" to capture.file?.absolutePath, "autoStopSeconds" to if (capture.autoStop) 300 else null, "runtimeVerified" to false)
            }
        }
    } catch (t: Throwable) {
        NetworkTrace.event("startup", "JCEF", "netlog_prepare_failed", "errorType" to NetworkTrace.errorType(t))
        emptyList()
    }

    fun status(): String {
        val file = capture.file ?: return "本次启动未准备 NetLog；勾选并应用后冷启动 IDE。"
        return "NetLog: ${file.absolutePath}\n准备时间: ${capture.startedAt}\n" +
            (if (capture.autoStop) "采集窗口：从 JCEF 启动起 5 分钟，最大 50 MB。" else "当前版本自动停止能力未知或不可用；复现后退出 IDE 完成采集。") +
            "\n文件已检测到: ${file.exists()}；字节数: ${if (file.exists()) file.length() else 0}（文件存在不代表目标请求已捕获）。"
    }
}
