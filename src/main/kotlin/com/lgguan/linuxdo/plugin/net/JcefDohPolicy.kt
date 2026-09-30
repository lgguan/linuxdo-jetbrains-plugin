package com.lgguan.linuxdo.plugin.net

import com.google.gson.Gson
import com.lgguan.linuxdo.plugin.common.HostPlatform
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Describes persistent, plugin-owned policies. Never writes browser/system-wide policy locations. */
internal data class JcefDohPolicy(val platform: HostPlatform, val id: String, val values: Map<String, String>) {
    fun prepare(command: (List<String>) -> Unit = ::runDefaults) {
        when (platform) {
            HostPlatform.WINDOWS -> WindowsJcefDohPolicy.write(id, values)
            HostPlatform.LINUX -> {
                val target = File(id, "managed/linuxdo-doh.json").toPath()
                Files.createDirectories(target.parent)
                val temporary = Files.createTempFile(target.parent, "linuxdo-doh-", ".tmp")
                try {
                    Files.writeString(temporary, Gson().toJson(values))
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { Files.deleteIfExists(temporary) }
            }
            HostPlatform.MAC -> macCommands().forEach(command)
        }
    }

    internal fun macCommands(): List<List<String>> = values.map { (name, value) ->
        listOf("/usr/bin/defaults", "write", id, name, "-string", value)
    }

    companion object {
        fun forProfile(platform: HostPlatform, profile: File, config: LinuxDoNetworkConfig): JcefDohPolicy {
            config.validateDoh()
            val id = when (platform) {
                // Keep the existing Windows namespace/profile so upgrades retain sessions.
                HostPlatform.WINDOWS -> "Software\\LinuxDoJetBrainsPlugin\\Jcef\\" + Integer.toHexString(profile.absolutePath.hashCode())
                HostPlatform.LINUX -> File(profile, "policies").absolutePath
                HostPlatform.MAC -> "com.lgguan.linuxdo.plugin.jcef." + MessageDigest.getInstance("SHA-256")
                    .digest(profile.canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }
            return JcefDohPolicy(platform, id, linkedMapOf(
                "DnsOverHttpsMode" to if (config.isDohEnabled) "secure" else "off",
                "DnsOverHttpsTemplates" to if (config.isDohEnabled) config.effectiveDohUrl else ""
            ))
        }

        private fun runDefaults(arguments: List<String>) {
            val process = ProcessBuilder(arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(5, TimeUnit.SECONDS)) { "macOS 插件 DoH 偏好写入超时" }
                check(process.exitValue() == 0) { "macOS 插件 DoH 偏好写入失败 (exit=${process.exitValue()})" }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }
}
