package com.lgguan.linuxdo.plugin.common

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.security.MessageDigest

/** File transfer retains every frame; imageFlavor would expose only a bitmap. */
internal class ClipboardImageFile(private val file: File) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return listOf(file)
    }

    companion object {
        fun save(bytes: ByteArray, directory: File): File {
            require(bytes.size <= com.lgguan.linuxdo.plugin.net.ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
            val header = bytes.take(12).toByteArray().toString(Charsets.ISO_8859_1)
            val extension = when {
                header.startsWith("GIF87a") || header.startsWith("GIF89a") -> "gif"
                bytes.take(8) == listOf(137, 80, 78, 71, 13, 10, 26, 10).map(Int::toByte) -> "png"
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"
                header.startsWith("RIFF") && header.substring(8) == "WEBP" -> "webp"
                else -> error("暂不支持复制此格式的原图文件")
            }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建图片缓存目录" }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return File(directory, "$hash.$extension").also { if (!it.exists()) it.writeBytes(bytes); ImageCachePolicy.prune(directory) }
        }
    }
}
