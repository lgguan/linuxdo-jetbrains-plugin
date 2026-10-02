package com.lgguan.linuxdo.plugin.ui.dialog

import com.lgguan.linuxdo.plugin.theme.*
import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState

internal object ComposerPreview {
    fun render(bodyText: String, onImageReady: () -> Unit): String = document(markdownToHtml(bodyText, onImageReady))
    fun document(body: String): String {
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val css = DocCamouflageCssBuilder.buildCss(theme, LinuxDoSettingsState()) + ForumContent.css
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>$css body{padding:12px;font-size:14px} .post-content{max-width:100%}</style>${RenderAssets.tags}</head>
            <body><div class="post-content" id="composer-content">$body</div><script>${ForumContent.script}</script></body></html>"""
    }
    internal fun markdownToHtml(md: String, onImageReady: () -> Unit = {}): String = ForumContent.render(DiscourseMarkdown.render(md), false)
}
