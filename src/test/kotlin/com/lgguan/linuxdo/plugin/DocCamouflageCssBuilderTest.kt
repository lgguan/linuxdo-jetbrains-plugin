package com.lgguan.linuxdo.plugin

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.ActionSummary
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.PostStream
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.theme.TopicDocumentRenderer
import com.lgguan.linuxdo.plugin.theme.EditorColorSchemeAdapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocCamouflageCssBuilderTest {

    @Test
    fun `additional floors render as insertable fragments with existing actions`() {
        val posts = listOf(Post(id = 901, username = "author", cooked = "<p>More content</p>", postNumber = 41))
        val topic = TopicDetailResponse(id = 900, title = "Long topic", postStream = PostStream(posts, listOf(901)))
        val fragment = TopicDocumentRenderer.buildFullDocHtml(
            topic, posts, null, null, EditorColorSchemeAdapter.getCurrentThemeColors(),
            LinuxDoSettingsState(), currentUsername = "reader", fragmentOnly = true)
        assertTrue(fragment.contains("id=\"floor-41\""))
        assertTrue(fragment.contains("data-post-id=\"901\""))
        assertTrue(fragment.contains("replyPost(41"))
        assertTrue(fragment.contains("toggleLikeUi"))
        assertTrue(fragment.contains("More content"))
        assertFalse(fragment.contains("<html"))
        assertFalse(fragment.contains("<script"))
        assertFalse(fragment.contains("Original Specification #1"))
    }

    @Test
    fun testDocHtmlGenerationAndImageFolding() {
        val theme = EditorColorSchemeAdapter.ThemeColors(
            bgHex = "#2B2D30",
            fgHex = "#DFE1E5",
            commentHex = "#7A7E85",
            keywordHex = "#CC7832",
            linkHex = "#589DF6",
            selectionBgHex = "#32435C",
            selectionFgHex = "#DFE1E5",
            codeBlockBgHex = "#1E1F22",
            borderHex = "#393B40",
            fontName = "JetBrains Mono",
            fontSize = 13,
            isDark = true
        )

        val settings = LinuxDoSettingsState().apply {
            foldImages = true
            hideAvatars = true
        }

        val samplePost = Post(
            id = 501,
            username = "linuxer",
            cooked = "<p>请查看架构图：<img src=\"https://example.com/arch.png\" alt=\"arch\" /></p>",
            postNumber = 1
        )

        val topic = TopicDetailResponse(
            id = 9999,
            title = "RFC 4040: JVM Metaspace 调优指南",
            postStream = PostStream(posts = listOf(samplePost))
        )

        val html = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(samplePost),
            categoryName = "开发调优",
            categorySlug = "dev",
            theme = theme,
            settings = settings
        )

        // Verify title and issue header are present
        assertTrue(html.contains("RFC 4040: JVM Metaspace 调优指南"))
        assertTrue(html.contains("Issue: #9999"))
        assertTrue(html.contains("dev.tuning")) // Namespace formatted
        assertTrue(html.contains("[Original Specification #1] by @linuxer"))

        // Verify image folding placeholder is present
        assertTrue(html.contains("[📷 Figure: arch.png"))
    }

    @Test
    fun testInlineTagsAndEmojisNotFolded() {
        val cooked = """
            <p>
                标签：<a href="/tag/jvm" class="discourse-tag"><img src="https://linux.do/tag.png" class="tag-icon"> jvm</a>
                表情：<img src="https://linux.do/images/emoji/twitter/sparkles.png?v=12" class="emoji" width="20" height="20" alt=":sparkles:">
                分类：<span class="badge-category"><img src="https://linux.do/cat.png" class="category-logo"> dev</span>
                大图：<img src="https://linux.do/uploads/screenshot.png" alt="screenshot">
            </p>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = true)

        // Tags, emojis, and category badges must NOT be folded into [📷 Figure
        assertFalse(processed.contains("[📷 Figure: tag.png"))
        assertFalse(processed.contains("[📷 Figure: sparkles.png"))
        assertFalse(processed.contains("[📷 Figure: cat.png"))

        // They must remain clean inline elements without fold-img-box wrappers
        assertTrue(processed.contains("""<a href="/tag/jvm" class="discourse-tag"><img src="https://linux.do/tag.png" class="tag-icon"> jvm</a>"""))
        assertTrue(processed.contains("""<span class="badge-category"><img src="https://linux.do/cat.png" class="category-logo"> dev</span>"""))
        assertTrue(processed.contains("""class="emoji""""))

        // Large content screenshot MUST be folded
        assertTrue(processed.contains("[📷 Figure: screenshot.png (点击展开 / Expand)]"))
        assertTrue(processed.contains("<div class=\"fold-img-box\">"))
    }

    @Test
    fun testLightboxWrapperStrippingAndFolding() {
        val cooked = """
            <div class="lightbox-wrapper">
                <a class="lightbox" href="https://linux.do/uploads/original/3X/8/8/diagram.png" title="diagram.png">
                    <img src="https://linux.do/uploads/optimized/3X/8/8/diagram_690x388.png" width="690" height="388" alt="diagram">
                    <div class="meta">
                        <span class="filename">diagram.png</span>
                        <span class="informations">690×388 45 KB</span>
                    </div>
                </a>
            </div>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = true)

        // Must fold to Figure with original image
        assertTrue(processed.contains("[📷 Figure: diagram.png (点击展开 / Expand)]"))
        assertTrue(processed.contains("data-orig-src=\"https://linux.do/uploads/original/3X/8/8/diagram.png\""))
        assertTrue(processed.contains("src=\"https://linux.do/uploads/optimized/3X/8/8/diagram_690x388.png\""))

        // Lightbox wrapper, anchor link, and meta must be stripped to prevent opening external browser
        assertFalse(processed.contains("class=\"lightbox\""))
        assertFalse(processed.contains("class=\"meta\""))
        assertFalse(processed.contains("690×388 45 KB"))

        // Verify NO duplicate placeholder or nested fold boxes
        assertEquals(1, Regex("""class=["']fold-img-box["']""").findAll(processed).count())
        assertEquals(1, Regex("""class=["']img-placeholder["']""").findAll(processed).count())
        assertEquals(1, Regex("""<img\s""").findAll(processed).count())

        // Verify img onclick calls openLightbox, NOT toggleImg
        assertTrue(processed.contains("onclick=\"openLightbox("))
        assertFalse(Regex("""<img[^>]*onclick=["']toggleImg""").containsMatchIn(processed))
    }

    @Test
    fun testMultipleImagesDoNotDuplicatePlaceholdersAndOpenLightbox() {
        val cooked = """
            <p>
                <div class="lightbox-wrapper">
                    <a class="lightbox" data-download-href="https://linux.do/uploads/default/dc6900feb2a83459c9962d56ee46e10ec62e4471" href="https://linux.do/uploads/default/original/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471.jpeg" title="similarweb_2025-08-07_073852_491">
                        <img src="https://linux.do/uploads/default/optimized/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471_2_690x388.jpeg" alt="similarweb_2025-08-07_073852_491" width="690" height="388">
                        <div class="meta"><span class="filename">similarweb.jpg</span></div>
                    </a>
                </div>
            </p>
            <p>* 数据图示来自 SimilarWeb</p>
            <p>
                <div class="lightbox-wrapper">
                    <a class="lightbox" data-download-href="https://linux.do/uploads/default/85832a2846a38ee6264a95396e2cf0947c813327" href="https://linux.do/uploads/default/original/3X/8/5/85832a2846a38ee6264a95396e2cf0947c813327.jpeg" title="similarweb_2025-08-07_073852_492">
                        <img src="https://linux.do/uploads/default/optimized/3X/8/5/85832a2846a38ee6264a95396e2cf0947c813327_2_690x388.jpeg" alt="similarweb_2025-08-07_073852_492" width="690" height="388">
                    </a>
                </div>
            </p>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = true)

        // Exactly 2 fold boxes, exactly 2 placeholders (1 per image), no nesting/duplicates
        assertEquals(2, Regex("""class=["']fold-img-box["']""").findAll(processed).count())
        assertEquals(2, Regex("""class=["']img-placeholder["']""").findAll(processed).count())
        assertEquals(2, Regex("""<img\s""").findAll(processed).count())

        // Verify correct file names with .jpeg extension are extracted (not data-download-href without extension)
        assertTrue(processed.contains("[📷 Figure: dc6900feb2a83459c9962d56ee46e10ec62e4471.jpeg - similarweb_2025-08-07_073852_491 (点击展开 / Expand)]"))
        assertTrue(processed.contains("[📷 Figure: 85832a2846a38ee6264a95396e2cf0947c813327.jpeg - similarweb_2025-08-07_073852_492 (点击展开 / Expand)]"))

        // Verify placeholder IDs are unique (1 placeholder per image)
        assertEquals(1, Regex("""id=["']ph-fold-img-1["']""").findAll(processed).count())
        assertEquals(1, Regex("""id=["']ph-fold-img-2["']""").findAll(processed).count())

        // Both images have openLightbox onclick, never toggleImg
        assertFalse(Regex("""<img[^>]*onclick=["']toggleImg""").containsMatchIn(processed))
        assertEquals(2, Regex("""<img[^>]*onclick=["']openLightbox""").findAll(processed).count())
    }

    @Test
    fun testDataDownloadHrefNotMistakenAsImageHref() {
        val cooked = """
            <div class="lightbox-wrapper">
                <a class="lightbox" data-download-href="https://linux.do/uploads/default/dc6900feb2a83459c9962d56ee46e10ec62e4471" href="https://linux.do/uploads/default/original/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471.jpeg" title="similarweb.jpeg">
                    <img src="https://linux.do/uploads/default/optimized/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471_2_690x388.jpeg" width="690" height="388">
                </a>
            </div>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = true)
        assertTrue(processed.contains("[📷 Figure: dc6900feb2a83459c9962d56ee46e10ec62e4471.jpeg (点击展开 / Expand)]"))
        assertTrue(processed.contains("data-orig-src=\"https://linux.do/uploads/default/original/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471.jpeg\""))
        assertTrue(processed.contains("src=\"https://linux.do/uploads/default/optimized/3X/d/c/dc6900feb2a83459c9962d56ee46e10ec62e4471_2_690x388.jpeg\""))
    }

    @Test
    fun testNormalizeUrl() {
        assertEquals("https://linux.do/uploads/pic.png", TopicDocumentRenderer.normalizeUrl("/uploads/pic.png"))
        assertEquals("https://linux.do/uploads/pic.png", TopicDocumentRenderer.normalizeUrl("//linux.do/uploads/pic.png"))
        assertEquals("https://linux.do/uploads/pic.png", TopicDocumentRenderer.normalizeUrl("uploads/pic.png"))
        assertEquals("https://linux.do/uploads/pic.png", TopicDocumentRenderer.normalizeUrl("https://linux.do/uploads/pic.png"))
        assertEquals("https://cdn.example.com/pic.png", TopicDocumentRenderer.normalizeUrl("https://cdn.example.com/pic.png"))
    }

    @Test
    fun testDirectImageLinkUnwrapped() {
        val cooked = """
            <p>
                <a href="https://example.com/network.png">
                    <img src="https://example.com/network.png" alt="network">
                </a>
            </p>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = true)

        // Outer <a> must be replaced by fold box so clicking doesn't open browser
        assertTrue(processed.contains("[📷 Figure: network.png (点击展开 / Expand)]"))
        assertFalse(processed.contains("<a href=\"https://example.com/network.png\">"))
    }

    @Test
    fun testIsInlineOrSmallImageDetection() {
        // Emojis
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/images/emoji/twitter/smile.png?v=12" class="emoji">""", "https://linux.do/images/emoji/twitter/smile.png?v=12"))
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://cdn.example.com/smile.png" width="20" height="20">""", "https://cdn.example.com/smile.png"))

        // Avatars
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/user_avatar/linux.do/user/40/1.png" class="avatar">""", "https://linux.do/user_avatar/linux.do/user/40/1.png"))

        // Tag icons & badges
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/tag.png" class="tag-icon">""", "https://linux.do/tag.png"))
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/logo.png" class="category-logo">""", "https://linux.do/logo.png"))
        assertTrue(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/site-icons/icon.png">""", "https://linux.do/site-icons/icon.png"))

        // Large images
        assertFalse(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://linux.do/uploads/pic.png" alt="pic">""", "https://linux.do/uploads/pic.png"))
        assertFalse(TopicDocumentRenderer.isInlineOrSmallImage("""<img src="https://example.com/photo.png" width="800" height="600">""", "https://example.com/photo.png"))
    }

    @Test
    fun testUnreadDotAndFloorJumpLink() {
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val settings = LinuxDoSettingsState()

        val post1 = Post(id = 101, username = "author1", cooked = "<p>First post</p>", postNumber = 1)
        val post2 = Post(id = 102, username = "author2", cooked = "<p>Reply to first</p>", postNumber = 2, replyToPostNumber = 1)
        val post3 = Post(id = 103, username = "author3", cooked = "<p>Unread post</p>", postNumber = 3)

        // Topic read up to post #2, so post #3 should have unread blue dot
        val topic = TopicDetailResponse(
            id = 777,
            title = "Test Topic",
            postStream = PostStream(posts = listOf(post1, post2, post3)),
            lastReadPostNumber = 2
        )

        val html = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(post1, post2, post3),
            categoryName = "dev",
            categorySlug = "dev",
            theme = theme,
            settings = settings
        )

        // Post 1 & 2 are read, so no unread dot for floor 1 or 2
        assertFalse(html.contains("id=\"dot-floor-1\""))
        assertFalse(html.contains("id=\"dot-floor-2\""))

        // Post 3 is unread, so MUST have unread blue dot
        assertTrue(html.contains("id=\"dot-floor-3\""))
        assertTrue(html.contains("class=\"unread-dot\""))
        assertTrue(html.contains("markFloorRead(777, 3)"))

        // Post 2 replies to Post 1, so MUST have clickable floor jump link to #1
        assertTrue(html.contains("jumpToFloor(1)"))
        assertTrue(html.contains("class=\"floor-jump-link\""))

        // All posts must have floor anchors
        assertTrue(html.contains("id=\"floor-1\""))
        assertTrue(html.contains("id=\"floor-2\""))
        assertTrue(html.contains("id=\"floor-3\""))
    }

    @Test
    fun testDirectImageRenderingWhenFoldImagesFalse() {
        val cooked = """
            <div class="lightbox-wrapper">
                <a class="lightbox" href="https://linux.do/pic.png">
                    <img src="https://linux.do/pic.png" alt="pic">
                    <div class="meta">
                        <span class="filename">pic.png</span>
                        <span class="informations">1920x1080 200KB</span>
                    </div>
                </a>
            </div>
        """.trimIndent()

        val processed = TopicDocumentRenderer.processContent(cooked, foldImages = false)

        // Must NOT contain fold placeholders
        assertFalse(processed.contains("[📷 Figure"))
        assertFalse(processed.contains("class=\"fold-img-box\""))

        // Must preserve the img directly
        assertTrue(processed.contains("src=\"https://linux.do/pic.png\""))

        // Must strip the annoying lightbox meta info
        assertFalse(processed.contains("1920x1080 200KB"))
        assertFalse(processed.contains("class=\"meta\""))
    }

    @Test
    fun testBalanceDivTags() {
        // Extra closing div
        val withExtraClose = "<div><p>Hello</p></div></div>"
        val balanced1 = TopicDocumentRenderer.balanceDivTags(withExtraClose)
        assertTrue(balanced1.startsWith("<div>"))
        val open1 = Regex("""<div[\s>]""", RegexOption.IGNORE_CASE).findAll(balanced1).count()
        val close1 = Regex("""</div>""", RegexOption.IGNORE_CASE).findAll(balanced1).count()
        org.junit.jupiter.api.Assertions.assertEquals(open1, close1)

        // Extra opening div
        val withExtraOpen = "<div><div><p>Hello</p></div>"
        val balanced2 = TopicDocumentRenderer.balanceDivTags(withExtraOpen)
        assertTrue(balanced2.endsWith("</div>"))
        val open2 = Regex("""<div[\s>]""", RegexOption.IGNORE_CASE).findAll(balanced2).count()
        val close2 = Regex("""</div>""", RegexOption.IGNORE_CASE).findAll(balanced2).count()
        org.junit.jupiter.api.Assertions.assertEquals(open2, close2)
    }

    @Test
    fun testAuthorPostActionsHidingLikeAndBoost() {
        val theme = EditorColorSchemeAdapter.ThemeColors(
            bgHex = "#2B2D30",
            fgHex = "#DFE1E5",
            commentHex = "#7A7E85",
            keywordHex = "#CC7832",
            linkHex = "#589DF6",
            selectionBgHex = "#32435C",
            selectionFgHex = "#DFE1E5",
            codeBlockBgHex = "#1E1F22",
            borderHex = "#393B40",
            fontName = "JetBrains Mono",
            fontSize = 13,
            isDark = true
        )

        val settings = LinuxDoSettingsState()

        val myPostWithoutLikes = Post(
            id = 101,
            username = "neo",
            cooked = "<p>My topic original post</p>",
            postNumber = 1
        )
        val myPostWithLikes = Post(
            id = 102,
            username = "neo",
            cooked = "<p>My second reply with likes</p>",
            postNumber = 2,
            actionsSummary = listOf(ActionSummary(id = 2, count = 7, acted = false, canAct = false))
        )
        val otherPost = Post(
            id = 103,
            username = "other_user",
            cooked = "<p>Someone else replying</p>",
            postNumber = 3,
            actionsSummary = listOf(ActionSummary(id = 2, count = 2, acted = false, canAct = true))
        )

        val topic = TopicDetailResponse(
            id = 8888,
            title = "Test Own Post Actions",
            postStream = PostStream(posts = listOf(myPostWithoutLikes, myPostWithLikes, otherPost))
        )

        val html = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(myPostWithoutLikes, myPostWithLikes, otherPost),
            categoryName = "开发",
            categorySlug = "dev",
            theme = theme,
            settings = settings,
            currentUsername = "neo"
        )

        // 1. For floor 1 (my post without likes):
        // Should NOT have Like button or Boost button, but should have Reply and Share
        val floor1Section = html.substringAfter("id=\"floor-1\"").substringBefore("id=\"floor-2\"")
        assertFalse(floor1Section.contains("toggleLikeUi(this, 101"), "Floor 1 should not contain like button")
        assertFalse(floor1Section.contains("boostPost(101"), "Floor 1 should not contain boost button")
        assertTrue(floor1Section.contains("replyPost(1, &quot;neo&quot;)"), "Floor 1 should contain reply button")
        assertTrue(floor1Section.contains("copyPostLink(8888, 1)"), "Floor 1 should contain share button")

        // 2. For floor 2 (my post with 7 likes):
        // Should have static like counter badge, but NOT clickable like or boost button
        val floor2Section = html.substringAfter("id=\"floor-2\"").substringBefore("id=\"floor-3\"")
        assertFalse(floor2Section.contains("toggleLikeUi(this, 102"), "Floor 2 should not contain clickable like button")
        assertFalse(floor2Section.contains("boostPost(102"), "Floor 2 should not contain boost button")
        assertTrue(floor2Section.contains("class=\"action-static\" title=\"获赞数\">♥ 7</span>"), "Floor 2 should contain static like badge")
        assertTrue(floor2Section.contains("replyPost(2, &quot;neo&quot;)"), "Floor 2 should contain reply button")
        assertTrue(floor2Section.contains("copyPostLink(8888, 2)"), "Floor 2 should contain share button")

        // 3. For floor 3 (other user's post):
        // MUST have like button and boost button
        val floor3Section = html.substringAfter("id=\"floor-3\"")
        assertTrue(floor3Section.contains("toggleLikeUi(this, 103, true)"), "Floor 3 should contain clickable like button")
        assertTrue(floor3Section.contains("boostPost(103, 3, &quot;other_user&quot;)"), "Floor 3 should contain boost button")
        assertTrue(floor3Section.contains("replyPost(3, &quot;other_user&quot;)"), "Floor 3 should contain reply button")
        assertTrue(floor3Section.contains("copyPostLink(8888, 3)"), "Floor 3 should contain share button")
    }

    @Test
    fun testHideAvatarsVsShowAvatars() {
        val theme = EditorColorSchemeAdapter.getCurrentThemeColors()
        val post = Post(
            id = 201,
            username = "neo",
            avatarTemplate = "/user_avatar/linux.do/neo/{size}/1234_2.png",
            cooked = "<p>Hello world</p>",
            postNumber = 1
        )
        val topic = TopicDetailResponse(
            id = 1234,
            title = "Test Avatars",
            postStream = PostStream(posts = listOf(post))
        )

        // 1. When hideAvatars = true (default)
        val settingsHidden = LinuxDoSettingsState().apply { hideAvatars = true }
        val htmlHidden = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(post),
            categoryName = "dev",
            categorySlug = "dev",
            theme = theme,
            settings = settingsHidden
        )
        assertFalse(htmlHidden.contains("class=\"avatar-img\""), "Avatar img tag must NOT be rendered when hideAvatars is true")
        assertTrue(htmlHidden.contains("display: none !important"), "CSS must hide avatars when hideAvatars is true")

        // 2. When hideAvatars = false
        val settingsShown = LinuxDoSettingsState().apply { hideAvatars = false }
        val htmlShown = TopicDocumentRenderer.buildFullDocHtml(
            topic = topic,
            posts = listOf(post),
            categoryName = "dev",
            categorySlug = "dev",
            theme = theme,
            settings = settingsShown
        )
        assertTrue(htmlShown.contains("class=\"avatar-img\""), "Avatar img tag MUST be rendered when hideAvatars is false")
        assertTrue(htmlShown.contains("src=\"https://linux.do/user_avatar/linux.do/neo/48/1234_2.png\""))
    }

    @Test
    fun testFoldImagesWithAltCaption() {
        val cookedWithAlt = """<p><img src="https://linux.do/uploads/arch.png" alt="系统微服务架构图"></p>"""
        val processedWithAlt = TopicDocumentRenderer.processContent(cookedWithAlt, foldImages = true)
        assertTrue(processedWithAlt.contains("[📷 Figure: arch.png - 系统微服务架构图 (点击展开 / Expand)]"))

        val cookedWithoutAlt = """<p><img src="https://linux.do/uploads/arch.png" alt="arch"></p>"""
        val processedWithoutAlt = TopicDocumentRenderer.processContent(cookedWithoutAlt, foldImages = true)
        assertTrue(processedWithoutAlt.contains("[📷 Figure: arch.png (点击展开 / Expand)]"))
    }

    @Test
    fun testExtractAttribute() {
        val tagA = """<a class="lightbox" data-download-href="https://linux.do/uploads/default/hash123" href="https://linux.do/uploads/default/orig.jpeg" title="My Diagram">"""
        assertEquals("https://linux.do/uploads/default/orig.jpeg", TopicDocumentRenderer.extractAttribute(tagA, "href"))
        assertEquals("https://linux.do/uploads/default/hash123", TopicDocumentRenderer.extractAttribute(tagA, "data-download-href"))
        assertEquals("My Diagram", TopicDocumentRenderer.extractAttribute(tagA, "title"))
        assertEquals("lightbox", TopicDocumentRenderer.extractAttribute(tagA, "class"))
        assertNull(TopicDocumentRenderer.extractAttribute(tagA, "src"))

        val tagImg = """<img src="https://linux.do/uploads/test.png" width=690 height="388" alt="Demo">"""
        assertEquals("https://linux.do/uploads/test.png", TopicDocumentRenderer.extractAttribute(tagImg, "src"))
        assertEquals("690", TopicDocumentRenderer.extractAttribute(tagImg, "width"))
        assertEquals("388", TopicDocumentRenderer.extractAttribute(tagImg, "height"))
        assertEquals("Demo", TopicDocumentRenderer.extractAttribute(tagImg, "alt"))
    }
}
