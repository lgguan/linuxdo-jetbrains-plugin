package com.lgguan.linuxdo.plugin

import com.intellij.util.xmlb.XmlSerializer
import com.lgguan.linuxdo.plugin.common.Constants
import com.lgguan.linuxdo.plugin.common.HostPlatform
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.net.JcefDohPolicy
import com.lgguan.linuxdo.plugin.net.LinuxDoNetworkConfig
import org.jdom.Element
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class ReleaseDefaultsTest {
    private val endpoint = "https://ldh.ddd.oaifree.com/query-dns"

    @Test fun `fresh and missing reading settings default to visible forum presentation`() {
        for (state in listOf(LinuxDoSettingsState(), XmlSerializer.deserialize(Element("state"), LinuxDoSettingsState::class.java))) {
            assertFalse(state.hideAvatars)
            assertFalse(state.foldImages)
            assertFalse(state.categoryNamespaceFormat)
        }
    }

    @Test fun `explicit saved reading choices survive loading and serialization`() {
        for (value in listOf(true, false)) {
            val xml = Element("state")
            for (name in listOf("hideAvatars", "foldImages", "categoryNamespaceFormat")) {
                xml.addContent(Element("option").setAttribute("name", name).setAttribute("value", value.toString()))
            }
            val target = LinuxDoSettingsState()
            target.loadState(XmlSerializer.deserialize(xml, LinuxDoSettingsState::class.java))
            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(target), LinuxDoSettingsState::class.java)
            for (state in listOf(target, restored)) {
                assertEquals(value, state.hideAvatars)
                assertEquals(value, state.foldImages)
                assertEquals(value, state.categoryNamespaceFormat)
            }
        }
    }

    @Test fun `fresh settings and all browser platforms use LinuxDo DoH`() {
        val config = LinuxDoSettingsState().toNetworkConfig()
        assertEquals(setOf("LINUXDO", "CUSTOM", "DISABLED"), Constants.DohProvider.values().map { it.name }.toSet())
        assertEquals(endpoint, config.effectiveDohUrl)
        assertEquals(endpoint, LinuxDoNetworkConfig().effectiveDohUrl)
        assertEquals("", config.effectiveBootstrapIp)
        assertTrue(config.strictDoh)
        for (platform in HostPlatform.values()) {
            val policy = JcefDohPolicy.forProfile(platform, File("build/test-profile"), config)
            assertEquals(endpoint, policy.values["DnsOverHttpsTemplates"])
            assertEquals("secure", policy.values["DnsOverHttpsMode"])
        }
    }

    @Test fun `removed providers load as LinuxDo without losing unrelated settings`() {
        for (provider in listOf("ALIDNS", "DNSPOD", "CLOUDFLARE", "GOOGLE")) {
            val xml = Element("state")
                .addContent(Element("option").setAttribute("name", "dohProvider").setAttribute("value", provider))
                .addContent(Element("option").setAttribute("name", "requestTimeoutSeconds").setAttribute("value", "27"))
            val restored = XmlSerializer.deserialize(xml, LinuxDoSettingsState::class.java)
            val target = LinuxDoSettingsState()
            target.loadState(restored)
            assertEquals(endpoint, target.toNetworkConfig().effectiveDohUrl, provider)
            assertEquals(27, target.requestTimeoutSeconds)
        }
    }

    @Test fun `provider choices and custom settings survive serialization`() {
        for (provider in Constants.DohProvider.values()) {
            val state = LinuxDoSettingsState().apply {
                dohProvider = provider
                customDohUrl = "https://resolver.example/dns-query"
                customBootstrapIp = "192.0.2.1"
            }
            val restored = XmlSerializer.deserialize(XmlSerializer.serialize(state), LinuxDoSettingsState::class.java)
            assertEquals(provider, restored.dohProvider)
            assertEquals(state.toNetworkConfig().effectiveDohUrl, restored.toNetworkConfig().effectiveDohUrl)
            assertEquals(state.customBootstrapIp, restored.customBootstrapIp)
            assertEquals(provider != Constants.DohProvider.DISABLED, restored.toNetworkConfig().isDohEnabled)
        }
    }
}
