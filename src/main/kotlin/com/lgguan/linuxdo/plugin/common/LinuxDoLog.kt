package com.lgguan.linuxdo.plugin.common

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object LinuxDoLog {

    private val LOG = Logger.getInstance("LinuxDo")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val fileTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val MAX_LOGS = 300
    private const val MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5MB

    data class LogEntry(
        val timestamp: String,
        val level: String,
        val message: String
    ) {
        override fun toString(): String = "[$timestamp] [$level] $message"
    }

    private val logHistory = ConcurrentLinkedDeque<LogEntry>()

    private val dropped = AtomicLong()
    private fun boundedExecutor(name: String) = ThreadPoolExecutor(
        1, 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1024),
        java.util.concurrent.ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { _, _ -> dropped.incrementAndGet() }
    ).apply { allowCoreThreadTimeOut(true) }
    private val logExecutor = boundedExecutor("LinuxDo-LogWriter-Thread")
    private val diagnosticExecutor = boundedExecutor("LinuxDo-NetworkTrace-Thread")

    fun dispose() {
        diagnosticExecutor.shutdownNow()
        logExecutor.shutdownNow()
        logHistory.clear()
    }

    fun diagnostic(message: String) {
        diagnosticExecutor.execute {
            val lost = dropped.getAndSet(0)
            if (lost > 0) warn("Network diagnostic/log queue overflow: dropped=$lost")
            info(message)
        }
    }

    fun sanitize(message: String): String = message
        .replace(Regex("(?i)(HTTP\\s+\\d{3})\\s*:[^\\r\\n]*"), "$1 [response omitted]")
        .replace(Regex("https?://[^\\s\\\"<>]+")) { com.lgguan.linuxdo.plugin.net.NetworkTrace.safeUrl(it.value) }
        .replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|csrf[_-]?token)\\s*[:=]\\s*[^\\r\\n]+"), "$1=[redacted]")
        .replace('\r', ' ').replace('\n', ' ').take(8192)

    val logFilePath: String by lazy {
        try {
            val dir = File(PathManager.getLogPath())
            if (!dir.exists()) dir.mkdirs()
            File(dir, "linuxdo.log").absolutePath
        } catch (t: Throwable) {
            val fallbackDir = File(System.getProperty("user.home"), ".linuxdo/logs")
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            File(fallbackDir, "linuxdo.log").absolutePath
        }
    }

    fun getLogFile(): File = File(logFilePath)

    private fun addEntry(level: String, message: String) {
        val now = LocalDateTime.now().format(timeFormatter)
        val fileNow = LocalDateTime.now().format(fileTimeFormatter)
        val entry = LogEntry(now, level, message)
        logHistory.addLast(entry)
        while (logHistory.size > MAX_LOGS) {
            logHistory.pollFirst()
        }
        println("[LinuxDo] [$now] [$level] $message")

        // Asynchronously persist to file with rotation check
        logExecutor.submit {
            try {
                val file = File(logFilePath)
                if (file.exists() && file.length() > MAX_FILE_SIZE_BYTES) {
                    val backup = File(file.parentFile, "${file.name}.1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }
                file.parentFile?.mkdirs()
                PrintWriter(FileWriter(file, true)).use { writer ->
                    writer.println("[$fileNow] [$level] $message")
                }
            } catch (ignored: Throwable) {
                // Prevent recursive logging
            }
        }
    }

    fun info(message: String) {
        val safe = sanitize(message)
        LOG.info(safe)
        addEntry("INFO", safe)
    }

    fun warn(message: String, throwable: Throwable? = null) {
        val safe = sanitize(message) + (throwable?.let { " (${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(it)})" } ?: "")
        LOG.warn(safe)
        addEntry("WARN", safe)
    }

    fun error(message: String, throwable: Throwable? = null) {
        val safe = sanitize(message) + (throwable?.let { " (${com.lgguan.linuxdo.plugin.net.NetworkTrace.errorType(it)})" } ?: "")
        LOG.warn(safe)
        addEntry("ERROR", safe)
    }

    fun getLogsText(): String {
        return logHistory.joinToString("\n") { it.toString() }
    }

    fun clear() {
        logHistory.clear()
        logExecutor.submit {
            try {
                val file = File(logFilePath)
                if (file.exists()) {
                    file.writeText("")
                }
            } catch (ignored: Throwable) {}
        }
    }
}
