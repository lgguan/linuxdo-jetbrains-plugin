package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.Disposer
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.common.HostPlatform
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CompletableFuture
import com.google.gson.JsonParser
import com.intellij.util.concurrency.AppExecutorUtil
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Owns a separate IDE-supplied cef_server, including its DNS service and cookie store. */
class IsolatedCefRuntime private constructor(val config: LinuxDoNetworkConfig) : Disposable {
    private class CefLoader(url: java.net.URL, parent: ClassLoader) : URLClassLoader(arrayOf(url), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
            // SharedMemory, WindowsPipe and PlatformUtils share one JNI library owner.
            // Share the stateless Thrift wire types used by native key events as well;
            // CefApp and the transport/browser instances remain private to this runtime.
            if ((name.startsWith("org.cef.") || name.startsWith("com.jetbrains.cef.")) &&
                !name.startsWith("com.jetbrains.cef.SharedMemory") && !name.startsWith("com.jetbrains.cef.remote.WindowsPipe") &&
                !name.startsWith("com.jetbrains.cef.remote.PlatformUtils") &&
                !name.startsWith("com.jetbrains.cef.remote.thrift.") && !name.startsWith("com.jetbrains.cef.remote.thrift_codegen.")) {
                val cls = findLoadedClass(name) ?: findClass(name)
                if (resolve) resolveClass(cls)
                cls
            } else super.loadClass(name, resolve)
        }
    }

    val profile = File(File(PathManager.getSystemPath(), "linuxdo-private-jcef"), Integer.toHexString(config.runtimeKey().hashCode()))
    private val platformCef = Class.forName("org.cef.CefApp")
    private val cefJar = locateCefJar(platformCef)
    val loader: ClassLoader = CefLoader(cefJar.toURI().toURL(), javaClass.classLoader)
    private val layout = findNativeLayout(cefJar)
    val nativeDir: File = layout.nativeDirectory
    private val policy = JcefDohPolicy.forProfile(layout.platform, profile, config)
    val policyKey: String = policy.id
    private val browsers = CopyOnWriteArrayList<LinuxDoBrowser>()
    @Volatile var disposed = false
        private set
    private lateinit var app: Any
    private val initialized = AtomicBoolean(false)
    private val nativeDisposalStarted = AtomicBoolean(false)
    private val termination = CompletableFuture<Void>()
    private var retirementWatchdog: ScheduledFuture<*>? = null
    private var startupWatchdog: ScheduledFuture<*>? = null
    @Volatile private var startupFailure: String? = null
    private var cookiePreparation: CompletableFuture<Void>? = null
    val settings: Any

    init {
        config.validateDoh()
        checkApi(platformCef)
        try {
            val cefApp = type("org.cef.CefApp")
            ensureSharedMemoryLoaded()
            run {
                val severity = type("org.cef.CefSettings\$LogSeverity")
                val level = if (java.lang.Boolean.getBoolean("linuxdo.private.debug")) "LOGSEVERITY_VERBOSE" else "LOGSEVERITY_INFO"
                val verbose = severity.enumConstants.first { (it as Enum<*>).name == level }
                File(PathManager.getLogPath()).mkdirs()
                type("org.cef.misc.CefLog").getMethod("init", String::class.java, severity)
                    .invoke(null, File(PathManager.getLogPath(), "linuxdo-private-jcef.log").absolutePath, verbose)
            }
            // isRemoteSupported() checks the JBR's default directory; recent IDEs ship
            // cef_server in the bundled jcef plugin instead. We supply that path explicitly.
            cefApp.getMethod("setIsRemoteEnabled", Boolean::class.javaPrimitiveType)
            check(profile.isDirectory || profile.mkdirs()) { "无法创建插件 JCEF 缓存目录: $profile" }
            val jcefConfig = type("com.jetbrains.cef.JCefAppConfig")
                .getMethod("getInstance", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, nativeDir.absolutePath, true)
            settings = call(jcefConfig, "getCefSettings")!!
            fun setting(name: String, value: Any) { type("org.cef.CefSettings").getField(name).set(settings, value) }
            setting("cache_path", profile.absolutePath)
            setting("windowless_rendering_enabled", true)
            setting("no_sandbox", true) // Matches the IDE-supplied cef_server launch contract.
            setting("chrome_policy_id", policyKey)
            setting("log_file", File(PathManager.getLogPath(), "linuxdo-private-chromium.log").absolutePath)
            policy.prepare()
            val args = (call(jcefConfig, "getAppArgsAsList") as List<*>).map { it.toString() }.toMutableList()
            args += "--disable-component-update"
            // Chromium 144's legacy connector tries same-family addresses serially.
            // Race DoH endpoints so an unreachable CDN IP does not stall every script
            // for the OS TCP timeout. Keep DNS HTTPS/ECH metadata intact.
            args += "--enable-features=EncryptedClientHello,UseDnsHttpsSvcb,HappyEyeballsV3," +
                "TimeoutTcpConnectAttempt:TimeoutTcpConnectAttemptMin/2s/TimeoutTcpConnectAttemptMax/5s"
            if (config.proxyPolicy == ProxyPolicy.DIRECT) args += "--no-proxy-server"
            else {
                val proxy = com.intellij.util.net.HttpConfigurable.getInstance()
                if (proxy.USE_HTTP_PROXY) {
                    val scheme = if (proxy.PROXY_TYPE_IS_SOCKS) "socks5" else "http"
                    args += "--proxy-server=$scheme://${proxy.PROXY_HOST}:${proxy.PROXY_PORT}"
                }
            }
            if (config.isDohEnabled && config.effectiveBootstrapIp.isNotBlank()) {
                val host = DohEndpoint.parse(config.effectiveDohUrl).url.host
                val forumHost = config.baseUrl.toHttpUrlOrNull()?.host
                require(host != forumHost) { "DoH 引导域名不能与论坛域名相同" }
                val ip = config.effectiveBootstrapIp.removeSurrounding("[", "]")
                args += "--host-resolver-rules=MAP $host ${if (':' in ip) "[$ip]" else ip}"
            }
            args += NetworkCapture.startupOptions()
            // Remote mode is set on the private classloader's CefApp, never the IDE's class.
            cefApp.getMethod("setIsRemoteEnabled", Boolean::class.javaPrimitiveType).invoke(null, true)
            val transportClass = type("com.jetbrains.cef.remote.ThriftTransport")
            val pipe = transportClass.getMethod("getServerPipe", String::class.java).invoke(null, "linuxdo-${UUID.randomUUID()}")
            val transport = transportClass.getConstructor(String::class.java).newInstance(pipe)
            val server = call(jcefConfig, "getServerExe") as File
            check(server.canonicalFile == layout.serverExecutable.canonicalFile) { "JCEF 启动配置与原生组件目录不匹配" }
            app = cefApp.getMethod("getInstance", Array<String>::class.java, type("org.cef.CefSettings"), transportClass, File::class.java)
                .invoke(null, args.toTypedArray(), settings, transport, server)
            call(app, "onInitialization", handler("org.cef.handler.CefAppStateHandler") { _, values ->
                if (values.firstOrNull().toString() == "INITIALIZED") {
                    initialized.set(true)
                    startupWatchdog?.cancel(false)
                    if (disposed) finishNativeDisposal()
                    else NetworkTrace.event("private-runtime", "JCEF", "initialized", "profile" to profile.absolutePath)
                }
                null
            })
            startupWatchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!initialized.get() && !disposed) {
                    startupFailure = "插件 JCEF 本地进程连接失败；详见 linuxdo-private-jcef.log"
                    com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn(startupFailure!!)
                    // CefApp.dispose() in state NEW only changes the Java state. It does
                    // not stop the already launched server. Retire this failed instance
                    // instead of returning it on every subsequent browser retry.
                    dispose()
                }
            }, 25, TimeUnit.SECONDS)
            if (initialized.get()) startupWatchdog?.cancel(false)
            NetworkTrace.event("private-runtime", "JCEF", "initialization_requested", "profile" to profile.absolutePath,
                "platform" to layout.platform.displayName, "architecture" to HostPlatform.architecture(),
                "doh" to NetworkTrace.safeUrl(config.effectiveDohUrl), "proxy" to config.proxyPolicy.name)
        } catch (t: Throwable) {
            if (::app.isInitialized) runCatching { dispose() }
            else runCatching { (loader as URLClassLoader).close() }
            throw if (t is InvocationTargetException) t.targetException else t
        }
    }

    internal fun ensureSharedMemoryLoaded() {
        val shared = Class.forName("com.jetbrains.cef.SharedMemory", true, javaClass.classLoader)
        synchronized(shared) {
            if (shared.getMethod("isIsLoaded").invoke(null) != true) {
                shared.getMethod("loadDynamicLib", String::class.java).invoke(null, layout.sharedMemoryLibrary.absolutePath)
            }
            check(shared.getMethod("isIsLoaded").invoke(null) == true) {
                "${layout.platform.displayName}/${HostPlatform.architecture()} 无法加载 JCEF 共享内存库: ${layout.sharedMemoryLibrary}；请使用 IDE 配套的 JBR"
            }
        }
    }

    fun type(name: String): Class<*> = Class.forName(name, true, loader)
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        // Use the public API declaration, even for a package-private implementation.
        val implementation = target.javaClass.methods.firstOrNull { m ->
            m.name == name && m.parameterCount == args.size && m.parameterTypes.zip(args).all { (p, a) ->
                a == null || p.isInstance(a) || (p.isPrimitive && a is Number) || (p == Boolean::class.javaPrimitiveType && a is Boolean)
            }
        } ?: error("JCEF API unavailable: $name/${args.size}")
        fun publicDeclaration(cls: Class<*>): java.lang.reflect.Method? {
            if (Modifier.isPublic(cls.modifiers)) {
                runCatching { cls.getMethod(name, *implementation.parameterTypes) }.getOrNull()?.let {
                    if (Modifier.isPublic(it.declaringClass.modifiers)) return it
                }
            }
            cls.interfaces.forEach { publicDeclaration(it)?.let { method -> return method } }
            return cls.superclass?.let { publicDeclaration(it) }
        }
        val method = publicDeclaration(target.javaClass) ?: implementation
        try { return method.invoke(target, *args) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    fun handler(name: String, fn: (String, Array<out Any?>) -> Any?): Any {
        val cls = type(name)
        return Proxy.newProxyInstance(loader, arrayOf(cls)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "LinuxDo isolated $name"
                "getNativeRef" -> 0L
                "setNativeRef" -> null
                else -> fn(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                    Boolean::class.javaPrimitiveType -> false
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Double::class.javaPrimitiveType -> 1.0
                    else -> null
                }
            }
        }
    }

    fun newClient(): Any {
        check(!disposed) { startupFailure ?: "插件 JCEF 已关闭" }
        return call(app, "createClient")!!
    }
    internal fun register(browser: LinuxDoBrowser) { browsers += browser }
    internal fun unregister(browser: LinuxDoBrowser) { browsers -= browser }
    fun cookies(): Any = type("org.cef.network.CefCookieManager").getMethod("getGlobalManager").invoke(null)

    /** Remove only the duplicate domain session created by the old importer. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized internal fun prepareBrowserCookies(browser: Any): CompletableFuture<Void> {
        cookiePreparation?.let { return it }
        val ready = CompletableFuture<Void>()
        cookiePreparation = ready
        try {
            val client = call(browser, "getDevToolsClient")!!
            val query = call(client, "executeDevToolsMethod", "Network.getCookies",
                """{"urls":["https://linux.do/session"]}""") as CompletableFuture<String>
            query.thenCompose { response ->
                val cookies = JsonParser.parseString(response).asJsonObject.getAsJsonArray("cookies")
                if (LoginCookieSupport.hasLegacySessionDuplicate(cookies)) {
                    (call(client, "executeDevToolsMethod", "Network.deleteCookies",
                        """{"name":"_forum_session","domain":".linux.do","path":"/"}""") as CompletableFuture<String>).thenApply {
                        NetworkTrace.event("private-runtime", "JCEF", "legacy_session_duplicate_removed")
                        it
                    }
                } else CompletableFuture.completedFuture("")
            }.orTimeout(5, TimeUnit.SECONDS).whenComplete { _, error ->
                if (error != null) {
                    com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Browser session preparation failed: ${NetworkTrace.errorType(error)}")
                }
                ready.complete(null)
            }
        } catch (t: Throwable) {
            com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Browser session preparation failed: ${NetworkTrace.errorType(t)}")
            ready.complete(null)
        }
        return ready
    }

    @Synchronized override fun dispose() {
        if (disposed) return
        disposed = true
        startupWatchdog?.cancel(false)
        browsers.toList().forEach { browser ->
            runCatching { Disposer.dispose(browser) }.onFailure {
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Private JCEF browser cleanup failed: ${NetworkTrace.errorType(it)}")
            }
        }
        // CefApp.dispose() in NEW races its initialization thread and leaves a live server.
        // Retire after initialization, or stop our exact process if startup never completes.
        if (initialized.get()) finishNativeDisposal()
        retirementWatchdog = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (!termination.isDone) forceNativeDisposal()
        }, 5, TimeUnit.SECONDS)
        termination.whenComplete { _, _ -> retirementWatchdog?.cancel(false) }
    }

    private fun nativeProcess(): Process? {
        val server = call(app, "getServer")!!
        val transport = call(server, "getThriftServer")!!
        // Package-private in 262. Only inspect the private classloader's process map.
        val field = type("com.jetbrains.cef.remote.ServerStarter").getDeclaredField("ourNativeServerProcesses")
        check(field.trySetAccessible()) { "无法读取插件私有 JCEF 进程句柄" }
        val processes = field.get(null) as Map<*, *>
        return processes[transport.toString()] as? Process
    }

    private fun finishNativeDisposal() {
        if (!nativeDisposalStarted.compareAndSet(false, true)) return
        runCatching {
            val process = nativeProcess()
            call(app, "dispose")
            if (process == null || !process.isAlive) termination.complete(null)
            else process.onExit().thenRun { termination.complete(null) }
        }.onFailure {
            com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Private JCEF cleanup failed: ${NetworkTrace.errorType(it)}")
        }
    }

    private fun forceNativeDisposal() {
        runCatching {
            val process = nativeProcess()
            if (process != null && process.isAlive) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
                check(!process.isAlive) { "插件私有 JCEF 进程未退出" }
            }
            call(app, "dispose")
            termination.complete(null)
        }.onFailure { termination.completeExceptionally(it) }
    }

    private fun awaitTermination() {
        try { termination.get(6, TimeUnit.SECONDS) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw e }
    }

    companion object {
        @Volatile private var current: IsolatedCefRuntime? = null
        fun currentOrNull(): IsolatedCefRuntime? = current?.takeUnless { it.disposed }
        fun prepare(onReady: (Result<IsolatedCefRuntime>) -> Unit) {
            ApplicationManager.getApplication().executeOnPooledThread {
                val result = runCatching { get() }
                ApplicationManager.getApplication().invokeLater({ onReady(result) }, com.intellij.openapi.application.ModalityState.any())
            }
        }
        @Synchronized fun get(): IsolatedCefRuntime {
            val config = LinuxDoSettingsState.getInstance().toNetworkConfig().copy(revision = 0)
            currentOrNull()?.let {
                if (it.config.runtimeKey() == config.runtimeKey()) return it
                it.dispose()
            }
            // Reusing a profile before its process exits makes CEF silently select a temporary profile.
            current?.takeIf { it.disposed && it.config.runtimeKey() == config.runtimeKey() }?.awaitTermination()
            return IsolatedCefRuntime(config).also { runtime ->
                current = runtime
                Disposer.register(ApplicationManager.getApplication().getService(LinuxDoPluginLifetime::class.java), runtime)
            }
        }
        @Volatile private var reportedFailure: String? = null
        fun supportFailure(): String? = runCatching {
            HostPlatform.architecture()
            val cls = Class.forName("org.cef.CefApp")
            checkApi(cls)
            val layout = findNativeLayout(locateCefJar(cls))
            if (layout.platform == HostPlatform.MAC) {
                check(File("/usr/bin/defaults").canExecute()) { "缺少 macOS 用户偏好工具 /usr/bin/defaults" }
            }
        }.exceptionOrNull()?.let {
            "${System.getProperty("os.name")}/${System.getProperty("os.arch")}: ${it.javaClass.simpleName}: ${it.message}；需要 JetBrains 2026.2+ 及配套 JBR/JCEF"
        }

        fun isSupported(): Boolean {
            val failure = supportFailure()
            if (failure != null && failure != reportedFailure) {
                reportedFailure = failure
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Private JCEF capability check failed: $failure")
            }
            return failure == null
        }

        private fun locateCefJar(cls: Class<*>): File {
            // IntelliJ's module classloaders need not expose a CodeSource. Resolve
            // the actual class resource through the platform's classpath helper.
            val path = PathManager.getJarPathForClass(cls)
                ?: error("无法定位 IDE JCEF 类库: ${cls.name}")
            return File(path).also { require(it.isFile) { "JCEF 类库不存在: $path" } }
        }

        private fun findNativeLayout(jar: File): JcefRuntimeLayout =
            JcefRuntimeLayout.resolve(jar, File(System.getProperty("java.home")))

        private fun checkApi(cefApp: Class<*>) {
            fun cls(name: String) = Class.forName(name, false, cefApp.classLoader)
            val cefSettings = cls("org.cef.CefSettings")
            val transport = cls("com.jetbrains.cef.remote.ThriftTransport")
            cefApp.getMethod("setIsRemoteEnabled", Boolean::class.javaPrimitiveType)
            cefApp.getMethod("getInstance", Array<String>::class.java, cefSettings, transport, File::class.java)
            cefSettings.getField("chrome_policy_id")
            transport.getConstructor(String::class.java)
            transport.getMethod("getServerPipe", String::class.java)
            cls("com.jetbrains.cef.JCefAppConfig").apply {
                getMethod("getInstance", String::class.java, Boolean::class.javaPrimitiveType)
                getMethod("getServerExe")
            }
            cls("org.cef.handler.CefNativeRenderHandler").getMethod("getDeviceScaleFactor", cls("org.cef.browser.CefBrowser"))
            cls("org.cef.browser.CefRendering\$CefRenderingWithHandler")
                .getConstructor(cls("org.cef.handler.CefRenderHandler"), java.awt.Component::class.java)
            cls("com.jetbrains.cef.SharedMemory").getMethod("loadDynamicLib", String::class.java)
            cls("com.jetbrains.cef.SharedMemory\$WithRaster").getMethod("wrapRaster")
            cls("com.jetbrains.cef.remote.ServerStarter").getDeclaredField("ourNativeServerProcesses")
            cls("org.cef.browser.CefBrowser").getMethod("notifyScreenInfoChanged")
        }
    }
}
