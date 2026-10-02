package com.lgguan.linuxdo.plugin.theme

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Structural transformations after sanitization; generated controls contain fixed plugin code only. */
internal object ForumContent {
    val css: String by lazy { requireNotNull(javaClass.getResource("/web/forum-content.css")).readText() }
    val script: String by lazy { requireNotNull(javaClass.getResource("/web/forum-content.js")).readText() }

    fun render(html: String, foldImages: Boolean, prefix: String = "", postUrl: String? = null): String {
        val document = Jsoup.parseBodyFragment(ForumHtml.clean(html))
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        var counter = 0
        if (prefix.isNotBlank()) {
            val anchors = mutableMapOf<String, String>()
            var anchorCount = 0
            body.select("h1,h2,h3,h4,h5,h6,a[name]").forEach { element ->
                val attr = if (element.tagName() != "a" || element.hasAttr("id")) "id" else "name"
                val old = element.attr(attr)
                val scoped = "content-$prefix-${anchorCount++}"
                if (old.isNotBlank()) anchors[old] = scoped
                element.attr(attr, scoped)
            }
            body.select("a[href^=#]").forEach { link -> anchors[link.attr("href").removePrefix("#")]?.let { link.attr("href", "#$it") } }
        }
        body.select(".lightbox .meta, .lightbox-wrapper .meta").remove()
        body.select("table").toList().forEach { table ->
            if (!table.parent()!!.hasClass("forum-table")) table.wrap("<div class=\"forum-table\"></div>")
        }
        body.select("aside.quote[data-post]").forEach { quote ->
            val floor = quote.attr("data-post").toIntOrNull()?.takeIf { it > 0 } ?: return@forEach
            val topic = quote.attr("data-topic").toLongOrNull()?.takeIf { it > 0 }
            val title = quote.selectFirst(".title") ?: quote.prependElement("div").addClass("title")
            if (title.select(".quote-controls").isEmpty()) title.prependElement("span").addClass("quote-controls").text("↪ #$floor ")
            if (topic != null) title.select(".quote-controls").attr("title", "查看引用：话题 $topic，第 $floor 楼")
        }
        body.select(".poll").forEach { node ->
            if (postUrl == null) node.after(Element("p").addClass("forum-unsupported").text("投票预览；发布后的选项与权限由论坛提供。"))
        }
        // Do not fold avatars, emojis, badges, or a onebox's source icon.
        body.select("img").toList().forEach { image ->
            val src = image.attr("src")
            if (src.isBlank() || inline(image)) return@forEach
            image.attr("loading", "lazy").attr("decoding", "async")
            val width = image.attr("width").toIntOrNull()
            val height = image.attr("height").toIntOrNull()
            if (width != null && height != null && width in 1..20000 && height in 1..20000)
                image.attr("style", "aspect-ratio:$width/$height;max-width:100%;height:auto")
            val link = image.parent()?.takeIf { it.tagName() == "a" }
            val imageLink = link?.takeIf { it.hasClass("lightbox") || it.attr("href").matches(Regex("(?i).*(?:/uploads/|\\.(?:png|jpe?g|gif|webp|bmp|svg)(?:\\?.*)?$).*")) }
            val original = image.attr("data-orig-src").ifBlank { imageLink?.attr("href").orEmpty() }.ifBlank { src }
            image.attr("data-orig-src", original).attr("data-thumb-src", src)
            if (foldImages) {
                val id = "fold-img-${prefix.takeIf { it.isNotBlank() }?.let { "$it-" }.orEmpty()}${++counter}"
                val name = original.substringAfterLast('/').substringBefore('?').ifBlank { "image" }
                val alt = image.attr("alt")
                val caption = name + if (alt.isNotBlank() && alt.lowercase() !in setOf("image", "screenshot", name.lowercase(), name.substringBeforeLast('.').lowercase())) " - $alt" else ""
                val wrapper = Element("div").addClass("fold-img-box")
                wrapper.appendElement("span").addClass("img-placeholder").attr("id", "ph-$id")
                    .attr("onclick", "toggleImg('$id', event)")
                    .attr("data-open-text", "[📷 Figure: $caption (点击展开 / Expand)]")
                    .attr("data-close-text", "[📷 Figure: $caption (点击收起 / Collapse)]")
                    .text("[📷 Figure: $caption (点击展开 / Expand)]")
                image.attr("id", id)
                image.attr("onclick", "openLightbox(this.getAttribute('data-orig-src') || this.src, this.getAttribute('alt'), event, this.getAttribute('data-thumb-src') || this.src)")
                val target = imageLink ?: image
                val outer = target.parent()?.takeIf { it.hasClass("lightbox-wrapper") } ?: target
                if (outer !== image) image.remove()
                outer.replaceWith(wrapper)
                wrapper.appendChild(image)
            }
        }
        return body.html()
    }

    private fun inline(image: Element): Boolean {
        val classes = image.className().lowercase()
        return listOf("emoji", "avatar", "icon", "badge", "tag", "logo", "inline-img", "favicon").any(classes::contains) ||
            image.parents().any { it.hasClass("badge-category") || it.hasClass("discourse-tag") || it.hasClass("hashtag-cooked") } ||
            image.attr("src").contains("/user_avatar/") ||
            ((image.attr("width").toIntOrNull() ?: 999) <= 48 && (image.attr("height").toIntOrNull() ?: 999) <= 48)
    }
}
