package com.lgguan.linuxdo.plugin.ui.dialog

import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** Pure transformations. Offsets are Swing/Java UTF-16 offsets; untouched text is never normalized. */
internal object MarkdownEditing {
    data class Snapshot(val source: String, val mark: Int, val dot: Int) {
        val start get() = minOf(mark, dot)
        val end get() = maxOf(mark, dot)
    }
    data class Edit(val start: Int, val end: Int, val replacement: String, val mark: Int, val dot: Int)
    data class LinkTarget(val start: Int, val end: Int, val label: String, val url: String)
    data class Fence(val start: Int, val end: Int, val bodyStart: Int, val bodyEnd: Int, val language: String, val marker: String, val prefix: String)
    private data class Line(val start: Int, val end: Int, val next: Int, val text: String)
    private data class Item(val outer: String, val indent: String, val marker: String, val task: String, val body: String)
    private val parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()
    private val itemPattern = Regex("^((?: {0,3}>[ \\t]?)*)([ \\t]*)([-+*]|[0-9]{1,9}[.)])[ \\t]+(\\[[ xX]](?:[ \\t]+|$))?(.*)$")
    private val fencePattern = Regex("^((?: {0,3}>[ \\t]?)* {0,3})(`{3,}|~{3,})(.*)$")
    private fun item(line: String): Item? = itemPattern.matchEntire(line)?.let {
        Item(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4], it.groupValues[5])
    }
    private fun lines(source: String): List<Line> {
        val result = mutableListOf<Line>(); var start = 0
        while (start <= source.length) {
            val lf = source.indexOf('\n', start)
            val next = if (lf < 0) source.length else lf + 1
            val end = if (lf < 0) source.length else if (lf > start && source[lf - 1] == '\r') lf - 1 else lf
            result += Line(start, end, next, source.substring(start, end))
            if (lf < 0) break
            start = next
        }
        return result
    }
    private fun selected(s: Snapshot) = lines(s.source).filter {
        it.start <= s.end && it.end >= s.start && (s.start == s.end || it.start < s.end)
    }
    private fun newline(s: Snapshot) = if (s.source.contains("\r\n")) "\r\n" else "\n"
    private fun result(s: Snapshot, start: Int, end: Int, value: String, a: Int = start, b: Int = start + value.length): Edit? =
        if (s.source.substring(start, end) == value) null else
            Edit(start, end, value, if (s.mark > s.dot) b else a, if (s.mark > s.dot) a else b)

    private fun fenceRegions(source: String): List<Pair<Fence, Boolean>> {
        val rows = lines(source); val result = mutableListOf<Pair<Fence, Boolean>>()
        var opening: Line? = null; var marker = ""; var language = ""; var prefix = ""
        for (row in rows) {
            val match = fencePattern.matchEntire(row.text) ?: continue
            val run = match.groupValues[2]; val tail = match.groupValues[3]
            if (opening == null) {
                if (run[0] == '`' && tail.contains('`')) continue
                opening = row; marker = run; language = tail.trim(); prefix = match.groupValues[1]
            } else if (run[0] == marker[0] && run.length >= marker.length && tail.isBlank() &&
                match.groupValues[1].count { it == '>' } == prefix.count { it == '>' }) {
                result += Fence(opening.start, row.end, opening.next, row.start, language, marker, prefix) to true
                opening = null
            }
        }
        opening?.let { result += Fence(it.start, source.length, it.next, source.length, language, marker, prefix) to false }
        return result
    }
    fun fence(s: Snapshot): Fence? = fenceRegions(s.source).firstOrNull {
        it.second && s.start >= it.first.start && s.end <= it.first.end
    }?.first
    private fun protected(s: Snapshot): Boolean {
        if (fenceRegions(s.source).any { s.start <= it.first.end && s.end >= it.first.start }) return true
        // The parser can identify code nested in list containers that our editable fence scanner
        // deliberately does not rewrite. Protect it even though we cannot offer fence modification.
        val rows = lines(s.source)
        var blocked = false
        fun check(node: Node) {
            val first = node.sourceSpans.firstOrNull() ?: return
            val last = node.sourceSpans.last()
            val start = rows[first.lineIndex].start + first.columnIndex
            val end = rows[last.lineIndex].start + last.columnIndex + last.length
            if (s.start <= end && s.end >= start) blocked = true
        }
        parser.parse(s.source).accept(object : AbstractVisitor() {
            override fun visit(node: FencedCodeBlock) = check(node)
        })
        return blocked
    }
    fun codeContext(s: Snapshot) = protected(s)

    private fun span(node: Node, rows: List<Line>): IntRange? {
        val spans = node.sourceSpans
        if (spans.size != 1) return null // Multiline inline syntax is deliberately left untouched.
        val value = spans.single()
        val row = rows.getOrNull(value.lineIndex) ?: return null
        val start = row.start + value.columnIndex
        return start until start + value.length
    }
    private fun nodes(s: Snapshot, accept: (Node) -> Boolean): List<Pair<Node, IntRange>> {
        val result = mutableListOf<Pair<Node, IntRange>>()
        val rows = lines(s.source)
        fun walk(node: Node) {
            if (accept(node)) span(node, rows)?.let { range ->
                if (s.start >= range.first && s.end <= range.last + 1) result += node to range
            }
            var child = node.firstChild
            while (child != null) { walk(child); child = child.next }
        }
        walk(parser.parse(s.source)); return result.sortedBy { it.second.last - it.second.first }
    }
    fun inline(s: Snapshot, marker: String, placeholder: String): Edit? {
        if (protected(s)) return null
        val existing = nodes(s) { when (marker) { "**" -> it is StrongEmphasis; "*" -> it is Emphasis; else -> it is Code } }.firstOrNull { (_, range) ->
            val raw = s.source.substring(range.first, range.last + 1)
            val width = if (marker == "`") raw.takeWhile { it == '`' }.length else marker.length
            s.start == s.end || (s.start == range.first && s.end == range.last + 1) ||
                (s.start == range.first + width && s.end == range.last + 1 - width)
        }
        if (existing != null) {
            val range = existing.second; val raw = s.source.substring(range.first, range.last + 1)
            val actual = if (marker == "`") raw.takeWhile { it == '`' } else
                if (marker == "**") raw.take(2) else raw.take(1)
            if (actual.isNotEmpty() && raw.endsWith(actual)) {
                val body = raw.substring(actual.length, raw.length - actual.length)
                val a = (s.mark - actual.length).coerceIn(range.first, range.first + body.length)
                val b = (s.dot - actual.length).coerceIn(range.first, range.first + body.length)
                return Edit(range.first, range.last + 1, body, a, b)
            }
        }
        val body = s.source.substring(s.start, s.end).ifEmpty { placeholder }
        val actual = if (marker == "`") "`".repeat(maxOf(1, Regex("`+").findAll(body).maxOfOrNull { it.value.length + 1 } ?: 1)) else marker
        val pad = if (marker == "`" && (body.startsWith('`') || body.endsWith('`'))) " " else ""
        return result(s, s.start, s.end, actual + pad + body + pad + actual,
            s.start + actual.length + pad.length, s.start + actual.length + pad.length + body.length)
    }
    fun block(s: Snapshot, prefix: String): Edit? {
        val rows = selected(s); if (rows.isEmpty() || protected(Snapshot(s.source, rows.first().start, rows.last().end))) return null
        fun parts(row: String): Pair<String, String> {
            val outer = Regex("^(?: {0,3}>[ \\t]?)*[ \\t]*").find(row)!!.value
            return outer to row.substring(outer.length)
        }
        val targetItem = item(prefix + "x")
        val remove = rows.all { row ->
            if (prefix == "> ") row.text.trimStart().startsWith(">")
            else if (targetItem != null) item(row.text)?.let {
                it.marker.first().isDigit() == targetItem.marker.first().isDigit() &&
                    it.task.isNotEmpty() == targetItem.task.isNotEmpty()
            } == true else parts(row.text).second.startsWith(prefix)
        }
        val body = rows.joinToString("") { row ->
            val transformed = if (prefix == "> ") {
                if (remove) row.text.replaceFirst(Regex("^([ \\t]*)>[ \\t]?"), "$1") else "> ${row.text}"
            } else {
                val (outer, content) = parts(row.text)
                val old = if (prefix.startsWith('#')) Regex("^#{1,6}[ \\t]+").find(content)?.value else
                    Regex("^(?:[-+*]|[0-9]{1,9}[.)])[ \\t]+(?:\\[[ xX]][ \\t]+)?").find(content)?.value
                val existingTask = item(row.text)?.task.orEmpty()
                val nextPrefix = if (prefix == "- [ ] " && existingTask.isNotEmpty()) "- $existingTask" else prefix
                outer + if (remove) content.removePrefix(old ?: prefix) else nextPrefix + content.removePrefix(old.orEmpty())
            }
            transformed + s.source.substring(row.end, row.next.coerceAtMost(rows.last().end))
        }
        return result(s, rows.first().start, rows.last().end, body)
    }
    fun enter(s: Snapshot): Edit? {
        if (s.start != s.end || protected(s)) return null
        val row = selected(s).singleOrNull() ?: return null
        if (s.dot != row.end) return null
        val value = item(row.text) ?: return null
        if (value.body.isBlank()) {
            val keep = value.outer + value.indent
            return result(s, row.start, row.end, keep, row.start + keep.length, row.start + keep.length)
        }
        val marker = if (value.marker.first().isDigit()) {
            val number = value.marker.dropLast(1).toLong() + 1
            if (number > 999999999) return null
            "$number${value.marker.last()}"
        } else value.marker
        val addition = newline(s) + value.outer + value.indent + marker + " " + if (value.task.isNotEmpty()) "[ ] " else ""
        return result(s, s.dot, s.dot, addition, s.dot + addition.length, s.dot + addition.length)
    }
    fun indent(s: Snapshot, outdent: Boolean): Edit? {
        val rows = selected(s); if (rows.isEmpty()) return null
        val regions = fenceRegions(s.source)
        val code = regions.any {
            it.second && rows.first().start >= it.first.bodyStart && rows.last().end < it.first.bodyEnd
        }
        if (!code && (protected(s) || !rows.all { item(it.text) != null })) return null
        val deltas = mutableListOf<Pair<Int, Int>>()
        val body = rows.joinToString("") { row ->
            val prefixLength = if (code) Regex("^(?: {0,3}>[ \\t]?)*").find(row.text)!!.value.length else item(row.text)!!.outer.length
            val rest = row.text.substring(prefixLength)
            val count = if (!outdent) 0 else if (rest.startsWith('\t')) 1 else rest.takeWhile { it == ' ' }.length.coerceAtMost(2)
            val delta = if (outdent) -count else 2
            deltas += (row.start + prefixLength) to delta
            row.text.take(prefixLength) + (if (outdent) rest.drop(count) else "  $rest") +
                s.source.substring(row.end, row.next.coerceAtMost(rows.last().end))
        }
        fun moved(pos: Int): Int {
            var moved = pos
            for ((at, delta) in deltas) if (pos >= at) moved += if (delta < 0) -minOf(-delta, pos - at) else delta
            return moved
        }
        return if (s.source.substring(rows.first().start, rows.last().end) == body) null else
            Edit(rows.first().start, rows.last().end, body, moved(s.mark), moved(s.dot))
    }
    fun link(s: Snapshot): LinkTarget? = nodes(s) { it is Link }.firstNotNullOfOrNull { (node, range) ->
        val raw = s.source.substring(range.first, range.last + 1)
        // References and autolinks are not rewritten. A preceding ! belongs to an image.
        if (!raw.startsWith('[') || !raw.endsWith(')') || !raw.contains("](") ||
            (range.first > 0 && s.source[range.first - 1] == '!')) null else {
            val link = node as Link
            val label = link.firstChild as? Text
            // Preserve titles and formatted labels rather than silently flattening them.
            if (link.title != null || label == null || label.next != null) null
            else LinkTarget(range.first, range.last + 1, label.literal, link.destination)
        }
    }
    fun replaceLink(s: Snapshot, label: String, url: String): Edit? {
        if (protected(s)) return null
        val target = link(s)
        if (target == null && nodes(s) { it is Link || it is Image || it is Code }.isNotEmpty()) return null
        val start = target?.start ?: s.start; val end = target?.end ?: s.end
        val escaped = label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\r", " ").replace("\n", " ")
        val destination = url.replace("<", "%3C").replace(">", "%3E").replace(" ", "%20").replace("\"", "%22").replace("\\", "%5C")
        return result(s, start, end, "[$escaped](<$destination>)", start + 1, start + 1 + escaped.length)
    }
    fun codeBlock(s: Snapshot, language: String, remove: Boolean = false): Edit? {
        require(remove || language.isEmpty() || language.matches(Regex("[A-Za-z0-9_+.\\-]{1,64}")))
        val old = fence(s)
        if (old != null) {
            val content = s.source.substring(old.bodyStart, old.bodyEnd)
            if (remove) return result(s, old.start, old.end, content)
            val firstEnd = s.source.indexOf('\n', old.start).let { if (s.source.getOrNull(it - 1) == '\r') it - 1 else it }
            val value = old.prefix + old.marker + language
            val delta = value.length - (firstEnd - old.start)
            fun moved(pos: Int) = if (pos >= firstEnd) pos + delta else pos.coerceAtMost(old.start + value.length)
            return if (s.source.substring(old.start, firstEnd) == value) null else
                Edit(old.start, firstEnd, value, moved(s.mark), moved(s.dot))
        }
        if (protected(s)) return null
        val body = s.source.substring(s.start, s.end).ifEmpty { "代码" }
        val marker = "`".repeat(maxOf(3, Regex("`+").findAll(body).maxOfOrNull { it.value.length + 1 } ?: 3))
        val nl = newline(s)
        val lead = if (s.start > 0 && s.source[s.start - 1] != '\n') nl else ""
        val tail = if (s.end < s.source.length && s.source[s.end] != '\r' && s.source[s.end] != '\n') nl else ""
        val prefix = lead + marker + language + nl
        return result(s, s.start, s.end, prefix + body + (if (body.endsWith('\n')) "" else nl) + marker + tail,
            s.start + prefix.length, s.start + prefix.length + body.length)
    }
}
