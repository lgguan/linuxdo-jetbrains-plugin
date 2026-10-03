package com.lgguan.linuxdo.plugin.ui.toolwindow

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService
import com.lgguan.linuxdo.plugin.service.LinuxDoTopicService
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import com.lgguan.linuxdo.plugin.editor.LinuxDoEditorOpener
import com.lgguan.linuxdo.plugin.ui.dialog.CommitReplyDialog
import com.lgguan.linuxdo.plugin.ui.dialog.LoginAuthDialog
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import com.intellij.ui.components.JBScrollPane
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser as JBCefBrowser
import com.lgguan.linuxdo.plugin.net.LinuxDoJSQuery as JBCefJSQuery
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingConstants

class DocViewerPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {

    private val backgroundTasks = com.lgguan.linuxdo.plugin.common.BackgroundTasks()

    private val listenerLifetime = com.intellij.openapi.util.Disposer.newDisposable()
    private val appearanceTimer = javax.swing.Timer(75) { updateAppearance() }.apply { isRepeats = false }
    private var renderOptions = renderOptions()

    private fun renderOptions(): Triple<Boolean, Boolean, Boolean> = LinuxDoSettingsState.getInstance().let {
        Triple(it.foldImages, it.hideAvatars, it.categoryNamespaceFormat)
    }

    private var jbCefBrowser: JBCefBrowser? = null
    private var viewerGeneration = 0L
    private var jsQuery: JBCefJSQuery? = null
    private var fallbackPane: JEditorPane? = null
    @Volatile private var currentTopic: TopicDetailResponse? = null
        set(value) {
            if(value==null){field=null;return}
            // Pending published replies and visible bodies share the same total cache budget.
            val bounded=com.lgguan.linuxdo.plugin.model.PostCache.bound(publishedReplies.values+value.postStream.posts,currentPostNumber ?: value.postStream.posts.firstOrNull()?.postNumber ?: 1)
            val ids=bounded.map { it.id }.toSet()
            publishedReplies.keys.retainAll(ids)
            bounded.filter { it.id in publishedReplies }.forEach { publishedReplies[it.id]=it }
            val included=value.postStream.posts.map { it.id }.toSet()
            field=value.copy(postStream=value.postStream.copy(posts=bounded.filter { it.id in included }))
        }
    @Volatile private var disposed = false
    private var topicTask: java.util.concurrent.Future<*>? = null
    private var requestedTopicId: Long? = null
    private var requestedFloor: Int? = null
    @Volatile private var loadGeneration = 0L
    private var pageKey = ""
    private var loadingPosts = false
    private var refreshingPosts = false
    private var loadingFloor = false
    private val returnFloors = mutableMapOf<Long, Int>()
    private val publishedReplies = linkedMapOf<Long, com.lgguan.linuxdo.plugin.model.Post>()
    private var readingClock = com.lgguan.linuxdo.plugin.service.ReadingClock()
    private var readingVersion = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
    private var readingIdentity = LinuxDoReadTrackingService.getInstance().identity()
    private val readerController by lazy { TopicReaderController(project, backgroundTasks,
        { currentTopic }, { currentTopic = it },
        { key, version -> !disposed && !project.isDisposed && key == pageKey && version == readingVersion && version == com.lgguan.linuxdo.plugin.net.SessionEpoch.current },
        { key, id, result -> jbCefBrowser?.cefBrowser?.executeJavaScript(
            "window.linuxDoReaderResult && window.linuxDoReaderResult(${com.google.gson.Gson().toJson(key)},${com.google.gson.Gson().toJson(id)},${result});", "", 0) }) }
    private var editorSelected = false
    private var readingScroll = -1L
    private var lastSyncStatus: String? = null
    private val readingTimer = javax.swing.Timer(1000) {
        if (!disposed && currentTopic != null && editorSelected && isShowing &&
            javax.swing.SwingUtilities.getWindowAncestor(this)?.isActive == true &&
            LinuxDoSettingsState.getInstance().autoReportReadTimings && jbCefBrowser?.runtime?.isUsable == true) {
            jbCefBrowser?.cefBrowser?.executeJavaScript("window.sampleDocReading && window.sampleDocReading();", "", 0)
        } else { readingClock.sample(emptySet(), false); flushReading() }
        if (readingClock.due()) flushReading()
        currentTopic?.let { topic ->
            val status = LinuxDoReadTrackingService.getInstance().syncStatus(topic.id)
            if (status != lastSyncStatus) {
                lastSyncStatus = status
                jbCefBrowser?.cefBrowser?.executeJavaScript("window.linuxDoSyncStatus && window.linuxDoSyncStatus(${com.google.gson.Gson().toJson(status)});", "", 0)
            }
        }
    }.apply { start() }

    fun setSelected(selected: Boolean) {
        editorSelected = selected
        if (selected && isShowing) jbCefBrowser?.component?.requestFocusInWindow()
        if (!selected) { readingClock.sample(emptySet(), false); flushReading() }
    }
    fun preferredFocusedComponent(): javax.swing.JComponent = jbCefBrowser?.component ?: this
    private fun flushReading() {
        val pending = readingClock.drain()
        currentTopic?.let { LinuxDoReadTrackingService.getInstance().submitTimings(it.id, pending, readingIdentity) }
    }

    @Volatile var currentPostNumber: Int? = null
        private set

    private val statusLabel = JBLabel("Select an issue/topic to view documentation", SwingConstants.CENTER)

    init {
        border = JBUI.Borders.empty()
        setupViewer()
        object : com.intellij.openapi.project.DumbAwareAction("刷新回复") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                refreshReplies()
            }
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F5, 0)), this, listenerLifetime)
        com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().addAuthListener(listenerLifetime) {
            if (!disposed) requestedTopicId?.let { loadTopic(it, currentPostNumber ?: requestedFloor) }
        }
        LinuxDoSettingsState.getInstance().addSettingsListener(listenerLifetime) {
            ApplicationManager.getApplication().invokeLater {
                if (disposed) return@invokeLater
                if (jbCefBrowser?.isDisposed == true) setupViewer()
                val nextOptions = renderOptions()
                if (renderOptions != nextOptions) {
                    renderOptions = nextOptions
                    currentTopic?.let { renderTopic(it, currentPostNumber) }
                } else appearanceTimer.restart()
            }
        }
        val appearanceConnection = ApplicationManager.getApplication().messageBus.connect(listenerLifetime)
        appearanceConnection.subscribe(com.intellij.openapi.editor.colors.EditorColorsManager.TOPIC,
            com.intellij.openapi.editor.colors.EditorColorsListener { scheduleAppearanceUpdate() })
        appearanceConnection.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC,
            com.intellij.ide.ui.LafManagerListener { scheduleAppearanceUpdate() })
    }

    private fun scheduleAppearanceUpdate() {
        ApplicationManager.getApplication().invokeLater {
            if (!disposed && !project.isDisposed) appearanceTimer.restart()
        }
    }

    /** Replace styling in the current document; retain loaded floors, selection and open controls. */
    private fun updateAppearance() {
        if (disposed || project.isDisposed || currentTopic == null) return
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val settings = LinuxDoSettingsState.getInstance()
        val css = com.lgguan.linuxdo.plugin.theme.DocCamouflageCssBuilder.buildCss(theme, settings) +
            com.lgguan.linuxdo.plugin.theme.ForumContent.css
        val encodedCss = com.google.gson.Gson().toJson(css)
        jbCefBrowser?.cefBrowser?.executeJavaScript("""
            (function() {
                var style = document.getElementById('linuxdo-reader-theme');
                if (!style) return;
                var anchor = Array.from(document.querySelectorAll('.post-entry')).find(function(el) { return el.getBoundingClientRect().bottom > 0; });
                var top = anchor && anchor.getBoundingClientRect().top;
                style.textContent = $encodedCss;
                document.body.style.removeProperty('font-size');
                document.body.style.removeProperty('line-height');
                document.querySelector('.doc-container').style.removeProperty('max-width');
                if (window.linuxDoPage) Object.assign(window.linuxDoPage, {
                    defaultFontSize: ${(theme.fontSize + 2).coerceAtLeast(15)}, fontSize: ${settings.readingFontSize},
                    lineHeight: ${settings.readingLineHeight}, width: ${settings.readingWidth}
                });
                window.dispatchEvent(new Event('resize'));
                if (anchor) requestAnimationFrame(function() { window.scrollBy(0, anchor.getBoundingClientRect().top - top); });
            })();
        """.trimIndent(), "", 0)
        fallbackPane?.let { currentTopic?.let { topic -> renderTopic(topic, currentPostNumber) } }
    }

    private fun setupViewer() {
        val generation = ++viewerGeneration
        jbCefBrowser?.let { com.intellij.openapi.util.Disposer.dispose(it) }
        jbCefBrowser = null
        jsQuery = null
        removeAll()
        if (com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.isSupported()) {
            add(JBLabel("正在启动正文浏览器...", SwingConstants.CENTER), BorderLayout.CENTER)
            com.lgguan.linuxdo.plugin.net.IsolatedCefRuntime.prepareBrowser { prepared ->
                if (disposed || project.isDisposed || generation != viewerGeneration) {
                    prepared.getOrNull()?.let { com.intellij.openapi.util.Disposer.dispose(it) }
                    return@prepareBrowser
                }
                prepared.onSuccess { browser ->
                    jbCefBrowser = browser
                    browser.onRuntimeFailure { reason ->
                        if (!disposed && !project.isDisposed && generation == viewerGeneration && jbCefBrowser === browser) {
                            readingClock.sample(emptySet(), false)
                            showBrowserFailure(reason)
                        }
                    }
                    browser.onRefreshRequested(::refreshReplies)
                    com.intellij.openapi.util.Disposer.register(listenerLifetime, browser)
                    setupJsBridges(browser)
                    removeAll()
                    add(browser.component, BorderLayout.CENTER)
                    currentTopic?.let { renderTopic(it, currentPostNumber) }
                        ?: requestedTopicId?.let { loadTopic(it, requestedFloor) }
                        ?: loadPlaceholder("Select an issue / document from the list to view.")
                    revalidate()
                    repaint()
                    if (editorSelected && isShowing) browser.component.requestFocusInWindow()
                }.onFailure {
                    com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Reader startup failed: ${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(it)}")
                    showBrowserFailure("正文浏览器启动失败或连接已断开")
                }
            }
        } else {
            // Fallback for non-JCEF environments
            val pane = JEditorPane().apply {
                contentType = "text/html"
                isEditable = false
            }
            fallbackPane = pane
            add(JBScrollPane(pane), BorderLayout.CENTER)
            pane.text = "<html><body><p style='padding:16px;color:#888;'>Select an issue from the list</p></body></html>"
        }
        revalidate()
        repaint()
    }

    private fun showBrowserFailure(reason: String) {
        removeAll()
        add(JPanel(java.awt.GridBagLayout()).apply {
            val box = JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                add(JBLabel(reason).apply { alignmentX = 0.5f })
                add(javax.swing.Box.createVerticalStrut(12))
                add(javax.swing.JButton("重试正文浏览器").apply {
                    alignmentX = 0.5f
                    addActionListener { if (!disposed && !project.isDisposed) setupViewer() }
                })
            }
            add(box)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun setupJsBridges(browser: JBCefBrowser) {
        val query = JBCefJSQuery.create(browser, documentOnly = true)
        com.intellij.openapi.util.Disposer.register(browser, query)
        this.jsQuery = query
        query.addHandler { rawPayload ->
            if (disposed) return@addHandler null
            val callbackGeneration = loadGeneration
            val callbackPage = browser.documentTrust.token
            val callbackEpoch = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
            try {
                val json = try { com.google.gson.JsonParser.parseString(rawPayload).asJsonObject } catch (_: Throwable) { null }
                val action = json?.get("action")?.asString ?: rawPayload.split(":").getOrNull(0) ?: ""
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.info("CEF bridge event: $action")

                when (action) {
                    "readerAction" -> {
                        val key = json?.get("key")?.asString.orEmpty()
                        val requestId = json?.get("requestId")?.asString.orEmpty()
                        val operation = json?.get("operation")?.asString.orEmpty()
                        val postId = json?.get("postId")?.asString?.toLongOrNull() ?: 0L
                        val input = json?.getAsJsonObject("input") ?: com.google.gson.JsonObject()
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || key != pageKey || callbackPage != browser.documentTrust.token || callbackEpoch != readingVersion || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            readerController.handle(key,requestId,operation,postId,input,callbackEpoch)
                        }
                    }
                    "saveImage" -> {
                        val url=json?.get("url")?.asString.orEmpty()
                        ApplicationManager.getApplication().invokeLater {
                            if(disposed || callbackPage!=browser.documentTrust.token || callbackEpoch!=readingVersion || callbackEpoch!=com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            if(!com.lgguan.linuxdo.plugin.net.DocumentTrust.isWebLink(url))return@invokeLater
                            val chooser=javax.swing.JFileChooser().apply { selectedFile=java.io.File(java.net.URI(url).path.substringAfterLast('/').ifBlank { "image.png" }) }
                            if(chooser.showSaveDialog(this)==javax.swing.JFileChooser.APPROVE_OPTION){
                                val target=chooser.selectedFile
                                if(target.exists() && Messages.showYesNoDialog(project,"替换已有文件 ${target.name}？","保存图片",Messages.getQuestionIcon())!=Messages.YES)return@invokeLater
                                backgroundTasks.submit {
                                    val result=runCatching { val bytes=downloadClipboardImage(url);if(disposed || callbackPage!=browser.documentTrust.token || callbackEpoch!=com.lgguan.linuxdo.plugin.net.SessionEpoch.current)return@runCatching;java.nio.file.Files.write(target.toPath(),bytes) }
                                    ApplicationManager.getApplication().invokeLater {
                                        if(!disposed && callbackPage==browser.documentTrust.token && callbackEpoch==com.lgguan.linuxdo.plugin.net.SessionEpoch.current)browser.cefBrowser.executeJavaScript("showDocToast(${com.google.gson.Gson().toJson(if(result.isSuccess) "图片已保存" else "图片保存失败")});","",0)
                                    }
                                }
                            }
                        }
                    }
                    "copyCode" -> {
                        val text = json?.get("text")?.asString.orEmpty()
                        val requestId = json?.get("requestId")?.asString.orEmpty()
                        val generation = loadGeneration
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            if (disposed || project.isDisposed || browser.isDisposed || generation != loadGeneration) return@invokeLater
                            val success = runCatching {
                                CopyPasteManager.getInstance().setContents(StringSelection(text))
                            }.isSuccess
                            browser.cefBrowser.executeJavaScript(
                                "window.docCodeCopyResult && window.docCodeCopyResult(${com.google.gson.Gson().toJson(requestId)}, $success);",
                                browser.cefBrowser.url, 0)
                        }
                    }
                    "copyImageFile" -> {
                        val url = json?.get("url")?.asString.orEmpty()
                        val generation = loadGeneration
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            backgroundTasks.submit {
                                val result = runCatching {
                                    val bytes = downloadClipboardImage(url)
                                    require(bytes.size <= com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
                                    val file = com.lgguan.linuxdo.plugin.common.ClipboardImageFile.save(bytes,
                                        com.lgguan.linuxdo.plugin.common.LinuxDoImageCache.getCacheDir())
                                    if (disposed || browser.isDisposed || generation != loadGeneration) return@submit
                                    com.lgguan.linuxdo.plugin.common.ImageClipboardWriter.writeContents(
                                        com.lgguan.linuxdo.plugin.common.ClipboardImageFile(file))
                                }
                                ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                    if (disposed || browser.isDisposed || generation != loadGeneration) return@invokeLater
                                    val message = result.fold({ "原图文件已复制，可粘贴到支持图片文件的应用" },
                                        { "原图文件复制失败：${it.message ?: "请稍后重试"}" })
                                    browser.cefBrowser.executeJavaScript("showDocToast(" + com.google.gson.Gson().toJson(message) + ");", browser.cefBrowser.url, 0)
                                }
                            }
                        }
                    }
                    "copyImage" -> {
                        val url = json?.get("url")?.asString.orEmpty()
                        val png = json?.get("png")?.asString.orEmpty()
                        val requestId = json?.get("requestId")?.asString.orEmpty()
                        val generation = loadGeneration
                        if (url.startsWith("https://") || url.startsWith("http://") || png.startsWith("data:image/png;base64,")) {
                            backgroundTasks.submit {
                                val result = runCatching {
                                    val bytes = if (png.startsWith("data:image/png;base64,")) {
                                        require(png.length <= 32 * 1024 * 1024) { "图片超过 24 MB" }
                                        java.util.Base64.getDecoder().decode(png.substringAfter(','))
                                    } else {
                                        downloadClipboardImage(url)
                                    }
                                    require(bytes.size <= com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
                                    val image = if (png.isNotBlank()) {
                                        com.lgguan.linuxdo.plugin.common.ImageSafety.decode(bytes)
                                    } else null
                                    if (image != null && !disposed && callbackGeneration == loadGeneration) {
                                        com.lgguan.linuxdo.plugin.common.ImageClipboardWriter.write(image)
                                    }
                                    bytes to image
                                }
                                ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                    if (disposed || project.isDisposed || browser.isDisposed || generation != loadGeneration) return@invokeLater
                                    val gson = com.google.gson.Gson()
                                    fun toast(message: String) {
                                        browser.cefBrowser.executeJavaScript("showDocToast(" + gson.toJson(message) + ");", browser.cefBrowser.url, 0)
                                    }
                                    result.fold(onSuccess = { (bytes, image) ->
                                        if (png.isBlank()) {
                                            // Chromium also decodes WebP/AVIF; Java ImageIO alone cannot.
                                            val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
                                            val args = listOf(url, encoded, requestId).joinToString(",") { gson.toJson(it) }
                                            browser.cefBrowser.executeJavaScript("window.decodeDocClipboardImage($args);", browser.cefBrowser.url, 0)
                                        } else {
                                            toast("图片已复制")
                                        }
                                    }, onFailure = { error ->
                                        com.lgguan.linuxdo.plugin.common.LinuxDoLog.warn("Image copy download failed: ${error.javaClass.simpleName}")
                                        toast(if (error is IllegalStateException && png.isNotBlank())
                                            "系统剪贴板暂时不可用，请稍后重试" else "图片复制失败：${error.message ?: "未知错误"}")
                                    })
                                }
                            }
                        }
                    }
                    "openMedia" -> {
                        val url = json?.get("url")?.asString.orEmpty()
                        if (url.startsWith("https://") || url.startsWith("http://")) {
                            ApplicationManager.getApplication().invokeLater {
                                if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                BrowserUtil.browse(url)
                            }
                        }
                    }
                    "loadPosts" -> {
                        val key = json?.get("key")?.asString ?: ""
                        val direction = json?.get("direction")?.asString ?: ""
                        val ids = json?.getAsJsonArray("ids")?.map { it.asLong } ?: emptyList()
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            loadMorePosts(key, direction, ids)
                        }
                    }
                    "jumpFloor" -> {
                        val key = json?.get("key")?.asString.orEmpty()
                        val requestId = json?.get("requestId")?.asString.orEmpty()
                        val floor = json?.get("floor")?.asInt ?: 0
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            loadFloor(key, requestId, floor)
                        }
                    }
                    "refreshPosts" -> {
                        val key = json?.get("key")?.asString.orEmpty()
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            refreshPostStream(key)
                        }
                    }
                    "like" -> {
                        val id=json?.get("postId")?.asLong ?: 0L
                        val input=com.google.gson.JsonObject().apply { addProperty("like",json?.get("like")?.asBoolean==true) }
                        val key=pageKey
                        ApplicationManager.getApplication().invokeLater {
                            if(!disposed && key==pageKey && callbackPage==browser.documentTrust.token && callbackEpoch==readingVersion && callbackEpoch==com.lgguan.linuxdo.plugin.net.SessionEpoch.current)
                                readerController.handle(key,java.util.UUID.randomUUID().toString(),"like",id,input,callbackEpoch)
                        }
                    }
                    "navigationReturn" -> {
                        val key = json?.get("key")?.asString
                        val floor = json?.get("floor")?.asInt ?: 0
                        ApplicationManager.getApplication().invokeLater {
                            if (!disposed && key == pageKey && callbackEpoch == com.lgguan.linuxdo.plugin.net.SessionEpoch.current && floor > 0)
                                currentTopic?.let { returnFloors[it.id] = floor }
                        }
                    }
                    "quoteReply", "reply" -> {
                        val floor = json?.get("floor")?.asInt ?: rawPayload.split(":").getOrNull(1)?.toIntOrNull() ?: 1
                        val author = json?.get("author")?.asString ?: rawPayload.split(":").getOrNull(2) ?: ""
                        currentTopic?.let { topic ->
                            ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                val key = pageKey
                                val selectedPost = currentTopic?.postStream?.posts?.firstOrNull { it.postNumber == floor }
                                if (!com.lgguan.linuxdo.plugin.model.PostCapabilities.reply(topic)) return@invokeLater
                                if (!com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().isLoggedIn) {
                                    openAuthDialog()
                                    return@invokeLater
                                }
                                val selected = json?.get("text")?.asString.orEmpty()
                                if (selectedPost == null) {
                                    backgroundTasks.submit {
                                        if (disposed || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@submit
                                        val target = DiscourseApiClient.getTopicAroundPost(topic.id,floor).getOrNull()?.postStream?.posts?.firstOrNull { it.postNumber==floor }
                                        ApplicationManager.getApplication().invokeLater {
                                            if(disposed || key!=pageKey || callbackEpoch!=com.lgguan.linuxdo.plugin.net.SessionEpoch.current)return@invokeLater
                                            if(target==null){browser.cefBrowser.executeJavaScript("showDocToast('引用楼层不可用，请先定位后重试');","",0);return@invokeLater}
                                            val quote=if(action=="quoteReply" && selected.isNotBlank() && selected.length<=100_000)com.lgguan.linuxdo.plugin.ui.dialog.DiscourseQuote.format(target.username,topic.id,floor,selected) else null
                                            CommitReplyDialog.open(project,topic.id,floor,target.username,target.id,quote){post->if(!disposed && key==pageKey)showPublishedReply(post)}
                                        }
                                    }
                                    return@invokeLater
                                }
                                val quote = if (action == "quoteReply" && selected.isNotBlank() && selected.length <= 100_000)
                                    com.lgguan.linuxdo.plugin.ui.dialog.DiscourseQuote.format(selectedPost.username, topic.id, floor, selected) else null
                                CommitReplyDialog.open(project, topic.id, floor, selectedPost.username, selectedPost.id, quote) { post ->
                                    if (!disposed && key == pageKey) showPublishedReply(post)
                                }
                            }
                        }
                    }
                    "openAuth" -> {
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            openAuthDialog()
                        }
                    }
                    "linkClick" -> {
                        val url = json?.get("url")?.asString ?: rawPayload.removePrefix("linkClick:")
                        if (url.isNotBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                handleDocLinkClick(url)
                            }
                        }
                    }
                    "tagClick" -> {
                        val url = json?.get("url")?.asString ?: ""
                        val text = json?.get("text")?.asString ?: ""
                        val cleanTag = extractTagOrCategory(url, text)
                        if (cleanTag.isNotBlank()) {
                            ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                searchTagInToolWindow(cleanTag)
                            }
                        }
                    }
                    "copyLink" -> {
                        val topicId = json?.get("topicId")?.asLong ?: rawPayload.split(":").getOrNull(1)?.toLongOrNull()
                        val floor = json?.get("floor")?.asInt ?: rawPayload.split(":").getOrNull(2)?.toIntOrNull()
                        if (topicId != null && currentTopic?.id == topicId) {
                            val url = "${LinuxDoSettingsState.getInstance().baseUrl}/t/$topicId/$floor"
                            CopyPasteManager.getInstance().setContents(StringSelection(url))
                            ApplicationManager.getApplication().invokeLater {
                            if (disposed || project.isDisposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                                Messages.showInfoMessage(project, "Link copied to clipboard: $url", "Share")
                            }
                        }
                    }
                    "readingSample" -> {
                        val floors = json?.getAsJsonArray("floors")?.map { it.asInt }?.toSet().orEmpty()
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current || readingVersion != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            val active = editorSelected && isShowing && javax.swing.SwingUtilities.getWindowAncestor(this)?.isActive == true && LinuxDoSettingsState.getInstance().autoReportReadTimings
                            val topic = currentTopic ?: return@invokeLater
                            val valid = floors.intersect(topic.postStream.posts.map { it.postNumber }.toSet())
                            val scroll = json?.get("scroll")?.asLong ?: readingScroll
                            val readFloors = readingClock.sample(valid, active, scroll != readingScroll, json?.get("bodyVisible")?.asBoolean ?: valid.isNotEmpty()).keys
                            readingScroll = scroll
                            readFloors.forEach { floor ->
                                currentPostNumber = floor
                                LinuxDoReadTrackingService.getInstance().markFloorRead(topic.id, floor)
                            }
                            if (readFloors.isNotEmpty()) jbCefBrowser?.cefBrowser?.executeJavaScript(
                                "window.applyDocRead && window.applyDocRead(${com.google.gson.Gson().toJson(readFloors)});", "", 0)
                            if (!active || readingClock.due()) flushReading()
                        }
                    }
                    "reportRead" -> {
                        val topicId = json?.get("topicId")?.asLong ?: currentTopic?.id
                        val floor = json?.get("floor")?.asInt ?: 1
                        ApplicationManager.getApplication().invokeLater {
                            if (disposed || callbackGeneration != loadGeneration || callbackPage != browser.documentTrust.token || callbackEpoch != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                            val topic = currentTopic ?: return@invokeLater
                            if (topic.id == topicId && topic.postStream.posts.any { it.postNumber == floor }) {
                                currentPostNumber = floor
                                LinuxDoReadTrackingService.getInstance().markFloorRead(topic.id, floor)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                com.lgguan.linuxdo.plugin.common.LinuxDoLog.error("Error processing JS bridge message", t)
            }
            JBCefJSQuery.Response("OK")
        }

        browser.onUserNavigation { url ->
            if (browser.isCurrentDocument(url)) return@onUserNavigation false
            if (com.lgguan.linuxdo.plugin.net.DocumentTrust.isWebLink(url)) {
                ApplicationManager.getApplication().invokeLater {
                    if (!disposed && !project.isDisposed) handleDocLinkClick(url)
                }
            }
            true
        }

        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (!disposed && frame?.isMain == true && browser.isCurrentDocument(frame.url)) {
                    frame.executeJavaScript(buildBridgeScript(query), frame.url, 0)
                }
            }
        }, browser.cefBrowser)
    }

    private fun downloadClipboardImage(url: String): ByteArray = runCatching {
        com.lgguan.linuxdo.plugin.net.ImageDownload.fetch(
            com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient.getClient(), url,
            LinuxDoSettingsState.getInstance().baseUrl + "/")
    }.getOrElse {
        com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.downloadBytes(url).getOrThrow()
    }

    private fun buildBridgeScript(query: JBCefJSQuery): String = """
                        window.intellijBridge = {
                            readerAction: function(key, requestId, operation, postId, input) {
                                ${query.inject(" JSON.stringify({ action: 'readerAction', key: key, requestId: requestId, operation: operation, postId: postId, input: input }) ")}
                            },
                            saveImage: function(url) {
                                ${query.inject(" JSON.stringify({ action: 'saveImage', url: url }) ")}
                            },
                            navigationReturn: function(key, floor) {
                                ${query.inject(" JSON.stringify({ action: 'navigationReturn', key: key, floor: floor }) ")}
                            },
                            quoteReply: function(floor, text) {
                                ${query.inject(" JSON.stringify({ action: 'quoteReply', floor: floor, text: text }) ")}
                            },
                            readingSample: function(floors, scroll, bodyVisible) {
                                ${query.inject(" JSON.stringify({ action: 'readingSample', floors: floors, scroll: scroll, bodyVisible: bodyVisible }) ")}
                            },
                            copyCode: function(text, requestId) {
                                ${query.inject(" JSON.stringify({ action: 'copyCode', text: text, requestId: requestId }) ")}
                            },
                            copyImageFile: function(url) {
                                ${query.inject(" JSON.stringify({ action: 'copyImageFile', url: url }) ")}
                            },
                            copyImage: function(url, png, requestId) {
                                ${query.inject(" JSON.stringify({ action: 'copyImage', url: url, png: png || '', requestId: requestId || '' }) ")}
                            },
                            openMedia: function(url) {
                                ${query.inject(" JSON.stringify({ action: 'openMedia', url: url }) ")}
                            },
                            toggleLike: function(postId, like) {
                                ${query.inject(" JSON.stringify({ action: 'like', postId: postId, like: like }) ")}
                            },
                            replyPost: function(floor, author) {
                                ${query.inject(" JSON.stringify({ action: 'reply', floor: floor, author: author }) ")}
                            },
                            copyPostLink: function(topicId, floor) {
                                ${query.inject(" JSON.stringify({ action: 'copyLink', topicId: topicId, floor: floor }) ")}
                            },
                            reportPostRead: function(topicId, floor) {
                                ${query.inject(" JSON.stringify({ action: 'reportRead', topicId: topicId, floor: floor }) ")}
                            },
                            openAuthDialog: function() {
                                ${query.inject(" JSON.stringify({ action: 'openAuth' }) ")}
                            },
                            handleLinkClick: function(url) {
                                ${query.inject(" JSON.stringify({ action: 'linkClick', url: url }) ")}
                            },
                            handleTagClick: function(url, text) {
                                ${query.inject(" JSON.stringify({ action: 'tagClick', url: url, text: text }) ")}
                            }
                        };
                        window.linuxDoJumpFloor = function(key, requestId, floor) {
                            ${query.inject(" JSON.stringify({ action: 'jumpFloor', key: key, requestId: requestId, floor: floor }) ")}
                        };
                        window.linuxDoLoadPosts = function(key, direction, ids) {
                            ${query.inject(" JSON.stringify({ action: 'loadPosts', key: key, direction: direction, ids: ids }) ")}
                        };
                        window.linuxDoRefreshPosts = function(key) {
                            ${query.inject(" JSON.stringify({ action: 'refreshPosts', key: key }) ")}
                        };
                    """.trimIndent()

    private fun handleDocLinkClick(url: String) {
        if (!com.lgguan.linuxdo.plugin.net.DocumentTrust.isWebLink(url)) return
        if (!com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.isSameOrigin(url, "https://linux.do")) {
            BrowserUtil.browse(url)
            return
        }
        // Do not open raw images or image lightbox targets in external browser
        if (url.matches(Regex(""".*\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?${'$'}""", RegexOption.IGNORE_CASE)) ||
            url.contains("/uploads/")
        ) {
            return
        }

        // Do not open tag or category links in external browser; redirect to search in tool window
        if (url.contains("/tag/") || (url.contains("/c/") && !url.contains("/uploads/"))) {
            val cleanTag = extractTagOrCategory(url, "")
            if (cleanTag.isNotBlank()) {
                searchTagInToolWindow(cleanTag)
            }
            return
        }

        val topicPattern = Regex("""https?://linux\.do/t/(?:[^/]+/)?(\d+)(?:/(\d+))?""")
        val match = topicPattern.find(url)
        if (match != null) {
            val targetTopicId = match.groupValues[1].toLongOrNull()
            if (targetTopicId != null) {
                LinuxDoEditorOpener.openTopic(project, targetTopicId, postNumber = match.groupValues.getOrNull(2)?.toIntOrNull())
                return
            }
        }

        if (url.contains("/login") || url.contains("challenge")) {
            openAuthDialog()
            return
        }

        // Open external links in default OS browser
        BrowserUtil.browse(url)
    }

    private fun openAuthDialog() {
        val dialog = LoginAuthDialog(project) {
            reloadCurrentTopic()
        }
        dialog.show()
    }

    fun loadTopic(topicId: Long, targetPostNumber: Int? = null) {
        if (disposed) return
        flushReading()
        readingClock = com.lgguan.linuxdo.plugin.service.ReadingClock()
        readingScroll = -1L
        lastSyncStatus = null
        readingVersion = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        readingIdentity = LinuxDoReadTrackingService.getInstance().identity()
        requestedTopicId = topicId
        requestedFloor = targetPostNumber
        currentPostNumber = targetPostNumber
        val generation = ++loadGeneration
        pageKey = ""
        loadingPosts = false
        refreshingPosts = false
        loadingFloor = false
        publishedReplies.clear()
        currentTopic = null
        loadPlaceholder("Loading issue #$topicId specifications...")

        topicTask?.cancel(true)
        topicTask = LinuxDoTopicService.getInstance().loadTopicDetail(
            topicId,
            onSuccess = { detail ->
                if (disposed || generation != loadGeneration || readingVersion != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@loadTopicDetail
                currentTopic = detail
                val settings = LinuxDoSettingsState.getInstance()

                // Determine effective target post number:
                // 1. Explicit targetPostNumber (e.g. from notification or link)
                // 2. Local recorded last read floor (if autoJumpToLastReadFloor is true)
                // 3. Server recorded last read floor (detail.lastReadPostNumber)
                val effectivePostNumber = targetPostNumber ?: if (settings.autoJumpToLastReadFloor) {
                    LinuxDoReadTrackingService.getInstance().getLastReadPostNumber(topicId)
                        ?: detail.lastReadPostNumber
                } else null

                renderTopic(detail, effectivePostNumber)

                // Mark target floor (or floor #1) as read locally
                val floorToMark = effectivePostNumber ?: 1
                currentPostNumber = floorToMark
                LinuxDoReadTrackingService.getInstance().markReadLocally(detail.id, floorToMark)
            },
            onError = { error ->
                if (disposed || generation != loadGeneration || readingVersion != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@loadTopicDetail
                val msg = error.message ?: ""
                val isChallenge = error is com.lgguan.linuxdo.plugin.net.CloudflareChallengeException
                if (isChallenge) {
                    loadChallengeNotice(topicId)
                } else {
                    loadPlaceholder("Failed to load specification: $msg")
                }
            },
            targetPostNumber = targetPostNumber ?: if (LinuxDoSettingsState.getInstance().autoJumpToLastReadFloor)
                LinuxDoReadTrackingService.getInstance().getLastReadPostNumber(topicId) else null,
            resumeLastRead = LinuxDoSettingsState.getInstance().autoJumpToLastReadFloor
        )
    }

    private fun loadChallengeNotice(topicId: Long) {
        jbCefBrowser?.newDocument()
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <style>
                    body {
                        background-color: ${theme.bgHex};
                        color: ${theme.fgHex};
                        font-family: '${theme.fontName}', -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                        padding: 32px 24px;
                        max-width: 720px;
                        margin: 0 auto;
                        line-height: 1.5;
                    }
                    .comment-block {
                        font-family: '${theme.fontName}', Consolas, monospace;
                        color: ${theme.commentHex};
                        white-space: pre;
                        font-size: 0.9em;
                        line-height: 1.4;
                        margin-bottom: 20px;
                    }
                    .auth-box {
                        background: ${theme.codeBlockBgHex};
                        border: 1px solid ${theme.borderHex};
                        border-radius: 8px;
                        padding: 20px;
                        margin-top: 16px;
                    }
                    .auth-title {
                        font-size: 1.15em;
                        font-weight: bold;
                        color: ${theme.keywordHex};
                        margin-bottom: 8px;
                    }
                    .auth-desc {
                        font-size: 0.92em;
                        color: ${theme.commentHex};
                        margin-bottom: 16px;
                    }
                    .auth-button {
                        display: inline-block;
                        background: #0969DA;
                        color: #ffffff;
                        font-weight: bold;
                        padding: 8px 18px;
                        border-radius: 6px;
                        border: none;
                        cursor: pointer;
                        font-size: 0.95em;
                    }
                    .auth-button:hover {
                        background: #218bff;
                    }
                </style>
            </head>
            <body>
                <div class="comment-block">/**
 * Security Verification Required (Cloudflare / Turnstile)
 * Target Issue: #$topicId
 * Status: 403 Forbidden - Access challenge triggered
 * Resolution: Resolve challenge in independent dialog popup
 */</div>
                <div class="auth-box">
                    <div class="auth-title">🔐 需要完成 Cloudflare 人机安全验证</div>
                    <div class="auth-desc">
                        linux.do 开启了 Cloudflare 防护机制。为保护主编辑区阅读视图不被网页覆盖，请点击下方按钮在<b>独立弹窗</b>中完成验证，验证后将自动载入正文。
                    </div>
                    <button class="auth-button" onclick="window.intellijBridge && window.intellijBridge.openAuthDialog()">
                        🔑 打开人机验证弹窗 (Resolve Challenge)
                    </button>
                </div>
            </body>
            </html>
        """.trimIndent()
        jbCefBrowser?.loadHTML(html)
        fallbackPane?.text = html
    }

    private fun reloadCurrentTopic() {
        requestedTopicId?.let { loadTopic(it, currentPostNumber ?: requestedFloor) }
    }

    fun refreshReplies() {
        if (disposed || project.isDisposed) return
        if (currentTopic == null || jbCefBrowser == null) reloadCurrentTopic()
        else jbCefBrowser?.cefBrowser?.executeJavaScript(
            "window.linuxDoPagination && window.linuxDoPagination.refresh();", "", 0)
    }

    private fun showPublishedReply(reply: com.lgguan.linuxdo.plugin.model.Post) {
        val topic = currentTopic ?: return
        val key = pageKey
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        if (reply.id <= 0 || reply.postNumber <= 1 || (reply.topicId != null && reply.topicId != topic.id)) {
            jbCefBrowser?.cefBrowser?.executeJavaScript(
                "window.showDocToast && showDocToast('回复已提交，暂未返回可定位楼层，请稍后刷新查看');", "", 0)
            return
        }
        // Keep the confirmed post even if an already-running index request predates this write.
        publishedReplies[reply.id] = reply
        currentTopic = com.lgguan.linuxdo.plugin.model.TopicRefresh.addReply(topic, reply)
        val settings = LinuxDoSettingsState.getInstance()
        val username = com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().currentUser?.username
        backgroundTasks.submit {
            val rendered = runCatching {
                val post = if (reply.cooked.isNotBlank()) reply else
                    DiscourseApiClient.getMorePosts(topic.id, listOf(reply.id)).getOrThrow().first { it.id == reply.id }
                post to TopicDocumentRenderer.buildPostFragment(topic, listOf(post), settings, username)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || key != pageKey || session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                rendered.onSuccess { (post, html) ->
                    currentTopic = com.lgguan.linuxdo.plugin.model.TopicRefresh.addReply(currentTopic ?: topic, post)
                    if (post.id in publishedReplies) publishedReplies[post.id] = post
                    currentPostNumber = post.postNumber
                    val gson = com.google.gson.Gson()
                    jbCefBrowser?.cefBrowser?.executeJavaScript(
                        "window.linuxDoPagination && window.linuxDoPagination.showReply(${gson.toJson(key)},${gson.toJson(post.id.toString())},${gson.toJson(html)});", "", 0)
                    jbCefBrowser?.component?.requestFocusInWindow()
                    if (jbCefBrowser == null) loadTopic(topic.id, post.postNumber)
                }.onFailure {
                    // The write already succeeded; only retry reading the known floor.
                    loadTopic(topic.id, reply.postNumber)
                }
            }
        }
    }

    private fun renderTopic(detail: TopicDetailResponse, targetPostNumber: Int? = null) {
        if (disposed) return
        renderOptions = renderOptions()
        jbCefBrowser?.newDocument()
        pageKey = java.util.UUID.randomUUID().toString()
        loadingPosts = false
        refreshingPosts = false
        loadingFloor = false
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val settings = LinuxDoSettingsState.getInstance()
        val category = LinuxDoTopicService.getInstance().getCategory(detail.categoryId)

        val bridgeJs = jsQuery?.let { buildBridgeScript(it) }

        try {
            val html = TopicDocumentRenderer.buildFullDocHtml(
                topic = detail,
                posts = detail.postStream.posts,
                categoryName = category?.name,
                categorySlug = category?.slug,
                theme = theme,
                settings = settings,
                bridgeScript = bridgeJs,
                targetPostNumber = targetPostNumber,
                paginationScript = "window.linuxDoPage = " + com.google.gson.Gson().toJson(mapOf(
                    "highest" to (detail.highestPostNumber ?: detail.postStream.posts.maxOfOrNull { it.postNumber } ?: 1),
                    "key" to pageKey, "returnFloor" to returnFloors[detail.id], "stream" to detail.postStream.stream.orEmpty().map { it.toString() },
                    "topic" to detail.id, "author" to (detail.details?.createdBy?.username ?: detail.postStream.posts.firstOrNull { it.postNumber==1 }?.username),
                    "unreadFloor" to ((detail.lastReadPostNumber ?: 0)+1).takeIf { it <= (detail.highestPostNumber ?: 1) },
                    "notificationLevel" to detail.details?.notificationLevel, "loggedIn" to com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().isLoggedIn,
                    "fontSize" to settings.readingFontSize, "defaultFontSize" to (theme.fontSize+2).coerceAtLeast(15), "lineHeight" to settings.readingLineHeight, "width" to settings.readingWidth,
                    "canVote" to detail.canVote, "userVoted" to detail.userVoted, "voteCount" to detail.voteCount, "votesLeft" to detail.votesLeft
                )) + ";\n" + paginationSource
            )

            jbCefBrowser?.loadHTML(html)
            fallbackPane?.text = html
        } catch (e: Throwable) {
            loadPlaceholder("Error rendering specification: ${e.message ?: "Unknown error"}")
        }
    }

    fun jumpToPostNumber(postNumber: Int) {
        ApplicationManager.getApplication().invokeLater {
            if (disposed || project.isDisposed || postNumber < 1) return@invokeLater
            if (jbCefBrowser == null || currentTopic == null) {
                requestedTopicId?.let { loadTopic(it, postNumber) }
            } else jbCefBrowser?.cefBrowser?.executeJavaScript(
                "window.linuxDoPagination && window.linuxDoPagination.jump($postNumber);", "", 0)
        }
    }

    private fun loadPlaceholder(message: String) {
        jbCefBrowser?.newDocument()
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val html = """
            <!DOCTYPE html>
            <html>
            <body style="background-color: ${theme.bgHex}; color: ${theme.commentHex}; font-family: '${theme.fontName}', monospace; padding: 24px;">
                <p>${TopicDocumentRenderer.escapeHtml(message)}</p>
            </body>
            </html>
        """.trimIndent()
        jbCefBrowser?.loadHTML(html)
        fallbackPane?.text = html
    }

    private fun extractTagOrCategory(url: String, text: String): String {
        if (url.contains("/tag/")) {
            val segment = url.substringAfter("/tag/").substringBefore("/").substringBefore("?")
            if (segment.isNotBlank()) {
                try {
                    val decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8)
                    if (decoded.isNotBlank()) return decoded.trim()
                } catch (_: Exception) {}
            }
        }
        if (url.contains("/c/")) {
            val segment = url.substringAfter("/c/").substringBefore("/").substringBefore("?")
            if (segment.isNotBlank()) {
                try {
                    val decoded = URLDecoder.decode(segment, StandardCharsets.UTF_8)
                    if (decoded.isNotBlank()) return decoded.trim()
                } catch (_: Exception) {}
            }
        }
        val cleanText = text.replace(Regex("""^#+\s*"""), "").trim()
        return cleanText
    }

    private fun searchTagInToolWindow(tag: String) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("API Docs") ?: return
        val doSearch = Runnable {
            val content = toolWindow.contentManager.selectedContent ?: toolWindow.contentManager.contents.firstOrNull()
            val mainPanel = content?.component as? LinuxDoDocMainPanel
            mainPanel?.issueListPanel?.search(tag)
        }
        if (toolWindow.isVisible) {
            toolWindow.activate(doSearch)
            doSearch.run()
        } else {
            toolWindow.show(doSearch)
        }
    }

    override fun dispose() {
        appearanceTimer.stop()
        topicTask?.cancel(true)
        backgroundTasks.dispose()
        if (disposed) return
        readingTimer.stop()
        flushReading()
        disposed = true
        com.intellij.openapi.util.Disposer.dispose(listenerLifetime)
        ++loadGeneration
        pageKey = ""
        try {
            jsQuery?.let { com.intellij.openapi.util.Disposer.dispose(it) }
            jsQuery = null
            jbCefBrowser?.let { com.intellij.openapi.util.Disposer.dispose(it) }
            jbCefBrowser = null
        } catch (_: Throwable) {}
    }

    private fun loadMorePosts(key: String, direction: String, ids: List<Long>) {
        val topic = currentTopic ?: return
        if (disposed || key != pageKey || loadingPosts || refreshingPosts || loadingFloor) return
        if (direction !in setOf("before", "after") || ids.isEmpty() || ids.size > 20 ||
            ids.distinct().size != ids.size || !topic.postStream.stream.orEmpty().containsAll(ids)) return
        loadingPosts = true
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        backgroundTasks.submit {
            val result = DiscourseApiClient.getMorePosts(topic.id, ids)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || key != pageKey || session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                loadingPosts = false
                val gson = com.google.gson.Gson()
                val html = result.mapCatching { received ->
                    val posts = received.filter { it.id in ids }.distinctBy { it.id }.sortedBy { it.postNumber }
                    val current = currentTopic ?: return@mapCatching ""
                    currentTopic = current.copy(postStream = current.postStream.copy(
                        posts = com.lgguan.linuxdo.plugin.model.PostCache.bound(current.postStream.posts + posts, currentPostNumber ?: posts.firstOrNull()?.postNumber ?: 1)))
                    TopicDocumentRenderer.buildPostFragment(current, posts, LinuxDoSettingsState.getInstance())
                }
                jbCefBrowser?.cefBrowser?.executeJavaScript(
                    "window.linuxDoPagination && window.linuxDoPagination.receive(${gson.toJson(key)},${gson.toJson(direction)},${gson.toJson(html.getOrDefault(""))},${html.isFailure},${(html.exceptionOrNull() as? com.lgguan.linuxdo.plugin.net.RateLimitException)?.retryAfterSeconds ?: 0});",
                    "", 0)
            }
        }
    }

    private fun refreshPostStream(key: String) {
        val topic = currentTopic ?: return
        if (disposed || key != pageKey || loadingPosts || refreshingPosts || loadingFloor) return
        refreshingPosts = true
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        backgroundTasks.submit {
            val fetched = readerController.readAround(key,topic.id,currentPostNumber ?: 1,session)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || key != pageKey || session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                refreshingPosts = false
                val result = fetched.mapCatching { fresh -> mergeFreshTopic(fresh) }
                result.onSuccess { currentTopic = it }
                val gson = com.google.gson.Gson()
                val stream = result.getOrNull()?.postStream?.stream.orEmpty().map { it.toString() }
                val fragment = fetched.getOrNull()?.let { TopicDocumentRenderer.buildPostFragment(it,it.postStream.posts,LinuxDoSettingsState.getInstance()) }.orEmpty()
                jbCefBrowser?.cefBrowser?.executeJavaScript(
                    "window.linuxDoPagination && window.linuxDoPagination.refreshed(${gson.toJson(key)},${gson.toJson(stream)},${result.isFailure},${currentTopic?.highestPostNumber ?: 1},${(result.exceptionOrNull() as? com.lgguan.linuxdo.plugin.net.RateLimitException)?.retryAfterSeconds ?: 0},${gson.toJson(fragment)});", "", 0)
            }
        }
    }

    private fun mergeFreshTopic(fresh: TopicDetailResponse, floor: Int? = null): TopicDetailResponse {
        val current = requireNotNull(currentTopic)
        val merged = if (floor == null) com.lgguan.linuxdo.plugin.model.TopicRefresh.merge(current, fresh)
            else com.lgguan.linuxdo.plugin.model.TopicRefresh.mergeAround(current, fresh, floor)
        publishedReplies.keys.removeAll(fresh.postStream.stream.orEmpty().toSet())
        val refreshed = publishedReplies.values.fold(merged) { topic, post -> com.lgguan.linuxdo.plugin.model.TopicRefresh.addReply(topic, post) }
        return refreshed.copy(postStream=refreshed.postStream.copy(posts=com.lgguan.linuxdo.plugin.model.PostCache.bound(refreshed.postStream.posts,floor ?: currentPostNumber ?: 1)))
    }

    private fun loadFloor(key: String, requestId: String, floor: Int) {
        val topic = currentTopic ?: return
        if (disposed || key != pageKey || loadingPosts || refreshingPosts || loadingFloor || floor < 1 || requestId.length !in 1..64) return
        loadingFloor = true
        val session = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        val settings = LinuxDoSettingsState.getInstance()
        val username = com.lgguan.linuxdo.plugin.service.LinuxDoAuthService.getInstance().currentUser?.username
        backgroundTasks.submit {
            val fetched = readerController.readAround(key,topic.id,floor,session).mapCatching { around ->
                val posts = around.postStream.posts.sortedBy { kotlin.math.abs(it.postNumber.toLong() - floor) }.take(20).sortedBy { it.postNumber }
                val bounded = around.copy(postStream = around.postStream.copy(posts = posts))
                com.lgguan.linuxdo.plugin.model.TopicRefresh.mergeAround(topic, bounded, floor)
                bounded to TopicDocumentRenderer.buildPostFragment(bounded, posts, settings, username)
            }
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed || key != pageKey || session != com.lgguan.linuxdo.plugin.net.SessionEpoch.current) return@invokeLater
                loadingFloor = false
                val result = fetched.mapCatching { (around, html) ->
                    currentTopic = mergeFreshTopic(around, floor)
                    html
                }
                val gson = com.google.gson.Gson()
                val ids = currentTopic?.postStream?.stream.orEmpty().map { it.toString() }
                val highest = currentTopic?.highestPostNumber ?: currentTopic?.postStream?.posts?.maxOfOrNull { it.postNumber } ?: 1
                jbCefBrowser?.cefBrowser?.executeJavaScript(
                    "window.linuxDoPagination && window.linuxDoPagination.jumped(${gson.toJson(key)},${gson.toJson(requestId)},${gson.toJson(ids)},${gson.toJson(result.getOrDefault(""))},${result.isFailure},$highest,${(result.exceptionOrNull() as? com.lgguan.linuxdo.plugin.net.RateLimitException)?.retryAfterSeconds ?: 0});", "", 0)
            }
        }
    }

    companion object {
        private val paginationSource by lazy {
            DocViewerPanel::class.java.getResourceAsStream("/web/topic-pagination.js")!!.bufferedReader().use { it.readText() }
        }
    }
}
