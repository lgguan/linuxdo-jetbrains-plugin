package com.lgguan.linuxdo.plugin

import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.editor.TopicOpenRequest
import com.lgguan.linuxdo.plugin.editor.TopicOpenResult
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.*
import com.lgguan.linuxdo.plugin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NotificationWorkflowTest {
    private val services = mutableListOf<LinuxDoNotificationService>()
    @AfterEach fun cleanup() { services.forEach { it.dispose() }; SessionEpoch.advance() }
    private fun auth() = LinuxDoAuthService(PersistentCookieJar(false).apply { injectCookie("_t", "synthetic") }).apply {
        setCurrentUserDirectly(UserInfo(7, "fixture"))
    }

    private class Server {
        val rows = (75L downTo 1).map { DiscourseNotification(it, 2, topicId = 8, postNumber = it.toInt()) }.toMutableList()
        val queries = mutableListOf<NotificationQuery>()
        val writes = mutableListOf<Long?>()
        var pageFailure: Throwable? = null
        var countFailure: Throwable? = null
        var writeFailure: Throwable? = null
        var totalReads = 0
        fun page(query: NotificationQuery): Result<NotificationListResponse> {
            queries += query
            pageFailure?.let { return Result.failure(it) }
            val matching = rows.filter(query.status::accepts)
            val items = matching.drop(query.offset).take(query.limit).map { it.copy() }
            val filter = query.status.filter?.let { "&filter=$it" }.orEmpty()
            return Result.success(NotificationListResponse(items, matching.size, 99,
                "/notifications?offset=${query.offset + query.limit}&limit=${query.limit}$filter"))
        }
        fun totals(): Result<NotificationTotals> {
            totalReads++
            return countFailure?.let { Result.failure(it) } ?: Result.success(NotificationTotals(rows.count { !it.read }, 8))
        }
        fun write(id: Long?): Result<Boolean> {
            writes += id
            writeFailure?.let { return Result.failure(it) }
            rows.replaceAll { if (id == null || it.id == id) it.copy(read = true) else it }
            return Result.success(true)
        }
    }
    private fun service(server: Server, auth: LinuxDoAuthService = auth()) = LinuxDoNotificationService(
        auth, { query, _ -> server.page(query) }, initialize = false, fetchTotals = { server.totals() },
        writeRead = { id, _ -> server.write(id) }, fetchTypes = { Result.success(mapOf("boosted" to 34)) }, baseUrl = { "https://linux.do" }
    ).also { services += it }

    @Test fun `history goes beyond a page and polling preserves cursor and old rows`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); service.loadMore(NotificationStatus.ALL)
        assertEquals(60, service.history(NotificationStatus.ALL).items.size)
        server.rows.add(0, DiscourseNotification(76, 2))
        service.refreshNotifications()
        assertEquals(61, service.history(NotificationStatus.ALL).items.size)
        assertEquals(60, service.history(NotificationStatus.ALL).next!!.offset)
        service.loadMore(NotificationStatus.ALL) // offset overlap caused by the new head is deduplicated
        assertEquals(76, service.history(NotificationStatus.ALL).items.size)
        assertNull(service.history(NotificationStatus.ALL).next)
        assertEquals(listOf(0, 30, 0, 60), server.queries.map { it.offset })
    }

    @Test fun `a burst larger than one page exposes the new head gap without dropping history`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); service.loadMore(NotificationStatus.ALL)
        server.rows.addAll(0, (175L downTo 76L).map { DiscourseNotification(it, 2) })
        service.refreshNotifications()
        assertEquals(90, service.recentNotifications.size)
        assertEquals(30, service.history(NotificationStatus.ALL).next!!.offset)
        while (service.history(NotificationStatus.ALL).next != null) service.loadMore(NotificationStatus.ALL)
        assertEquals(175, service.recentNotifications.size)
    }

    @Test fun `refresh removes deleted head entries and an exhausted empty status has no stale rows`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); server.rows.removeAt(0); service.refreshNotifications()
        assertFalse(service.recentNotifications.any { it.id == 75L })
        service.refresh(NotificationStatus.UNREAD); server.rows.replaceAll { it.copy(read = true) }
        service.refresh(NotificationStatus.UNREAD)
        assertTrue(service.history(NotificationStatus.UNREAD).items.isEmpty())
        assertNull(service.history(NotificationStatus.UNREAD).next)
    }

    @Test fun `failed page retry keeps contents and requests the same offset`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); server.pageFailure = IOException("offline")
        service.loadMore(NotificationStatus.ALL)
        assertEquals(30, service.recentNotifications.size)
        assertEquals(30, service.history(NotificationStatus.ALL).failedQuery!!.offset)
        server.pageFailure = null; service.loadMore(NotificationStatus.ALL)
        assertEquals(60, service.recentNotifications.size)
        assertNull(service.history(NotificationStatus.ALL).error)
    }

    @Test fun `refresh failure retains all history and can retry head`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); service.loadMore(NotificationStatus.ALL)
        server.pageFailure = HttpStatusException(403); service.refreshNotifications()
        assertEquals(60, service.recentNotifications.size)
        assertEquals(0, service.history(NotificationStatus.ALL).failedQuery!!.offset)
        server.pageFailure = null; service.loadMore(NotificationStatus.ALL)
        assertEquals(60, service.recentNotifications.size)
        assertEquals(60, service.history(NotificationStatus.ALL).next!!.offset)
    }

    @Test fun `empty inaccessible page can continue with known totals and empty final page stops`() {
        val query = NotificationQuery()
        val empty = NotificationListResponse(emptyList(), 70, loadMoreNotifications = "/notifications?offset=30&limit=30")
        assertEquals(30, query.next(empty, "https://linux.do")!!.offset)
        assertNull(query.copy(offset = 60).next(empty.copy(loadMoreNotifications = "/notifications?offset=90&limit=30"), "https://linux.do"))
        assertNull(query.next(NotificationListResponse(), "https://linux.do"))
    }

    @Test fun `bad pagination links cannot send credentials outside current notification endpoint`() {
        val query = NotificationQuery(NotificationStatus.UNREAD)
        listOf("https://evil.example/notifications?offset=30", "http://linux.do/notifications?offset=30",
            "//evil.example/notifications?offset=30", "https://user@linux.do/notifications?offset=30",
            "/notifications/mark-read?offset=30", "/notifications?offset=0&filter=unread",
            "/notifications?offset=30&filter=read", "/notifications?offset=30&recent=true",
            "/notifications?offset=30&offset=60&filter=unread", "/notifications?offset=30&filter=unread#fragment")
            .forEach { link -> assertThrows(IllegalArgumentException::class.java, { query.next(NotificationListResponse(totalRows = 100, loadMoreNotifications = link), "https://linux.do") }, link) }
        assertEquals(30, query.next(NotificationListResponse(totalRows = 100,
            loadMoreNotifications = "https://linux.do/notifications?offset=30&limit=30&filter=unread&username=other"), "https://linux.do")!!.offset)
        assertFalse(query.url("https://linux.do").contains("username"))
    }

    @Test fun `account count is independent of the loaded page and PMs are counted once`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications()
        assertEquals(30, service.recentNotifications.size)
        assertEquals(83, service.unreadCount)
        assertEquals(75, service.countState.totals!!.notifications)
        assertEquals(8, service.countState.totals!!.personalMessages)
        server.countFailure = IOException("no totals")
        service.refreshNotifications()
        assertEquals(83, service.unreadCount)
        assertTrue(service.countState.stale)
        assertTrue(service.countState.label.contains("未更新"))
    }

    @Test fun `unavailable counts never become a fabricated zero`() {
        val server = Server().apply { countFailure = HttpStatusException(404) }; val service = service(server)
        service.refreshNotifications()
        assertEquals(-1, service.unreadCount)
        assertEquals("未读数量暂不可用", service.countState.label)
        assertEquals(30, service.recentNotifications.size)
    }

    @Test fun `totals parsing rejects missing ordinary malformed negative fractional or overflowing counts`() {
        fun parse(text: String) = NotificationTotals.parse(JsonParser.parseString(text).asJsonObject)
        assertEquals(81, parse("""{"unread_notifications":73,"unread_personal_messages":8,"unseen_reviewables":999,"group_inboxes":[{"count":100}]}""").total)
        assertEquals(0, parse("""{"unread_notifications":0}""").total)
        listOf("{}", """{"unread_notifications":null}""", """{"unread_notifications":-1}""",
            """{"unread_notifications":"2"}""", """{"unread_notifications":0.5}""",
            """{"unread_notifications":2147483647,"unread_personal_messages":1}""").forEach { input ->
                assertThrows(Exception::class.java) { parse(input) }
            }
    }

    @Test fun `single read and account-wide read update all loaded filters and refresh totals`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); service.refresh(NotificationStatus.UNREAD); service.loadMore(NotificationStatus.UNREAD)
        service.markAsRead(75)
        assertTrue(service.recentNotifications.first { it.id == 75L }.read)
        assertFalse(service.history(NotificationStatus.UNREAD).items.any { it.id == 75L })
        assertEquals(59, service.history(NotificationStatus.UNREAD).next!!.offset)
        assertEquals(82, service.unreadCount)
        service.loadMore(NotificationStatus.UNREAD)
        assertEquals(74, service.history(NotificationStatus.UNREAD).items.size)
        service.markAsRead()
        assertEquals(listOf(75L, null), server.writes)
        assertTrue(server.rows.all { it.read }) // all 75, including rows never loaded in the all view
        assertTrue(service.history(NotificationStatus.UNREAD).items.isEmpty())
        assertEquals(8, service.unreadCount)
    }

    @Test fun `failed mark read preserves flags and retry can succeed`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); server.writeFailure = HttpStatusException(403)
        var confirmed = true
        service.markAsRead(75) { confirmed = it }
        assertFalse(confirmed); assertFalse(service.recentNotifications.first().read)
        assertEquals(83, service.unreadCount); assertNotNull(service.lastReadError)
        server.writeFailure = null; service.markAsRead(75) { confirmed = it }
        assertTrue(confirmed); assertTrue(service.recentNotifications.first().read)
    }

    @Test fun `successful write followed by failed totals retains stale last valid count`() {
        val server = Server(); val service = service(server)
        service.refreshNotifications(); server.countFailure = IOException("offline")
        service.markAsRead(75)
        assertTrue(service.recentNotifications.first().read)
        assertEquals(83, service.unreadCount)
        assertTrue(service.countState.stale)
    }

    @Test fun `rate limiting and cloudflare stop pages counts and write retry until reset`() {
        for (failure in listOf(RateLimitException(60), CloudflareChallengeException())) {
            val server = Server(); val service = service(server)
            service.refreshNotifications(); server.pageFailure = failure
            service.loadMore(NotificationStatus.ALL)
            assertTrue(service.isCircuitBroken())
            service.loadMore(NotificationStatus.ALL); service.markAsRead(75); service.refreshNotifications()
            assertEquals(2, server.queries.size); assertTrue(server.writes.isEmpty())
            assertEquals(1, server.totalReads)
            service.resetCircuitBreaker(); server.pageFailure = null; service.loadMore(NotificationStatus.ALL)
            assertEquals(60, service.recentNotifications.size)
        }
    }

    @Test fun `concurrent polling and manual refresh coalesce both page and totals`() {
        val server = Server(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val service = LinuxDoNotificationService(auth(), { query, _ ->
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); server.page(query)
        }, initialize = false, fetchTotals = { server.totals() }, fetchTypes = { Result.success(emptyMap()) }, baseUrl = { "https://linux.do" }).also { services += it }
        val callbacks = java.util.concurrent.atomic.AtomicInteger()
        val worker = thread { service.refreshNotifications { callbacks.incrementAndGet() } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        service.refreshNotifications { callbacks.incrementAndGet() }
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, server.queries.size); assertEquals(1, server.totalReads); assertEquals(2, callbacks.get())
    }

    @Test fun `account changes discard page counts and late mark acknowledgements`() {
        val auth = auth(); val server = Server(); val service = service(server, auth)
        service.refreshNotifications()
        auth.setCurrentUserDirectly(UserInfo(8, "other"))
        assertTrue(service.history(NotificationStatus.ALL).items.isEmpty())
        assertEquals(-1, service.unreadCount)
        val late = LinuxDoNotificationService(auth, { q, _ -> server.page(q) }, initialize = false,
            fetchTotals = { server.totals() }, writeRead = { _, _ -> auth.logout(); Result.success(true) },
            fetchTypes = { Result.success(emptyMap()) }, baseUrl = { "https://linux.do" }).also { services += it }
        late.refreshNotifications(); late.markAsRead(75)
        assertTrue(late.history(NotificationStatus.ALL).items.isEmpty())
        assertEquals(-1, late.unreadCount)
    }

    @Test fun `same-account verification preserves a valid count and cannot bypass cooldown`() {
        val server = Server(); val auth = auth()
        val service = LinuxDoNotificationService(auth, { q, _ -> server.page(q) }, initialize = true,
            fetchTotals = { server.totals() }, fetchTypes = { Result.success(emptyMap()) }, baseUrl = { "https://linux.do" }).also { services += it }
        val counts = mutableListOf<Int>(); service.addCountListener { counts += it }
        server.countFailure = IOException("offline")
        auth.setCurrentUserDirectly(UserInfo(7, "fixture"))
        assertTrue(counts.all { it == 83 }); assertTrue(service.countState.stale)
        service.triggerCircuitBreaker("test", 600)
        val requests = server.queries.size
        auth.setCurrentUserDirectly(UserInfo(7, "fixture"))
        assertTrue(service.isCircuitBroken()); assertEquals(requests, server.queries.size)
    }

    @Test fun `loss of confirmed login without an epoch change clears history and counts`() {
        val auth = LinuxDoAuthService(PersistentCookieJar(false).apply { injectCookie("_t", "synthetic") },
            fetchUser = { Result.failure(HttpStatusException(401)) }, initialize = false)
        auth.setCurrentUserDirectly(UserInfo(7, "fixture"))
        val server = Server()
        val service = LinuxDoNotificationService(auth, { q, _ -> server.page(q) }, initialize = true,
            fetchTotals = { server.totals() }, fetchTypes = { Result.success(emptyMap()) }, baseUrl = { "https://linux.do" }).also { services += it }
        assertEquals(30, service.recentNotifications.size)
        val epoch = SessionEpoch.current
        auth.refreshCurrentUser(force = true)
        assertEquals(epoch, SessionEpoch.current)
        assertNull(auth.currentUser)
        assertTrue(service.history(NotificationStatus.ALL).items.isEmpty()); assertEquals(-1, service.unreadCount)
    }

    @Test fun `mark read requires a positive server acknowledgement`() {
        fun confirmed(text: String) = NotificationReadConfirmation.parse(JsonParser.parseString(text).asJsonObject)
        assertTrue(confirmed("""{"success":"OK"}""")); assertTrue(confirmed("""{"success":true}"""))
        assertFalse(confirmed("{}")); assertFalse(confirmed("""{"success":false}""")); assertFalse(confirmed("""{"success":null}"""))
    }

    @Test fun `display confirmation requires request account floor and visible body and is single use`() {
        var writes = 0
        fun request(floor: Int? = 7) = TopicOpenRequest(5, floor) { if (it == TopicOpenResult.SUCCESS) writes++ }
        val good = request()
        good.acknowledge("expired", 5, 7, true, true); assertEquals(0, writes)
        good.acknowledge(good.id, 5, 7, true, true)
        good.acknowledge(good.id, 5, 7, true, true); assertEquals(1, writes)
        listOf(Triple(6L, 7, true), Triple(5L, 8, true), Triple(5L, 7, false)).forEach { (account, floor, visible) ->
            val pending = request(); pending.acknowledge(pending.id, account, floor, visible, true)
        }
        val missing = request(); missing.acknowledge(missing.id, 5, 0, true, false)
        val cancelled = request(); cancelled.finish(TopicOpenResult.CANCELLED); cancelled.acknowledge(cancelled.id, 5, 7, true, true)
        assertEquals(1, writes)
        val bodyOnly = request(null); bodyOnly.acknowledge(bodyOnly.id, 5, 3, true, true)
        assertEquals(2, writes)
    }

    @Test fun `unknown boost badge and private message routing follow site configuration`() {
        NotificationTypes.configure(SessionEpoch.current, emptyMap())
        val unknown = DiscourseNotification(1, 34, topicId = 8, postNumber = 7)
        assertEquals("发来通知", unknown.getTypeActionLabel())
        assertNull(NotificationTypes.topicTarget(unknown))
        assertEquals("https://linux.do/t/8/7", NotificationTypes.webTarget(unknown, "https://linux.do"))
        NotificationTypes.configure(SessionEpoch.current, mapOf("assigned" to 34, "boosted" to 44))
        assertNull(NotificationTypes.topicTarget(unknown))
        val boost = unknown.copy(notificationType = 44)
        assertEquals("发送了微回复", boost.getTypeActionLabel()); assertEquals(8L, NotificationTypes.topicTarget(boost))
        val badge = DiscourseNotification(2, 12, data = NotificationData(badgeId = 42))
        assertEquals("https://linux.do/badges/42", NotificationTypes.webTarget(badge, "https://linux.do"))
        assertEquals(8L, NotificationTypes.topicTarget(unknown.copy(notificationType = 6)))
        assertNull(NotificationTypes.topicTarget(unknown.copy(notificationType = 1000)))
    }
}
