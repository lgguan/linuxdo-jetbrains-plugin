package com.lgguan.linuxdo.plugin.net

import okhttp3.OkHttpClient
import okhttp3.Request

/** Native transport: image CDNs need not grant browser fetch/canvas CORS access. */
internal object ImageDownload {
    const val MAX_BYTES = 24 * 1024 * 1024

    fun fetch(client: OkHttpClient, url: String, referer: String): ByteArray {
        val request = Request.Builder().url(url).header("Referer", referer).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "图片下载失败：HTTP ${response.code}" }
            val body = checkNotNull(response.body) { "图片内容为空" }
            require(body.contentLength() <= MAX_BYTES) { "图片超过 24 MB" }
            val bytes = body.byteStream().use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "图片为空或超过 24 MB" }
            return bytes
        }
    }
}
