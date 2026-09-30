package com.lgguan.linuxdo.plugin.common

import java.util.Locale

internal enum class HostPlatform(val displayName: String, val sharedMemoryLibrary: String) {
    WINDOWS("Windows", "shared_mem_helper.dll"),
    MAC("macOS", "libshared_mem_helper.dylib"),
    LINUX("Linux", "libshared_mem_helper.so");

    companion object {
        fun detect(osName: String = System.getProperty("os.name", "unknown")): HostPlatform =
            when (osName.lowercase(Locale.ROOT)) {
                "mac os x", "macos", "darwin" -> MAC
                "linux" -> LINUX
                else -> if (osName.startsWith("Windows", ignoreCase = true)) WINDOWS
                    else error("不支持的操作系统: $osName")
            }

        fun architecture(value: String = System.getProperty("os.arch", "unknown")): String =
            when (value.lowercase(Locale.ROOT)) {
                "amd64", "x86_64", "x64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> error("不支持的处理器架构: $value；需要 x64 或 ARM64 的 IDE/JBR")
            }
    }
}
