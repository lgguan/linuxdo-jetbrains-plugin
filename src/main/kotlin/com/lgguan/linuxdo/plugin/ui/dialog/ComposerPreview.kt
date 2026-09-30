package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.common.LinuxDoImageCache
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter

/** Shared preview for topic and reply composers. */
internal object ComposerPreview {
    fun render(bodyText: String, onImageReady: () -> Unit): String {
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val bodyHtml = if (bodyText.isNotBlank()) {
            markdownToHtml(bodyText, onImageReady)
        } else {
            "<p style='color:${theme.commentHex}; font-style:italic;'>正文预览将显示在此处...</p>"
        }

        return """
            <html>
            <head>
                <style>
                    body {
                        font-family: '${theme.fontName}', -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                        font-size: 13px;
                        background-color: ${theme.bgHex};
                        color: ${theme.fgHex};
                        line-height: 1.5;
                        margin: 12px;
                    }
                    h1 { color: ${theme.fgHex}; font-size: 18px; margin: 12px 0 6px 0; font-weight: bold; }
                    h2 { color: ${theme.fgHex}; font-size: 16px; margin: 10px 0 4px 0; font-weight: bold; }
                    h3 { color: ${theme.fgHex}; font-size: 14px; margin: 8px 0 2px 0; font-weight: bold; }
                    p, div, li, span { color: ${theme.fgHex}; font-size: 13px; }
                    strong, b { color: ${theme.fgHex}; font-weight: bold; }
                    em, i { color: ${theme.fgHex}; font-style: italic; }
                    blockquote {
                        border-left: 3px solid #0969DA;
                        margin: 6px 0;
                        padding-left: 10px;
                        color: ${theme.commentHex};
                    }
                    pre {
                        background-color: ${theme.codeBlockBgHex};
                        color: ${theme.fgHex};
                        border: 1px solid ${theme.borderHex};
                        padding: 6px 10px;
                        font-family: Consolas, monospace;
                        font-size: 12px;
                    }
                    code {
                        background-color: ${theme.codeBlockBgHex};
                        color: ${theme.keywordHex};
                        font-family: Consolas, monospace;
                        font-size: 12px;
                    }
                    img { max-width: 100%; height: auto; }
                    a { color: #58A6FF; text-decoration: none; }
                    fieldset {
                        border: 1px dashed ${theme.borderHex};
                        padding: 6px;
                        margin: 6px 0;
                        color: ${theme.fgHex};
                    }
                    legend { font-weight: bold; padding: 0 4px; color: ${theme.keywordHex}; }
                </style>
            </head>
            <body text="${theme.fgHex}" bgcolor="${theme.bgHex}">
                $bodyHtml
            </body>
            </html>
        """.trimIndent()
    }

    internal fun markdownToHtml(md: String, onImageReady: () -> Unit = {}): String {
        var html = escapeHtml(md)
        html = html.replace(Regex("""```(?:\w+)?\n([\s\S]*?)\n```""")) { m ->
            "<pre><code>${m.groupValues[1]}</code></pre>"
        }
        html = html.replace(Regex("""`([^`]+)`""")) { m ->
            "<code>${m.groupValues[1]}</code>"
        }
        html = html.replace(Regex("""(?m)^###\s+(.*)$""")) { "<h3>${it.groupValues[1]}</h3>" }
        html = html.replace(Regex("""(?m)^##\s+(.*)$""")) { "<h2>${it.groupValues[1]}</h2>" }
        html = html.replace(Regex("""(?m)^#\s+(.*)$""")) { "<h1>${it.groupValues[1]}</h1>" }
        html = html.replace(Regex("""\*\*\*([^*]+)\*\*\*""")) { "<strong><em>${it.groupValues[1]}</em></strong>" }
        html = html.replace(Regex("""\*\*([^*]+)\*\*""")) { "<strong>${it.groupValues[1]}</strong>" }
        html = html.replace(Regex("""\*([^*]+)\*""")) { "<em>${it.groupValues[1]}</em>" }
        html = html.replace(Regex("""!\[(.*?)\]\((.*?)\)""")) {
            val alt = it.groupValues[1]
            val rawUrl = org.jsoup.parser.Parser.unescapeEntities(it.groupValues[2].trim(), true)
            val resolvedSrc = LinuxDoImageCache.resolve(rawUrl)
            if (resolvedSrc != null) {
                val dimensionAttr = LinuxDoImageCache.getImageDimensionAttr(resolvedSrc, maxWidth = 420)
                "<p><img src='${escapeHtml(resolvedSrc)}' alt='$alt'$dimensionAttr/></p>"
            } else {
                LinuxDoImageCache.asyncFetchIfMissing(rawUrl) {
                    onImageReady()
                }
                val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
                "<div style='margin: 6px 0; padding: 6px 10px; background: ${theme.codeBlockBgHex}; border: 1px dashed ${theme.borderHex}; color: ${theme.commentHex}; font-size: 11px;'>" +
                    "🖼 <b>[图片: $alt]</b> <span style='font-size: 10px; color: ${theme.keywordHex};'>⏳ 正在加载预览...</span>" +
                "</div>"
            }
        }
        html = html.replace(Regex("""\[(.*?)\]\((.*?)\)""")) {
            val text = it.groupValues[1]
            val url = it.groupValues[2]
            if (com.lgguan.linuxdo.plugin.net.DocumentTrust.isWebLink(org.jsoup.parser.Parser.unescapeEntities(url, true)))
                "<a href='$url'>$text</a>" else text
        }
        html = html.replace(Regex("""\[details=(.*?)\]([\s\S]*?)\[/details\]""")) {
            val title = it.groupValues[1]
            val body = it.groupValues[2]
            "<fieldset><legend>$title</legend>$body</fieldset>"
        }
        html = html.replace(Regex("""(?m)^&gt;\s+(.*)$""")) { "<blockquote>${it.groupValues[1]}</blockquote>" }
        html = html.replace(Regex("""(?m)^-\s+(.*)$""")) { "<li>${it.groupValues[1]}</li>" }
        html = html.replace(Regex("""(?m)^\d+\.\s+(.*)$""")) { "<li>${it.groupValues[1]}</li>" }
        html = html.replace("\n", "<br>")
        return html
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

}
