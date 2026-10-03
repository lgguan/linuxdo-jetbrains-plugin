package com.lgguan.linuxdo.plugin.api

import com.lgguan.linuxdo.plugin.net.HttpFailure
import com.lgguan.linuxdo.plugin.net.CsrfRejectedException
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.net.SessionCache

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.DiscourseUrls
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.*
import com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge
import com.lgguan.linuxdo.plugin.net.RateLimitException
import com.lgguan.linuxdo.plugin.net.NetworkTrace
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object DiscourseApiClient {

    fun getPersonalContent(query: PersonalContentQuery, username: String, version: Long): Result<PersonalContentPage> = runCatching {
        SessionEpoch.requireCurrent(version)
        val base = getBaseUrl()
        val response = readerGet(PersonalContentParser.path(query, username), version).getOrThrow().asJsonObject
        SessionEpoch.requireCurrent(version)
        PersonalContentParser.parse(response, query, base, username)
    }

    private val gson = Gson()
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    @Volatile
    private var isSniBlockedDetected: Boolean = false

    fun resetSniBlockDetection() {
        isSniBlockedDetected = false
    }

    fun isConnectionResetOrSniBlock(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            val msg = cur.message ?: ""
            if (cur is java.net.SocketException && (msg.contains("Connection reset", ignoreCase = true) || msg.contains("Software caused connection abort", ignoreCase = true) || msg.contains("reset", ignoreCase = true))) {
                return true
            }
            if (cur is javax.net.ssl.SSLException && (msg.contains("Connection reset", ignoreCase = true) || msg.contains("reset", ignoreCase = true) || msg.contains("handshake_failure", ignoreCase = true))) {
                return true
            }
            if (cur is java.net.SocketTimeoutException && msg.contains("Connect timed out", ignoreCase = true)) {
                return true
            }
            if (msg.contains("Connection reset", ignoreCase = true) || msg.contains("Connection was reset", ignoreCase = true) || msg.contains("Connect timed out", ignoreCase = true)) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    fun shouldUseJcefBridge(exception: Throwable? = null): Boolean {
        if (!LinuxDoJcefBridge.isSupported()) return false
        val mode = try {
            LinuxDoSettingsState.getInstance().networkMode
        } catch (_: Throwable) {
            LinuxDoSettingsState.NetworkMode.AUTO.name
        }
        if (mode == LinuxDoSettingsState.NetworkMode.FORCE_JCEF.name) return true
        if (mode == LinuxDoSettingsState.NetworkMode.JAVA_ONLY.name) return false

        // In AUTO mode
        if (isSniBlockedDetected) return true
        if (exception != null && isConnectionResetOrSniBlock(exception)) {
            isSniBlockedDetected = true
            LinuxDoLog.warn("Java connection failed; trying JCEF. Cause is unverified; consult DNS/connect/TLS diagnostics.")
            return true
        }
        return false
    }

    fun getBaseUrl(): String {
        return LinuxDoSettingsState.getInstance().baseUrl.trim().removeSuffix("/")
    }

    private val csrfCache = SessionCache<String>()

    fun getCsrfToken(forceRefresh: Boolean = false): String? {
        val session = SessionEpoch.current
        if (forceRefresh) csrfCache.clear()
        val traceId = NetworkTrace.newId()
        if (!forceRefresh && !csrfCache.get().isNullOrBlank()) {
            return csrfCache.get()
        }

        val baseUrl = getBaseUrl()
        val url = "$baseUrl/session/csrf"

        if (shouldUseJcefBridge()) {
            val domToken = LinuxDoJcefBridge.getCachedDomCsrfToken()
            if (!forceRefresh && !domToken.isNullOrBlank()) {
                csrfCache.put(session, domToken)
                return domToken
            }
            try {
                val res = LinuxDoJcefBridge.executeGet<JsonObject>(url, traceId = traceId)
                val token = res.getOrNull()?.get("csrf")?.asString ?: res.getOrNull()?.get("csrf_token")?.asString
                if (!token.isNullOrBlank()) {
                    csrfCache.put(session, token)
                    LinuxDoLog.info("CSRF acquired via JCEF (present=true)")
                    return token
                }
            } catch (t: Throwable) {
                LinuxDoLog.warn("JCEF CSRF token fetch failed: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(t)}")
            }
        }

        LinuxDoLog.info("Fetching CSRF token from $url (forceRefresh=$forceRefresh)...")

        val client = LinuxDoHttpClient.getClient()
        val request = Request.Builder()
            .tag(NetworkTrace.Id::class.java, NetworkTrace.Id(traceId))
            .url(url)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.source()?.readString(Charsets.UTF_8)?.trim() ?: ""
                if (response.isSuccessful) {
                    val token = try {
                        val element = com.google.gson.JsonParser.parseString(body)
                        if (element.isJsonObject) {
                            val obj = element.asJsonObject
                            obj.get("csrf")?.asString ?: obj.get("csrf_token")?.asString
                        } else if (element.isJsonPrimitive) {
                            element.asString
                        } else {
                            null
                        }
                    } catch (_: Throwable) {
                        null
                    } ?: null

                    if (!token.isNullOrBlank()) {
                        csrfCache.put(session, token)
                        LinuxDoLog.info("CSRF acquired (present=true)")
                        return token
                    }
                } else {
                    LinuxDoLog.warn("Failed to fetch CSRF: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.warn("CSRF token fetch exception: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}")
            if (shouldUseJcefBridge(e)) {
                try {
                    NetworkTrace.event(traceId, "JAVA", "engine_fallback", "target" to "JCEF", "errorType" to NetworkTrace.errorType(e))
                    val res = LinuxDoJcefBridge.executeGet<JsonObject>(url, traceId = traceId)
                    val token = res.getOrNull()?.get("csrf")?.asString ?: res.getOrNull()?.get("csrf_token")?.asString
                    if (!token.isNullOrBlank()) {
                        csrfCache.put(session, token)
                        LinuxDoLog.info("CSRF acquired via fallback (present=true)")
                        return token
                    }
                } catch (t: Throwable) {
                    LinuxDoLog.warn("JCEF fallback CSRF fetch failed: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(t)}")
                }
            }
        }
        return csrfCache.get()
    }

    private val readCooldown = com.lgguan.linuxdo.plugin.net.ForumReadCooldown()

    private inline fun <reified T> executeGet(url: String, version: Long = SessionEpoch.current): Result<T> =
        readCooldown.read { executeGetNow<T>(url, version) }

    private inline fun <reified T> executeGetNow(url: String, version: Long): Result<T> {
        if (version != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        val traceId = NetworkTrace.newId()
        if (shouldUseJcefBridge()) {
            LinuxDoLog.info("Routing GET $url via JCEF (Chromium ECH) Bridge")
            return LinuxDoJcefBridge.executeGet<T>(url, traceId = traceId, expectedVersion = version)
        }

        val client = LinuxDoHttpClient.getClient()
        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .tag(NetworkTrace.Id::class.java, NetworkTrace.Id(traceId))
            .tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(version))
            .url(url)
            .get()
            .build()

        LinuxDoLog.info("GET $url")

        return try {
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                val body = response.body?.source()?.readString(Charsets.UTF_8) ?: ""
                LinuxDoLog.info("GET $url -> HTTP ${response.code} in ${duration}ms (body size: ${body.length})")
                HttpFailure.classify(response.code, response.headers.toMap(), body)?.let { return Result.failure(it) }
                val data = gson.fromJson(body, T::class.java)
                Result.success(data)
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            LinuxDoLog.error("GET $url failed after ${duration}ms", e)
            if (shouldUseJcefBridge(e)) {
                LinuxDoLog.info("Auto-recovering GET $url via JCEF (Chromium ECH) Bridge...")
                NetworkTrace.event(traceId, "JAVA", "engine_fallback", "target" to "JCEF", "errorType" to NetworkTrace.errorType(e))
                return LinuxDoJcefBridge.executeGet<T>(url, traceId = traceId, expectedVersion = version)
            }
            Result.failure(e)
        }
    }

    fun getCategories(): Result<List<Category>> {
        val baseUrl = getBaseUrl()
        // 1. Try site.json first, which contains the complete list of all categories & subcategories
        try {
            val siteUrl = DiscourseUrls.site(baseUrl)
            val siteResult = executeGet<SiteResponse>(siteUrl)
            if (siteResult.isSuccess) {
                val siteCats = siteResult.getOrNull()?.categories
                if (!siteCats.isNullOrEmpty()) {
                    LinuxDoLog.info("Loaded ${siteCats.size} categories & subcategories from site.json")
                    return Result.success(siteCats)
                }
            }
        } catch (t: Throwable) {
            LinuxDoLog.warn("site.json fetch failed, falling back to categories.json: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(t)}")
        }

        // 2. Fallback to categories.json?include_subcategories=true
        val catUrl = DiscourseUrls.categories(baseUrl)
        val catResult = executeGet<CategoryListResponse>(catUrl)
        return catResult.map { it.categoryList.categories }
    }

    fun getTopicList(
        filter: Constants.TopicFilter,
        categorySlug: String? = null,
        categoryId: Int? = null,
        page: Int = 0,
        tag: String? = null
    ): Result<TopicList> {
        val baseUrl = getBaseUrl()
        if (categoryId != null && !categorySlug.isNullOrBlank()) {
            val url = DiscourseUrls.topicList(baseUrl, filter, categorySlug, categoryId, page, tag)
            val result = executeGet<TopicListResponse>(url)
            if (result.isSuccess) {
                return result.map { it.topicList }
            }
            val err = result.exceptionOrNull()
            // If 404, fallback to categoryId-only URL in Discourse (/c/:id/l/latest.json)
            if (err?.message?.contains("404") == true) {
                val fallbackUrl = DiscourseUrls.topicList(baseUrl, filter, null, categoryId, page, tag)
                LinuxDoLog.info("Category $categorySlug/$categoryId returned 404, attempting fallback to $fallbackUrl")
                val fallbackResult = executeGet<TopicListResponse>(fallbackUrl)
                if (fallbackResult.isSuccess) {
                    return fallbackResult.map { it.topicList }
                }
            }
            return result.map { it.topicList }
        }

        val url = DiscourseUrls.topicList(baseUrl, filter, categorySlug, categoryId, page, tag)

        val result = executeGet<TopicListResponse>(url)
        return result.map { it.topicList }
    }

    fun getTopicDetail(topicId: Long, trackVisit: Boolean = true): Result<TopicDetailResponse> {
        val url = DiscourseUrls.topicDetail(getBaseUrl(), topicId, trackVisit)
        return executeGet<TopicDetailResponse>(url).map(::withScrollableSources)
    }

    fun getMorePosts(topicId: Long, postIds: List<Long>): Result<List<Post>> {
        if (postIds.isEmpty()) return Result.success(emptyList())
        val url = DiscourseUrls.topicPosts(getBaseUrl(), topicId, postIds)
        val result = executeGet<PostStreamResponse>(url)
        return result.map { it.postStream.posts.map(::withScrollableSource) }
    }

    fun getTopicAroundPost(topicId: Long, postNumber: Int): Result<TopicDetailResponse> =
        executeGet<TopicDetailResponse>("${getBaseUrl()}/t/$topicId/$postNumber.json?track_visit=false").map(::withScrollableSources)

    private fun withScrollableSources(topic: TopicDetailResponse): TopicDetailResponse =
        topic.copy(postStream = topic.postStream.copy(posts = topic.postStream.posts.map(::withScrollableSource)))

    private fun withScrollableSource(post: Post): Post {
        if (!post.raw.isNullOrBlank() || !com.lgguan.linuxdo.plugin.theme.ScrollableSourceBlocks.needsRaw(post.cooked)) return post
        return executeGet<Post>("${getBaseUrl()}/posts/${post.id}.json").fold(
            onSuccess = { post.copy(raw = it.raw) },
            onFailure = {
                LinuxDoLog.warn("Could not load scrollable source for post ${post.id}: ${it.javaClass.simpleName}")
                post
            }
        )
    }

    fun search(query: String, page: Int = 1): Result<SearchResultResponse> {
        return TopicSearch.search(query,
            byId = { id -> executeGet<Topic>(DiscourseUrls.topicDetail(getBaseUrl(), id, trackVisit = false)) },
            byText = { text -> executeGet<SearchResultResponse>(DiscourseUrls.search(getBaseUrl(), text, page)) }
        )
    }

    fun getPost(postId: Long): Result<Post> = executeGet("${getBaseUrl()}/posts/$postId.json")

    internal fun readerGet(path: String, version: Long): Result<com.google.gson.JsonElement> = runCatching {
        SessionEpoch.requireCurrent(version)
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains(".."))
        executeGet<com.google.gson.JsonElement>(getBaseUrl() + path, version).getOrThrow().also { SessionEpoch.requireCurrent(version) }
    }

    /** A rejected CSRF token may be refreshed; an uncertain write is never replayed. */
    internal fun readerWrite(path: String, method: String, data: JsonObject, version: Long): Result<com.google.gson.JsonElement> = runCatching {
        SessionEpoch.requireCurrent(version)
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains(".."))
        require(method in setOf("POST", "PUT", "DELETE"))
        val csrf = getCsrfToken()
        SessionEpoch.requireCurrent(version)
        val url = getBaseUrl() + path
        val body = data.toString()
        val responseBody = if (shouldUseJcefBridge()) {
            val response = LinuxDoJcefBridge.execute(LinuxDoJcefBridge.BridgeRequest(url = url, method = method,
                headers = buildMap { put("Accept", "application/json"); put("Content-Type", "application/json"); csrf?.let { put("X-CSRF-Token", it) } },
                body = body, sessionVersion = version)).getOrThrow()
            HttpFailure.classify(response.status, response.headers.orEmpty(), response.body.orEmpty())?.let { throw it }
            response.body.orEmpty()
        } else {
            executeWrite(csrf) { token -> Request.Builder().url(url).method(method, body.toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json").apply { token?.let { header("X-CSRF-Token", it) } }
                .tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(version)).build() }.use { response ->
                val text = response.body?.string().orEmpty()
                HttpFailure.classify(response.code, response.headers.toMap(), text)?.let { throw it }; text
            }
        }
        SessionEpoch.requireCurrent(version)
        parseReaderResponse(responseBody)
    }

    internal fun parseReaderResponse(body: String): com.google.gson.JsonElement {
        val element=if(body.isBlank())JsonObject() else com.google.gson.JsonParser.parseString(body)
        if(element.isJsonObject){
            val data=element.asJsonObject;val success=data.get("success")
            val errors=data.getAsJsonArray("errors")?.map { org.jsoup.Jsoup.parse(it.asString).text().take(2000) }.orEmpty()
            if(errors.isNotEmpty() || (success?.isJsonPrimitive==true && success.asJsonPrimitive.isBoolean && !success.asBoolean))
                throw com.lgguan.linuxdo.plugin.net.ForumValidationException(422, errors.ifEmpty { listOf(org.jsoup.Jsoup.parse(data.get("message")?.asString ?: "论坛拒绝此操作").text().take(2000)) })
        }
        return element
    }

    internal fun readerBootstrap(version: Long): Result<String> = readCooldown.read { runCatching {
        SessionEpoch.requireCurrent(version)
        val url = "${getBaseUrl()}/latest"
        val html = if (shouldUseJcefBridge()) LinuxDoJcefBridge.execute(LinuxDoJcefBridge.BridgeRequest(url = url,
            headers = mapOf("Accept" to "text/html"), sessionVersion = version)).getOrThrow().body.orEmpty()
        else LinuxDoHttpClient.getClient().newCall(Request.Builder().url(url).header("Accept", "text/html")
            .tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(version)).build()).execute().use { response ->
            val text = response.body?.string().orEmpty(); HttpFailure.classify(response.code, response.headers.toMap(), text)?.let { throw it }; text
        }
        SessionEpoch.requireCurrent(version); html
    } }

    internal fun readDraft(key: String, expectedVersion: Long): Result<JsonObject> = runCatching {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,200}")))
        SessionEpoch.requireCurrent(expectedVersion)
        val response = executeGet<JsonObject>("${getBaseUrl()}/drafts/$key.json", expectedVersion).getOrThrow()
        SessionEpoch.requireCurrent(expectedVersion)
        response
    }

    internal fun writeDraft(key: String, sequence: Long, data: JsonObject?, expectedVersion: Long): Result<JsonObject> = runCatching {
        require(key.matches(Regex("[A-Za-z0-9_-]{1,200}")))
        SessionEpoch.requireCurrent(expectedVersion)
        val csrf = getCsrfToken()
        SessionEpoch.requireCurrent(expectedVersion)
        val url = if (data == null) "${getBaseUrl()}/drafts/$key.json" else "${getBaseUrl()}/drafts.json"
        val method = if (data == null) "DELETE" else "POST"
        val form = FormBody.Builder().add("sequence", sequence.toString()).apply {
            if (data != null) { add("draft_key", key); add("data", data.toString()) }
        }.build()
        if (shouldUseJcefBridge()) {
            val encoded = (0 until form.size).joinToString("&") { "${form.encodedName(it)}=${form.encodedValue(it)}" }
            val response = LinuxDoJcefBridge.executeForm(url, method, encoded, csrf, expectedVersion = expectedVersion).getOrThrow()
            gson.fromJson(response.body, JsonObject::class.java)
        } else {
            executeWrite(csrf) { token ->
                Request.Builder().tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(expectedVersion))
                    .url(url).method(method, form).apply { token?.let { header("X-CSRF-Token", it) } }.build()
            }.use { response ->
                HttpFailure.classify(response.code, response.headers.toMap(), response.peekBody(1024).string())?.let { throw it }
                gson.fromJson(response.body?.string(), JsonObject::class.java)
            }
        }.also { SessionEpoch.requireCurrent(expectedVersion) }
    }

    fun getCurrentUser(): Result<UserInfo?> {
        val url = DiscourseUrls.currentUser(getBaseUrl())
        val result = executeGet<CurrentUserResponse>(url)
        return result.map { it.currentUser }
    }

    fun getNotifications(query: NotificationQuery = NotificationQuery(), expectedVersion: Long = SessionEpoch.current): Result<NotificationListResponse> =
        executeGet(query.url(getBaseUrl()), expectedVersion)

    fun getNotificationTotals(expectedVersion: Long = SessionEpoch.current): Result<NotificationTotals> =
        executeGet<JsonObject>("${getBaseUrl()}/notifications/totals.json", expectedVersion).mapCatching(NotificationTotals::parse)

    fun getNotificationTypes(expectedVersion: Long = SessionEpoch.current): Result<Map<String, Int>> =
        executeGet<JsonObject>(DiscourseUrls.site(getBaseUrl()), expectedVersion).mapCatching { site ->
            val types = site.getAsJsonObject("notification_types") ?: error("本站未返回通知类型配置")
            types.entrySet().associate { it.key to it.value.asInt }
        }

    private fun executeWrite(csrf: String?, build: (String?) -> Request): okhttp3.Response {
        val version = build(csrf).tag(SessionEpoch.Stamp::class.java)?.version ?: SessionEpoch.current
        return com.lgguan.linuxdo.plugin.net.CsrfRecovery.execute(csrf, version,
            send = { LinuxDoHttpClient.getClient().newCall(build(it)).execute() },
            failure = { HttpFailure.classify(it.code, it.headers.toMap(), it.peekBody(8192).string()) },
            close = { it.close() }, refresh = { getCsrfToken(forceRefresh = true) })
    }

    fun markNotificationRead(notificationId: Long? = null, expectedVersion: Long = SessionEpoch.current): Result<Boolean> {
        if (expectedVersion != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        val url = DiscourseUrls.markNotificationsRead(getBaseUrl())
        val session = expectedVersion
        val csrf = getCsrfToken()
        if (session != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        LinuxDoLog.info("markNotificationRead: notificationId=$notificationId")
        val formBodyStr = if (notificationId != null) "id=$notificationId" else ""

        if (shouldUseJcefBridge()) {
            return LinuxDoJcefBridge.executeForm(url, "PUT", formBodyStr, csrf, expectedVersion = session)
                .mapCatching { NotificationReadConfirmation.parse(gson.fromJson(it.body, JsonObject::class.java)) }
        }

        fun buildRequest(token: String?): Request {
            val formBuilder = FormBody.Builder()
            if (notificationId != null) {
                formBuilder.add("id", notificationId.toString())
            }
            val reqBuilder = Request.Builder().tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(session))
                .url(url)
                .put(formBuilder.build())
                .header("Accept", "application/json")
            if (!token.isNullOrBlank()) {
                reqBuilder.header("X-CSRF-Token", token)
            }
            return reqBuilder.build()
        }

        return try {
            val response = executeWrite(csrf, ::buildRequest)
            response.use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    LinuxDoLog.info("markNotificationRead success: notificationId=$notificationId")
                    Result.success(NotificationReadConfirmation.parse(gson.fromJson(body, JsonObject::class.java)))
                } else {
                    LinuxDoLog.warn("markNotificationRead failed HTTP ${res.code}")
                    Result.failure(HttpFailure.classify(res.code, res.headers.toMap(), body)!!)
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.error("markNotificationRead exception: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}", e)
            Result.failure(e)
        }
    }

    fun reportTimings(topicId: Long, topicTimeMs: Long, timings: Map<Int, Long>, expectedVersion: Long = SessionEpoch.current): Result<Boolean> {
        if (topicId <= 0 || topicTimeMs !in 0..60_000 || timings.any { (floor, ms) -> floor <= 0 || ms !in 1..60_000 })
            return Result.failure(IllegalArgumentException("已读同步批次超出服务器范围"))
        if (expectedVersion != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        val url = DiscourseUrls.postTimings(getBaseUrl())
        val session = expectedVersion
        val csrf = getCsrfToken()
        if (session != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        LinuxDoLog.info("reportTimings: topicId=$topicId, posts=${timings.keys}")

        val formBodyStr = buildString {
            append("topic_id=").append(topicId)
            append("&topic_time=").append(topicTimeMs)
            for ((postNumber, time) in timings) {
                append("&timings[").append(postNumber).append("]=").append(time)
            }
        }

        if (shouldUseJcefBridge()) {
            return LinuxDoJcefBridge.executeForm(url, "POST", formBodyStr, csrf, expectedVersion = session).mapCatching {
                parseReaderResponse(it.body.orEmpty()); true
            }
        }

        fun buildRequest(token: String?): Request {
            val formBuilder = FormBody.Builder()
                .add("topic_id", topicId.toString())
                .add("topic_time", topicTimeMs.toString())

            for ((postNumber, time) in timings) {
                formBuilder.add("timings[$postNumber]", time.toString())
            }

            val reqBuilder = Request.Builder().tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(session))
                .url(url)
                .post(formBuilder.build())
            if (!token.isNullOrBlank()) {
                reqBuilder.header("X-CSRF-Token", token)
            }
            return reqBuilder.build()
        }

        return try {
            val response = executeWrite(csrf, ::buildRequest)
            response.use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    LinuxDoLog.info("reportTimings success: topicId=$topicId, posts=${timings.keys}")
                    parseReaderResponse(body)
                    Result.success(true)
                } else {
                    LinuxDoLog.warn("reportTimings failed HTTP ${res.code}")
                    Result.failure(HttpFailure.classify(res.code, res.headers.toMap(), body)!!)
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.error("reportTimings exception: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}", e)
            Result.failure(e)
        }
    }

    fun createReply(topicId: Long, rawContent: String, replyToPostNumber: Int? = null,
        expectedVersion: Long = SessionEpoch.current): Result<Post> =
        publishReply(topicId, rawContent, replyToPostNumber, expectedVersion).mapCatching { outcome ->
            (outcome as? com.lgguan.linuxdo.plugin.model.PublishOutcome.Published)?.post
                ?: throw IllegalStateException("已提交审核，请在网页查看")
        }

    internal fun publishReply(topicId: Long, raw: String, floor: Int?, version: Long,
        draftKey: String? = null): Result<com.lgguan.linuxdo.plugin.model.PublishOutcome> = publishPost(JsonObject().apply {
        addProperty("topic_id", topicId)
        addProperty("raw", raw)
        if (floor != null && floor > 0) addProperty("reply_to_post_number", floor)
        if (draftKey != null) addProperty("draft_key", draftKey)
        addProperty("nested_post", true)
    }, version)

    private fun publishPost(json: JsonObject, version: Long): Result<com.lgguan.linuxdo.plugin.model.PublishOutcome> = runCatching {
        SessionEpoch.requireCurrent(version)
        val csrf = getCsrfToken()
        SessionEpoch.requireCurrent(version)
        val url = DiscourseUrls.createPost(getBaseUrl())
        val response = if (shouldUseJcefBridge()) {
            LinuxDoJcefBridge.executePostJson<JsonObject>(url, json.toString(), csrf, expectedVersion = version).getOrThrow()
        } else {
            executeWrite(csrf) { token ->
                Request.Builder().tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(version)).url(url)
                    .post(json.toString().toRequestBody(JSON_MEDIA_TYPE)).apply {
                        if (!token.isNullOrBlank()) header("X-CSRF-Token", token)
                    }.build()
            }.use { res ->
                val body = res.body?.string().orEmpty()
                HttpFailure.classify(res.code, res.headers.toMap(), body)?.let { throw it }
                com.google.gson.JsonParser.parseString(body).asJsonObject
            }
        }
        com.lgguan.linuxdo.plugin.model.PublishOutcome.parse(response)
    }.recoverCatching { error ->
        throw com.lgguan.linuxdo.plugin.model.PublishOutcome.failure(error)
    }

    fun toggleLike(postId: Long, like: Boolean, expectedVersion: Long = SessionEpoch.current): Result<Boolean> {
        if (expectedVersion != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        val baseUrl = getBaseUrl()
        val session = expectedVersion
        val csrf = getCsrfToken()
        if (session != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        LinuxDoLog.info("toggleLike: postId=$postId, like=$like")

        if (shouldUseJcefBridge()) {
            return if (like) {
                val url = DiscourseUrls.postActions(baseUrl)
                val json = JsonObject().apply {
                    addProperty("id", postId)
                    addProperty("post_action_type_id", 2)
                }
                LinuxDoJcefBridge.executePostJson<JsonObject>(url, json.toString(), csrf, expectedVersion = session).map { true }
            } else {
                val url = DiscourseUrls.removePostAction(baseUrl, postId, 2)
                val reqHeaders = HashMap<String, String>().apply {
                    put("Accept", "application/json")
                    if (!csrf.isNullOrBlank()) put("X-CSRF-Token", csrf)
                }
                LinuxDoJcefBridge.execute(LinuxDoJcefBridge.BridgeRequest(url = url, method = "DELETE", headers = reqHeaders, sessionVersion = session)).map { true }
            }
        }

        fun buildRequest(token: String?): Request {
            val reqBuilder = if (like) {
                val url = DiscourseUrls.postActions(baseUrl)
                val json = JsonObject().apply {
                    addProperty("id", postId)
                    addProperty("post_action_type_id", 2) // 2 = like
                }
                Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody(JSON_MEDIA_TYPE))
            } else {
                val url = DiscourseUrls.removePostAction(baseUrl, postId, 2)
                Request.Builder()
                    .url(url)
                    .delete()
            }
            if (!token.isNullOrBlank()) {
                reqBuilder.header("X-CSRF-Token", token)
            }
            return reqBuilder.tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(session)).build()
        }

        try {
            val response = executeWrite(csrf, ::buildRequest)
            return response.use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(HttpFailure.classify(res.code, res.headers.toMap(), body)!!)
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.error("toggleLike exception: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}", e)
            return Result.failure(e)
        }
    }

    fun boostPost(postId: Long, content: String, expectedVersion: Long = SessionEpoch.current): Result<com.lgguan.linuxdo.plugin.model.PostBoost> =
        readerWrite("/discourse-boosts/posts/$postId/boosts", "POST", JsonObject().apply { addProperty("raw",content.trim()) },expectedVersion)
            .mapCatching { gson.fromJson(it,com.lgguan.linuxdo.plugin.model.PostBoost::class.java) }

    fun getBoost(boostId: Long, expectedVersion: Long = SessionEpoch.current): Result<com.lgguan.linuxdo.plugin.model.PostBoost> =
        readerGet("/discourse-boosts/boosts/$boostId.json",expectedVersion)
            .mapCatching { gson.fromJson(it,com.lgguan.linuxdo.plugin.model.PostBoost::class.java) }

    fun deleteBoost(boostId: Long, expectedVersion: Long = SessionEpoch.current): Result<Boolean> =
        readerWrite("/discourse-boosts/boosts/$boostId", "DELETE", JsonObject(),expectedVersion).map { true }

    fun createTopic(title: String, rawContent: String, categoryId: Int?, tags: List<String> = emptyList(),
        expectedVersion: Long = SessionEpoch.current): Result<Post> =
        publishTopic(title, rawContent, categoryId, tags, expectedVersion).mapCatching { outcome ->
            (outcome as? com.lgguan.linuxdo.plugin.model.PublishOutcome.Published)?.post
                ?: throw IllegalStateException("已提交审核，请在网页查看")
        }

    internal fun publishTopic(title: String, raw: String, categoryId: Int?, tags: List<String>, version: Long,
        draftKey: String? = null): Result<com.lgguan.linuxdo.plugin.model.PublishOutcome> = publishPost(JsonObject().apply {
        addProperty("title", title.trim())
        addProperty("raw", raw)
        if (categoryId != null) addProperty("category", categoryId)
        add("tags", com.google.gson.JsonArray().apply { tags.distinct().forEach { add(it) } })
        addProperty("archetype", "regular")
        if (draftKey != null) addProperty("draft_key", draftKey)
    }, version)

    fun uploadImageBytes(bytes: ByteArray, fileName: String, mimeType: String = "image/png", expectedVersion: Long = SessionEpoch.current): Result<UploadResponse> {
        if (expectedVersion != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        if (bytes.size > com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES) return Result.failure(IllegalArgumentException("图片超过 24 MB"))
        val baseUrl = getBaseUrl()
        val url = DiscourseUrls.upload(baseUrl)
        val session = expectedVersion
        val csrf = getCsrfToken()
        if (session != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        LinuxDoLog.info("uploadImageBytes: size=${bytes.size}")

        if (shouldUseJcefBridge()) {
            return LinuxDoJcefBridge.uploadImage(url, bytes, fileName, mimeType, csrf, expectedVersion = session)
        }

        val mediaType = mimeType.toMediaType()
        val fileBody = bytes.toRequestBody(mediaType)

        fun buildRequest(token: String?): Request {
            val multipart = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("type", "composer")
                .addFormDataPart("synchronous", "true")
                .addFormDataPart("files[]", fileName, fileBody)
                .build()

            val reqBuilder = Request.Builder().tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(session))
                .url(url)
                .post(multipart)
            if (!token.isNullOrBlank()) {
                reqBuilder.header("X-CSRF-Token", token)
            }
            return reqBuilder.build()
        }

        try {
            val response = executeWrite(csrf, ::buildRequest)
            return response.use { res ->
                val body = res.body?.string().orEmpty()
                if (res.isSuccessful) {
                    val upload = gson.fromJson(body, UploadResponse::class.java)
                    Result.success(upload)
                } else {
                    Result.failure(HttpFailure.classify(res.code, res.headers.toMap(), body)!!)
                }
            }
        } catch (e: Exception) {
            LinuxDoLog.error("uploadImageBytes exception: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(e)}", e)
            return Result.failure(e)
        }
    }

    fun uploadImageFile(file: java.io.File, expectedVersion: Long = SessionEpoch.current): Result<UploadResponse> {
        if (expectedVersion != SessionEpoch.current) return Result.failure(com.lgguan.linuxdo.plugin.net.StaleSessionException())
        val bytes = try {
            require(file.length() <= com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
            file.inputStream().use { it.readNBytes(com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES + 1) }
        } catch (e: Exception) { return Result.failure(e) }
        val mimeType = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            else -> "image/png"
        }
        return uploadImageBytes(bytes, file.name, mimeType, expectedVersion)
    }

    fun getPopularTags(): Result<List<String>> {
        val url = DiscourseUrls.tags(getBaseUrl())
        val result = executeGet<TagListResponse>(url)
        return result.map { it.tags.map { tag -> tag.text.ifBlank { tag.id } } }
    }

    fun searchTags(query: String): Result<List<TagItem>> =
        executeGet<TagSearchResultResponse>(DiscourseUrls.browseTags(getBaseUrl(), query)).map { it.results }

    private val browseTagsCache = SessionCache<TagListResponse>()
    internal fun browseTags(): Result<TagListResponse> {
        browseTagsCache.get()?.let { return Result.success(it) }
        val version = SessionEpoch.current
        return executeGet<TagListResponse>(DiscourseUrls.tags(getBaseUrl()), version).onSuccess { browseTagsCache.put(version, it) }
    }

    internal fun searchUsers(query: String): Result<List<String>> = executeGet<JsonObject>(
        "${getBaseUrl()}/u/search/users.json?term=${java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)}")
        .map { response -> response.getAsJsonArray("users")?.mapNotNull { it.asJsonObject.get("username")?.asString }.orEmpty() }

    internal fun searchComposerTags(query: String, categoryId: Int?, selectedIds: List<String>): Result<TagSearchResultResponse> {
        return executeGet(DiscourseUrls.composerTags(getBaseUrl(), query, categoryId, selectedIds))
    }

    internal fun listDrafts(offset: Int, version: Long): Result<List<com.lgguan.linuxdo.plugin.service.DraftEntry>> = runCatching {
        SessionEpoch.requireCurrent(version)
        val response = executeGet<JsonObject>("${getBaseUrl()}/drafts.json?offset=$offset&limit=50").getOrThrow()
        SessionEpoch.requireCurrent(version)
        com.lgguan.linuxdo.plugin.service.DraftEntry.parse(response)
    }

    private val capabilitiesCache = SessionCache<com.lgguan.linuxdo.plugin.model.ComposerCapabilities>()
    private val searchCapabilitiesCache = SessionCache<SearchCapabilities>()
    internal fun searchCapabilities(): Result<SearchCapabilities> {
        searchCapabilitiesCache.get()?.let { return Result.success(it) }
        return composerCapabilities().mapCatching { searchCapabilitiesCache.get() ?: error("搜索设置尚未加载") }
    }
    internal fun composerCapabilities(): Result<com.lgguan.linuxdo.plugin.model.ComposerCapabilities> = runCatching {
        capabilitiesCache.get()?.let { return@runCatching it }
        val version = SessionEpoch.current
        val html = readCooldown.read { runCatching {
            val url = "${getBaseUrl()}/latest"
            if (shouldUseJcefBridge()) LinuxDoJcefBridge.execute(LinuxDoJcefBridge.BridgeRequest(url = url,
                headers = mapOf("Accept" to "text/html"), sessionVersion = version)).getOrThrow().body.orEmpty()
            else LinuxDoHttpClient.getClient().newCall(Request.Builder().url(url).header("Accept", "text/html")
                .tag(SessionEpoch.Stamp::class.java, SessionEpoch.Stamp(version)).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                HttpFailure.classify(response.code, response.headers.toMap(), body)?.let { throw it }
                body
            }
        } }.getOrThrow()
        SessionEpoch.requireCurrent(version)
        searchCapabilitiesCache.put(version, SearchCapabilities.parse(html))
        com.lgguan.linuxdo.plugin.model.ComposerCapabilities.parse(html).also { capabilitiesCache.put(version, it) }
    }
}
