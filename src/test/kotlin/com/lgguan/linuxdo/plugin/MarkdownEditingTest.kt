package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.ui.dialog.MarkdownEditing as M
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarkdownEditingTest {
    private fun apply(s: M.Snapshot, edit: M.Edit?): M.Snapshot {
        if (edit == null) return s
        return M.Snapshot(s.source.replaceRange(edit.start, edit.end, edit.replacement), edit.mark, edit.dot)
    }
    private fun caret(text: String) = M.Snapshot(text, text.length, text.length)
    @ParameterizedTest @ValueSource(strings = ["**", "*", "`"])
    fun `format wraps and removes complete markers preserving selection`(marker: String) {
        val original = "中文😀é".let { M.Snapshot(it, 0, it.length) }
        val added = apply(original, M.inline(original, marker, "占位"))
        assertEquals(marker + original.source + marker, added.source)
        assertEquals(original.source, added.source.substring(added.start, added.end))
        val removed = apply(added, M.inline(added, marker, "占位"))
        assertEquals(original, removed)
    }
    @Test fun `reverse selection direction survives formatting`() {
        val original = M.Snapshot("alpha", 5, 0)
        val added = apply(original, M.inline(original, "**", "x"))
        assertEquals(7, added.mark); assertEquals(2, added.dot)
        assertEquals(original, apply(added, M.inline(added, "**", "x")))
    }
    @Test fun `empty selection creates selected placeholder`() {
        val result = apply(caret(""), M.inline(caret(""), "**", "粗体"))
        assertEquals("**粗体**", result.source); assertEquals("粗体", result.source.substring(result.start, result.end))
    }
    @Test fun `caret inside existing strong removes that layer`() {
        val s = M.Snapshot("a **hello** z", 6, 6)
        val result = apply(s, M.inline(s, "**", "x"))
        assertEquals("a hello z", result.source); assertEquals(4, result.dot)
    }
    @Test fun `nested italic removal preserves outer strong`() {
        val s = M.Snapshot("**a *b* c**", 5, 6)
        assertEquals("**a b c**", apply(s, M.inline(s, "*", "x")).source)
    }
    @Test fun `line selection ending at next start excludes next line`() {
        val s = M.Snapshot("one\ntwo", 0, 4)
        assertEquals("- one\ntwo", apply(s, M.block(s, "- ")).source)
    }
    @Test fun `CRLF remains unchanged outside transformed lines`() {
        val s = M.Snapshot("one\r\ntwo\r\nlast", 0, 8)
        assertEquals("> one\r\n> two\r\nlast", apply(s, M.block(s, "> ")).source)
    }
    @ParameterizedTest @ValueSource(strings = ["# ", "## ", "### ", "#### ", "##### ", "###### ", "> ", "- ", "1. ", "- [ ] "])
    fun `block toggles entire selected lines`(prefix: String) {
        val s = M.Snapshot("a\nb", 0, 3)
        val added = apply(s, M.block(s, prefix))
        assertEquals(prefix + "a\n" + prefix + "b", added.source)
        assertEquals("a\nb", apply(added, M.block(added, prefix)).source)
    }
    @Test fun `mixed task conversion preserves completed tasks and indentation`() {
        val s = M.Snapshot("  - [x] done\n  3. next", 0, 21)
        assertEquals("  - [x] done\n  - [ ] next", apply(s, M.block(s, "- [ ] ")).source)
    }
    @Test fun `numbered list toggle recognizes all numbers`() {
        val s = M.Snapshot("2. a\n3) b", 0, 9)
        assertEquals("a\nb", apply(s, M.block(s, "1. ")).source)
    }
    @ParameterizedTest @ValueSource(strings = ["- text", "+ text", "9) text", ">   - [x] text"])
    fun `list continuation preserves context`(input: String) {
        val continuation = when (input) {
            "9) text" -> "10) "
            ">   - [x] text" -> ">   - [ ] "
            else -> input.take(2)
        }
        val s = caret(input); val result = apply(s, M.enter(s))
        assertEquals(input + "\n" + continuation, result.source); assertEquals(result.source.length, result.dot)
    }
    @ParameterizedTest @ValueSource(strings = ["- ", "1. ", "- [ ] "])
    fun `empty item exits without extra newline`(input: String) {
        val s = caret(input); assertEquals("", apply(s, M.enter(s)).source)
    }
    @Test fun `empty quoted item retains quote and indent`() {
        val s = caret(">   - "); assertEquals(">   ", apply(s, M.enter(s)).source)
    }
    @Test fun `enter only intercepts unselected list end`() {
        assertNull(M.enter(M.Snapshot("- text", 3, 3)))
        assertNull(M.enter(M.Snapshot("- text", 0, 6)))
        assertNull(M.enter(caret("plain")))
        assertNull(M.enter(caret("999999999. text")))
    }
    @Test fun `CRLF continuation uses CRLF`() {
        val s = caret("first\r\n- last"); assertEquals("first\r\n- last\r\n- ", apply(s, M.enter(s)).source)
    }
    @Test fun `indent and outdent preserve reverse caret positions`() {
        val s = M.Snapshot("> - first\n> - second", 20, 2)
        val added = apply(s, M.indent(s, false))
        assertEquals(">   - first\n>   - second", added.source)
        assertEquals(s, apply(added, M.indent(added, true)))
    }
    @Test fun `outdent handles tabs without changing body`() {
        val s = caret("\t- text"); assertEquals("- text", apply(s, M.indent(s, true)).source)
        assertNull(M.indent(caret("- text"), true))
        assertNull(M.indent(caret("plain"), false))
        assertNull(M.indent(M.Snapshot("- list\nplain", 0, 12), false))
    }
    @Test fun `fence context protects code but allows body indentation`() {
        val s = M.Snapshot("```kotlin\n- code\n```", 10, 16)
        assertNull(M.block(s, "- ")); assertNull(M.inline(s, "**", "x")); assertNull(M.enter(M.Snapshot(s.source, 16, 16)))
        assertEquals("```kotlin\n  - code\n```", apply(s, M.indent(s, false)).source)
        assertNull(M.indent(M.Snapshot(s.source, 0, s.source.length), false))
    }
    @Test fun `unclosed code stays untouched`() {
        val s = caret("```\n- code")
        assertNull(M.block(s, "- ")); assertNull(M.enter(s)); assertNull(M.codeBlock(s, "kotlin"))
    }
    @Test fun `code nested inside list containers stays protected`() {
        val s = M.Snapshot("- ```\n  - code\n  ```", 10, 10)
        assertNull(M.inline(s, "**", "x")); assertNull(M.block(s, "- "))
        assertNull(M.codeBlock(s, "kotlin")); assertNull(M.indent(s, false))
    }
    @Test fun `quoted fences protect content and retain quote when language changes`() {
        val s = M.Snapshot("> ```\n> - code\n> ```", 8, 14)
        assertNull(M.block(s, "> ")); assertNull(M.enter(M.Snapshot(s.source, 14, 14)))
        assertEquals("> ```kotlin\n> - code\n> ```", apply(s, M.codeBlock(s, "kotlin")).source)
        assertEquals("> ```\n>   - code\n> ```", apply(s, M.indent(s, false)).source)
    }
    @Test fun `partial selection does not strip formatting from other words`() {
        val s = M.Snapshot("**hello world**", 2, 7)
        assertEquals("****hello** world**", apply(s, M.inline(s, "**", "x")).source)
    }
    @Test fun `code block chooses safe fence and keeps Unicode body selected`() {
        val s = "中文```\n😀".let { M.Snapshot(it, 0, it.length) }
        val added = apply(s, M.codeBlock(s, "kotlin"))
        assertEquals("````kotlin\n${s.source}\n````", added.source)
        assertEquals(s.source, added.source.substring(added.start, added.end))
        assertEquals(s.source + "\n", apply(added, M.codeBlock(added, "", true)).source)
    }
    @Test fun `language change preserves body and shifts caret`() {
        val s = M.Snapshot("```\ncode\n```", 6, 6)
        val changed = apply(s, M.codeBlock(s, "c++"))
        assertEquals("```c++\ncode\n```", changed.source); assertEquals(9, changed.dot)
        assertNull(M.codeBlock(changed, "c++"))
        val backwards = M.Snapshot(s.source, 8, 4)
        val updated = apply(backwards, M.codeBlock(backwards, "c++"))
        assertEquals(11, updated.mark); assertEquals(7, updated.dot)
    }
    @ParameterizedTest @ValueSource(strings = ["bad language", "x\n", "<>code"])
    fun `invalid languages are rejected`(language: String) { assertThrows(IllegalArgumentException::class.java) { M.codeBlock(caret(""), language) } }
    @Test fun `inline link can be edited at caret without nesting`() {
        val s = M.Snapshot("a [文字](https://example.com/a(b)) z", 5, 5)
        assertEquals("https://example.com/a(b)", M.link(s)?.url)
        assertEquals("a [新文字](<https://example.com/a%20b>) z", apply(s, M.replaceLink(s, "新文字", "https://example.com/a b")).source)
    }
    @Test fun `link escaping preserves existing URL percent encoding`() {
        val s = caret(""); val result = apply(s, M.replaceLink(s, "[文字]", "https://example.com/a%20b?q=(x)"))
        assertEquals("[\\[文字\\]](<https://example.com/a%20b?q=(x)>)", result.source)
        assertEquals("[文字]", M.link(M.Snapshot(result.source, 4, 4))?.label)
    }
    @ParameterizedTest @ValueSource(strings = ["[**label**](https://example.com)", "[label](https://example.com \"title\")"])
    fun `complex links are preserved`(source: String) {
        val s = M.Snapshot(source, 3, 3)
        assertNull(M.link(s)); assertNull(M.replaceLink(s, "new", "https://example.com"))
    }
    @ParameterizedTest @ValueSource(strings = ["![image](https://example.com)", "[ref][id]\n\n[id]: https://example.com", "`[code](https://example.com)`"])
    fun `images references and inline code are not rewritten`(source: String) {
        val s = M.Snapshot(source, 3, 3)
        assertNull(M.link(s)); assertNull(M.replaceLink(s, "new", "https://example.com"))
    }
}
