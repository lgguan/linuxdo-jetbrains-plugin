package com.lgguan.linuxdo.plugin.ui.dialog

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.net.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A transient, read-only application page. It never uses the HTTP bridge's JSON carrier. */
internal object ForumPreviewService {
    fun cook(source: String, version: Long): String {
        SessionEpoch.requireCurrent(version)
        val result = CompletableFuture<String>()
        val requestId = UUID.randomUUID().toString()
        var browser: LinuxDoBrowser? = null
        var query: LinuxDoJSQuery? = null
        val base = DiscourseApiClient.getBaseUrl()
        ApplicationManager.getApplication().invokeLater({
            if (result.isDone || version != SessionEpoch.current) return@invokeLater
            runCatching {
                val view = LinuxDoBrowser(readOnly = true)
                browser = view
                val callback = LinuxDoJSQuery.create(view)
                query = callback
                callback.addHandler { message ->
                    runCatching {
                        val data = JsonParser.parseString(message).asJsonObject
                        if (data.get("id")?.asString == requestId && !result.isDone) {
                            SessionEpoch.requireCurrent(version)
                            if (data.has("html")) result.complete(data.get("html").asString)
                            else result.completeExceptionally(IllegalStateException(data.get("error")?.asString ?: "论坛预览失败"))
                        }
                    }.onFailure { result.completeExceptionally(it) }
                    LinuxDoJSQuery.Response("")
                }
                view.onUserNavigation { !LinuxDoJcefBridge.isSameOrigin(it, base) }
                view.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
                    override fun onLoadEnd(b: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, status: Int) {
                        if (frame?.isMain != true || !LinuxDoJcefBridge.isSameOrigin(frame.url, base) || result.isDone) return
                        val sourceJson = Gson().toJson(source)
                        val idJson = Gson().toJson(requestId)
                        val script = """
                            (function(){
                              if(window.__linuxdoPreviewStarted)return;
                              window.__linuxdoPreviewStarted=true;
                              var attempts=0;
                              function run(){
                                var cook;
                                try { cook=require('discourse/lib/text').cook; } catch(e){}
                                if(!cook){if(++attempts<40){setTimeout(run,500);return;}
                                  var message=JSON.stringify({id:$idJson,error:'论坛渲染引擎尚未加载，请完成登录验证后重试'});
                                  ${callback.inject("message")} return;}
                                Promise.resolve(cook($sourceJson)).then(function(html){
                                  var message=JSON.stringify({id:$idJson,html:String(html)});
                                  ${callback.inject("message")}
                                }).catch(function(){
                                  var message=JSON.stringify({id:$idJson,error:'论坛预览失败，本地预览已保留'});
                                  ${callback.inject("message")}
                                });
                              } run();
                            })();
                        """.trimIndent()
                        b?.executeJavaScript(script, frame.url, 0)
                    }
                }, view.cefBrowser)
                view.loadURL("$base/latest")
                view.createImmediately()
            }.onFailure { result.completeExceptionally(it) }
        }, ModalityState.any())
        try {
            return result.get(35, TimeUnit.SECONDS).also { SessionEpoch.requireCurrent(version) }
        } finally {
            result.cancel(false)
            ApplicationManager.getApplication().invokeLater({ query?.dispose(); browser?.dispose() }, ModalityState.any())
        }
    }
}
