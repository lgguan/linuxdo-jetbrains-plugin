package com.lgguan.linuxdo.plugin.model

import com.google.gson.JsonParser
import java.util.regex.Pattern

/** The same official emoji catalog is used by native validation and the Chromium counter. */
internal object BoostText {
    private fun resource(name: String) = JsonParser.parseString(requireNotNull(javaClass.getResource("/boost/$name.json")).readText())
    val names: Set<String> by lazy {
        resource("emojis").asJsonArray.map { it.asJsonObject.get("name").asString }.toSet() +
            resource("aliases").asJsonObject.entrySet().flatMap { (name, aliases) -> listOf(name) + aliases.asJsonArray.map { it.asString } }
    }
    val unicode: Map<String, String> by lazy {
        resource("emoji_to_name").asJsonObject.entrySet().associate { (symbol, name) -> symbol.replace("\uFE0F", "") to name.asString }
    }
    val toned: Set<String> by lazy { resource("tonable_emojis").asJsonArray.map { it.asString }.toSet() }
    private val shortcode = Regex(":[a-z0-9_+-]+(?::t\\d)?:")
    private val graphemes = Pattern.compile("\\X")
    data class Stats(val visible: Int, val emoji: Int)
    fun stats(raw: String, custom: Set<String> = emptySet(), denied: Set<String> = emptySet()): Stats {
        var emojis = 0
        val replaced = shortcode.replace(raw.trim()) { match ->
            val name = match.value.drop(1).dropLast(1).replace(Regex(":t\\d$"), "")
            if (name in names || name in custom) { emojis++; "\uFFFC" } else match.value
        }
        val matcher = graphemes.matcher(replaced)
        var visible = 0
        while (matcher.find()) {
            visible++
            val cluster = matcher.group().replace("\uFE0F", "")
            val untoned = String(cluster.codePoints().filter { it !in 0x1F3FB..0x1F3FF }.toArray(),0,
                cluster.codePoints().filter { it !in 0x1F3FB..0x1F3FF }.count().toInt())
            val name = (unicode[cluster] ?: unicode[untoned]?.takeIf { cluster != untoned && it in toned })?.replace(Regex(":t\\d$"), "")
            if (name != null && name !in denied) emojis++
        }
        return Stats(visible, emojis)
    }
    fun validate(raw: String, custom: Set<String> = emptySet(), denied: Set<String> = emptySet()) {
        require(raw.isNotBlank()) { "请输入 Boost 内容" }
        require(raw.codePointCount(0,raw.length) <= 1000) { "内容过长" }
        val stats = stats(raw,custom,denied)
        require(stats.visible <= 16) { "Boost 最多 16 个可见字符" }
        require(stats.emoji <= 5) { "Boost 最多 5 个表情" }
    }
}
