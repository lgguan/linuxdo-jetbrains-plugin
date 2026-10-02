package com.lgguan.linuxdo.plugin.theme

/** Fixed template icons; no forum markup or user input is inserted into SVG. */
internal object ReaderIcons {
    fun svg(name: String): String {
        val body = when (name) {
            "like" -> """<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"/>"""
            "reply" -> """<path d="m9 5-6 6 6 6M3 11h10a7 7 0 0 1 7 7v1"/>"""
            "boost" -> """<path d="M14 5c3-3 7-3 7-3s0 4-3 7l-6 6-5-5 7-5ZM7 10H3l3-5h8M12 15v4l5-3V10M6 14c-3 0-4 4-4 8 4 0 8-1 8-4"/><circle cx="16" cy="7" r="1"/>"""
            "share" -> """<path d="M12 16V3m-5 5 5-5 5 5M5 13v7h14v-7"/>"""
            "bookmark" -> """<path d="M6 3h12v18l-6-4-6 4V3Z"/>"""
            "edit" -> """<path d="m16 3 5 5-12 12-6 1 1-6L16 3ZM14 5l5 5"/>"""
            "history" -> """<path d="M3 11a9 9 0 1 1 2 7M3 4v7h7M12 7v5l3 2"/>"""
            "delete" -> """<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>"""
            "recover" -> """<path d="M3 10a9 9 0 1 1 2 8M3 4v6h6"/>"""
            "flag" -> """<path d="M5 21V3h14l-3 5 3 5H5"/>"""
            "reaction", "reactionUsers" -> """<circle cx="12" cy="12" r="9"/><path d="M8 14c2 3 6 3 8 0M8 8h.01M16 8h.01"/>"""
            "replies" -> """<path d="M3 3h18v13H8l-5 5V3Z"/>"""
            "accept", "unaccept" -> """<path d="m5 12 4 4L19 6"/>"""
            "more" -> """<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>"""
            else -> """<path d="m12 4 6 7h-4v9h-4v-9H6l6-7Z"/>"""
        }
        return """<svg class="reader-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">$body</svg>"""
    }
}
