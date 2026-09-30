package com.lgguan.linuxdo.plugin.ui.dialog

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.lgguan.linuxdo.plugin.api.DiscourseApiClient
import com.lgguan.linuxdo.plugin.common.BackgroundTasks
import com.lgguan.linuxdo.plugin.common.ImageSafety
import com.lgguan.linuxdo.plugin.common.LinuxDoImageCache
import com.lgguan.linuxdo.plugin.net.ImageDownload
import com.lgguan.linuxdo.plugin.net.SessionEpoch
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JLabel
import javax.swing.JTextArea

/** Both composers use the same bounded, session-scoped upload and insertion path. */
internal class ComposerImageUpload(
    private val project: Project,
    private val textArea: JTextArea,
    private val statusLabel: JLabel,
    private val tasks: BackgroundTasks,
    private val disposed: () -> Boolean,
    private val changed: () -> Unit
) {
    fun bytes(bytes: ByteArray, name: String) = upload(name) { bytes }

    fun file(file: File) = upload(file.name) {
        require(file.length() <= ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
        file.inputStream().use { it.readNBytes(ImageDownload.MAX_BYTES + 1) }
    }

    fun bitmap(image: Image) = upload("clipboard-${System.currentTimeMillis()}.png") {
        val icon = ImageIcon(image)
        val width = icon.iconWidth
        val height = icon.iconHeight
        require(width > 0 && height > 0 && width.toLong() * height <= ImageSafety.MAX_PIXELS) { "图片尺寸无效或过大" }
        val buffered = image as? BufferedImage ?: BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try { icon.paintIcon(null, graphics, 0, 0) } finally { graphics.dispose() }
        }
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(buffered, "png", output)) { "无法编码图片" }
            output.toByteArray()
        }
    }

    private fun upload(name: String, read: () -> ByteArray) {
        statusLabel.text = "⏳ 正在处理并上传图片..."
        val session = SessionEpoch.current
        tasks.submit {
            var localUri: String? = null
            val result = runCatching {
                check(session == SessionEpoch.current && !disposed()) { "账号已切换或编辑器已关闭" }
                val bytes = read()
                require(bytes.size <= ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
                localUri = LinuxDoImageCache.cacheImageBytes(bytes, name)
                DiscourseApiClient.uploadImageBytes(bytes, name, mimeType(name), expectedVersion = session).getOrThrow()
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed() || project.isDisposed) return@invokeLater
                if (session != SessionEpoch.current) {
                    statusLabel.text = "账号已切换，请重新上传图片"
                    return@invokeLater
                }
                result.onSuccess { upload ->
                    val target = upload.shortUrl ?: upload.url
                    listOf(upload.shortUrl, upload.url, upload.shortPath, target).forEach { LinuxDoImageCache.put(it, localUri) }
                    val label = (upload.originalFilename ?: name).replace(Regex("[\\[\\]\\r\\n]"), "_")
                    textArea.insert("\n![$label]($target)\n", textArea.caretPosition)
                    statusLabel.text = "🟢 图片上传成功并已插入！"
                    statusLabel.toolTipText = null
                    changed()
                }.onFailure {
                    val message = ComposerErrors.parse(it.message)
                    statusLabel.text = "🔴 图片上传失败: ${message.take(35)}"
                    statusLabel.toolTipText = message
                    Messages.showErrorDialog(project, "图片上传失败:\n${ComposerErrors.format(message)}", "上传错误")
                }
            }, ModalityState.any())
        }
    }

    companion object {
        internal fun mimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            else -> "image/png"
        }
    }
}
