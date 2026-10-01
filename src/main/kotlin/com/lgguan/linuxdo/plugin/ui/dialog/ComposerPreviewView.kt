package com.lgguan.linuxdo.plugin.ui.dialog

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser
import com.lgguan.linuxdo.plugin.net.DocumentTrust
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import com.lgguan.linuxdo.plugin.theme.ForumContent
import com.intellij.ide.BrowserUtil
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*

/** The document stays mounted across renders, so the preview keeps its scroll position. */
internal class ComposerPreviewView(private val tasks: BackgroundTasks, private val disposed: () -> Boolean) : Disposable {
    val component = JPanel(BorderLayout())
    val status = JLabel("本地预览")
    val fallbackPane = JEditorPane("text/html", "").apply { isEditable = false }
    private val fallbackScroll = JScrollPane(fallbackPane)
    private var browser: LinuxDoBrowser? = null
    private var ready = false
    private var body = ""
    private val generation = AtomicLong()
    private var initialized = false
    @Volatile private var closed = false

    init {
        component.minimumSize = java.awt.Dimension(0, 0)
        status.border = com.intellij.util.ui.JBUI.Borders.empty(5, 8)
        status.font = status.font.deriveFont(11f)
        status.foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
        component.add(fallbackScroll); component.add(status, BorderLayout.SOUTH)
    }

    private fun display(html: String) {
        if (closed || disposed()) return
        body = html
        if (!initialized) {
            initialized = true
            runCatching {
                val view = LinuxDoBrowser()
                browser = view
                view.onUserNavigation { url ->
                    if (view.isCurrentDocument(url)) false else {
                        if (DocumentTrust.isWebLink(url)) ApplicationManager.getApplication().invokeLater { BrowserUtil.browse(url) }
                        true
                    }
                }
                view.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                    override fun onLoadEnd(b: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, status: Int) {
                        if (frame?.isMain == true && view.isCurrentDocument(frame.url)) {
                            ready = true
                            updateBrowser()
                        }
                    }
                }, view.cefBrowser)
                view.loadHTML(ComposerPreview.document(html))
                component.remove(fallbackScroll)
                component.add(view.component, BorderLayout.CENTER)
                component.revalidate()
                view.createImmediately()
            }.onFailure { browser?.dispose(); browser = null; status.text = "本地预览（兼容模式）" }
        }
        if (browser != null) updateBrowser() else {
            val scroll = fallbackScroll.verticalScrollBar.value
            fallbackPane.text = ComposerPreview.document(html)
            SwingUtilities.invokeLater { fallbackScroll.verticalScrollBar.value = scroll }
        }
    }

    private fun updateBrowser() {
        val view = browser ?: return
        if (!ready || view.isDisposed || closed) return
        view.cefBrowser.executeJavaScript("(function(){var y=window.scrollY;document.getElementById('composer-content').innerHTML=${Gson().toJson(body)};window.scrollTo(0,y);})();", view.cefBrowser.url, 0)
    }

    fun local(source: String) {
        val request = generation.incrementAndGet()
        val version = SessionEpoch.current
        status.text = "本地预览"
        tasks.submit {
            val result = runCatching { ComposerPreview.markdownToHtml(source) }
            ApplicationManager.getApplication().invokeLater({
                if (request != generation.get() || closed || disposed() || version != SessionEpoch.current) return@invokeLater
                result.onSuccess(::display).onFailure { status.text = "本地预览失败，正文已保留" }
            }, ModalityState.any())
        }
    }

    fun forum(source: String) {
        val request = generation.incrementAndGet()
        val version = SessionEpoch.current
        status.text = "正在加载论坛渲染引擎…"
        tasks.submit {
            val result = runCatching { ForumContent.render(ForumPreviewService.cook(source, version), false) }
            ApplicationManager.getApplication().invokeLater({
                if (request != generation.get() || closed || disposed() || version != SessionEpoch.current) return@invokeLater
                result.onSuccess { display(it); status.text = "论坛引擎预览（发布时由论坛最终处理）" }
                    .onFailure { status.text = "论坛预览失败，当前预览已保留"; status.toolTipText = it.message }
            }, ModalityState.any())
        }
    }
    fun invalidate() { generation.incrementAndGet() }
    override fun dispose() { closed = true; invalidate(); browser?.dispose(); browser = null }
}
