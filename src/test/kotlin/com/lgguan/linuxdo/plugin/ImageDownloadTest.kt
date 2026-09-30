package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.net.ImageDownload
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ImageDownloadTest {
    @Test
    fun `native image transport accepts CDN without CORS headers and sends referer`() {
        val pixels = byteArrayOf(1, 2, 3)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://linux.do/", chain.request().header("Referer"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(pixels.toResponseBody()).build()
        }.build()
        assertArrayEquals(pixels, ImageDownload.fetch(client, "https://cdn.example/image.webp", "https://linux.do/"))
    }

    @Test
    fun `failed downloads do not enter clipboard decode path`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(403).message("Forbidden").body("<html>challenge</html>".toResponseBody()).build()
        }.build()
        assertThrows(IllegalStateException::class.java) {
            ImageDownload.fetch(client, "https://cdn.example/image.png", "https://linux.do/")
        }
    }
}
