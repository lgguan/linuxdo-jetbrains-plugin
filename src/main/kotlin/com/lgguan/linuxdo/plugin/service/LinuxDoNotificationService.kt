package com.lgguan.linuxdo.plugin.service

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.IdeFocusManager
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.lgguan.linuxdo.plugin.model.DiscourseNotification
import com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
import com.lgguan.linuxdo.plugin.net.RateLimitException
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeListener
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/**
 * Linux Do 通知服务 (Application-level 单例).
 *
 * 核心安全机制设计:
 * 1. 【单IDE多窗口联动】：Application 级全局单例，无论开多少个项目窗口，后台始终仅有 1 个调度器，状态与通知在所有窗口广播同步；
 * 2. 【未激活IDE自动降权】：实时感知 IDE 焦点状态。当用户切到其他软件或离开电脑时，自动将轮询权重降低（间隔拉长到 5 分钟），杜绝后台挂机刷量；
 * 3. 【随机时间抖动 (Jitter)】：每次调度动态注入 ±20% 随机偏移，打散周期特征，彻底消除机械机器人心跳指纹；
 * 4. 【429 智能熔断保护 (Circuit Breaker)】：遇到 HTTP 429 或 Cloudflare 连续拦截时，自动触发 15 分钟熔断冷却，停止请求并向用户弹窗提醒，严防封号。
 */
class LinuxDoNotificationService(
    private val auth: LinuxDoAuthService = LinuxDoAuthService.getInstance(),
    private val fetch: () -> Result<List<DiscourseNotification>> = { DiscourseApiClient.getNotifications() },
    initialize: Boolean = true
) : Disposable {
    @Volatile private var disposed = false

    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()

    @Volatile
    var unreadCount: Int = 0
        private set

    val recentNotifications = CopyOnWriteArrayList<DiscourseNotification>()
    private val countListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val notificationListeners = CopyOnWriteArrayList<(List<DiscourseNotification>) -> Unit>()

    private val lastKnownNotificationIds = Collections.synchronizedSet(HashSet<Long>())
    @Volatile
    private var isInitialized = false

    @Volatile
    var lastFetchTimestamp: Long = 0L
        private set

    // ==========================================
    // 429 智能安全熔断状态
    // ==========================================
    @Volatile
    var circuitBreakerUntilTimestamp: Long = 0L
        private set

    @Volatile
    var circuitBreakerReason: String = ""
        private set

    // ==========================================
    // 焦点感知与防抖状态
    // ==========================================
    @Volatile
    private var lastFocusWakeupTimestamp: Long = 0L

    private val focusChangeListener = PropertyChangeListener { evt ->
        if ("activeWindow" == evt.propertyName && evt.newValue != null) {
            onIdeFocusRegained()
        }
    }

    private var pollFuture: ScheduledFuture<*>? = null
    private val executor: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "LinuxDo-Notification-Poller").apply { isDaemon = true }
        }
    }

    init {
        if (initialize) try {
            // 1. 监听登录状态自适应启停
            auth.addAuthListener(listenerLifetime) { user ->
                if (user != null) {
                    resetCircuitBreaker()
                    startPolling()
                    refreshNotifications()
                } else {
                    stopPolling()
                    resetCircuitBreaker()
                    recentNotifications.clear()
                    lastKnownNotificationIds.clear()
                    isInitialized = false
                    updateUnreadCount(0)
                    notifyNotificationListeners(emptyList())
                }
            }

            // 2. 监听 IDE 全局窗口焦点变化
            try {
                KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .addPropertyChangeListener("activeWindow", focusChangeListener)
            } catch (t: Throwable) {
                LinuxDoLog.warn("Failed to register focus change listener: ${t.message}")
            }

            // 3. 监听插件设置变化（若设置开启/关闭或更改了间隔）
            LinuxDoSettingsState.getInstance().addSettingsListener(listenerLifetime) { settings ->
                if (settings.enableNotificationPolling && auth.isLoggedIn) {
                    scheduleNextPoll(calculateNextDelaySeconds(isIdeActive()))
                } else if (!settings.enableNotificationPolling) {
                    stopPolling()
                }
            }

            if (auth.isLoggedIn) {
                startPolling()
                refreshNotifications()
            }
        } catch (t: Throwable) {
            LinuxDoLog.warn("LinuxDoNotificationService init error: ${t.message}")
        }
    }

    fun addCountListener(owner: com.intellij.openapi.Disposable, listener: (Int) -> Unit) {
        addCountListener(listener)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { countListeners.remove(listener) })
    }

    fun addCountListener(listener: (Int) -> Unit) {
        countListeners.add(listener)
        listener(unreadCount)
    }

    fun removeCountListener(listener: (Int) -> Unit) {
        countListeners.remove(listener)
    }

    fun addNotificationListener(owner: com.intellij.openapi.Disposable, listener: (List<DiscourseNotification>) -> Unit) {
        addNotificationListener(listener)
        com.intellij.openapi.util.Disposer.register(owner, com.intellij.openapi.Disposable { notificationListeners.remove(listener) })
    }

    fun addNotificationListener(listener: (List<DiscourseNotification>) -> Unit) {
        notificationListeners.add(listener)
        listener(recentNotifications.toList())
    }

    fun removeNotificationListener(listener: (List<DiscourseNotification>) -> Unit) {
        notificationListeners.remove(listener)
    }

    // ==========================================
    // 焦点感知与窗口活跃检测
    // ==========================================

    /**
     * 判断当前 IDE 进程是否处于前台激活状态
     */
    fun isIdeActive(): Boolean {
        return try {
            val kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            if (kfm.activeWindow != null) return true
            Frame.getFrames().any { it.isShowing && it.isActive }
        } catch (_: Throwable) {
            true // 异常时默认活跃
        }
    }

    /**
     * 当用户从其他应用（浏览器、微信等）重新切回 IDE 时触发
     */
    private fun onIdeFocusRegained() {
        if (!auth.isLoggedIn) return
        if (isCircuitBroken()) return
        val settings = LinuxDoSettingsState.getInstance()
        if (!settings.enableNotificationPolling) return

        val now = System.currentTimeMillis()
        // 防抖：切回触发至少间隔 30 秒，防止快速连续 Alt+Tab 刷请求
        if (now - lastFocusWakeupTimestamp < 30_000L) return

        val activeIntervalMs = settings.notificationActiveIntervalSeconds * 1000L
        val elapsedSinceLastFetch = now - lastFetchTimestamp

        // 如果距离上次刷新已超过前台正常轮询周期，安排一次轻量随机延迟刷新 (2~5秒)
        if (elapsedSinceLastFetch >= activeIntervalMs) {
            lastFocusWakeupTimestamp = now
            val jitterDelay = ThreadLocalRandom.current().nextLong(2L, 6L)
            LinuxDoLog.info("IDE focus regained after ${elapsedSinceLastFetch / 1000}s idle, scheduled quick poll in ${jitterDelay}s")
            scheduleNextPoll(jitterDelay)
        }
    }

    // ==========================================
    // 轮询调度与随机抖动 (Jitter)
    // ==========================================

    @Synchronized
    fun startPolling() {
        if (pollFuture != null && !pollFuture!!.isCancelled && !pollFuture!!.isDone) return
        val settings = LinuxDoSettingsState.getInstance()
        if (!settings.enableNotificationPolling) return

        // 首次启动延迟 5 秒探测
        scheduleNextPoll(5L)
        LinuxDoLog.info("Notification poller started with focus-aware backoff & jitter")
    }

    @Synchronized
    fun stopPolling() {
        pollFuture?.cancel(true)
        pollFuture = null
    }

    @Synchronized
    fun scheduleNextPoll(delaySeconds: Long) {
        pollFuture?.cancel(false)
        try {
            pollFuture = executor.schedule(
                {
                    try {
                        runPollingCycle()
                    } catch (t: Throwable) {
                        LinuxDoLog.warn("Notification polling cycle error: ${t.message}")
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS
            )
            LinuxDoLog.info("Scheduled next notification poll in ${delaySeconds}s (IDE active: ${isIdeActive()})")
        } catch (e: Throwable) {
            LinuxDoLog.warn("Failed to schedule notification poll: ${e.message}")
        }
    }

    private fun runPollingCycle() {
        if (!auth.isLoggedIn) return
        val settings = LinuxDoSettingsState.getInstance()
        if (!settings.enableNotificationPolling) {
            scheduleNextPoll(120L)
            return
        }

        // 1. 检查防风控熔断保护
        if (isCircuitBroken()) {
            val remainingSec = getRemainingCircuitBreakerSeconds()
            LinuxDoLog.info("Circuit breaker active, skipping background poll. Remaining cooldown: ${remainingSec}s")
            val nextCheckDelay = (remainingSec + ThreadLocalRandom.current().nextLong(10L, 30L)).coerceAtLeast(30L)
            scheduleNextPoll(nextCheckDelay)
            return
        }

        // 2. 执行网络请求拉取通知
        try {
            doFetchNotifications()
        } catch (t: Throwable) {
            LinuxDoLog.warn("Error during doFetchNotifications: ${t.message}")
        }

        // 3. 计算下一次调度的延迟（根据 IDE 活跃状态与随机抖动）
        val active = isIdeActive()
        val nextDelay = calculateNextDelaySeconds(active)
        scheduleNextPoll(nextDelay)
    }

    /**
     * 计算下次轮询延迟时间（秒）
     * - 活跃状态：基准（默认 60s）± 20% 随机抖动
     * - 未激活/后台状态：大幅降权基准（默认 300s）± 20% 随机抖动
     */
    fun calculateNextDelaySeconds(isActive: Boolean): Long {
        val settings = LinuxDoSettingsState.getInstance()
        val baseSeconds = if (isActive) {
            settings.notificationActiveIntervalSeconds.toLong().coerceAtLeast(Constants.MIN_NOTIFICATION_INTERVAL_SECONDS.toLong())
        } else {
            // 未激活状态降低轮询权重
            settings.notificationInactiveIntervalSeconds.toLong().coerceAtLeast(120L)
        }

        // ±20% 随机抖动范围，至少 5 秒
        val jitterSpan = (baseSeconds * 0.20).toLong().coerceAtLeast(5L)
        val jitter = ThreadLocalRandom.current().nextLong(-jitterSpan, jitterSpan + 1)
        val finalDelay = (baseSeconds + jitter).coerceAtLeast(Constants.MIN_NOTIFICATION_INTERVAL_SECONDS.toLong())
        return finalDelay
    }

    // ==========================================
    // 429 智能安全熔断机制 (Circuit Breaker)
    // ==========================================

    fun isCircuitBroken(): Boolean {
        return System.currentTimeMillis() < circuitBreakerUntilTimestamp
    }

    fun getRemainingCircuitBreakerSeconds(): Long {
        val diff = circuitBreakerUntilTimestamp - System.currentTimeMillis()
        return (diff / 1000L).coerceAtLeast(0L)
    }

    fun triggerCircuitBreaker(reason: String, cooldownSeconds: Long = Constants.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS) {
        val finalCooldown = cooldownSeconds.coerceAtLeast(300L) // 熔断保护最少 5 分钟
        circuitBreakerUntilTimestamp = System.currentTimeMillis() + (finalCooldown * 1000L)
        circuitBreakerReason = reason
        LinuxDoLog.warn("Circuit breaker TRIGGERED: $reason. Cooldown for ${finalCooldown}s until ${java.util.Date(circuitBreakerUntilTimestamp)}")

        // 向开发者展示一次温和的防封提示气泡
        pushCircuitBreakerToast(reason, finalCooldown)

        // 重新安排调度至熔断冷却后
        val nextDelay = finalCooldown + ThreadLocalRandom.current().nextLong(15L, 45L)
        scheduleNextPoll(nextDelay)
    }

    fun resetCircuitBreaker() {
        circuitBreakerUntilTimestamp = 0L
        circuitBreakerReason = ""
    }

    private fun pushCircuitBreakerToast(reason: String, cooldownSeconds: Long) {
        val app = ApplicationManager.getApplication() ?: return
        val epoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        app.invokeLater {
            if (disposed || epoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
            try {
                val targetProject = IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
                    ?: ProjectManager.getInstance().openProjects.firstOrNull { it.isOpen }
                val minutes = (cooldownSeconds + 59) / 60
                val content = "检测到 <b>$reason</b>，插件已自动进入安全熔断保护模式。<br>已<b>暂停自动轮询 $minutes 分钟</b>，防止高频访问导致账号被风控或临时封禁。"
                val group = try {
                    NotificationGroupManager.getInstance().getNotificationGroup("LinuxDo Notifications")
                } catch (_: Throwable) { null }

                val notification = group?.createNotification(
                    "Linux Do 频控熔断保护 (HTTP 429)",
                    content,
                    NotificationType.WARNING
                ) ?: Notification(
                    "LinuxDo Notifications",
                    "Linux Do 频控熔断保护 (HTTP 429)",
                    content,
                    NotificationType.WARNING
                )
                notification.notify(targetProject)
            } catch (e: Throwable) {
                LinuxDoLog.warn("Failed to push circuit breaker toast: ${e.message}")
            }
        }
    }

    // ==========================================
    // 数据拉取与更新核心
    // ==========================================

    fun refreshNotifications(onComplete: ((List<DiscourseNotification>) -> Unit)? = null) {
        val requestVersion = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        if (!auth.isLoggedIn) {
            updateUnreadCount(0)
            onComplete?.invoke(emptyList())
            return
        }

        // 处于熔断保护期时直接拦截网络发包
        if (isCircuitBroken()) {
            val remaining = getRemainingCircuitBreakerSeconds()
            LinuxDoLog.warn("refreshNotifications skipped: circuit breaker active for ${remaining}s")
            onComplete?.invoke(recentNotifications.toList())
            return
        }

        val app = ApplicationManager.getApplication()
        if (app != null) {
            app.executeOnPooledThread {
                val list = doFetchNotifications()
                val epoch = requestVersion
        app.invokeLater {
            if (disposed || epoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                    onComplete?.invoke(list)
                }
            }
        } else {
            val list = doFetchNotifications()
            onComplete?.invoke(list)
        }
    }

    private fun doFetchNotifications(): List<DiscourseNotification> {
        if (disposed || isCircuitBroken()) {
            return recentNotifications.toList()
        }

        if (disposed || !auth.isLoggedIn) return emptyList()
        val epoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        val result = fetch()
        return com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(epoch) {
        if (disposed) return@ifCurrent emptyList()
        lastFetchTimestamp = System.currentTimeMillis()

        if (result.isSuccess) {
            val list = result.getOrNull() ?: emptyList()
            val isFirstRun = !isInitialized
            isInitialized = true

            // 增量检测真正新到的未读通知
            val newUnread = if (!isFirstRun) {
                list.filter { it.id !in lastKnownNotificationIds && !it.read }
            } else {
                emptyList()
            }

            for (item in list) {
                lastKnownNotificationIds.add(item.id)
            }

            recentNotifications.clear()
            recentNotifications.addAll(list)

            val unread = list.count { !it.read }
            updateUnreadCount(unread)
            notifyNotificationListeners(list)

            // 推送 IDE 原生气泡
            if (newUnread.isNotEmpty()) {
                for (notification in newUnread) {
                    pushIdeNotification(notification)
                }
            }
            list
        } else {
            val error = result.exceptionOrNull()
            LinuxDoLog.warn("doFetchNotifications failed: ${error?.message}")

            if (error is RateLimitException) {
                triggerCircuitBreaker(
                    reason = "论坛访问过于频繁 (HTTP 429)",
                    cooldownSeconds = error.retryAfterSeconds
                )
            } else if (error is CloudflareChallengeException) {
                triggerCircuitBreaker(
                    reason = "Cloudflare 安全验证拦截",
                    cooldownSeconds = 600L
                )
            }

            recentNotifications.toList()
        }
            } ?: emptyList()
    }

    fun pushIdeNotification(notification: DiscourseNotification, project: Project? = null) {
        val app = ApplicationManager.getApplication() ?: return
        val epoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        app.invokeLater {
            if (disposed || epoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
            try {
                val targetProject = project
                    ?: IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
                    ?: ProjectManager.getInstance().openProjects.firstOrNull { it.isOpen }

                val author = com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer.escapeHtml(notification.getDisplayAuthor())
                val action = notification.getTypeActionLabel()
                val title = com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer.escapeHtml(notification.getDisplayTitle())
                val content = "<b>@$author</b> $action<br><span style='color:gray;'>$title</span>"

                val group = try {
                    NotificationGroupManager.getInstance().getNotificationGroup("LinuxDo Notifications")
                } catch (_: Throwable) {
                    null
                }

                val ideNotification = group?.createNotification(
                    "Linux Do 新通知",
                    content,
                    NotificationType.INFORMATION
                ) ?: Notification(
                    "LinuxDo Notifications",
                    "Linux Do 新通知",
                    content,
                    NotificationType.INFORMATION
                )

                if (notification.topicId != null) {
                    ideNotification.addAction(
                        NotificationAction.createSimpleExpiring("查看帖子 (View Post)") {
                            val p = targetProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
                            if (p != null) {
                                LinuxDoEditorOpener.openTopic(
                                    p,
                                    notification.topicId,
                                    notification.getDisplayTitle(),
                                    postNumber = notification.postNumber
                                )
                                markAsRead(notification.id)
                            }
                        }
                    )
                }

                ideNotification.notify(targetProject)
                LinuxDoLog.info("Pushed IDE notification: id=${notification.id}")
            } catch (e: Throwable) {
                LinuxDoLog.warn("Failed to push IDE notification: ${e.message}")
            }
        }
    }

    fun markAsRead(notificationId: Long? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (disposed || !auth.isLoggedIn || isCircuitBroken()) { onComplete?.invoke(false); return }
        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        val work = {
            val result = if (version == com.lgguan.linuxdo.plugin.net.SessionEpoch.current)
                DiscourseApiClient.markNotificationRead(notificationId, version) else Result.success(false)
            com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(version) {
                if (!disposed) {
                    if (result.getOrDefault(false)) {
                        recentNotifications.filter { notificationId == null || it.id == notificationId }.forEach { it.read = true }
                        updateUnreadCount(recentNotifications.count { !it.read })
                        notifyNotificationListeners(recentNotifications.toList())
                    } else (result.exceptionOrNull() as? RateLimitException)?.let { triggerCircuitBreaker("HTTP 429", it.retryAfterSeconds) }
                    com.lgguan.linuxdo.plugin.common.invokeLoginUiLater {
                        if (!disposed && version == com.lgguan.linuxdo.plugin.net.SessionEpoch.current) onComplete?.invoke(result.getOrDefault(false))
                    }
                }
            }
            Unit
        }
        ApplicationManager.getApplication()?.executeOnPooledThread { work() } ?: work()
    }

    private fun updateUnreadCount(count: Int) {
        unreadCount = count
        val app = ApplicationManager.getApplication()
        if (app != null) {
            val epoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        app.invokeLater {
            if (disposed || epoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                for (listener in countListeners) {
                    try {
                        listener(count)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } else {
            for (listener in countListeners) {
                try {
                    listener(count)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun notifyNotificationListeners(list: List<DiscourseNotification>) {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            val epoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        app.invokeLater {
            if (disposed || epoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                for (listener in notificationListeners) {
                    try {
                        listener(list)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } else {
            for (listener in notificationListeners) {
                try {
                    listener(list)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun dispose() {
        disposed = true
        com.intellij.openapi.util.Disposer.dispose(listenerLifetime)
        countListeners.clear()
        notificationListeners.clear()
        stopPolling()
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removePropertyChangeListener("activeWindow", focusChangeListener)
        } catch (_: Throwable) {}
        try {
            executor.shutdownNow()
        } catch (_: Throwable) {}
    }

    companion object {
        fun getInstance(): LinuxDoNotificationService {
            return ApplicationManager.getApplication()?.getService(LinuxDoNotificationService::class.java)
                ?: LinuxDoNotificationServiceHolder.INSTANCE
        }
    }

    private object LinuxDoNotificationServiceHolder {
        val INSTANCE = LinuxDoNotificationService()
    }
}
