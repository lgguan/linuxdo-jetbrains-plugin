package com.lgguan.linuxdo.plugin

import okio.AsyncTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class PluginRestartPolicyTest {
    @Test fun `plugin requires restart while cancelled timeouts retain an Okio watchdog`() {
        // Real socket and call timeouts use this same scheduler. Cancelling the
        // operation does not terminate its plugin-loaded Thread subclass.
        val timeout = AsyncTimeout().apply { timeout(1, TimeUnit.DAYS) }
        timeout.enter()
        assertFalse(timeout.exit())
        val watchdog = Thread.getAllStackTraces().keys.first {
            it.javaClass.name == "okio.AsyncTimeout\$Watchdog" &&
                it.javaClass.classLoader === AsyncTimeout::class.java.classLoader
        }
        watchdog.join(200)
        assertTrue(watchdog.isAlive, "Reassess restart policy if Okio gains immediate shutdown")

        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val descriptor = factory.newDocumentBuilder().parse(
            Path.of("src/main/resources/META-INF/plugin.xml").toFile()
        )
        assertEquals("true", descriptor.documentElement.getAttribute("require-restart"),
            "IDE must defer unloading until restart while a dependency retains its loader")
    }
}
