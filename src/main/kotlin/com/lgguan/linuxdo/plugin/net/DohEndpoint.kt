package com.lgguan.linuxdo.plugin.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The same RFC 8484 endpoint semantics for Chromium and the Java DNS client. */
internal class DohEndpoint private constructor(val template: String, private val variable: String?) {
    val useGet: Boolean get() = variable != null
    val url: HttpUrl = expand("AA")

    fun expand(dns: String): HttpUrl {
        val expanded = when (variable) {
            "{?dns}" -> template.replace(variable, "?dns=$dns")
            "{&dns}" -> template.replace(variable, "&dns=$dns")
            "{dns}" -> template.replace(variable, dns)
            else -> template
        }
        return requireNotNull(expanded.toHttpUrlOrNull()) { "DoH 地址必须是有效的 HTTPS URL" }
    }

    companion object {
        fun parse(value: String): DohEndpoint {
            val text = value.trim()
            require(text.isNotEmpty() && text.none { it.isWhitespace() || it == '\\' }) { "DoH 地址不能为空或包含空白字符" }
            val variables = Regex("\\{[^}]*}").findAll(text).map { it.value }.toList()
            require(variables.size <= 1 && variables.all { it in listOf("{dns}", "{?dns}", "{&dns}") }) {
                "DoH 仅支持单个 {dns}、{?dns} 或 {&dns} 模板变量"
            }
            val variable = variables.singleOrNull()
            val rest = variable?.let { text.replace(it, "") } ?: text
            require('{' !in rest && '}' !in rest) { "DoH 模板格式无效" }
            if (variable != null) {
                val before = text.substringBefore(variable)
                require(before.indexOf('/', before.indexOf("://") + 3) >= 0) { "DoH 模板变量必须位于路径或查询参数中" }
                require(variable != "{?dns}" || '?' !in before) { "已有查询参数时请使用 {&dns}" }
                require(variable != "{&dns}" || '?' in before) { "{&dns} 需要已有查询参数" }
            }
            val endpoint = DohEndpoint(text, variable)
            require(endpoint.url.isHttps && endpoint.url.username.isEmpty() && endpoint.url.password.isEmpty() && endpoint.url.fragment == null) {
                "DoH 地址必须使用 HTTPS，且不能包含用户凭据或片段"
            }
            return endpoint
        }
    }
}
