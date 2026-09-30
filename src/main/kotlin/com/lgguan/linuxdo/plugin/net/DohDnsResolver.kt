package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class DohDnsResolver internal constructor(
    private val systemDns: Dns,
    private val clientBuilder: () -> OkHttpClient.Builder
) : Dns, java.io.Closeable {
    constructor() : this(Dns.SYSTEM, { OkHttpClient.Builder() })
    private data class State(val config: LinuxDoNetworkConfig, val dns: Dns?, val error: String? = null)
    @Volatile private var state: State? = null
    private val transports = java.util.concurrent.CopyOnWriteArrayList<OkHttpClient>()

    @Synchronized
    fun updateConfiguration(config: LinuxDoNetworkConfig? = null) {
        val desired = (config ?: LinuxDoSettingsState.getInstance().toNetworkConfig()).copy(revision = 0)
        if (state?.config == desired) return
        state = try {
            desired.validateDoh()
            if (!desired.isDohEnabled) State(desired, null) else {
                val endpoint = DohEndpoint.parse(desired.effectiveDohUrl)
                val timeout = desired.requestTimeoutSeconds.coerceIn(1, 60).toLong()
                val transport = clientBuilder()
                    .eventListenerFactory(NetworkEventListener.factory("DOH_BOOTSTRAP"))
                    .connectTimeout(timeout, TimeUnit.SECONDS)
                    .readTimeout(timeout, TimeUnit.SECONDS)
                    .callTimeout(timeout, TimeUnit.SECONDS)
                if (desired.proxyPolicy == ProxyPolicy.DIRECT) transport.proxy(Proxy.NO_PROXY)
                if (endpoint.useGet) transport.interceptors().add(0, okhttp3.Interceptor { chain ->
                    val request = chain.request()
                    val dns = requireNotNull(request.url.queryParameter("dns"))
                    chain.proceed(request.newBuilder().url(endpoint.expand(dns)).build())
                })
                val builder = DnsOverHttps.Builder().client(transport.build().also { transports.add(it) })
                    .url(if (endpoint.useGet) endpoint.url.newBuilder().removeAllQueryParameters("dns").build() else endpoint.url)
                    .post(!endpoint.useGet).includeIPv6(false)
                if (desired.effectiveBootstrapIp.isNotBlank()) builder.bootstrapDnsHosts(InetAddress.getByName(desired.effectiveBootstrapIp))
                NetworkTrace.event("resolver", "DOH", "configured", "endpoint" to NetworkTrace.safeUrl(endpoint.template),
                    "proxyPolicy" to desired.proxyPolicy.name, "method" to if (endpoint.useGet) "GET" else "POST")
                State(desired, builder.build())
            }
        } catch (e: Exception) { State(desired, null, e.message ?: e.javaClass.simpleName) }
    }

    @Synchronized
    override fun close() {
        transports.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdownNow()
        }
        transports.clear()
        state = null
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (state == null) updateConfiguration()
        val current = requireNotNull(state)
        if (!current.config.isDohEnabled) return systemDns.lookup(hostname)
        var failure: Exception? = null
        if (current.dns != null) {
            try {
                val result = current.dns.lookup(hostname)
                if (result.isNotEmpty()) return result
                failure = UnknownHostException("DoH 返回空地址列表")
            } catch (e: Exception) { failure = e }
        }
        if (current.config.strictDoh) {
            throw UnknownHostException("DoH 解析失败 ($hostname): ${current.error ?: failure?.message ?: "解析器未初始化"}；严格模式禁止回退系统 DNS")
                .also { if (failure != null) it.initCause(failure) }
        }
        return systemDns.lookup(hostname)
    }

    companion object {
        fun testDoH(config: LinuxDoNetworkConfig, testHost: String = "linux.do"): Pair<Boolean, String> {
            if (!config.isDohEnabled) return false to "DoH 未启用"
            val start = System.nanoTime()
            return try {
                config.validateDoh()
                val ips = DohDnsResolver().use { resolver ->
                    resolver.updateConfiguration(config.copy(strictDoh = true))
                    resolver.lookup(testHost)
                }
                true to "解析成功 (${(System.nanoTime() - start) / 1_000_000} ms): $testHost -> [${ips.joinToString { it.hostAddress }}]"
            } catch (e: Exception) { false to "DoH resolution failed: ${e.message}" }
        }

        fun testDoH(provider: Constants.DohProvider, customUrl: String = "", customBootstrapIp: String = "",
                    testHost: String = "linux.do", proxyPolicy: ProxyPolicy = ProxyPolicy.DIRECT): Pair<Boolean, String> =
            testDoH(LinuxDoNetworkConfig(dohProvider = provider, customDohUrl = customUrl,
                customBootstrapIp = customBootstrapIp, proxyPolicy = proxyPolicy), testHost)
    }
}
