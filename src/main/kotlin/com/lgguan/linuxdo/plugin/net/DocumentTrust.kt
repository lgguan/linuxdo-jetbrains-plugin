package com.lgguan.linuxdo.plugin.net

import java.net.URI
import java.util.UUID

/** Capability for one locally generated document. A fragment never changes its origin. */
internal class DocumentTrust {
    @Volatile var url: String = ""
        private set
    @Volatile var token: String = ""
        private set

    fun renew(): String {
        token = UUID.randomUUID().toString()
        url = "https://linux.do/__linuxdo_plugin_document_$token.html"
        return url
    }

    fun accepts(frameUrl: String?, mainFrame: Boolean, pageToken: String): Boolean =
        mainFrame && token.isNotEmpty() && pageToken == token && isCurrent(frameUrl)

    fun isCurrent(target: String?): Boolean = url.isNotEmpty() && target?.substringBefore('#') == url

    fun isFloorJump(target: String): Boolean = isCurrent(target) &&
        runCatching { URI(target).fragment?.matches(Regex("post-[1-9][0-9]*")) == true }.getOrDefault(false)

    companion object {
        fun isWebLink(target: String): Boolean = runCatching {
            val uri = URI(target)
            uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
        }.getOrDefault(false)
    }
}
