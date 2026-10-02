package com.lgguan.linuxdo.plugin.theme

/** Exact packaged resources only; never proxy forum JavaScript through this endpoint. */
internal object RenderAssets {
    const val PATH = "https://linux.do/__linuxdo_plugin_assets/"
    private val names = setOf("highlight.min.js", "mermaid.min.js", "tex-svg-full.js")
    fun allowedPlayer(url: String): Boolean = runCatching {
        val uri = java.net.URI(url)
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            (uri.host == "player.bilibili.com" && uri.path == "/player.html" || uri.host == "www.youtube-nocookie.com" && uri.path.matches(Regex("/embed/[\\w-]{11}")))
    }.getOrDefault(false)
    fun resource(url: String): ByteArray? {
        if (!url.startsWith(PATH)) return null
        val name = url.removePrefix(PATH)
        if (name !in names) return null
        return javaClass.getResourceAsStream("/web/vendor/$name")?.use { it.readBytes() }
    }
    val tags: String get() = """
        <script>window.MathJax={loader:{load:[]},startup:{typeset:false},tex:{packages:['base','ams','newcommand','noundefined']},options:{enableMenu:false,skipHtmlTags:['script','noscript','style','textarea','pre','code']}};</script>
        <script src="${PATH}highlight.min.js"></script><script src="${PATH}mermaid.min.js"></script><script src="${PATH}tex-svg-full.js"></script>
    """.trimIndent()
}
