package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.LinuxDoLog
import com.lgguan.linuxdo.plugin.common.invokeLoginUiLater
import com.lgguan.linuxdo.plugin.net.LinuxDoBrowser as JBCefBrowser
import java.util.Timer
import java.util.TimerTask

object CloudflareBypassService {
    private fun getCookieManager(browser: JBCefBrowser?): JBCefBrowser.Cookies? = try {
        // Do not merge an unrelated global session into the browser's request context.
        if (browser != null) browser.jbCefCookieManager
        else IsolatedCefRuntime.currentOrNull()?.let { JBCefBrowser.Cookies(it) }
    } catch (_: Exception) {
        LinuxDoLog.warn("Unable to access browser cookie manager")
        null
    }

    fun clearBrowserCookies(browser: JBCefBrowser? = null) {
        try {
            browser?.jbCefCookieManager?.deleteCookies(null, null)
            getCookieManager(browser)?.let {
                it.deleteCookies("", "")
                it.flushStore()
            }
        } catch (e: Exception) {
            LinuxDoLog.warn("Unable to clear browser cookies: ${e.message}")
        }
    }

    fun syncCookiesFromJcef(browser: JBCefBrowser? = null, onComplete: (Int) -> Unit) {
        val generation = LinuxDoHttpClient.cookieJar.generation
        val timer = Timer("LinuxDo-cookie-sync", true)
        val snapshot = LoginCookieSnapshot { cookies ->
            timer.cancel()
            LinuxDoHttpClient.cookieJar.saveBrowserCookies(cookies, generation)
            invokeLoginUiLater { onComplete(cookies.size) }
        }
        // Fallback timer if total is -1 or store is empty
        timer.schedule(object : TimerTask() {
            override fun run() = snapshot.finish()
        }, 1200)
        try {
            val mgr = getCookieManager(browser)
            val accepted = mgr?.visitAllCookies { cookie, index, total, _ ->
                snapshot.visit(LoginCookieSupport.fromCef(cookie), index, total)
            } ?: false
            if (!accepted) snapshot.finish()
        } catch (_: Exception) {
            LinuxDoLog.warn("Unable to read browser cookies")
            snapshot.finish()
        }
    }
}
