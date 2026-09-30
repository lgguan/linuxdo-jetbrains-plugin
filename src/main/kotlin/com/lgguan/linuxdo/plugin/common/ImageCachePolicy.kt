package com.lgguan.linuxdo.plugin.common

import java.io.File
import java.nio.file.Files

internal object ImageCachePolicy {
    const val MAX_BYTES = 256L * 1024 * 1024
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    /** Only regular files immediately inside our owned cache directory are eligible. */
    @Synchronized fun prune(directory: File, now: Long = System.currentTimeMillis(), maxBytes: Long = MAX_BYTES) {
        val files = directory.listFiles()?.filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }?.sortedBy { it.lastModified() }.orEmpty()
        var total = files.sumOf { it.length() }
        files.forEach { file ->
            if (now - file.lastModified() > MAX_AGE_MS || total > maxBytes) {
                val size = file.length()
                if (file.delete()) total -= size
            }
        }
    }
}
