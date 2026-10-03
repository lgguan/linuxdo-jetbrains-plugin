package com.lgguan.linuxdo.plugin.model

import com.google.gson.JsonParser
import org.jsoup.Jsoup

internal data class SearchCapabilities(val tagging: Boolean = false, val solved: Boolean = false,
    val votes: Boolean = false, val experts: Boolean = false, val confirmed: Boolean = false) {
    companion object {
        fun parse(html: String): SearchCapabilities {
            val node = requireNotNull(Jsoup.parse(html).selectFirst("script#data-preloaded[type=application/json]"))
            val data = JsonParser.parseString(node.data()).asJsonObject
            val element = requireNotNull(data.get("siteSettings"))
            val settings = if (element.isJsonPrimitive) JsonParser.parseString(element.asString).asJsonObject else element.asJsonObject
            fun enabled(key: String) = settings.get(key)?.takeUnless { it.isJsonNull }?.asBoolean == true
            return SearchCapabilities(enabled("tagging_enabled"), enabled("solved_enabled"), enabled("topic_voting_enabled"),
                enabled("enable_category_experts") && enabled("show_category_expert_advanced_search_filters"), true)
        }
    }
}
