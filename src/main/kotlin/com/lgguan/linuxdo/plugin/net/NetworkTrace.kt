package com.lgguan.linuxdo.plugin.net

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import okhttp3.*
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class DiagnosticLifetime {
    private var closed = false
    @Synchronized fun isClosed() = closed
    @Synchronized fun whileOpen(action: () -> Unit) { if (!closed) action() }
    @Synchronized fun close(action: () -> Unit) {
        if (!closed) { closed = true; action() }
    }
}

/** Only explicitly selected metadata belongs here, never headers, bodies or raw CDP events. */
object NetworkTrace {
    val sessionId: String = UUID.randomUUID().toString()
    private val sequence = AtomicLong()
    internal val activeDnsCall = ThreadLocal<String>()
    private val gson = Gson()
    data class Id(val value: String = newId())
    fun newId(): String = "r${sequence.incrementAndGet()}"

    fun safeUrl(value: String?): String = try {
        val uri = URI(value ?: "")
        if (uri.scheme in listOf("https", "http") && uri.host != null) {
            URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        } else "[non-http-url]"
    } catch (_: Exception) { "[invalid-url]" }

    fun errorType(error: Throwable): String {
        val names = mutableListOf<String>()
        var next: Throwable? = error
        repeat(5) {
            val current = next ?: return@repeat
            names.add(current.javaClass.simpleName)
            next = current.cause?.takeUnless { it === current }
        }
        return names.joinToString(" -> ")
    }

    fun event(id: String, engine: String, stage: String, vararg fields: Pair<String, Any?>) {
        val data = linkedMapOf<String, Any?>("session" to sessionId, "id" to id, "engine" to engine, "event" to stage)
        data.putAll(fields)
        LinuxDoLog.diagnostic(gson.toJson(data))
    }
}

/** Per-call state: redirects/retries keep the call ID, individual connection attempts have their own events. */
class NetworkEventListener(
    private val id: String,
    private val engine: String,
    private val parentId: String? = null,
    private val sink: (String, Map<String, Any?>) -> Unit = { event, fields ->
        NetworkTrace.event(id, engine, event, *fields.toList().toTypedArray())
    }
) : EventListener() {
    private val started = System.nanoTime()
    private var phase = "call"
    private var connected = false
    private val starts = mutableMapOf<String, Long>()
    private fun emit(event: String, vararg fields: Pair<String, Any?>) {
        sink(event, mapOf("parentId" to parentId, "elapsedMs" to (System.nanoTime() - started) / 1_000_000, *fields))
    }
    private fun begin(stage: String, vararg fields: Pair<String, Any?>) {
        phase = stage
        starts[stage] = System.nanoTime()
        emit("${stage}_start", *fields)
    }
    private fun end(stage: String, vararg fields: Pair<String, Any?>) {
        emit("${stage}_end", "durationMs" to starts.remove(stage)?.let { (System.nanoTime() - it) / 1_000_000 }, *fields)
    }
    override fun callStart(call: Call) = emit("call_start", "method" to call.request().method, "url" to NetworkTrace.safeUrl(call.request().url.toString()))
    override fun dnsStart(call: Call, domainName: String) {
        NetworkTrace.activeDnsCall.set(id)
        begin("dns", "host" to domainName)
    }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        NetworkTrace.activeDnsCall.remove()
        end("dns", "host" to domainName, "addresses" to inetAddressList.map { it.hostAddress })
    }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connected = true
        begin("connect", "ip" to inetSocketAddress.address?.hostAddress, "host" to inetSocketAddress.hostString, "port" to inetSocketAddress.port, "proxyType" to proxy.type().name)
    }
    override fun secureConnectStart(call: Call) = begin("tls")
    override fun secureConnectEnd(call: Call, handshake: Handshake?) = end("tls", "tls" to handshake?.tlsVersion?.javaName, "cipher" to handshake?.cipherSuite?.javaName)
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) = end("connect", "protocol" to protocol?.toString())
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) = emit("connect_failed", "phase" to phase, "ip" to inetSocketAddress.address?.hostAddress, "errorType" to NetworkTrace.errorType(ioe))
    override fun connectionAcquired(call: Call, connection: Connection) {
        phase = "http"
        emit("connection_acquired", "reused" to !connected, "ip" to connection.route().socketAddress.address?.hostAddress, "proxyType" to connection.route().proxy.type().name, "protocol" to connection.protocol().toString())
        connected = false
    }
    override fun responseHeadersEnd(call: Call, response: Response) = emit("response", "status" to response.code)
    override fun callEnd(call: Call) = emit("call_end")
    override fun callFailed(call: Call, ioe: IOException) {
        if (NetworkTrace.activeDnsCall.get() == id) NetworkTrace.activeDnsCall.remove()
        emit("call_failed", "phase" to phase, "errorType" to NetworkTrace.errorType(ioe))
    }

    companion object {
        fun factory(engine: String) = EventListener.Factory { call ->
            NetworkEventListener(call.request().tag(NetworkTrace.Id::class.java)?.value ?: NetworkTrace.newId(), engine, NetworkTrace.activeDnsCall.get())
        }
    }
}
