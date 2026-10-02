package com.lgguan.linuxdo.plugin.config

import com.lgguan.linuxdo.plugin.common.Constants
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState",
    storages = [Storage("LinuxDoSettings.xml")]
)
class LinuxDoSettingsState : PersistentStateComponent<LinuxDoSettingsState> {

    var baseUrl: String = Constants.DEFAULT_BASE_URL
        set(@Suppress("UNUSED_PARAMETER") value) { field = Constants.DEFAULT_BASE_URL }
    var dohProvider: Constants.DohProvider = Constants.DohProvider.LINUXDO
    var customDohUrl: String = ""
    var customBootstrapIp: String = ""

    var hideAvatars: Boolean = true
    var foldImages: Boolean = true
    var categoryNamespaceFormat: Boolean = true
    var autoJumpToLastReadFloor: Boolean = true
    var autoReportReadTimings: Boolean = true
    var readingFontSize: Int = 0
    var readingLineHeight: Double = 1.7
    var readingWidth: Int = 980

    var userAgent: String = Constants.DEFAULT_USER_AGENT
    var requestTimeoutSeconds: Int = 15
    var networkMode: String = NetworkMode.AUTO.name
    var proxyPolicy: String = com.lgguan.linuxdo.plugin.net.ProxyPolicy.DIRECT.name
    var strictDoh: Boolean = true
    var networkDiagnosticToken: String = ""
    var networkDiagnosticArmedSession: String = ""

    enum class NetworkMode(val label: String, val description: String) {
        AUTO("自动切换 (推荐)", "优先使用标准网络；连接失败时尝试 JCEF，具体原因需查看诊断"),
        FORCE_JCEF("强制 JCEF 网桥", "始终通过内置 Chromium 发送请求；ECH 状态以诊断结果为准"),
        JAVA_ONLY("仅标准 Java 网络", "仅使用标准 Java OkHttp，适合已开启全局/系统代理的环境")
    }

    fun toNetworkConfig(): com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig {
        val engine = when (networkMode) {
            NetworkMode.FORCE_JCEF.name -> com.lgguan.linuxdo.plugin.net.EngineMode.FORCE_JCEF
            NetworkMode.JAVA_ONLY.name -> com.lgguan.linuxdo.plugin.net.EngineMode.JAVA_ONLY
            else -> com.lgguan.linuxdo.plugin.net.EngineMode.AUTO
        }
        val policy = try {
            com.lgguan.linuxdo.plugin.net.ProxyPolicy.valueOf(proxyPolicy)
        } catch (_: Throwable) {
            com.lgguan.linuxdo.plugin.net.ProxyPolicy.DIRECT
        }
        return com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig(
            baseUrl = baseUrl,
            dohProvider = dohProvider,
            customDohUrl = customDohUrl,
            customBootstrapIp = customBootstrapIp,
            engineMode = engine,
            proxyPolicy = policy,
            requestTimeoutSeconds = requestTimeoutSeconds,
            userAgent = userAgent,
            strictDoh = strictDoh
        )
    }

    var enableNotificationPolling: Boolean = true
    var notificationActiveIntervalSeconds: Int = Constants.DEFAULT_NOTIFICATION_ACTIVE_INTERVAL_SECONDS
    var notificationInactiveIntervalSeconds: Int = Constants.DEFAULT_NOTIFICATION_INACTIVE_INTERVAL_SECONDS

    @Transient
    private val settingsListeners = java.util.concurrent.CopyOnWriteArrayList<(LinuxDoSettingsState) -> Unit>()

    fun addSettingsListener(owner: com.intellij.openapi.Disposable, listener: (LinuxDoSettingsState) -> Unit) {
        addSettingsListener(listener)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { settingsListeners.remove(listener) })
    }

    fun addSettingsListener(listener: (LinuxDoSettingsState) -> Unit) {
        settingsListeners.add(listener)
    }

    fun removeSettingsListener(listener: (LinuxDoSettingsState) -> Unit) {
        settingsListeners.remove(listener)
    }

    fun fireSettingsChanged() {
        for (listener in settingsListeners) {
            try {
                listener(this)
            } catch (t: Throwable) {
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Settings listener error: ${t.message}")
            }
        }
    }

    override fun getState(): LinuxDoSettingsState {
        return this
    }

    override fun loadState(state: LinuxDoSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
        fireSettingsChanged()
    }

    companion object {
        private val DEFAULT_INSTANCE = LinuxDoSettingsState()

        fun getInstance(): LinuxDoSettingsState {
            val app = ApplicationManager.getApplication()
            return if (app != null) {
                app.getService(LinuxDoSettingsState::class.java) ?: DEFAULT_INSTANCE
            } else {
                DEFAULT_INSTANCE
            }
        }
    }
}
