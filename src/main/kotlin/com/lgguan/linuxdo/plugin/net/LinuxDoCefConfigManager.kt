package com.lgguan.linuxdo.plugin.net

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import java.io.File

/** Reports private runtime configuration; never changes the IDE-wide JCEF app. */
object LinuxDoCefConfigManager {
    enum class CefConfigStatus(val description: String) {
        NOT_CONFIGURED("DoH 未启用"),
        PREPARED("配置已保存，将用于插件独立浏览器"),
        RUNTIME_MATCH_UNVERIFIED("插件独立浏览器配置已应用，连接待验证"),
        RECREATE_ON_NEXT_CONNECTION("配置已保存，下次连接将重建插件独立浏览器")
    }

    fun isJcefStarted(): Boolean = IsolatedCefRuntime.currentOrNull() != null
    fun getJcefCacheDir(): File = IsolatedCefRuntime.currentOrNull()?.profile ?: File(PathManager.getSystemPath(), "linuxdo-private-jcef")
    fun getLocalStateFile(): File = File(getJcefCacheDir(), "Local State")

    fun getConfigStatus(config: LinuxDoNetworkConfig): CefConfigStatus {
        config.validateDoh()
        if (!config.isDohEnabled) return CefConfigStatus.NOT_CONFIGURED
        val runtime = IsolatedCefRuntime.currentOrNull() ?: return CefConfigStatus.PREPARED
        return if (runtime.config.runtimeKey() == config.runtimeKey()) {
            CefConfigStatus.RUNTIME_MATCH_UNVERIFIED
        } else {
            CefConfigStatus.RECREATE_ON_NEXT_CONNECTION
        }
    }

    fun getLocalStateDohInfo(): Pair<String?, String?> = try {
        val file = getLocalStateFile()
        if (!file.isFile) null to null else {
            val doh = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("dns_over_https")
            doh?.get("mode")?.asString to doh?.get("templates")?.asString
        }
    } catch (_: Exception) { null to null }
}
