package com.lgguan.linuxdo.plugin.net

import com.intellij.openapi.Disposable
import com.intellij.ui.jcef.JBCefCookie
import com.lgguan.linuxdo.plugin.common.HostPlatform
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandler
import org.cef.handler.CefLifeSpanHandler
import org.cef.handler.CefLoadHandler
import org.cef.network.CefCookie
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.lang.reflect.Proxy
import java.nio.ByteOrder
import java.util.Date
import java.util.UUID
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Swing browser view backed exclusively by the plugin's own cef_server. */
class LinuxDoBrowser(val runtime: IsolatedCefRuntime = IsolatedCefRuntime.get(), private val readOnly: Boolean = false) : Disposable {
    @Volatile var isDisposed = false
        private set
    private val disposalStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var created = false
    @Volatile private var nativeReady = false
    private val nativeReadyFuture = java.util.concurrent.CompletableFuture<Void>()
    private val runtimeFailure = java.util.concurrent.CompletableFuture<String>()
    private var startupWatchdog: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var pendingUrl: String? = null
    // Painting never waits for a browser RPC or holds the native mutex while Swing draws.
    private val paintLock = Any()
    private val imageLock = Any()
    private var pixels = IntArray(0)
    @Volatile private var paintFailed = false
    @Volatile private var viewSize = Dimension(1, 1)
    @Volatile private var paintedFrames = 0L
    private var resizeFrame = 0L
    private var repaintAttempts = 0
    private var image: BufferedImage? = null
    private var popup: BufferedImage? = null
    private var popupBounds = Rectangle()
    private var sharedCache: Any? = null
    internal val documentTrust = DocumentTrust().apply { renew() }
    private val documentUrl get() = documentTrust.url
    @Volatile private var documentBytes: ByteArray? = null
    @Volatile private var navigationHandler: ((String) -> Boolean)? = null
    val rawClient = runtime.newClient()
    val component = object : JPanel() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            synchronized(imageLock) {
                image?.let { g.drawImage(it, 0, 0, width, height, null) }
                popup?.let { g.drawImage(it, popupBounds.x, popupBounds.y, popupBounds.width, popupBounds.height, null) }
            }
        }
        override fun addNotify() { super.addNotify(); createImmediately() }
    }.apply {
        preferredSize = Dimension(1000, 720)
        minimumSize = Dimension(0, 0)
        isFocusable = true
        enableInputMethods(true)
    }
    val rawBrowser: Any
    val cefBrowser: CefBrowser
    val jbCefClient = ClientHandlers()
    val jbCefCookieManager = Cookies(runtime)
    private val resizeTimer = javax.swing.Timer(40) { resizeNativeView() }.apply { isRepeats = false }
    private val repaintTimer = javax.swing.Timer(100) {
        val size = viewSize
        val scale = deviceScale()
        val painted = synchronized(imageLock) {
            image?.let { it.width == kotlin.math.ceil(size.width * scale).toInt() &&
                it.height == kotlin.math.ceil(size.height * scale).toInt() && paintedFrames > resizeFrame } == true
        }
        if (!isDisposed && runtime.isUsable && !painted && repaintAttempts++ < 20) {
            cefBrowser.wasResized(size.width, size.height)
            runtime.call(rawBrowser, "invalidate")
        } else (it.source as javax.swing.Timer).stop()
    }

    init {
        val render = runtime.handler("org.cef.handler.CefNativeRenderHandler") { method, args ->
            when (method) {
                "getViewRect" -> viewSize.let { Rectangle(0, 0, it.width, it.height) }
                "getScreenPoint" -> (args[1] as Point).let { point ->
                    val origin = runCatching { component.locationOnScreen }.getOrDefault(Point())
                    BrowserGeometry.screenPoint(HostPlatform.detect(), origin, point, screenBounds(), deviceScale())
                }
                "getDeviceScaleFactor" -> deviceScale()
                "getScreenInfo" -> {
                    val bounds = screenBounds()
                    runtime.call(args[1]!!, "Set", deviceScale(), 32, 4, false, bounds, bounds)
                    true
                }
                "onPaintWithSharedMem" -> { paintShared(args); null }
                "onPopupShow" -> { if (args[1] == false) synchronized(imageLock) { popup = null }; component.repaint(); null }
                "onPopupSize" -> { synchronized(imageLock) { popupBounds = Rectangle(args[1] as Rectangle) }; null }
                "onCursorChange" -> {
                    val cursor = args[1] as Int
                    SwingUtilities.invokeLater { if (!isDisposed && cursor in 0..13) component.cursor = Cursor.getPredefinedCursor(cursor) }
                    true
                }
                else -> null
            }
        }
        val rendering = runtime.type("org.cef.browser.CefRendering\$CefRenderingWithHandler")
            .getConstructor(runtime.type("org.cef.handler.CefRenderHandler"), Component::class.java).newInstance(render, component)
        rawBrowser = runtime.call(rawClient, "createBrowser", "about:blank", rendering, false)!!
        cefBrowser = adapt(rawBrowser, CefBrowser::class.java) as CefBrowser
        jbCefClient.addLifeSpanHandler(object : org.cef.handler.CefLifeSpanHandlerAdapter() {
            override fun onAfterCreated(browser: CefBrowser?) {
                runtime.prepareBrowserCookies(rawBrowser).thenRun {
                    synchronized(this@LinuxDoBrowser) {
                        if (!isDisposed) {
                            nativeReady = true
                            startupWatchdog?.cancel(false)
                            pendingUrl?.let { pendingUrl = null; browser?.loadURL(it) }
                            nativeReadyFuture.complete(null)
                        }
                    }
                }
            }
            override fun onBeforePopup(browser: CefBrowser?, frame: org.cef.browser.CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
                targetUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }?.let { url ->
                    SwingUtilities.invokeLater { if (navigationHandler?.invoke(url) != true) loadURL(url) }
                }
                return true
            }
        }, cefBrowser)
        val requestHandler = runtime.handler("org.cef.handler.CefRequestHandler") { method, args ->
            when (method) {
                "onBeforeBrowse" -> {
                    val url = runtime.call(args[2]!!, "getURL") as String
                    val main = runtime.call(args[1]!!, "isMain") as Boolean
                    if (documentBytes != null && !main) !com.lgguan.linuxdo.plugin.theme.RenderAssets.allowedPlayer(url)
                    else navigationHandler?.invoke(url) ?: false
                }
                "onOpenURLFromTab" -> {
                    val url = args[2] as String
                    SwingUtilities.invokeLater { if (navigationHandler?.invoke(url) != true) loadURL(url) }
                    true
                }
                "getResourceRequestHandler" -> {
                    val url = runtime.call(args[2]!!, "getURL") as String
                    if (documentBytes != null) com.lgguan.linuxdo.plugin.theme.RenderAssets.resource(url)?.let { bytes ->
                        return@handler runtime.handler("org.cef.handler.CefResourceRequestHandler") { name, _ ->
                            if (name == "getResourceHandler") documentResource(bytes, "application/javascript") else null
                        }
                    }
                    SwingUtilities.invokeLater { scheduleResize() }
                    if (readOnly) return@handler runtime.handler("org.cef.handler.CefResourceRequestHandler") { name, resourceArgs ->
                        if (name == "onBeforeResourceLoad") runtime.call(resourceArgs[2]!!, "getMethod") !in setOf("GET", "HEAD", "OPTIONS") else null
                    }
                    documentBytes?.takeIf { url.substringBefore('#') == documentUrl }?.let { bytes ->
                        runtime.handler("org.cef.handler.CefResourceRequestHandler") { name, _ ->
                            if (name == "getResourceHandler") documentResource(bytes) else null
                        }
                    }
                }
                else -> null
            }
        }
        runtime.call(rawClient, "addRequestHandler", requestHandler)
        component.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) = scheduleResize()
            override fun componentMoved(e: ComponentEvent?) = updateScreenInfo()
        })
        component.addPropertyChangeListener("graphicsConfiguration") { updateScreenInfo() }
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { component.requestFocusInWindow(); cefBrowser.sendMouseEvent(e) }
            override fun mouseReleased(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
            override fun mouseClicked(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
            override fun mouseEntered(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
            override fun mouseExited(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
        })
        component.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
            override fun mouseDragged(e: MouseEvent) = cefBrowser.sendMouseEvent(e)
        })
        component.addMouseWheelListener {
            if (!it.isConsumed && !isDisposed) {
                cefBrowser.sendMouseWheelEvent(BrowserWheelSupport.toCefEvent(it))
                it.consume()
            }
        }
        component.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isConsumed) return
                val refresh = refreshHandler
                if (e.keyCode == KeyEvent.VK_F5 && refresh != null) {
                    e.consume()
                    refresh()
                } else cefBrowser.sendKeyEvent(e)
            }
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F5 && refreshHandler != null) e.consume()
                else if (!e.isConsumed) cefBrowser.sendKeyEvent(e)
            }
            override fun keyTyped(e: KeyEvent) = cefBrowser.sendKeyEvent(e)
        })
        component.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) = cefBrowser.setFocus(true)
            override fun focusLost(e: FocusEvent?) = cefBrowser.setFocus(false)
        })
        component.addInputMethodListener(object : InputMethodListener {
            override fun caretPositionChanged(event: InputMethodEvent?) {}
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                val text = event.text ?: return
                val committed = buildString { var c = text.first(); repeat(event.committedCharacterCount) { append(c); c = text.next() } }
                if (committed.isNotEmpty()) runtime.call(rawBrowser, "ImeCommitText", committed, null, 0)
                event.consume()
            }
        })
        runtime.register(this)
    }

    internal fun runtimeFailed(reason: String) {
        nativeReadyFuture.completeExceptionally(java.io.IOException(reason))
        runtimeFailure.complete(reason)
    }

    fun onRuntimeFailure(handler: (String) -> Unit) {
        runtimeFailure.thenAccept { reason -> SwingUtilities.invokeLater { handler(reason) } }
    }

    private var refreshHandler: (() -> Unit)? = null

    /** Generated documents refresh their data; native reload would lose the document body. */
    fun onRefreshRequested(handler: () -> Unit) { refreshHandler = handler }

    private fun paintShared(args: Array<out Any?>) {
        if (isDisposed) return
        val width = args[5] as Int
        val height = args[6] as Int
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000) return
        try {
            synchronized(paintLock) {
                if (isDisposed) return
                val sharedClass = Class.forName("com.jetbrains.cef.SharedMemory", true, javaClass.classLoader)
                val rasterClass = Class.forName("com.jetbrains.cef.SharedMemory\$WithRaster", true, javaClass.classLoader)
                val cache = sharedCache ?: Class.forName("com.jetbrains.cef.SharedMemoryCache", true, javaClass.classLoader)
                    .getConstructor().newInstance().also { sharedCache = it }
                val raster = cache.javaClass.getMethod("get", String::class.java, Long::class.javaPrimitiveType).invoke(cache, args[3], args[4])
                // Serialize metadata too: main and popup callbacks may share the cached native raster.
                rasterClass.getMethod("setWidth", Int::class.javaPrimitiveType).invoke(raster, width)
                rasterClass.getMethod("setHeight", Int::class.javaPrimitiveType).invoke(raster, height)
                rasterClass.getMethod("setDirtyRectsCount", Int::class.javaPrimitiveType).invoke(raster, args[2])
                val count = width * height
                if (pixels.size != count) pixels = IntArray(count)
                sharedClass.getMethod("lock").invoke(raster)
                try {
                val bytes = rasterClass.getMethod("wrapRaster").invoke(raster) as java.nio.ByteBuffer
                bytes.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
                } finally { sharedClass.getMethod("unlock").invoke(raster) }
                synchronized(imageLock) {
                    if (isDisposed) return
                    val previous = if (args[1] == true) popup else image
                    val bitmap = previous?.takeIf { it.width == width && it.height == height }
                        ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    pixels.copyInto((bitmap.raster.dataBuffer as DataBufferInt).data)
                    if (args[1] == true) popup = bitmap else image = bitmap
                    if (args[1] != true) paintedFrames++
                }
            }
        } catch (error: Exception) {
            // An exception escaping a Thrift render callback can close the entire runtime's transport.
            if (!paintFailed && !isDisposed) {
                paintFailed = true
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Private JCEF paint failed: ${NetworkTrace.errorType(error)}")
                SwingUtilities.invokeLater { scheduleResize() }
            }
            return
        }
        component.repaint()
    }

    private fun deviceScale(): Double = BrowserGeometry.scale(component.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0)

    private fun screenBounds(): Rectangle = runCatching {
        if (HostPlatform.detect() == HostPlatform.MAC) {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
        } else component.graphicsConfiguration?.bounds ?: Rectangle(0, 0, component.width, component.height)
    }.getOrDefault(Rectangle(0, 0, component.width, component.height))

    private fun updateScreenInfo() {
        if (created && !isDisposed) {
            runtime.call(rawBrowser, "notifyScreenInfoChanged")
            scheduleResize()
        }
    }

    private fun scheduleResize() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater { scheduleResize() }; return }
        viewSize = Dimension(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1))
        if (created && !isDisposed && !resizeTimer.isRunning) resizeTimer.start()
    }

    private fun resizeNativeView() {
        if (!created || isDisposed || !runtime.isUsable) return
        viewSize = Dimension(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1))
        resizeFrame = paintedFrames
        repaintAttempts = 0
        cefBrowser.wasResized(viewSize.width, viewSize.height)
        // OSR viewport changes alone do not guarantee a new frame after a burst of resizes.
        runtime.call(rawBrowser, "invalidate")
        // CEF can apply its resize after that first invalidation. Confirm an actual
        // frame at the requested size; bounded retries stop as soon as it arrives.
        repaintTimer.restart()
    }

    internal fun adapt(value: Any?, target: Class<*>): Any? {
        if (value == null || target.isInstance(value) || target.isPrimitive) return value
        if (target.isEnum) return target.enumConstants.first { (it as Enum<*>).name == (value as Enum<*>).name }
        if (target.isInterface && target.name.startsWith("org.cef.")) {
            return Proxy.newProxyInstance(target.classLoader, arrayOf(target)) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "LinuxDo private ${target.simpleName}"
                    "hashCode" -> System.identityHashCode(value)
                    "equals" -> proxy === args?.get(0)
                    else -> adapt(runtime.call(value, method.name, *(args ?: emptyArray())), method.returnType)
                }
            }
        }
        return value
    }

    /** Replace the private browser's session, awaiting Chromium before verification starts. */
    @Suppress("UNCHECKED_CAST")
    internal fun replaceSessionCookies(cookies: List<okhttp3.Cookie>, version: Long): java.util.concurrent.CompletableFuture<Void> {
        createImmediately()
        return nativeReadyFuture.thenCompose { runCatching {
            SessionEpoch.requireCurrent(version)
            check(!isDisposed) { "登录浏览器已关闭" }
            cefBrowser.stopLoad()
            val client = runtime.call(rawBrowser, "getDevToolsClient")!!
            val values = cookies.map { cookie ->
                linkedMapOf<String, Any>(
                    "name" to cookie.name, "value" to cookie.value,
                    "url" to "https://linux.do/", "path" to cookie.path,
                    "secure" to cookie.secure, "httpOnly" to cookie.httpOnly
                ).apply {
                    if (!cookie.hostOnly) put("domain", ".${cookie.domain}")
                    if (cookie.persistent) put("expires", cookie.expiresAt / 1000.0)
                }
            }
            val clear = runtime.call(client, "executeDevToolsMethod", "Network.clearBrowserCookies", "{}") as java.util.concurrent.CompletableFuture<String>
            clear.thenCompose {
                SessionEpoch.requireCurrent(version)
                check(!isDisposed) { "登录浏览器已关闭" }
                runtime.call(client, "executeDevToolsMethod", "Network.setCookies",
                    com.google.gson.Gson().toJson(mapOf("cookies" to values))) as java.util.concurrent.CompletableFuture<String>
            }.thenAccept { SessionEpoch.requireCurrent(version) }
        }.getOrElse { java.util.concurrent.CompletableFuture.failedFuture(it) }
        }.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Synchronized fun createImmediately() {
        if (!created && !isDisposed) {
            created = true
            viewSize = Dimension(component.width.coerceAtLeast(1), component.height.coerceAtLeast(1))
            startupWatchdog = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService().schedule({
                if (!nativeReady && !isDisposed) runtime.fail("正文浏览器启动超时，请重试")
            }, 25, java.util.concurrent.TimeUnit.SECONDS)
            runtime.call(rawBrowser, "createImmediately")
        }
    }
    @Synchronized fun loadURL(url: String) {
        if (isDisposed) return
        if (!nativeReady) pendingUrl = url else runtime.call(rawBrowser, "loadURL", url)
    }
    fun loadHTML(html: String) {
        // Serve only this view's synthetic document locally. Subresources retain the
        // forum origin, so private images receive the same cookies as the login view.
        documentBytes = html.toByteArray(Charsets.UTF_8)
        loadURL(documentUrl)
    }
    fun newDocument() { documentBytes = null; documentTrust.renew() }
    fun isCurrentDocument(url: String?) = documentTrust.isCurrent(url)
    fun isCurrentFloorJump(url: String) = documentTrust.isFloorJump(url)
    private fun documentResource(bytes: ByteArray, mime: String = "text/html"): Any {
        var offset = 0
        return runtime.handler("org.cef.handler.CefResourceHandler") { method, args ->
            when (method) {
                "open" -> { runtime.call(args[1]!!, "set", true); true }
                "processRequest" -> { runtime.call(args[1]!!, "Continue"); true }
                "getResponseHeaders" -> {
                    runtime.call(args[0]!!, "setStatus", 200)
                    runtime.call(args[0]!!, "setMimeType", mime)
                    runtime.call(args[0]!!, "setHeaderMap", mapOf("Content-Type" to "$mime; charset=utf-8", "Cache-Control" to "no-store", "Content-Security-Policy" to "default-src 'none'; script-src 'unsafe-inline' https://linux.do/__linuxdo_plugin_assets/; style-src 'unsafe-inline'; img-src https: http: data: blob:; media-src https: http: blob:; frame-src https://player.bilibili.com https://www.youtube-nocookie.com; connect-src 'none'; object-src 'none'; base-uri https://linux.do; form-action 'none'"))
                    runtime.call(args[1]!!, "set", bytes.size)
                    null
                }
                "read", "readResponse" -> {
                    val count = minOf(args[1] as Int, bytes.size - offset)
                    if (count > 0) bytes.copyInto(args[0] as ByteArray, 0, offset, offset + count)
                    offset += count
                    runtime.call(args[2]!!, "set", count)
                    count > 0
                }
                else -> null
            }
        }
    }
    fun onUserNavigation(callback: (String) -> Boolean) { navigationHandler = callback }
    override fun dispose() {
        if (!disposalStarted.compareAndSet(false, true)) return
        isDisposed = true
        startupWatchdog?.cancel(false)
        SwingUtilities.invokeLater { resizeTimer.stop(); repaintTimer.stop() }
        nativeReadyFuture.completeExceptionally(java.io.IOException("登录浏览器已关闭"))
        runtime.unregister(this)
        try {
            if (runtime.isUsable) {
                runtime.call(rawBrowser, "close", true)
                runtime.call(rawClient, "dispose")
            }
        } finally {
            synchronized(paintLock) { sharedCache = null; pixels = IntArray(0) }
            synchronized(imageLock) { image = null; popup = null }
            documentBytes = null; navigationHandler = null
        }
    }

    inner class ClientHandlers {
        private fun add(name: String, handler: Any, publicType: Class<*>) {
            val proxy = runtime.handler(publicType.name) { method, args ->
                if (isDisposed) null else {
                    val callback = publicType.methods.firstOrNull { it.name == method && it.parameterCount == args.size }
                    callback?.invoke(handler, *args.mapIndexed { i, a -> adapt(a, callback.parameterTypes[i]) }.toTypedArray())
                }
            }
            runtime.call(rawClient, name, proxy)
        }
        fun addLifeSpanHandler(handler: CefLifeSpanHandler, browser: CefBrowser) = add("addLifeSpanHandler", handler, CefLifeSpanHandler::class.java)
        fun addLoadHandler(handler: CefLoadHandler, browser: CefBrowser) = add("addLoadHandler", handler, CefLoadHandler::class.java)
        fun addDisplayHandler(handler: CefDisplayHandler, browser: CefBrowser) = add("addDisplayHandler", handler, CefDisplayHandler::class.java)
    }

    class Cookies(private val runtime: IsolatedCefRuntime) {
        private fun manager() = runtime.cookies()
        fun deleteCookies(url: String?, name: String?) { runtime.call(manager(), "deleteCookies", url ?: "", name ?: "") }
        fun flushStore() { runtime.call(manager(), "flushStore", null) }
        fun setCookie(url: String, cookie: JBCefCookie) {
            val cls = cookie.javaClass
            fun field(name: String): Any? = cls.getMethod(name).invoke(cookie)
            val native = runtime.type("org.cef.network.CefCookie").constructors.single().newInstance(
                field("getName"), field("getValue"), field("getDomain"), field("getPath"), field("isSecure"), field("isHttpOnly"),
                cookie.creation, cookie.lastAccess, cookie.hasExpires(), cookie.expires)
            runtime.call(manager(), "setCookie", url, native)
        }
        fun visitAllCookies(visitor: (CefCookie, Int, Int, Any?) -> Boolean): Boolean {
            val handler = runtime.handler("org.cef.callback.CefCookieVisitor") { method, args ->
                if (method != "visit") null else {
                    val cookie = args[0]!!
                    fun f(name: String) = cookie.javaClass.getField(name).get(cookie)
                    val converted = CefCookie(f("name") as String, f("value") as String, f("domain") as String, f("path") as String,
                        f("secure") as Boolean, f("httponly") as Boolean, f("creation") as Date?, f("lastAccess") as Date?, f("hasExpires") as Boolean, f("expires") as Date?)
                    visitor(converted, args[1] as Int, args[2] as Int, null)
                }
            }
            return runtime.call(manager(), "visitAllCookies", handler) == true
        }
    }
}

class LinuxDoJSQuery private constructor(private val browser: LinuxDoBrowser, private val documentOnly: Boolean) : Disposable {
    data class Response(val response: String)
    private val function = "linuxdoQuery" + UUID.randomUUID().toString().replace("-", "")
    private var handler: ((String) -> Response?)? = null
    private val runtime = browser.runtime
    private val router: Any
    private var disposed = false
    init {
        val config = runtime.type("org.cef.browser.CefMessageRouter\$CefMessageRouterConfig")
            .getConstructor(String::class.java, String::class.java).newInstance(function, function + "Cancel")
        router = runtime.type("org.cef.browser.CefMessageRouter").getMethod("create", config.javaClass).invoke(null, config)
        val callback = runtime.handler("org.cef.handler.CefMessageRouterHandler") { name, args ->
            if (name == "onQuery") {
                val frame = args[1]
                val main = frame != null && runtime.call(frame, "isMain") == true
                val url = frame?.let { runtime.call(it, "getURL") as? String }
                val message = args[3] as String
                val envelope = if (documentOnly) runCatching { com.google.gson.JsonParser.parseString(message).asJsonObject }.getOrNull() else null
                val accepted = !disposed && !browser.isDisposed && main &&
                    if (documentOnly) browser.documentTrust.accepts(url, main, envelope?.get("pageToken")?.asString.orEmpty())
                    else url != null && LinuxDoJcefBridge.isSameOrigin(url, "https://linux.do")
                if (accepted) {
                    val response = handler?.invoke(if (documentOnly) envelope!!.get("payload").asString else message)
                    runtime.call(args[5]!!, "success", response?.response ?: "")
                } else runtime.call(args[5]!!, "failure", 403, "Untrusted page")
                true
            } else null
        }
        runtime.call(router, "addHandler", callback, true)
        runtime.call(browser.rawClient, "addMessageRouter", router)
    }
    fun addHandler(handler: (String) -> Response?) { this.handler = handler }
    fun inject(expression: String): String {
        val request = if (documentOnly) "JSON.stringify({pageToken:'${browser.documentTrust.token}',payload:($expression)})" else "($expression)"
        return "window.$function({request:$request,onSuccess:function(){},onFailure:function(){}});"
    }
    @Synchronized override fun dispose() {
        if (disposed) return
        disposed = true
        handler = null
        if (!browser.isDisposed) runtime.call(browser.rawClient, "removeMessageRouter", router)
        runtime.call(router, "dispose")
    }
    companion object { @JvmOverloads fun create(browser: LinuxDoBrowser, documentOnly: Boolean = false) = LinuxDoJSQuery(browser, documentOnly) }
}
