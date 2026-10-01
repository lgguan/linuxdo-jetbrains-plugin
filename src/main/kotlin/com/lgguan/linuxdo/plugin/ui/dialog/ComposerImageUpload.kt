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
    internal var transport: (ByteArray, String, String, Long) -> com.lgguan.linuxdo.plugin.model.UploadResponse = { bytes, name, type, version ->
        DiscourseApiClient.uploadImageBytes(bytes, name, type, expectedVersion = version).getOrThrow()
    }
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

    private data class Job(val name: String, val read: () -> ByteArray, val position: javax.swing.text.Position,
        val session: Long, val generation: Int)
    private val queue = java.util.ArrayDeque<Job>()
    private val failed = java.util.ArrayDeque<Job>()
    private val idle = mutableListOf<() -> Unit>()
    private var running = 0
    @Volatile private var generation = 0
    val pending: Int get() = queue.size + running
    val failures: Int get() = failed.size
    fun discardFailures() { failed.clear(); changed() }
    fun whenIdle(action: () -> Unit) { if (pending == 0) action() else idle.add(action) }
    fun cancel() { generation++; queue.clear(); failed.clear(); idle.clear() }
    fun retry() {
        if (!textArea.isEnabled || disposed()) return
        val jobs = failed.toList(); failed.clear()
        jobs.filter { it.session == SessionEpoch.current }.forEach { queue.add(it.copy(generation = generation)) }
        changed(); drain()
    }
    private fun upload(name: String, read: () -> ByteArray) {
        if (!textArea.isEnabled || disposed()) return
        queue.add(Job(name, read, textArea.document.createPosition(textArea.caretPosition), SessionEpoch.current, generation))
        statusLabel.text = "正在处理并上传图片…"
        changed()
        drain()
    }
    private fun drain() {
        while (running < 2 && queue.isNotEmpty()) {
            val job = queue.removeFirst()
            running++
            tasks.submit {
                var localUri: String? = null
                val result = runCatching {
                    check(job.session == SessionEpoch.current && !disposed() && job.generation == generation) { "账号已切换或编辑器已关闭" }
                    val bytes = job.read()
                    require(bytes.size <= ImageDownload.MAX_BYTES) { "图片超过 24 MB" }
                    localUri = LinuxDoImageCache.cacheImageBytes(bytes, job.name)
                    transport(bytes, job.name, mimeType(job.name), job.session)
                }
                ApplicationManager.getApplication().invokeLater({
                    running--
                    if (disposed() || project.isDisposed || job.generation != generation || job.session != SessionEpoch.current) {
                        drain()
                        return@invokeLater
                    }
                    result.onSuccess { upload ->
                        val target = upload.shortUrl ?: upload.url
                        listOf(upload.shortUrl, upload.url, upload.shortPath, target).forEach { LinuxDoImageCache.put(it, localUri) }
                        val label = (upload.originalFilename ?: job.name).replace(Regex("""[\[\]\r\n]"""), "_")
                        textArea.insert("\n![$label]($target)\n", job.position.offset.coerceAtMost(textArea.document.length))
                        statusLabel.text = "图片已上传并插入"
                        statusLabel.toolTipText = null
                    }.onFailure {
                        failed.add(job)
                        statusLabel.text = "图片上传失败，可点击「重试图片」"
                        statusLabel.toolTipText = ComposerErrors.parse(it)
                    }
                    changed()
                    drain()
                    if (pending == 0) idle.toList().also { idle.clear() }.forEach { it() }
                }, ModalityState.any())
            }
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
