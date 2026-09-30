package com.lgguan.linuxdo.plugin.common

import com.lgguan.linuxdo.plugin.net.ImageDownload
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

internal object ImageSafety {
    const val MAX_PIXELS = 16_000_000L
    fun dimensions(bytes: ByteArray): Dimension {
        require(bytes.size in 1..ImageDownload.MAX_BYTES) { "图片为空或超过 24 MB" }
        ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("不支持此图片格式")
            try {
                reader.input = input
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "图片解码尺寸超过 1600 万像素" }
                return Dimension(width, height)
            } finally { reader.dispose() }
        }
    }
    fun decode(bytes: ByteArray): BufferedImage {
        dimensions(bytes)
        return ImageIO.read(bytes.inputStream()) ?: error("无法解码图片")
    }
}
