package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.theme.ForumHtml
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Markdown AST owns escaping and code boundaries. BBCode containers recurse into the same parser. */
internal object DiscourseMarkdown {
    private val extensions = listOf(TablesExtension.create(), AutolinkExtension.create(),
        StrikethroughExtension.create(), TaskListItemsExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()
    private val renderer = HtmlRenderer.builder().extensions(extensions).softbreak("<br>\n").sanitizeUrls(true).build()
    private val opening = Regex("^ {0,3}\\[(quote|details|spoiler|poll)(?:=(.*?))?](.*)$", RegexOption.IGNORE_CASE)
    private fun escape(value: String) = TopicDocumentRenderer.escapeHtml(value)

    fun render(source: String): String = ForumHtml.clean(blocks(source.replace("\r\n", "\n"), 0))

    private fun markdown(source: String): String {
        val node = parser.parse(mathMarkup(source))
        node.accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                if (image.destination.startsWith("upload://")) {
                    val file = image.destination.removePrefix("upload://")
                    if (file.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")))
                        image.destination = "https://linux.do/uploads/short-url/$file"
                }
                visitChildren(image)
            }
            override fun visit(text: Text) {
                val markers = Regex("\\[/?spoiler]", RegexOption.IGNORE_CASE).findAll(text.literal).toList()
                if (markers.isEmpty()) return
                var offset = 0
                markers.forEach { marker ->
                    if (marker.range.first > offset) text.insertBefore(Text(text.literal.substring(offset, marker.range.first)))
                    text.insertBefore(HtmlInline().apply { literal = if (marker.value.startsWith("[/")) "</span>" else "<span class=\"spoiler\">" })
                    offset = marker.range.last + 1
                }
                if (offset < text.literal.length) text.insertBefore(Text(text.literal.substring(offset)))
                text.unlink()
            }
        })
        return renderer.render(node)
    }

    /** Preserve TeX before Markdown consumes underscores and backslash delimiters. */
    private fun mathMarkup(source: String): String {
        val result=StringBuilder();var index=0;var codeChar:Char?=null;var codeLength=0
        while(index<source.length){
            val char=source[index]
            if(char=='`' || char=='~' && (index==0 || source[index-1]=='\n')){
                var end=index+1;while(end<source.length && source[end]==char)end++
                val size=end-index
                if(codeChar==null && (char=='`' || size>=3)){codeChar=char;codeLength=size}
                else if(codeChar==char && size>=codeLength)codeChar=null
                result.append(source.substring(index,end));index=end;continue
            }
            if(codeChar==null && !(index>=4 && source.substring(source.lastIndexOf('\n',index-1)+1,index).startsWith("    "))){
                val opening=when {source.startsWith("$$",index)->"$$";source.startsWith("\\[",index)->"\\[";source.startsWith("\\(",index)->"\\(";char=='$' && (index==0 || source[index-1]!='\\')->"$";else->null}
                if(opening!=null){
                    val closing=when(opening){"\\["->"\\]";"\\("->"\\)";else->opening}
                    val end=source.indexOf(closing,index+opening.length)
                    if(end>index+opening.length){
                        val tex=source.substring(index+opening.length,end)
                        val display=opening=="$$" || opening=="\\["
                        if(display || !tex.contains('\n') && tex.firstOrNull()?.isWhitespace()==false && tex.lastOrNull()?.isWhitespace()==false){
                            val tag=if(display)"div" else "span"
                            if(display)result.append('\n')
                            result.append("<$tag class=\"math\" data-math-source=\"${escape(tex)}\">${escape(tex)}</$tag>")
                            if(display)result.append('\n')
                            index=end+closing.length;continue
                        }
                    }
                }
            }
            result.append(char);index++
        }
        return result.toString()
    }

    private fun blocks(source: String, depth: Int): String {
        if (depth > 24) return "<pre>${escape(source)}</pre>"
        val lines = source.split('\n')
        val result = StringBuilder()
        val pending = StringBuilder()
        var fence: Char? = null
        var fenceLength = 0
        var index = 0
        fun flush() { if (pending.isNotEmpty()) { result.append(markdown(pending.toString())); pending.clear() } }
        while (index < lines.size) {
            val line = lines[index]
            val marker = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(line)
            if (marker != null) {
                val run = marker.groupValues[1]
                if (fence == null) { fence = run[0]; fenceLength = run.length }
                else if (run[0] == fence && run.length >= fenceLength && marker.groupValues[2].isBlank()) fence = null
                pending.append(line).append('\n'); index++; continue
            }
            val start = if (fence == null) opening.matchEntire(line) else null
            if (start == null) { pending.append(line).append('\n'); index++; continue }
            val tag = start.groupValues[1].lowercase()
            val closing = Regex("\\[/$tag]", RegexOption.IGNORE_CASE)
            var count = 1
            var end = index
            var innerFence: Char? = null
            var innerLength = 0
            var closeMatch: MatchResult? = null
            while (end < lines.size) {
                val candidate = if (end == index) start.groupValues[3] else lines[end]
                val f = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").find(candidate)
                if (f != null) {
                    val run = f.groupValues[1]
                    if (innerFence == null) { innerFence = run[0]; innerLength = run.length }
                    else if (innerFence == run[0] && run.length >= innerLength && f.groupValues[2].isBlank()) innerFence = null
                }
                if (innerFence == null && f == null) {
                    if (end != index && opening.matchEntire(candidate)?.groupValues?.get(1)?.equals(tag, true) == true) count++
                    closing.findAll(candidate).forEach { match -> if (--count == 0) closeMatch = match }
                    if (closeMatch != null) break
                }
                end++
            }
            if (closeMatch == null) { pending.append(line).append('\n'); index++; continue }
            flush()
            val inside = if (end == index) start.groupValues[3].substring(0, closeMatch!!.range.first) else
                (listOf(start.groupValues[3]) + lines.subList(index + 1, end) + lines[end].substring(0, closeMatch!!.range.first)).joinToString("\n")
            val content = blocks(inside, depth + 1)
            val arg = start.groupValues[2].trim('"', '\'')
            result.append(when (tag) {
                "quote" -> {
                    val post = Regex("(?:^|,\\s*)post:(\\d+)").find(arg)?.groupValues?.get(1)
                    val topic = Regex("(?:^|,\\s*)topic:(\\d+)").find(arg)?.groupValues?.get(1)
                    val attrs = (post?.let { " data-post=\"$it\"" }.orEmpty() + topic?.let { " data-topic=\"$it\"" }.orEmpty())
                    "<aside class=\"quote\"$attrs><div class=\"title\">${escape(arg.substringBefore(','))}</div><blockquote>$content</blockquote></aside>"
                }
                "details" -> "<details><summary>${escape(arg.ifBlank { "点击展开" })}</summary>$content</details>"
                "spoiler" -> "<div class=\"spoiler\">$content</div>"
                else -> "<div class=\"forum-unsupported\">投票请在网页查看</div><pre>${escape(lines.subList(index, end + 1).joinToString("\n"))}</pre>"
            })
            val tail = (if (end == index) start.groupValues[3] else lines[end]).substring(closeMatch!!.range.last + 1)
            if (tail.isNotBlank()) pending.append(tail).append('\n')
            index = end + 1
        }
        flush()
        return result.toString()
    }
}
