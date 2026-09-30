package com.lgguan.linuxdo.plugin.common

import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.net.LinuxDoHttpClient
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.swing.ImageIcon

/**
 * Manages caching and resolving image URLs for dialog markdown preview.
 * Maps Discourse upload URLs (e.g. upload://..., /uploads/...) to local file URLs (file:/...)
 * so that Swing HTMLEditorKit / JEditorPane can render uploaded images without protocol or auth errors.
 */
object LinuxDoImageCache {
    private val dimensions = ConcurrentHashMap<String, java.awt.Dimension>()
    private val memoryCache = ConcurrentHashMap<String, String>() // discourseUrl / shortUrl -> local file URL (file:/...)
    private val downloadingUrls = ConcurrentHashMap.newKeySet<String>()

    fun getCacheDir(): File {
        val dir = File(PathManager.getSystemPath(), "linuxdo-plugin-images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Store mapping from Discourse URLs (upload://..., /uploads/..., shortPath, etc.) to a local file
     */
    fun put(urlOrKey: String?, localFileOrUri: String?) {
        if (urlOrKey.isNullOrBlank() || localFileOrUri.isNullOrBlank()) return
        val normalizedUri = if (localFileOrUri.startsWith("file:")) {
            localFileOrUri
        } else {
            try {
                File(localFileOrUri).toURI().toURL().toExternalForm()
            } catch (_: Throwable) {
                localFileOrUri
            }
        }
        if (memoryCache.size >= 2048) memoryCache.clear()
        memoryCache[urlOrKey] = normalizedUri

        // Also normalize variants (e.g. without leading slash, or short-url hash)
        if (urlOrKey.startsWith("upload://")) {
            val hashExt = urlOrKey.removePrefix("upload://")
            memoryCache[hashExt] = normalizedUri
            memoryCache["/uploads/short-url/$hashExt"] = normalizedUri
        }
    }

    /**
     * Resolve a markdown image URL to a local file URL if possible.
     */
    fun resolve(url: String): String? {
        val trimmed = url.trim()
        val uri = memoryCache[trimmed] ?: trimmed.takeIf { it.startsWith("file:") } ?: return null
        // Preview only files whose dimensions were checked on a worker thread.
        if (dimensions.containsKey(uri) && runCatching { File(java.net.URI(uri)).isFile }.getOrDefault(false)) return uri
        memoryCache.remove(trimmed)
        return null
    }

    /**
     * Save bytes to disk cache and return the local file URL
     */
    fun cacheImageBytes(bytes: ByteArray, preferredName: String): String {
        val size = ImageSafety.dimensions(bytes)
        val safeName = preferredName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = "${System.currentTimeMillis()}-$safeName"
        val file = File(getCacheDir(), fileName)
        file.writeBytes(bytes)
        ImageCachePolicy.prune(getCacheDir())
        val fileUrl = file.toURI().toURL().toExternalForm()
        if (dimensions.size >= 2048) { dimensions.clear(); memoryCache.clear() }
        dimensions[fileUrl] = size
        put(preferredName, fileUrl)
        return fileUrl
    }

    /**
     * Asynchronously fetch remote/discourse image if not already cached
     */
    fun asyncFetchIfMissing(rawUrl: String, onFinished: () -> Unit) {
        val trimmed = rawUrl.trim()
        if (resolve(trimmed) != null) return
        if (!downloadingUrls.add(trimmed)) return

        val fetchUrl = when {
            trimmed.startsWith("upload://") -> {
                val hashExt = trimmed.removePrefix("upload://")
                "${DiscourseApiClient.getBaseUrl()}/uploads/short-url/$hashExt"
            }
            trimmed.startsWith("/uploads/") -> {
                "${DiscourseApiClient.getBaseUrl()}$trimmed"
            }
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> {
                trimmed
            }
            else -> null
        }

        if (fetchUrl == null) {
            downloadingUrls.remove(trimmed)
            return
        }

        val version = com.lgguan.linuxdo.plugin.net.SessionEpoch.current
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val bytes = if (DiscourseApiClient.shouldUseJcefBridge()) {
                    com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.downloadBytes(fetchUrl).getOrThrow()
                } else runCatching {
                    com.lgguan.linuxdo.plugin.net.ImageDownload.fetch(LinuxDoHttpClient.getClient(), fetchUrl, "https://linux.do/")
                }.getOrElse { error ->
                    if (DiscourseApiClient.shouldUseJcefBridge(error)) com.lgguan.linuxdo.plugin.net.LinuxDoJcefBridge.downloadBytes(fetchUrl).getOrThrow()
                    else throw error
                }
                val cachedUri = cacheImageBytes(bytes, "downloaded.img")
                com.lgguan.linuxdo.plugin.net.SessionEpoch.ifCurrent(version) {
                    put(trimmed, cachedUri)
                    put(fetchUrl, cachedUri)
                }
                ApplicationManager.getApplication().invokeLater({
                    if (version == com.lgguan.linuxdo.plugin.net.SessionEpoch.current) onFinished()
                }, ModalityState.any())
            } catch (t: Throwable) {
                LinuxDoLog.warn("Image preview failed: ${t.javaClass.simpleName}")
            } finally { downloadingUrls.remove(trimmed) }
        }
    }

    /**
     * Compute image dimensions string e.g. " width='450' height='300'" to constrain image in Swing HTMLEditorKit
     */
    fun getImageDimensionAttr(localFileUrl: String, maxWidth: Int = 450): String {
        try {
            val size = dimensions[localFileUrl] ?: return " width='$maxWidth'"
            val origW = size.width
            val origH = size.height
            if (origW > 0 && origH > 0) {
                return if (origW > maxWidth) {
                    val scaledH = (origH.toDouble() * maxWidth / origW).toInt()
                    " width='$maxWidth' height='$scaledH'"
                } else {
                    " width='$origW' height='$origH'"
                }
            }
        } catch (_: Throwable) {}
        return " width='$maxWidth'"
    }

    fun clear() {
        memoryCache.clear()
        dimensions.clear()
        downloadingUrls.clear()
    }
}
