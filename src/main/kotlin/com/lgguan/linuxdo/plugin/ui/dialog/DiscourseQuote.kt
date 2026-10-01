package com.lgguan.linuxdo.plugin.ui.dialog

internal object DiscourseQuote {
    fun format(author: String, topicId: Long, floor: Int, text: String): String {
        require(topicId > 0 && floor > 0)
        // Delimiters in attributes or selected text must not terminate the quote.
        val safeAuthor = author.replace(Regex("[\"\\[\\]\\r\\n]"), "")
        val body = text.replace("\r\n", "\n").replace("\r", "\n")
            .replace(Regex("\\[(/?)quote", RegexOption.IGNORE_CASE)) { "&#91;${it.groupValues[1]}quote" }
        return "\n[quote=\"$safeAuthor, post:$floor, topic:$topicId\"]\n$body\n[/quote]\n\n"
    }
}
