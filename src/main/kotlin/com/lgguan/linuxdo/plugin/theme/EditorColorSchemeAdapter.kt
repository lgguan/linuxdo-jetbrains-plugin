package com.lgguan.linuxdo.plugin.theme

import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color

object EditorColorSchemeAdapter {

    data class ThemeColors(
        val bgHex: String,
        val fgHex: String,
        val commentHex: String,
        val keywordHex: String,
        val linkHex: String,
        val selectionBgHex: String,
        val selectionFgHex: String,
        val codeBlockBgHex: String,
        val borderHex: String,
        val fontName: String,
        val fontSize: Int,
        val isDark: Boolean
    )

    fun getCurrentThemeColors(): ThemeColors {
        val scheme = try { EditorColorsManager.getInstance()?.globalScheme } catch (_: Throwable) { null }
        val isDark = try { !JBColor.isBright() } catch (_: Throwable) { true }

        val bg = scheme?.defaultBackground ?: (if (isDark) Color(0x2B, 0x2D, 0x30) else Color(0xF8, 0xF9, 0xFA))
        val fg = scheme?.defaultForeground ?: (if (isDark) Color(0xDF, 0xE1, 0xE5) else Color(0x1F, 0x23, 0x28))
        val comment = scheme?.getColor(EditorColors.LINE_NUMBERS_COLOR)
            ?: (if (isDark) Color(0x7A, 0x7E, 0x85) else Color(0x6E, 0x77, 0x81))
        val selectionBg = scheme?.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: (if (isDark) Color(0x32, 0x43, 0x5C) else Color(0xB6, 0xD6, 0xFB))
        val selectionFg = scheme?.getColor(EditorColors.SELECTION_FOREGROUND_COLOR) ?: fg

        // Code block background is slightly lighter or darker than main bg
        val codeBlockBg = if (isDark) {
            ColorUtil.darker(bg, 1).let { if (it == bg) Color(0x1E, 0x1F, 0x22) else it }
        } else {
            Color(0xF6, 0xF8, 0xFA)
        }

        val border = if (isDark) Color(0x39, 0x3B, 0x40) else Color(0xD0, 0xD7, 0xDE)
        val keyword = if (isDark) "#CC7832" else "#0033B3"
        val link = if (isDark) "#589DF6" else "#0969DA"

        val rawFont = scheme?.editorFontName ?: "JetBrains Mono"
        val fontName = rawFont.substringBefore(",").trim().ifBlank { "JetBrains Mono" }
        val fontSize = scheme?.editorFontSize?.takeIf { it in 10..24 } ?: 13

        return ThemeColors(
            bgHex = "#" + ColorUtil.toHex(bg),
            fgHex = "#" + ColorUtil.toHex(fg),
            commentHex = "#" + ColorUtil.toHex(comment),
            keywordHex = keyword,
            linkHex = link,
            selectionBgHex = "#" + ColorUtil.toHex(selectionBg),
            selectionFgHex = "#" + ColorUtil.toHex(selectionFg),
            codeBlockBgHex = "#" + ColorUtil.toHex(codeBlockBg),
            borderHex = "#" + ColorUtil.toHex(border),
            fontName = fontName,
            fontSize = fontSize,
            isDark = isDark
        )
    }
}
