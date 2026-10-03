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
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.net.StaleSessionException
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
    private val fetch: (NotificationQuery, Long) -> Result<NotificationListResponse> = { query, version -> DiscourseApiClient.getNotifications(query, version) },
    initialize: Boolean = true,
    private val fetchTotals: (Long) -> Result<NotificationTotals> = DiscourseApiClient::getNotificationTotals,
    private val writeRead: (Long?, Long) -> Result<Boolean> = DiscourseApiClient::markNotificationRead,
    private val fetchTypes: (Long) -> Result<Map<String, Int>> = DiscourseApiClient::getNotificationTypes,
    private val baseUrl: () -> String = DiscourseApiClient::getBaseUrl
) : Disposable {
    @Volatile private var disposed = false

    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()

    @Volatile
    var unreadCount: Int = -1
        private set

    val recentNotifications = CopyOnWriteArrayList<DiscourseNotification>()
    private val countListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val notificationListeners = CopyOnWriteArrayList<(List<DiscourseNotification>) -> Unit>()

    @Volatile var countState = NotificationCountState()
        private set
    @Volatile var lastReadError: String? = null
        private set
    private val stateLock = Any()
    private val requestLock = Any()
    private val flights = mutableMapOf<Pair<Long, NotificationQuery>, MutableList<(Result<NotificationHistory>) -> Unit>>()
    private val histories = mutableMapOf<NotificationStatus, NotificationHistory>()
    private var typesVersion: Long? = null
    private var cacheVersion = SessionEpoch.current

    fun history(status: NotificationStatus): NotificationHistory = synchronized(stateLock) {
        ensureSession()
        histories[status] ?: NotificationHistory()
    }

    private fun ensureSession() {
        if (cacheVersion != SessionEpoch.current) {
            cacheVersion = SessionEpoch.current
            histories.clear()
            recentNotifications.clear()
            lastKnownNotificationIds.clear()
            isInitialized = false
            typesVersion = null
            lastReadError = null
            countState = NotificationCountState()
            unreadCount = -1
        }
    }

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
                val sessionChanged = synchronized(stateLock) {
                    val changed = cacheVersion != SessionEpoch.current
                    ensureSession()
                    changed
                }
                updateUnreadCount(countState.totals?.total ?: -1)
                notifyNotificationListeners(recentNotifications.toList())
                if (user != null) {
                    if (sessionChanged) resetCircuitBreaker()
                    startPolling()
                    refreshNotifications()
                } else {
                    stopPolling()
                    resetCircuitBreaker()
                    synchronized(stateLock) {
                        histories.clear()
                        recentNotifications.clear()
                        lastKnownNotificationIds.clear()
                        isInitialized = false
                        countState = NotificationCountState()
                        lastReadError = null
                    }
                    updateUnreadCount(-1)
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
            refreshNotifications()
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
                val content = "检测到 <b>$reason</b>。<br>已<b>暂停自动轮询 $minutes 分钟</b>，冷却结束后自动恢复。"
                val group = try {
                    NotificationGroupManager.getInstance().getNotificationGroup("LinuxDo Notifications")
                } catch (_: Throwable) { null }

                val notification = group?.createNotification(
                    "Linux Do 自动轮询已暂停",
                    content,
                    NotificationType.WARNING
                ) ?: Notification(
                    "LinuxDo Notifications",
                    "Linux Do 自动轮询已暂停",
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
        requestPage(NotificationQuery()) { onComplete?.invoke(recentNotifications.toList()) }
    }

    fun refresh(status: NotificationStatus, onComplete: (Result<NotificationHistory>) -> Unit = {}) =
        requestPage(NotificationQuery(status), onComplete)

    fun loadMore(status: NotificationStatus, onComplete: (Result<NotificationHistory>) -> Unit = {}) {
        val cached = history(status)
        val query = cached.failedQuery ?: cached.next ?: if (!cached.loaded) NotificationQuery(status) else null
        if (query == null) { onComplete(Result.success(cached)); return }
        requestPage(query, onComplete)
    }

    /** Polling and every window join the same in-flight request. Network and read writes are serialized. */
    private fun requestPage(query: NotificationQuery, onComplete: (Result<NotificationHistory>) -> Unit) {
        val version = SessionEpoch.current
        if (disposed || !auth.isLoggedIn || isCircuitBroken()) {
            onComplete(Result.failure(IllegalStateException(if (isCircuitBroken()) "通知请求正在冷却，稍后重试" else "请先登录")))
            return
        }
        val userId = auth.currentUser?.id ?: run {
            onComplete(Result.failure(StaleSessionException()))
            return
        }
        val key = version to query
        synchronized(flights) {
            flights[key]?.let { it.add(onComplete); return }
            flights[key] = mutableListOf(onComplete)
        }
        val work = {
            val result = synchronized(requestLock) { readPage(query, version, userId) }
            val callbacks = synchronized(flights) { flights.remove(key).orEmpty().toList() }
            dispatch { callbacks.forEach { callback -> runCatching { callback(result) } } }
        }
        ApplicationManager.getApplication()?.executeOnPooledThread { work() } ?: work()
    }

    private fun readPage(query: NotificationQuery, version: Long, userId: Long): Result<NotificationHistory> {
        if (disposed || version != SessionEpoch.current || auth.currentUser?.id != userId) return Result.failure(StaleSessionException())
        if (isCircuitBroken()) return Result.failure(IllegalStateException("通知请求正在冷却，稍后重试"))
        // One configuration read per account; unconfirmed plugin IDs use generic text and a web target.
        if (typesVersion != version) {
            val types = runCatching { fetchTypes(version).getOrThrow() }
            SessionEpoch.ifCurrent(version) {
                types.getOrNull()?.let { NotificationTypes.configure(version, it) }
                typesVersion = version
            }
            handleFailure(types.exceptionOrNull())
        }
        if (isCircuitBroken() || version != SessionEpoch.current || auth.currentUser?.id != userId) return Result.failure(IllegalStateException("通知请求已暂停"))
        val page = runCatching { fetch(query, version).getOrThrow() }
        val counts = if (query.offset == 0 && !isCircuitBroken() && version == SessionEpoch.current &&
            page.exceptionOrNull() !is RateLimitException && page.exceptionOrNull() !is CloudflareChallengeException)
            runCatching { fetchTotals(version).getOrThrow() } else null
        return SessionEpoch.ifCurrent(version) {
            synchronized(stateLock) {
                ensureSession()
                if (disposed || auth.currentUser?.id != userId) return@synchronized Result.failure<NotificationHistory>(StaleSessionException())
                lastFetchTimestamp = System.currentTimeMillis()
                counts?.let {
                    countState = if (it.isSuccess) NotificationCountState(it.getOrThrow()) else countState.copy(stale = true)
                    handleFailure(it.exceptionOrNull())
                    updateUnreadCount(countState.totals?.total ?: -1)
                }
                val previous = histories[query.status] ?: NotificationHistory()
                val result = page.mapCatching { previous.merge(query, it, baseUrl()) }
                if (result.isSuccess) {
                    val updated = result.getOrThrow()
                    histories[query.status] = updated
                    // Refresh read flags across loaded filters without dropping history.
                    val flags = page.getOrThrow().notifications.associateBy { it.id }
                    histories.replaceAll { status, value ->
                        val removedUnread = value.items.count { !it.read && flags[it.id]?.read == true }
                        value.copy(items = value.items.map { flags[it.id] ?: it }.filter(status::accepts),
                            next = if (status == NotificationStatus.UNREAD && removedUnread > 0)
                                value.next?.let { it.copy(offset = (it.offset - removedUnread).coerceAtLeast(0)) } else value.next)
                    }
                    if (query.status == NotificationStatus.ALL) {
                        val head = page.getOrThrow().notifications
                        val newUnread = if (isInitialized && query.offset == 0) head.filter { it.id !in lastKnownNotificationIds && !it.read } else emptyList()
                        if (query.offset == 0) isInitialized = true
                        head.forEach { lastKnownNotificationIds.add(it.id) }
                        newUnread.forEach { pushIdeNotification(it) }
                    }
                    recentNotifications.clear()
                    recentNotifications.addAll(histories[NotificationStatus.ALL]?.items.orEmpty())
                } else {
                    handleFailure(result.exceptionOrNull())
                    histories[query.status] = previous.copy(error = "通知加载失败，已有内容已保留；请重试", failedQuery = query)
                }
                notifyNotificationListeners(recentNotifications.toList())
                result
            }
        } ?: Result.failure(StaleSessionException())
    }

    private fun handleFailure(error: Throwable?) {
        when (error) {
            is RateLimitException -> triggerCircuitBreaker("论坛访问过于频繁 (HTTP 429)", error.retryAfterSeconds)
            is CloudflareChallengeException -> triggerCircuitBreaker("Cloudflare 安全验证拦截", 600L)
        }
    }

    private fun dispatch(action: () -> Unit) {
        val app = ApplicationManager.getApplication()
        if (app != null) app.invokeLater { if (!disposed) action() } else if (!disposed) action()
    }

    /** Both popup and IDE balloon wait for the reader to acknowledge the displayed target. */
    fun openNotification(project: Project, notification: DiscourseNotification, onComplete: ((Boolean) -> Unit)? = null) {
        val version = SessionEpoch.current
        val userId = auth.currentUser?.id
        if (userId == null || disposed) { onComplete?.invoke(false); return }
        val target = NotificationTypes.topicTarget(notification)
        if (target == null) {
            com.intellij.ide.BrowserUtil.browse(NotificationTypes.webTarget(notification, baseUrl()))
            onComplete?.invoke(false)
            return
        }
        LinuxDoEditorOpener.openTopic(project, target, notification.getDisplayTitle(), notification.postNumber) { outcome ->
            if (outcome == com.lgguan.linuxdo.plugin.editor.TopicOpenResult.SUCCESS && version == SessionEpoch.current && auth.currentUser?.id == userId && !disposed) {
                if (!notification.read) markAsRead(notification.id, onComplete) else onComplete?.invoke(true)
            } else {
                if (version == SessionEpoch.current && auth.currentUser?.id == userId) {
                    lastReadError = "通知目标未成功显示，未读状态已保留；可重试打开或手动标读"
                    notifyNotificationListeners(recentNotifications.toList())
                }
                onComplete?.invoke(false)
            }
        }
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

                ideNotification.addAction(
                    NotificationAction.createSimple("查看通知 / 重试") {
                        val p = targetProject ?: ProjectManager.getInstance().openProjects.firstOrNull()
                        if (epoch != SessionEpoch.current) ideNotification.expire()
                        else if (p != null) openNotification(p, notification) { if (it) ideNotification.expire() }
                    }
                )
                if (!notification.read) ideNotification.addAction(
                    NotificationAction.createSimple("手动标为已读 / 重试") {
                        if (epoch != SessionEpoch.current) ideNotification.expire()
                        else markAsRead(notification.id) { if (it) ideNotification.expire() }
                    }
                )

                ideNotification.notify(targetProject)
                LinuxDoLog.info("Pushed IDE notification: id=${notification.id}")
            } catch (e: Throwable) {
                LinuxDoLog.warn("Failed to push IDE notification: ${e.message}")
            }
        }
    }

    fun markAsRead(notificationId: Long? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (disposed || !auth.isLoggedIn || isCircuitBroken()) { onComplete?.invoke(false); return }
        val version = SessionEpoch.current
        val userId = auth.currentUser?.id
        val work = {
            synchronized(requestLock) {
                val result = if (version == SessionEpoch.current && auth.currentUser?.id == userId && userId != null && !disposed && !isCircuitBroken())
                    runCatching { writeRead(notificationId, version).getOrThrow() } else Result.failure(StaleSessionException())
                SessionEpoch.ifCurrent(version) {
                    synchronized(stateLock) {
                        ensureSession()
                        if (!disposed && auth.currentUser?.id == userId) {
                            if (result.getOrDefault(false)) {
                                val affected = histories.values.flatMap { it.items }.filter { notificationId == null || it.id == notificationId }
                                    .distinctBy { it.id }.map { it.copy(read = true) }
                                val ids = affected.map { it.id }.toSet()
                                histories.replaceAll { status, value ->
                                    val removed = value.items.count { !it.read && it.id in ids }
                                    value.copy(items = (value.items.map { if (it.id in ids) it.copy(read = true) else it } +
                                        if (status == NotificationStatus.READ) affected else emptyList()).distinctBy { it.id }.filter(status::accepts).sortedByDescending { it.id },
                                        next = when {
                                            status == NotificationStatus.UNREAD && notificationId == null -> null
                                            status == NotificationStatus.UNREAD -> value.next?.let { it.copy(offset = (it.offset - removed).coerceAtLeast(0)) }
                                            status == NotificationStatus.READ -> NotificationQuery(status)
                                            else -> value.next
                                        }, error = null, failedQuery = null)
                                }
                                recentNotifications.replaceAll { if (notificationId == null || it.id == notificationId) it.copy(read = true) else it }
                                lastReadError = null
                            } else lastReadError = "标读失败，未读状态已保留；可手动重试"
                            handleFailure(result.exceptionOrNull())
                            notifyNotificationListeners(recentNotifications.toList())
                        }
                    }
                }
                if (result.getOrDefault(false) && version == SessionEpoch.current && auth.currentUser?.id == userId && !disposed) {
                    val totals = if (!isCircuitBroken()) runCatching { fetchTotals(version).getOrThrow() } else Result.failure(IllegalStateException("请求正在冷却"))
                    SessionEpoch.ifCurrent(version) {
                        if (!disposed && auth.currentUser?.id == userId) {
                            countState = if (totals.isSuccess) NotificationCountState(totals.getOrThrow()) else countState.copy(stale = true)
                            handleFailure(totals.exceptionOrNull())
                            updateUnreadCount(countState.totals?.total ?: -1)
                            notifyNotificationListeners(recentNotifications.toList())
                        }
                    }
                }
                dispatch { onComplete?.invoke(result.getOrDefault(false) && version == SessionEpoch.current && auth.currentUser?.id == userId) }
            }
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
