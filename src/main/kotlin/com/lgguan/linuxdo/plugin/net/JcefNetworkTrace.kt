package com.lgguan.linuxdo.plugin.net

import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser as JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/** Runtime-reflected CDP adapter keeps the 241 compile baseline and needs no remote debugging port. */
object JcefNetworkTrace {
    private val runtimeLogged = AtomicBoolean()

    fun install(web: JBCefBrowser, owner: String) {
        try { installHandlers(web, owner) }
        catch (t: Throwable) { NetworkTrace.event(owner, "JCEF", "observer_unavailable", "errorType" to NetworkTrace.errorType(t)) }
    }

    private fun installHandlers(web: JBCefBrowser, owner: String) {
        val lifetime = DiagnosticLifetime()
        val start = System.nanoTime()
        var detach: (() -> Unit)? = null
        fun emit(event: String, vararg values: Pair<String, Any?>) {
            lifetime.whileOpen {
                NetworkTrace.event(owner, "JCEF", event,
                    "browserId" to web.cefBrowser.identifier, "elapsedMs" to (System.nanoTime() - start) / 1_000_000, *values)
            }
        }
        Disposer.register(web, com.intellij.openapi.Disposable {
            lifetime.close {
                    NetworkTrace.event(owner, "JCEF", "browser_disposed", "browserId" to web.cefBrowser.identifier)
                    try { detach?.invoke() } catch (_: Throwable) { }
                    detach = null
            }
        })
        web.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser?) {
                if (lifetime.isClosed() || browser == null) return
                emit("browser_created")
                logRuntime()
                try {
                    // CEF callback thread, not Swing EDT. Only selected request-context preferences.
                    val rc = web.runtime.call(web.rawBrowser, "getRequestContext")
                    val api = web.runtime.type("org.cef.browser.CefRequestContext")
                    for (key in listOf("proxy", "dns_over_https.mode", "dns_over_https.templates")) {
                        val has = api.getMethod("hasPreference", String::class.java).invoke(rc, key) as Boolean
                        val canSet = api.getMethod("canSetPreference", String::class.java).invoke(rc, key) as Boolean
                        val value = if (has) api.getMethod("getPreference", String::class.java).invoke(rc, key) else null
                        val selected = when (key) {
                            "proxy" -> (value as? Map<*, *>)?.get("mode")?.toString()
                            "dns_over_https.templates" -> (value as? String)?.let(NetworkTrace::safeUrl)
                            else -> (value as? String)?.takeIf { it in listOf("secure", "automatic", "off", "") }
                        }
                        emit("preference_observed", "scope" to "request_context", "key" to key,
                            "exists" to has, "modifiable" to canSet, "selectedValue" to selected,
                            "globalDoHVerified" to false)
                    }
                } catch (t: Throwable) { emit("preference_unavailable", "errorType" to NetworkTrace.errorType(t)) }
                try {
                    val client = web.runtime.call(web.rawBrowser, "getDevToolsClient")
                    val clientClass = web.runtime.type("org.cef.browser.CefDevToolsClient")
                    val listenerClass = web.runtime.type("org.cef.browser.CefDevToolsClient\$EventListener")
                    val listener = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { proxy, method, args ->
                        when (method.name) {
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.get(0)
                            "toString" -> "LinuxDoNetworkObserver"
                            "onEvent" -> {
                                lifetime.whileOpen {
                                        val name = args?.get(0) as? String ?: ""
                                        val json = args?.get(1) as? String ?: ""
                                        try { selectEvent(name, json)?.let { emit(name, *it.toList().toTypedArray()) } }
                                        catch (_: Exception) { emit("cdp_event_parse_failed", "eventName" to name) }
                                }
                                null
                            }
                            else -> null
                        }
                    }
                    lifetime.whileOpen {
                        clientClass.getMethod("addEventListener", listenerClass).invoke(client, listener)
                        detach = { clientClass.getMethod("removeEventListener", listenerClass).invoke(client, listener) }
                    }
                    if (lifetime.isClosed()) return
                    val future = clientClass.getMethod("executeDevToolsMethod", String::class.java)
                        .invoke(client, "Network.enable") as CompletableFuture<*>
                    future.whenComplete { _, error -> emit("cdp_network_enable", "success" to (error == null), "errorType" to error?.let(NetworkTrace::errorType)) }
                    emit("cdp_available", "note" to "events before Network.enable completion may be missing")
                } catch (t: Throwable) { emit("cdp_unavailable", "errorType" to NetworkTrace.errorType(t), "ech" to "unknown") }
            }
        }, web.cefBrowser)
        web.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) = emit("loading_state", "loading" to isLoading)
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: org.cef.network.CefRequest.TransitionType?) {
                if (frame?.isMain == true) emit("main_load_start", "url" to NetworkTrace.safeUrl(frame.url))
            }
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) emit("main_load_end", "url" to NetworkTrace.safeUrl(frame.url), "status" to httpStatusCode)
            }
            override fun onLoadError(browser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
                if (frame?.isMain == true) emit("main_load_error", "url" to NetworkTrace.safeUrl(failedUrl), "cefError" to errorCode?.name)
            }
        }, web.cefBrowser)
    }

    internal fun selectEvent(name: String, json: String): Map<String, Any?>? {
        if (name !in setOf("Network.requestWillBeSent", "Network.responseReceived", "Network.loadingFailed", "Network.loadingFinished", "Network.requestServedFromCache")) return null
        if (json.length > 262144) return mapOf("omitted" to "event_exceeds_256KiB")
        val root = JsonParser.parseString(json).asJsonObject
        val result = linkedMapOf<String, Any?>("cdpRequestId" to root.get("requestId")?.asString)
        if (name == "Network.requestWillBeSent") {
            val request = root.getAsJsonObject("request")
            result["url"] = NetworkTrace.safeUrl(request?.get("url")?.asString)
            result["method"] = request?.get("method")?.asString
            result["redirectStatus"] = root.getAsJsonObject("redirectResponse")?.get("status")?.asInt
        }
        if (name == "Network.responseReceived") {
            val response = root.getAsJsonObject("response")
            result["url"] = NetworkTrace.safeUrl(response.get("url")?.asString)
            for (key in listOf("status", "remoteIPAddress", "remotePort", "protocol", "connectionReused", "fromDiskCache", "fromServiceWorker")) {
                result[key] = response.get(key)?.takeIf { it.isJsonPrimitive }?.asString
            }
            val timing = response.getAsJsonObject("timing")
            for (key in listOf("dnsStart", "dnsEnd", "connectStart", "connectEnd", "sslStart", "sslEnd", "receiveHeadersEnd")) {
                result[key] = timing?.get(key)?.asDouble
            }
            val security = response.getAsJsonObject("securityDetails")
            result["tls"] = security?.get("protocol")?.asString
            result["ech"] = security?.get("encryptedClientHello")?.takeIf { it.isJsonPrimitive }?.asBoolean?.toString() ?: "unknown"
        }
        if (name == "Network.loadingFailed") {
            result["error"] = root.get("errorText")?.asString?.let { Regex("(?:net::)?ERR_[A-Z0-9_]+").find(it)?.value } ?: "unknown"
            result["canceled"] = root.get("canceled")?.asBoolean
        }
        return result
    }

    private fun logRuntime() {
        if (!runtimeLogged.compareAndSet(false, true)) return
        try {
            val app = IsolatedCefRuntime.currentOrNull() ?: return
            val settings = app.settings
            val path = settings?.javaClass?.getField("cache_path")?.get(settings) as? String
            NetworkTrace.event("startup", "JCEF", "runtime_observed",
                "ide" to ApplicationInfo.getInstance().build.asString(), "jbr" to System.getProperty("java.runtime.version"),
                "cefVersion" to NetworkCapture.cefVersion().second, "actualCachePath" to (path ?: "unknown"),
                "preparedCachePath" to app.profile.absolutePath,
                "actualArguments" to "plugin-owned cef_server; see private NetLog", "ech" to "unknown")
        } catch (t: Throwable) { NetworkTrace.event("startup", "JCEF", "runtime_unavailable", "errorType" to NetworkTrace.errorType(t)) }
    }
}
