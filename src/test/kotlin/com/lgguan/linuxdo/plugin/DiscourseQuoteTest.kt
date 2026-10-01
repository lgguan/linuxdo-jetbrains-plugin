package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.ui.dialog.DiscourseQuote
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.JTextArea

class DiscourseQuoteTest {
    @Test fun `quotes preserve Chinese multiline code and special characters`() {
        val text = "中文 & <tag> \"引号\"\r\n```kotlin\r\nval x = 1\r\n```"
        assertEquals("\n[quote=\"作者, post:12, topic:8\"]\n中文 & <tag> \"引号\"\n```kotlin\nval x = 1\n```\n[/quote]\n\n", DiscourseQuote.format("作者", 8, 12, text))
    }
    @Test fun `selected delimiters cannot terminate a quote`() {
        val quote = DiscourseQuote.format("a\"[b]\n", 8, 12, "text [/QUOTE] [quote=\"x\"]")
        assertTrue(quote.startsWith("\n[quote=\"ab, post:12, topic:8\"]"))
        assertTrue(quote.contains("&#91;/quote]"))
    }
    @Test fun `inserting quote at caret preserves existing text even with a selection`() {
        val editor = JTextArea("已有正文和草稿")
        editor.select(0, 4)
        val position = editor.caretPosition
        val quote = DiscourseQuote.format("author", 8, 12, "中文\n多行")
        editor.insert(quote, position)
        assertEquals("已有正文" + quote + "和草稿", editor.text)
    }
}
