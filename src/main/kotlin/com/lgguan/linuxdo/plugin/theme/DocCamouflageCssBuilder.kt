package com.lgguan.linuxdo.plugin.theme

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState

object DocCamouflageCssBuilder {
    private val navigationCss by lazy {
        requireNotNull(javaClass.getResource("/web/topic-navigation.css")).readText()
    }

    fun buildCss(theme: EditorColorSchemeAdapter.ThemeColors, settings: LinuxDoSettingsState): String {
        val avatarDisplay = if (settings.hideAvatars) "none" else "inline-block"
        val fontSize = if (settings.readingFontSize == 0) (theme.fontSize + 2).coerceAtLeast(15) else settings.readingFontSize.coerceIn(12, 32)

        val fgColor = if (theme.isDark) "#E6EDF3" else "#1F2328"
        val titleColor = if (theme.isDark) "#FFFFFF" else "#0A0C10"
        val metaColor = if (theme.isDark) "#8B949E" else "#57606A"

        return """
            :root {
                --bg: ${theme.bgHex};
                --fg: $fgColor;
                --title-color: $titleColor;
                --comment: $metaColor;
                --keyword: ${theme.keywordHex};
                --string: ${theme.linkHex};
                --link: ${theme.linkHex};
                --selection-bg: ${theme.selectionBgHex};
                --selection-fg: ${theme.selectionFgHex};
                --code-bg: ${theme.codeBlockBgHex};
                --border: ${theme.borderHex};
                --font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", '${theme.fontName}', 'JetBrains Mono', Consolas, sans-serif;
                --code-font-family: '${theme.fontName}', 'JetBrains Mono', 'Fira Code', Consolas, Monaco, monospace;
                --font-size: ${fontSize}px;
            }

            * {
                box-sizing: border-box;
            }

            body {
                background-color: var(--bg);
                color: var(--fg);
                font-family: var(--font-family);
                font-size: var(--font-size);
                line-height: ${settings.readingLineHeight.coerceIn(1.2, 2.5)};
                margin: 0;
                padding: 16px 24px 40px 24px;
                word-wrap: break-word;
            }

            .doc-container {
                max-width: ${settings.readingWidth.coerceIn(480, 1600)}px;
                margin: 0 auto;
                padding: 0;
                width: 100%;
            }

            ::selection {
                background-color: var(--selection-bg);
                color: var(--selection-fg);
            }

            a {
                color: var(--link);
                text-decoration: none;
            }

            a:hover {
                text-decoration: underline;
            }

            /* Document Header (Javadoc / RFC Spec style) */
            .doc-header {
                max-width: 100%;
                margin: 0 auto 16px auto;
                padding-bottom: 10px;
                border-bottom: 1px dashed var(--border);
                width: 100%;
            }

            .doc-title {
                font-family: var(--font-family);
                font-size: 1.45em;
                font-weight: 700;
                line-height: 1.35;
                color: var(--title-color);
                margin-bottom: 6px;
            }

            .doc-meta-comment {
                font-family: var(--code-font-family);
                color: var(--comment);
                font-size: 0.88em;
                line-height: 1.4;
                white-space: pre;
                margin: 0;
            }

            /* Post Floor / Block */
            .post-entry {
                max-width: 100%;
                margin: 0 auto 16px auto;
                padding-bottom: 12px;
                border-bottom: 1px solid var(--border);
                width: 100%;
            }

            .post-entry:last-child {
                border-bottom: none;
            }

            .floor-comment-header {
                font-family: var(--code-font-family);
                color: var(--comment);
                font-size: 0.88em;
                margin-bottom: 6px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                user-select: none;
            }

            .floor-meta {
                display: flex;
                align-items: center;
                gap: 8px;
            }

            .avatar-img {
                display: $avatarDisplay;
                width: 22px;
                height: 22px;
                border-radius: 50%;
                vertical-align: middle;
                border: 1px solid var(--border);
                flex-shrink: 0;
            }

            ${if (settings.hideAvatars) """
            .avatar-img,
            .post-content img.avatar,
            .post-content .avatar,
            aside.quote .title img.avatar {
                display: none !important;
            }
            """ else """
            .post-content img.avatar,
            aside.quote .title img.avatar {
                display: inline-block;
                width: 20px;
                height: 20px;
                border-radius: 50%;
                vertical-align: middle;
                margin-right: 4px;
            }
            """}

            .author-badge {
                color: var(--keyword);
                font-weight: bold;
            }

            .floor-number {
                color: var(--comment);
            }

            .floor-actions {
                display: flex;
                gap: 10px;
                font-size: 0.88em;
            }

            .action-link {
                color: var(--comment);
                cursor: pointer;
                border: 1px solid var(--border);
                padding: 3px 8px;
                border-radius: 4px;
                background-color: var(--code-bg);
                transition: all 0.2s ease;
            }

            .action-link:hover {
                color: var(--fg);
                border-color: var(--link);
            }

            .action-link.liked {
                color: #e06c75;
                border-color: #e06c75;
            }

            .action-static {
                color: var(--comment);
                border: 1px solid var(--border);
                padding: 3px 8px;
                border-radius: 4px;
                background-color: var(--code-bg);
                opacity: 0.85;
                user-select: none;
            }

            /* Discourse Cooked Content Styling */
            .post-content {
                color: var(--fg);
                font-size: 1em;
                line-height: 1.48;
            }

            .post-content p {
                margin: 5px 0;
            }

            .post-content pre {
                background-color: var(--code-bg);
                border: 1px solid var(--border);
                border-radius: 6px;
                padding: 8px 12px;
                overflow-x: auto;
                font-family: var(--code-font-family);
                font-size: 0.92em;
                line-height: 1.45;
                margin: 8px 0;
            }

            .post-content code {
                background-color: var(--code-bg);
                padding: 2px 6px;
                border-radius: 4px;
                font-family: var(--code-font-family);
                font-size: 0.92em;
                border: 1px solid var(--border);
            }

            pre code {
                padding: 0;
                border: none;
                background-color: transparent;
            }

            .doc-code-block { position: relative; min-width: 0; max-width: 100%; margin: 8px 0; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
            .doc-code-copy { position: absolute; top: 6px; right: 8px; z-index: 1; font: inherit; font-size: .82em; color: var(--comment); background: var(--code-bg); border: 1px solid var(--border); border-radius: 4px; padding: 2px 8px; cursor: pointer; user-select: none; }
            .doc-code-copy:hover, .doc-code-copy:focus-visible { color: var(--fg); border-color: var(--link); }
            .doc-code-copy:focus-visible { outline: 2px solid var(--link); outline-offset: 2px; }
            .doc-code-copy:disabled { cursor: wait; opacity: .65; }
            .post-content .doc-code-block > pre { margin: 0; border: 0; border-radius: 0; min-height: 36px; padding-right: 7.5em; }
            .source-block > .doc-code-block { margin: 0; border: 0; border-radius: 0; }

            .post-content blockquote {
                margin: 8px 0;
                padding: 6px 12px;
                border-left: 4px solid var(--comment);
                color: var(--comment);
                background-color: var(--code-bg);
                border-radius: 0 4px 4px 0;
            }

            .source-block { min-width: 0; margin: 12px 0; border: 1px solid var(--border); border-radius: 6px; overflow: hidden; }
            .source-block-title { padding: 6px 12px; color: var(--comment); background: var(--code-bg); border-bottom: 1px solid var(--border); font-size: .85em; }
            .post-content .source-block-code { margin: 0; padding: 12px; max-height: 480px; max-height: min(60vh, 640px); overflow: auto; white-space: pre; overflow-wrap: normal; word-break: normal; border: 0; border-radius: 0; tab-size: 4; }
            .post-content .source-block-code code { white-space: inherit; }
            .source-block-unavailable { padding: 0 12px; color: var(--comment); }

            /* Image Fold / Camouflage Mode */
            .fold-img-box {
                display: block;
                margin: 8px 0;
                max-width: 100%;
            }

            ${if (settings.foldImages) """
            .fold-img-box img {
                display: none;
                max-width: 85% !important;
                max-height: 480px !important;
                width: auto !important;
                height: auto !important;
                object-fit: contain;
                margin: 6px 0;
                border: 1px solid var(--border);
                border-radius: 6px;
                cursor: zoom-in !important;
                box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
                transition: filter 0.15s ease, border-color 0.15s ease;
            }

            .fold-img-box img.expanded {
                display: block;
            }

            .fold-img-box img.expanded:hover {
                filter: brightness(1.05);
                border-color: var(--link);
            }

            .img-placeholder {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                color: var(--link);
                background: var(--code-bg);
                border: 1px dashed var(--border);
                padding: 3px 8px;
                border-radius: 4px;
                cursor: pointer;
                font-size: 0.86em;
                margin: 4px 0;
                user-select: none;
            }
            .img-placeholder:hover {
                border-color: var(--link);
                background-color: var(--selection-bg);
            }
            .img-placeholder.is-expanded {
                border-style: solid;
                border-color: var(--keyword);
                color: var(--keyword);
                background-color: var(--selection-bg);
            }
            """ else """
            .fold-img-box img,
            .post-content img:not(.emoji):not(.avatar):not([class*="icon"]):not([class*="badge"]):not([class*="tag"]):not([class*="hashtag"]):not([class*="logo"]):not(.inline-img) {
                display: block;
                max-width: 90% !important;
                max-height: 540px !important;
                width: auto !important;
                height: auto !important;
                object-fit: contain;
                border-radius: 6px;
                margin: 8px 0;
                border: 1px solid var(--border);
                box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
                cursor: zoom-in !important;
                transition: filter 0.15s ease, border-color 0.15s ease;
            }

            .post-content img:not(.emoji):not(.avatar):not([class*="icon"]):not([class*="badge"]):not([class*="tag"]):not([class*="hashtag"]):not([class*="logo"]):not(.inline-img):hover {
                filter: brightness(1.05);
                border-color: var(--link);
            }

            .lightbox-wrapper .meta,
            .post-content .lightbox-wrapper .meta {
                display: none !important;
            }

            .lightbox-wrapper {
                margin: 8px 0;
            }
            """}

            /* Discourse Hashtags, Tags & Badges - seamless inline display */
            .post-content .hashtag-cooked,
            .post-content .hashtag,
            .post-content [class*="hashtag"],
            .post-content .discourse-tag,
            .post-content a.discourse-tag,
            .post-content span.discourse-tag,
            .post-content .badge-category,
            .post-content .badge-wrapper,
            .post-content a[href*="/tag/"] {
                display: inline-flex !important;
                align-items: center !important;
                vertical-align: baseline !important;
                margin: 0 2px !important;
                padding: 1px 7px !important;
                border-radius: 4px !important;
                font-size: 0.88em !important;
                line-height: 1.35 !important;
                white-space: nowrap !important;
                cursor: pointer !important;
                text-decoration: none !important;
                color: var(--link) !important;
                background-color: var(--code-bg) !important;
                border: 1px solid var(--border) !important;
                transition: all 0.15s ease !important;
            }

            .post-content .hashtag-cooked:hover,
            .post-content .hashtag:hover,
            .post-content [class*="hashtag"]:hover,
            .post-content a.discourse-tag:hover,
            .post-content a.badge-category:hover,
            .post-content a[href*="/tag/"]:hover {
                background-color: var(--selection-bg) !important;
                color: var(--selection-fg) !important;
                border-color: var(--link) !important;
                text-decoration: none !important;
                transform: translateY(-1px);
            }

            .post-content .hashtag-cooked:active,
            .post-content .hashtag:active,
            .post-content a[href*="/tag/"]:active {
                transform: scale(0.96) !important;
            }

            .post-content .hashtag-icon-placeholder {
                display: inline-flex !important;
                align-items: center !important;
                justify-content: center !important;
                line-height: 1 !important;
                margin-right: 4px !important;
                vertical-align: middle !important;
            }

            /* Inline emojis, avatars, badges, and tag icons - rendered seamlessly inline without line break */
            .post-content img.emoji,
            .post-content img.avatar,
            .post-content img[class*="icon"],
            .post-content img[class*="badge"],
            .post-content img[class*="tag"],
            .post-content img[class*="hashtag"],
            .post-content img[class*="logo"],
            .post-content .hashtag-cooked img,
            .post-content .hashtag img,
            .post-content [class*="hashtag"] img,
            .post-content .discourse-tag img,
            .post-content .badge-category img,
            .post-content .badge-wrapper img,
            .post-content a[href*="/tag/"] img,
            .post-content img.inline-img {
                display: inline-block !important;
                width: auto !important;
                height: 1.15em !important;
                max-width: 16px !important;
                max-height: 16px !important;
                vertical-align: -0.15em !important;
                margin: 0 3px 0 0 !important;
                padding: 0 !important;
                border: none !important;
                border-radius: 2px !important;
                box-shadow: none !important;
                background: transparent !important;
                cursor: pointer !important;
            }

            .post-content .hashtag-icon-placeholder svg,
            .post-content .hashtag-cooked svg,
            .post-content .hashtag svg,
            .post-content [class*="hashtag"] svg,
            .post-content .discourse-tag svg,
            .post-content .badge-category svg {
                display: inline-block !important;
                width: 13px !important;
                height: 13px !important;
                max-width: 13px !important;
                max-height: 13px !important;
                vertical-align: -0.12em !important;
                margin-right: 2px !important;
                fill: currentColor !important;
                flex-shrink: 0 !important;
            }

            /* Table formatting */
            table {
                border-collapse: collapse;
                width: 100%;
                margin: 10px 0;
            }

            th, td {
                border: 1px solid var(--border);
                padding: 6px 10px;
                text-align: left;
            }

            th {
                background-color: var(--code-bg);
                color: var(--keyword);
            }

            /* Boost reactions */
            .boost-container {
                display: flex;
                flex-wrap: wrap;
                gap: 6px;
                margin-top: 6px;
                padding-top: 4px;
            }

            .boost-tag {
                display: inline-flex;
                align-items: center;
                background-color: var(--code-bg);
                border: 1px solid var(--border);
                border-radius: 10px;
                padding: 1px 8px;
                font-size: 0.82em;
                color: var(--comment);
                line-height: 1.35;
            }

            /* Unread floor indicator */
            .floor-position {
                display: flex;
                align-items: center;
                gap: 8px;
                flex: 0 0 auto;
                white-space: nowrap;
            }
            .floor-read-indicator {
                display: inline-flex;
                flex: 0 0 7px;
                width: 7px;
                height: 7px;
            }
            .unread-dot {
                display: inline-block;
                width: 7px;
                height: 7px;
                border-radius: 50%;
                background-color: #388BFD;
                box-shadow: 0 0 5px rgba(56, 139, 253, 0.7);
                cursor: pointer;
                transition: transform 0.15s ease, opacity 0.2s ease;
            }
            .unread-dot:hover {
                transform: scale(1.35);
            }
            .unread-dot.read {
                display: none !important;
            }

            /* Floor jump links and highlighting */
            .floor-jump-link {
                color: var(--link);
                cursor: pointer;
                text-decoration: underline;
                font-weight: 600;
            }
            .floor-jump-link:hover {
                color: var(--keyword);
            }

            @keyframes floorHighlight {
                0% { background-color: var(--selection-bg); }
                100% { background-color: transparent; }
            }
            .highlight-flash {
                animation: floorHighlight 1.8s ease-out;
            }

            /* High-resolution Image Lightbox Modal */
            .image-lightbox-overlay {
                position: fixed;
                top: 0;
                left: 0;
                width: 100vw;
                height: 100vh;
                background: rgba(10, 12, 16, 0.90);
                backdrop-filter: blur(8px);
                -webkit-backdrop-filter: blur(8px);
                z-index: 999999;
                display: none;
                flex-direction: column;
                user-select: none;
                animation: lbFadeIn 0.15s ease-out;
            }

            @keyframes lbFadeIn {
                from { opacity: 0; }
                to { opacity: 1; }
            }

            .image-lightbox-overlay.active {
                display: flex !important;
            }

            .image-lightbox-header {
                height: 44px;
                padding: 0 16px;
                display: flex;
                align-items: center;
                justify-content: space-between;
                background: rgba(22, 27, 34, 0.85);
                border-bottom: 1px solid var(--border);
                color: var(--fg);
                font-family: var(--code-font-family);
                font-size: 0.86em;
                z-index: 100;
            }

            .image-lightbox-title {
                max-width: 55vw;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
                color: var(--fg);
                font-weight: 500;
            }

            .image-lightbox-tools {
                display: flex;
                align-items: center;
                gap: 6px;
            }

            .lb-btn {
                background: var(--code-bg);
                border: 1px solid var(--border);
                color: var(--fg);
                border-radius: 4px;
                padding: 3px 9px;
                cursor: pointer;
                font-size: 0.88em;
                font-family: var(--code-font-family);
                transition: all 0.15s ease;
                user-select: none;
            }

            .lb-btn:hover {
                background: var(--selection-bg);
                border-color: var(--link);
                color: var(--selection-fg);
            }

            .lb-close-btn {
                font-size: 1.1em;
                font-weight: bold;
                padding: 2px 10px;
                margin-left: 4px;
            }

            .lb-close-btn:hover {
                background: #da3633 !important;
                color: #ffffff !important;
                border-color: #da3633 !important;
            }

            .image-lightbox-body {
                flex: 1;
                width: 100%;
                height: calc(100vh - 44px);
                display: flex;
                align-items: center;
                justify-content: center;
                overflow: hidden;
                position: relative;
                cursor: grab;
            }

            .image-lightbox-body.grabbing {
                cursor: grabbing;
            }

            .image-lightbox-img {
                max-width: 94vw;
                max-height: calc(92vh - 44px);
                width: auto;
                height: auto;
                object-fit: contain;
                border-radius: 4px;
                box-shadow: 0 10px 36px rgba(0, 0, 0, 0.65);
                transform-origin: center center;
                transition: transform 0.12s ease-out;
                pointer-events: auto;
            }
            .doc-meta-comment { white-space: pre-wrap; overflow-wrap: anywhere; }
            .doc-container, .post-entry, .post-content, .floor-meta, .floor-number { min-width: 0; overflow-wrap: anywhere; }
            .floor-comment-header { flex-wrap: nowrap; gap: 8px; }
            .floor-actions { flex-wrap: wrap; gap: 8px; }
            .floor-meta { flex: 1 1 340px; }
            .floor-actions { max-width: 100%; }
            .post-content pre, .post-content table { max-width: 100%; overflow-x: auto; }
            .post-content table { display: block; }
            .post-content video, .post-content iframe { max-width: 100% !important; }
            .post-content video { width: 100%; height: auto; max-height: 75vh; background: #000; }
            .post-content .lightbox-wrapper, .post-content a.lightbox { max-width: 100%; }
            .post-content a.lightbox { display: inline-block; }
            .post-content img { object-fit: contain; }
            .image-lightbox-header, .image-lightbox-tools { flex-wrap: wrap; gap: 6px; }
            .image-lightbox-body { min-height: 0; flex: 1; }
            .image-lightbox-img { max-height: 100%; }
            .media-fallback { display: block; font-size: .85em; margin: 4px 0 12px; }
            .embedded-video-card { padding: 16px; border: 1px solid var(--border); border-radius: 6px; background: var(--code-bg); }
            .embedded-video-card p { margin: 8px 0; }
            .embedded-video-frame { display: block; width: 100%; height: auto; aspect-ratio: 16 / 9; border: 0; }
            @media (max-width: 600px) {
                body { padding: 12px 12px 28px; }
                .doc-title { font-size: 1.2em; }
                .floor-actions { flex: 1 1 100%; }
                .image-lightbox-header { padding: 8px; }
                .image-lightbox-title { max-width: 100%; }
                .post-content img { max-width: 100% !important; }
            }
        """.trimIndent() + "\n" + navigationCss + "\n" + requireNotNull(javaClass.getResource("/web/boost.css")).readText()
    }

}
