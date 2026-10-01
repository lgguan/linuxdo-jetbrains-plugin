package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.BrowserIdentity
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import okhttp3.*
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object LinuxDoHttpClient {

    val cookieJar by lazy { PersistentCookieJar() }
    private val resolverDelegate = lazy { DohDnsResolver() }
    val dohResolver by resolverDelegate
    private val ownedClients = CopyOnWriteArrayList<OkHttpClient>()

    @Volatile
    private var client: OkHttpClient? = null

    fun getClient(): OkHttpClient {
        com.intellij.openapi.application.ApplicationManager.getApplication()
            ?.getService(LinuxDoPluginLifetime::class.java)
        val c = client
        if (c != null) return c
        return synchronized(this) {
            client ?: buildClient().also { client = it }
        }
    }

    fun rebuildClient() {
        try {
            dohResolver.updateConfiguration()
        } catch (_: Throwable) {}
        synchronized(this) {
            client = buildClient()
        }
    }

    @Synchronized
    fun dispose() {
        ownedClients.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        ownedClients.clear()
        client = null
        if (resolverDelegate.isInitialized()) dohResolver.close()
    }

    private fun buildClient(): OkHttpClient {
        val settings = try {
            LinuxDoSettingsState.getInstance()
        } catch (_: Throwable) {
            null
        }
        val netConfig = settings?.toNetworkConfig() ?: LinuxDoNetworkConfig()
        val timeoutSeconds = (netConfig.requestTimeoutSeconds).coerceIn(5, 60).toLong()

        val builder = OkHttpClient.Builder()
            .eventListenerFactory(NetworkEventListener.factory("JAVA"))
            .dns(dohResolver)
            .cookieJar(cookieJar)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)

        // Proxy policy integration
        if (netConfig.proxyPolicy == ProxyPolicy.DIRECT) {
            builder.proxy(Proxy.NO_PROXY)
        } else {
            try {
                IdeProxySettings.current()?.let { proxy ->
                    builder.proxy(proxy.javaProxy())
                    builder.proxyAuthenticator(IdeProxySettings.authenticator(proxy))
                }
            } catch (_: Throwable) {
                // Unit tests do not create the IDE proxy settings service.
            }
        }

        // Headers & Cloudflare Challenge Interceptor
        builder.addInterceptor { chain ->
            val original = chain.request()
            val requestBuilder = original.newBuilder()
            // Prevent OkHttp follow-ups from replaying a write after an ambiguous response.
            // Explicit CSRF recovery builds a fresh request only after a definite rejection.
            original.body?.let { body ->
                requestBuilder.method(original.method, object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun isOneShot() = true
                    override fun writeTo(sink: okio.BufferedSink) = body.writeTo(sink)
                })
            }

            val currentSettings = try {
                LinuxDoSettingsState.getInstance()
            } catch (_: Throwable) {
                null
            }
            val ua = currentSettings?.userAgent?.ifBlank { Constants.DEFAULT_USER_AGENT } ?: Constants.DEFAULT_USER_AGENT
            val chromeVersionMatch = Regex("Chrome/(\\d+)").find(ua)
            val chromeMajor = chromeVersionMatch?.groupValues?.get(1) ?: "144"

            requestBuilder.header("User-Agent", ua)
            requestBuilder.header("Accept", "application/json, text/plain, */*")
            requestBuilder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            requestBuilder.header("X-Requested-With", "XMLHttpRequest")
            requestBuilder.header("sec-ch-ua", "\"Not(A:Brand\";v=\"99\", \"Chromium\";v=\"$chromeMajor\", \"Google Chrome\";v=\"$chromeMajor\"")
            requestBuilder.header("sec-ch-ua-mobile", "?0")
            BrowserIdentity.clientHintPlatform(ua)?.let { requestBuilder.header("sec-ch-ua-platform", "\"$it\"") }
                ?: requestBuilder.removeHeader("sec-ch-ua-platform")
            requestBuilder.header("sec-fetch-dest", "empty")
            requestBuilder.header("sec-fetch-mode", "cors")
            requestBuilder.header("sec-fetch-site", "same-origin")
            requestBuilder.header("Referer", "https://linux.do/")

            val epoch = original.tag(SessionEpoch.Stamp::class.java)?.version ?: SessionEpoch.current
            SessionEpoch.requireCurrent(epoch)
            cookieJar.requestEpoch.set(epoch)
            try {
                val response = chain.proceed(requestBuilder.build())
                if (epoch != SessionEpoch.current) {
                    response.close()
                    throw StaleSessionException()
                }
                response
            } finally { cookieJar.requestEpoch.remove() }

        }

        return builder.build().also { ownedClients.add(it) }
    }
}

class CloudflareChallengeException(message: String = "Cloudflare 安全验证未通过") : java.io.IOException(message)

class RateLimitException(
    val retryAfterSeconds: Long = Constants.DEFAULT_CIRCUIT_BREAKER_COOLDOWN_SECONDS,
    message: String = "已触发论坛请求频率限制 (HTTP 429)，请等待冷却结束后重试"
) : java.io.IOException(message)
