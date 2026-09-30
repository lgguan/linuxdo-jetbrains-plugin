package com.lgguan.linuxdo.plugin.net

import com.intellij.credentialStore.Credentials
import com.intellij.openapi.application.ApplicationManager
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy

/** Public 2024.2+ proxy APIs, resolved at runtime because our compile SDK is 241. */
internal object IdeProxySettings {
    internal data class ManualProxy(val host: String, val port: Int, val socks: Boolean) {
        fun javaProxy(): Proxy = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress(host, port))

        fun browserArgument(): String = "--proxy-server=${if (socks) "socks5" else "http"}://" +
            "${if (':' in host && !host.startsWith('[')) "[$host]" else host}:$port"
    }

    private class Api {
        val settings = Class.forName("com.intellij.util.net.ProxySettings")
        val staticConfiguration = Class.forName("com.intellij.util.net.ProxyConfiguration\$StaticProxyConfiguration")
        val credentialStore = Class.forName("com.intellij.util.net.ProxyCredentialStore")
    }

    private val api by lazy { Api() }

    fun current(): ManualProxy? {
        if (ApplicationManager.getApplication() == null) return null
        val settings = api.settings.getMethod("getInstance").invoke(null)
        val configuration = api.settings.getMethod("getProxyConfiguration").invoke(settings)
        return readConfiguration(configuration, api.staticConfiguration)
    }

    /** Shared by Java HTTP and JCEF so both use the same HTTP/SOCKS host and port. */
    internal fun readConfiguration(configuration: Any, staticType: Class<*>): ManualProxy? {
        if (!staticType.isInstance(configuration)) return null
        val host = staticType.getMethod("getHost").invoke(configuration) as String
        val port = staticType.getMethod("getPort").invoke(configuration) as Int
        val protocol = staticType.getMethod("getProtocol").invoke(configuration) as Enum<*>
        if (host.isBlank() || port !in 1..65535) return null
        return ManualProxy(host, port, protocol.name == "SOCKS")
    }

    private fun credentials(proxy: ManualProxy): Credentials? {
        val store = api.credentialStore.getMethod("getInstance").invoke(null)
        return api.credentialStore.getMethod("getCredentials", String::class.java, Int::class.javaPrimitiveType)
            .invoke(store, proxy.host, proxy.port) as Credentials?
    }

    fun authenticator(proxy: ManualProxy): Authenticator = authenticator { credentials(proxy) }

    internal fun authenticator(credentials: () -> Credentials?): Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // A rejected credential must not cause repeated retries of the same request.
            if (response.request.header("Proxy-Authorization") != null) return null
            val saved = credentials() ?: return null
            val user = saved.userName?.takeIf { it.isNotBlank() } ?: return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", okhttp3.Credentials.basic(user, saved.getPasswordAsString() ?: ""))
                .build()
        }
    }
}
