package com.lgguan.linuxdo.plugin.theme

/** Discourse sanitizes HTML demos in cooked; preserve their raw source as inert, escaped text. */
internal object ScrollableSourceBlocks {
    private val marker = Regex("""\bdata-theme-scrollable\s*=\s*(?:"true"|'true'|true(?=\s|>))""", RegexOption.IGNORE_CASE)
    private val tokens = Regex(
        """<!--[\s\S]*?-->|<script\b[^>]*>[\s\S]*?</script\s*>|<style\b[^>]*>[\s\S]*?</style\s*>|</?div\b(?:"[^"]*"|'[^']*'|[^'">])*>""",
        RegexOption.IGNORE_CASE
    )
    private data class Block(val start: Int, val end: Int, val source: String)

    private fun blocks(html: String): List<Block> {
        val found = mutableListOf<Block>()
        var start = -1
        var bodyStart = -1
        var depth = 0
        for (match in tokens.findAll(html)) {
            val tag = match.value
            if (!tag.startsWith("<div", true) && !tag.startsWith("</div", true)) continue
            if (tag.startsWith("</div", true)) {
                if (start >= 0 && --depth == 0) {
                    found += Block(start, match.range.last + 1, html.substring(bodyStart, match.range.first))
                    start = -1
                }
            } else if (start >= 0) {
                depth++
            } else if (marker.containsMatchIn(tag)) {
                start = match.range.first
                bodyStart = match.range.last + 1
                depth = 1
            }
        }
        return found
    }

    fun needsRaw(cooked: String): Boolean = blocks(cooked).isNotEmpty()

    fun render(cooked: String, raw: String?): String {
        val cookedBlocks = blocks(cooked)
        if (cookedBlocks.isEmpty()) return cooked
        val sources = raw?.let(::blocks).orEmpty()
        val result = StringBuilder()
        var offset = 0
        cookedBlocks.forEachIndexed { index, block ->
            result.append(cooked, offset, block.start)
            val source = sources.getOrNull(index)?.source
            result.append("""<section class="source-block"><div class="source-block-title">HTML 源码</div>""")
            if (source != null) {
                result.append("""<pre class="source-block-code" tabindex="0" aria-label="HTML 源码，可滚动"><code>""")
                    .append(TopicDocumentRenderer.escapeHtml(source.trim('\r', '\n')))
                    .append("</code></pre>")
            } else {
                result.append("""<p class="source-block-unavailable">完整源码暂时无法获取，请刷新帖子重试。</p>""")
            }
            result.append("</section>")
            offset = block.end
        }
        return result.append(cooked, offset, cooked.length).toString()
    }
}
