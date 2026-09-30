package com.lgguan.linuxdo.plugin

import com.google.gson.JsonParser
import com.lgguan.linuxdo.plugin.common.BrowserIdentity
import com.lgguan.linuxdo.plugin.common.HostPlatform
import com.lgguan.linuxdo.plugin.common.PlatformShortcuts
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.net.BrowserGeometry
import com.lgguan.linuxdo.plugin.net.JcefDohPolicy
import com.lgguan.linuxdo.plugin.net.JcefRuntimeLayout
import com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.JPanel

class CrossPlatformSupportTest {
    @TempDir lateinit var temporary: File

    @Test fun `platform detection supports IDE architectures and rejects unknown hosts`() {
        assertEquals(HostPlatform.WINDOWS, HostPlatform.detect("Windows 11"))
        assertEquals(HostPlatform.MAC, HostPlatform.detect("Darwin"))
        assertEquals(HostPlatform.MAC, HostPlatform.detect("Mac OS X"))
        assertEquals(HostPlatform.LINUX, HostPlatform.detect("Linux"))
        for (value in listOf("amd64", "x86_64", "x64")) assertEquals("x64", HostPlatform.architecture(value))
        for (value in listOf("aarch64", "arm64")) assertEquals("arm64", HostPlatform.architecture(value))
        assertThrows(IllegalStateException::class.java) { HostPlatform.detect("FreeBSD") }
        assertThrows(IllegalStateException::class.java) { HostPlatform.architecture("x86") }
    }

    private fun file(path: String): File = File(temporary, path).apply {
        parentFile.mkdirs()
        writeText("fixture")
        setExecutable(true)
    }

    private fun bundle(root: String, platform: HostPlatform): File {
        file("$root/${platform.sharedMemoryLibrary}")
        when (platform) {
            HostPlatform.WINDOWS -> { file("$root/cef_server.exe"); file("$root/libcef.dll") }
            HostPlatform.LINUX -> { file("$root/cef_server"); file("$root/libcef.so") }
            HostPlatform.MAC -> {
                file("$root/Frameworks/cef_server.app/Contents/MacOS/cef_server")
                file("$root/Frameworks/cef_server.app/Contents/Frameworks/cef_server Helper.app/Contents/MacOS/cef_server Helper")
                file("$root/Frameworks/cef_server.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework")
            }
        }
        return File(temporary, root)
    }

    @Test fun `resolve IDE bundles including mac app and paths with spaces`() {
        for (platform in HostPlatform.entries) {
            val root = "${platform.name} IDE.app/Contents/plugins/jcef-plugin"
            val jar = file("$root/lib/modules/intellij.libraries.jcef.jar")
            val native = bundle("$root/jcef", platform)
            val layout = JcefRuntimeLayout.resolve(jar, File(temporary, "missing-jbr"), platform)
            assertEquals(native, layout.nativeDirectory)
            assertTrue(layout.serverExecutable.isFile)
            assertEquals(File(native, platform.sharedMemoryLibrary), layout.sharedMemoryLibrary)
        }
    }

    @Test fun `JBR fallback is used only when it has a complete bundle`() {
        val jar = file("plugins/jcef-plugin/lib/modules/intellij.libraries.jcef.jar")
        file("plugins/jcef-plugin/jcef/cef_server")
        val native = bundle("jbr/lib", HostPlatform.LINUX)
        assertEquals(native, JcefRuntimeLayout.resolve(jar, File(temporary, "jbr"), HostPlatform.LINUX).nativeDirectory)
        File(native, "libshared_mem_helper.so").delete()
        val failure = assertThrows(IllegalStateException::class.java) {
            JcefRuntimeLayout.resolve(jar, File(temporary, "jbr"), HostPlatform.LINUX)
        }
        assertTrue(failure.message!!.contains("libshared_mem_helper.so"))
    }

    @Test fun `mac layout rejects missing nested framework`() {
        val root = bundle("mac", HostPlatform.MAC)
        File(root, "Frameworks/cef_server.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework").delete()
        assertThrows(IllegalStateException::class.java) { JcefRuntimeLayout.resolve(listOf(root), HostPlatform.MAC) }
    }

    @Test fun `linux policies are isolated valid JSON and replaced when disabling DoH`() {
        val profile = File(temporary, "private profile")
        val config = LinuxDoNetworkConfig(dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = "https://dns.example/query?tag=test{&dns}")
        val enabled = JcefDohPolicy.forProfile(HostPlatform.LINUX, profile, config)
        enabled.prepare()
        val policyFile = File(enabled.id, "managed/linuxdo-doh.json")
        val json = JsonParser.parseString(policyFile.readText()).asJsonObject
        assertEquals("secure", json["DnsOverHttpsMode"].asString)
        assertEquals(config.effectiveDohUrl, json["DnsOverHttpsTemplates"].asString)
        assertTrue(policyFile.canonicalPath.startsWith(profile.canonicalPath + File.separator))
        JcefDohPolicy.forProfile(HostPlatform.LINUX, profile,
            config.copy(dohProvider = Constants.DohProvider.DISABLED)).prepare()
        val disabled = JsonParser.parseString(policyFile.readText()).asJsonObject
        assertEquals("off", disabled["DnsOverHttpsMode"].asString)
        assertEquals("", disabled["DnsOverHttpsTemplates"].asString)
        assertEquals(listOf("linuxdo-doh.json"), policyFile.parentFile.list()!!.toList())
    }

    @Test fun `mac preferences have a profile-specific domain and never invoke a shell`() {
        val config = LinuxDoNetworkConfig(dohProvider = Constants.DohProvider.CUSTOM,
            customDohUrl = "https://dns.example/query?value=one&other=two{&dns}")
        val policy = JcefDohPolicy.forProfile(HostPlatform.MAC, File(temporary, "A B"), config)
        val commands = mutableListOf<List<String>>()
        policy.prepare { commands += it }
        assertTrue(policy.id.startsWith("com.lgguan.linuxdo.plugin.jcef."))
        assertNotEquals(policy.id, JcefDohPolicy.forProfile(HostPlatform.MAC, File(temporary, "B"), config).id)
        assertEquals(listOf("/usr/bin/defaults", "write", policy.id, "DnsOverHttpsTemplates", "-string", config.effectiveDohUrl), commands[1])
        assertThrows(IllegalStateException::class.java) { policy.prepare { error("write failed") } }
    }

    @Test fun `windows retains its existing policy key without loading the registry backend`() {
        val profile = File(temporary, "profile")
        val policy = JcefDohPolicy.forProfile(HostPlatform.WINDOWS, profile, LinuxDoNetworkConfig())
        assertEquals("Software\\LinuxDoJetBrainsPlugin\\Jcef\\" + Integer.toHexString(profile.absolutePath.hashCode()), policy.id)
    }

    @Test fun `submit only accepts the exact host modifier and ignores consumed events`() {
        fun key(modifiers: Int) = KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0, modifiers, KeyEvent.VK_ENTER, '\n')
        for (platform in HostPlatform.entries) {
            val primary = if (platform == HostPlatform.MAC) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
            assertTrue(PlatformShortcuts.isSubmit(key(primary), platform))
            assertFalse(PlatformShortcuts.isSubmit(key(0), platform))
            assertFalse(PlatformShortcuts.isSubmit(key(primary or InputEvent.ALT_DOWN_MASK), platform))
            assertFalse(PlatformShortcuts.isSubmit(key(if (platform == HostPlatform.MAC) InputEvent.CTRL_DOWN_MASK else InputEvent.META_DOWN_MASK), platform))
            assertFalse(PlatformShortcuts.isSubmit(key(primary).apply { consume() }, platform))
        }
    }

    @Test fun `browser identity follows defaults and explicitly overridden user agents`() {
        for (platform in HostPlatform.entries) {
            assertEquals(platform.displayName, BrowserIdentity.clientHintPlatform(BrowserIdentity.defaultUserAgent(platform)))
        }
        assertNull(BrowserIdentity.clientHintPlatform("custom-client/1.0"))
        assertEquals("Android", BrowserIdentity.clientHintPlatform("Mozilla/5.0 (Linux; Android 14)"))
    }

    @Test fun `screen coordinates follow CEF platform contracts at fractional and retina scales`() {
        val origin = Point(-200, 100)
        val position = Point(10, 20)
        val bounds = Rectangle(0, 0, 1920, 1080)
        assertEquals(Point(-237, 150), BrowserGeometry.screenPoint(HostPlatform.WINDOWS, origin, position, bounds, 1.25))
        assertEquals(Point(-380, 240), BrowserGeometry.screenPoint(HostPlatform.LINUX, origin, position, bounds, 2.0))
        assertEquals(Point(-190, 960), BrowserGeometry.screenPoint(HostPlatform.MAC, origin, position, bounds, 2.0))
        assertEquals(1.0, BrowserGeometry.scale(Double.NaN))
        assertEquals(1.0, BrowserGeometry.scale(0.0))
    }
}
