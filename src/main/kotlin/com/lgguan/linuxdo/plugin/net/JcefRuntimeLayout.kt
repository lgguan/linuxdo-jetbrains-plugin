package com.lgguan.linuxdo.plugin.net

import com.lgguan.linuxdo.plugin.common.HostPlatform
import java.io.File

/** Native files must come from the same IDE bundle as its Java JCEF API. */
internal data class JcefRuntimeLayout(
    val platform: HostPlatform,
    val nativeDirectory: File,
    val serverExecutable: File,
    val sharedMemoryLibrary: File
) {
    companion object {
        fun resolve(jar: File, javaHome: File, platform: HostPlatform = HostPlatform.detect()): JcefRuntimeLayout {
            val pluginRoot = jar.parentFile?.parentFile?.parentFile
            val roots = listOfNotNull(
                pluginRoot?.let { File(it, "jcef") },
                File(javaHome, if (platform == HostPlatform.WINDOWS) "bin" else "lib"),
                if (platform == HostPlatform.MAC) javaHome.parentFile else null
            ).map { it.absoluteFile.normalize() }.distinct()
            return resolve(roots, platform)
        }

        fun resolve(roots: List<File>, platform: HostPlatform): JcefRuntimeLayout {
            val serverPath = when (platform) {
                HostPlatform.WINDOWS -> "cef_server.exe"
                HostPlatform.LINUX -> "cef_server"
                HostPlatform.MAC -> "Frameworks/cef_server.app/Contents/MacOS/cef_server"
            }
            val failures = mutableListOf<String>()
            for (root in roots) {
                val server = File(root, serverPath)
                val helper = File(root, platform.sharedMemoryLibrary)
                val required = mutableListOf(server, helper)
                if (platform == HostPlatform.MAC) {
                    required += File(server.parentFile.parentFile, "Frameworks/cef_server Helper.app/Contents/MacOS/cef_server Helper")
                    required += File(server.parentFile.parentFile, "Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework")
                } else if (platform == HostPlatform.LINUX) {
                    required += File(root, "libcef.so")
                } else {
                    required += File(root, "libcef.dll")
                }
                val missing = required.filterNot { it.isFile && it.canRead() }
                if (missing.isNotEmpty()) {
                    failures += missing.joinToString { it.path }
                    continue
                }
                val executables = if (platform == HostPlatform.MAC) listOf(server, required[2]) else listOf(server)
                if (platform != HostPlatform.WINDOWS && executables.any { !it.canExecute() }) {
                    failures += "原生进程缺少执行权限: ${server.path}"
                    continue
                }
                return JcefRuntimeLayout(platform, root, server, helper)
            }
            error("${platform.displayName} JCEF 原生组件不可用: ${failures.joinToString("; ")}")
        }
    }
}
