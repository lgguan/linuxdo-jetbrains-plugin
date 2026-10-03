package com.lgguan.linuxdo.plugin.theme

import com.lgguan.linuxdo.plugin.config.LinuxDoSettingsState
import com.lgguan.linuxdo.plugin.model.Post
import com.lgguan.linuxdo.plugin.model.TopicDetailResponse
import com.lgguan.linuxdo.plugin.service.LinuxDoAuthService
import com.lgguan.linuxdo.plugin.service.LinuxDoReadTrackingService

object TopicDocumentRenderer {

    private val enhancementsScript by lazy {
        requireNotNull(javaClass.getResource("/web/topic-media.js")).readText()
    }

    fun buildFullDocHtml(
        topic: TopicDetailResponse,
        posts: List<Post>,
        categoryName: String?,
        categorySlug: String?,
        theme: EditorColorSchemeAdapter.ThemeColors,
        settings: LinuxDoSettingsState,
        bridgeScript: String? = null,
        currentUsername: String? = try {
            LinuxDoAuthService.getInstance().currentUser?.username
        } catch (_: Throwable) {
            null
        },
        targetPostNumber: Int? = null,
        paginationScript: String = "",
        fragmentOnly: Boolean = false
    ): String {
        val postsHtml = buildPostFragment(topic, posts, settings, currentUsername)
        if (fragmentOnly) return postsHtml
        val css = DocCamouflageCssBuilder.buildCss(theme, settings) + ForumContent.css
        val namespace = if (settings.categoryNamespaceFormat) {
            NamespaceFormatter.format(categoryName, categorySlug)
        } else {
            categoryName ?: "general"
        }

        val headerDoc = """
/**
 * Package: $namespace | Issue: #${topic.id}
 * Views: ${topic.views} | Replies: ${topic.replyCount} | Likes: ${topic.likeCount}
 */
        """.trim()

        val bridgeScriptTag = if (!bridgeScript.isNullOrBlank()) {
            "<script>\n$bridgeScript\n</script>"
        } else ""

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <base href="https://linux.do/">
                <style id="linuxdo-reader-theme">$css</style>
                ${RenderAssets.tags}
                $bridgeScriptTag
                <script>
                    function jumpToFloor(floorNum) {
                        if (window.linuxDoPagination) return window.linuxDoPagination.jump(floorNum);
                        var el = document.querySelector('[data-post-number="' + floorNum + '"]') ||
                                 document.getElementById('floor-' + floorNum) ||
                                 document.getElementById('post-num-' + floorNum);
                        if (el) {
                            el.scrollIntoView({ behavior: 'smooth', block: 'start' });
                            el.classList.add('highlight-flash');
                            setTimeout(function() {
                                el.classList.remove('highlight-flash');
                            }, 1800);
                            return true;
                        }
                        return window.linuxDoPagination ? window.linuxDoPagination.jump(floorNum) : false;
                    }

                    ${if (targetPostNumber != null && targetPostNumber > 1) """
                    function autoJumpToTarget(targetFloor) {
                        if (!targetFloor || targetFloor <= 1) return;
                        var attempts = 0;
                        function tryJump() {
                            attempts++;
                            if (jumpToFloor($targetPostNumber)) {
                                showDocToast('已自动定位至上次阅读楼层 #' + $targetPostNumber);
                                return;
                            }
                            if (attempts < 15) {
                                setTimeout(tryJump, 200);
                            }
                        }
                        if (document.readyState === 'complete') {
                            setTimeout(tryJump, 200);
                        } else {
                            window.addEventListener('load', function() {
                                setTimeout(tryJump, 200);
                            });
                            setTimeout(tryJump, 350);
                        }
                    }
                    autoJumpToTarget($targetPostNumber);
                    """ else ""}

                    function toggleLikeUi(el, postId, shouldLike) {
                        if (!el) return;
                        var currentText = el.innerText || '';
                        var match = currentText.match(/\d+/);
                        var count = match ? parseInt(match[0], 10) : 0;
                        if (shouldLike) {
                            count++;
                            el.className = 'action-link liked';
                            el.innerText = '♥ ' + count;
                            el.setAttribute('onclick', 'toggleLikeUi(this, ' + postId + ', false)');
                        } else {
                            count = Math.max(0, count - 1);
                            el.className = 'action-link';
                            el.innerText = count > 0 ? '♡ ' + count : '♡ Like';
                            el.setAttribute('onclick', 'toggleLikeUi(this, ' + postId + ', true)');
                        }
                        if (window.intellijBridge && window.intellijBridge.toggleLike) {
                            window.intellijBridge.toggleLike(postId, shouldLike);
                        }
                    }

                    function toggleImg(id, e) {
                        if (e) {
                            if (e.stopPropagation) e.stopPropagation();
                            if (e.preventDefault) e.preventDefault();
                        }
                        var box = e && e.target && e.target.closest('.fold-img-box');
                        var img = box ? box.querySelector('img') : document.getElementById(id);
                        var ph = box ? box.querySelector('.img-placeholder') : document.getElementById('ph-' + id);
                        if (img) {
                            if (img.classList.contains('expanded')) {
                                img.classList.remove('expanded');
                                if (ph) {
                                    ph.innerText = ph.getAttribute('data-open-text') || '[📷 Figure (展开)]';
                                    ph.classList.remove('is-expanded');
                                }
                            } else {
                                img.classList.add('expanded');
                                if (ph) {
                                    ph.innerText = ph.getAttribute('data-close-text') || '[📷 Figure (收起)]';
                                    ph.classList.add('is-expanded');
                                }
                            }
                        }
                    }

                    var docReadFloors = new Set();
                    window.applyDocRead = function(floors) {
                        floors.forEach(function(floorNum) {
                            docReadFloors.add(Number(floorNum));
                            var dot = document.querySelector('#dot-floor-' + floorNum + ', [data-floor="' + floorNum + '"]');
                            if (dot) { dot.classList.add('read'); dot.style.display = 'none'; }
                        });
                        var page = window.linuxDoPage;
                        if (page && page.unreadFloor) {
                            while (docReadFloors.has(Number(page.unreadFloor))) page.unreadFloor++;
                            if (page.unreadFloor > (window.linuxDoPagination ? window.linuxDoPagination.lastFloor() : page.highest)) page.unreadFloor = null;
                            if (window.refreshDocReaderTools) window.refreshDocReaderTools();
                        }
                    };
                    window.markFloorRead = function(topicId, floorNum) {
                        window.applyDocRead([floorNum]);
                        if (window.intellijBridge && window.intellijBridge.reportPostRead) {
                            window.intellijBridge.reportPostRead(topicId, floorNum);
                        }
                    };

                    var readingScrollSequence = 0;
                    addEventListener('scroll', function() { readingScrollSequence++; }, {passive:true});
                    window.sampleDocReading = function() {
                        var floors = [], bodyVisible = false;
                        // The host checks selected editor, visible body and foreground IDE.
                        // OSR input focus can remain in the topic list on the first opening.
                        if (document.visibilityState === 'visible') {
                            var navigation = document.querySelector('.topic-navigation');
                            var readingBottom = navigation ? Math.min(window.innerHeight, navigation.getBoundingClientRect().top) : window.innerHeight;
                            document.querySelectorAll('.post-content').forEach(function(el) {
                                var r = el.getBoundingClientRect();
                                var visible = Math.min(r.bottom, readingBottom) - Math.max(r.top, 0);
                                if (visible > 0 && r.right > 0 && r.left < window.innerWidth) bodyVisible = true;
                                if (visible >= Math.min(r.height, readingBottom) * 0.5 && r.right > 0 && r.left < window.innerWidth) {
                                    floors.push(parseInt(el.closest('.post-entry').getAttribute('data-post-number'), 10));
                                }
                            });
                        }
                        if (window.intellijBridge) window.intellijBridge.readingSample(floors, readingScrollSequence, bodyVisible);
                    };
                    window.observeDocPosts = function() {};

                    // --- Image Lightbox Component ---
                    var lbCurrentSrc = '';
                    var lbScale = 1.0;
                    var lbTranslateX = 0;
                    var lbTranslateY = 0;
                    var lbIsDragging = false;
                    function normalizeDocUrl(url) {
                        if (!url) return '';
                        var u = ('' + url).trim();
                        if (!u) return '';
                        if (u.indexOf('http://') === 0 || u.indexOf('https://') === 0) {
                            return u;
                        }
                        if (u.indexOf('//') === 0) {
                            return 'https:' + u;
                        }
                        if (u.indexOf('/') === 0) {
                            return 'https://linux.do' + u;
                        }
                        return 'https://linux.do/' + u;
                    }

                    function openLightbox(src, title, e, fallbackSrc) {
                        if (e) {
                            if (e.stopPropagation) e.stopPropagation();
                            if (e.preventDefault) e.preventDefault();
                        }
                        var primaryUrl = normalizeDocUrl(src || fallbackSrc);
                        var fallbackUrl = normalizeDocUrl(fallbackSrc);
                        if (!primaryUrl) return;

                        lbCurrentSrc = primaryUrl;
                        lbScale = 1.0;
                        lbTranslateX = 0;
                        lbTranslateY = 0;

                        var overlay = document.getElementById('img-lightbox-overlay');
                        var img = document.getElementById('img-lb-img');
                        if (img) { img.setAttribute('data-orig-src', primaryUrl); img.setAttribute('data-thumb-src', fallbackUrl); }
                        var titleEl = document.getElementById('img-lb-title');
                        var zoomEl = document.getElementById('img-lb-zoom');

                        if (titleEl) {
                            var cleanName = (title || primaryUrl.split('/').pop().split('?')[0] || 'Image Viewer').trim();
                            titleEl.innerText = cleanName;
                        }
                        if (zoomEl) {
                            zoomEl.innerText = '100%';
                        }
                        if (img) {
                            img.onerror = function() {
                                if (fallbackUrl && img.src !== fallbackUrl) {
                                    img.src = fallbackUrl;
                                    lbCurrentSrc = fallbackUrl;
                                }
                            };
                            img.src = primaryUrl;
                            img.style.transform = 'translate(0px, 0px) scale(1)';
                        }
                        if (overlay) {
                            overlay.classList.add('active');
                        }
                    }

                    function closeLightbox() {
                        var overlay = document.getElementById('img-lightbox-overlay');
                        var img = document.getElementById('img-lb-img');
                        if (overlay) {
                            overlay.classList.remove('active');
                        }
                        if (img) {
                            img.onerror = null;
                            img.src = '';
                        }
                        lbCurrentSrc = '';
                        lbScale = 1.0;
                        lbTranslateX = 0;
                        lbTranslateY = 0;
                    }

                    function updateLbTransform() {
                        var img = document.getElementById('img-lb-img');
                        var zoomEl = document.getElementById('img-lb-zoom');
                        if (img) {
                            img.style.transform = 'translate(' + lbTranslateX + 'px, ' + lbTranslateY + 'px) scale(' + lbScale + ')';
                        }
                        if (zoomEl) {
                            zoomEl.innerText = Math.round(lbScale * 100) + '%';
                        }
                    }

                    function lbZoomIn() {
                        lbScale = Math.min(lbScale * 1.25, 5.0);
                        updateLbTransform();
                    }

                    function lbZoomOut() {
                        lbScale = Math.max(lbScale / 1.25, 0.25);
                        updateLbTransform();
                    }

                    function lbResetZoom() {
                        lbScale = 1.0;
                        lbTranslateX = 0;
                        lbTranslateY = 0;
                        updateLbTransform();
                    }

                    function lbFitScreen() {
                        lbScale = 1.0;
                        lbTranslateX = 0;
                        lbTranslateY = 0;
                        updateLbTransform();
                    }

                    function lbOpenExternal() {
                        if (lbCurrentSrc && window.intellijBridge && window.intellijBridge.handleLinkClick) {
                            window.intellijBridge.handleLinkClick(lbCurrentSrc);
                        }
                    }

                    function setupLightboxEvents() {
                        var body = document.getElementById('img-lb-body');
                        var img = document.getElementById('img-lb-img');

                        if (body) {
                            body.addEventListener('mousedown', function(e) {
                                if (e.target === img || e.target === body) {
                                    lbIsDragging = true;
                                    lbStartX = e.clientX - lbTranslateX;
                                    lbStartY = e.clientY - lbTranslateY;
                                    body.classList.add('grabbing');
                                    e.preventDefault();
                                }
                            });

                            window.addEventListener('mousemove', function(e) {
                                if (!lbIsDragging) return;
                                lbTranslateX = e.clientX - lbStartX;
                                lbTranslateY = e.clientY - lbStartY;
                                updateLbTransform();
                            });

                            window.addEventListener('mouseup', function() {
                                if (lbIsDragging) {
                                    lbIsDragging = false;
                                    if (body) body.classList.remove('grabbing');
                                }
                            });

                            body.addEventListener('wheel', function(e) {
                                var overlay = document.getElementById('img-lightbox-overlay');
                                if (overlay && overlay.classList.contains('active')) {
                                    e.preventDefault();
                                    if (e.deltaY < 0) {
                                        lbZoomIn();
                                    } else {
                                        lbZoomOut();
                                    }
                                }
                            }, { passive: false });
                        }

                        if (img) {
                            img.addEventListener('dblclick', function(e) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (lbScale !== 1.0) {
                                    lbResetZoom();
                                } else {
                                    lbScale = 2.0;
                                    updateLbTransform();
                                }
                            });
                        }

                        window.addEventListener('keydown', function(e) {
                            if (e.ctrlKey || e.altKey || e.metaKey || e.target.closest('input,textarea,select,[contenteditable=true]')) return;
                            var overlay = document.getElementById('img-lightbox-overlay');
                            if (overlay && overlay.classList.contains('active')) {
                                if (e.key === 'Escape') {
                                    closeLightbox();
                                } else if (e.key === '+' || e.key === '=') {
                                    lbZoomIn();
                                } else if (e.key === '-' || e.key === '_') {
                                    lbZoomOut();
                                } else if (e.key === '0') {
                                    lbResetZoom();
                                }
                            }
                        });
                    }

                    function showDocToast(msg) {
                        var toast = document.getElementById('doc-toast');
                        if (!toast) {
                            toast = document.createElement('div');
                            toast.id = 'doc-toast';
                            toast.style.cssText = 'position:fixed;bottom:28px;left:50%;transform:translateX(-50%);background:var(--code-bg);color:var(--keyword);border:1px solid var(--link);padding:7px 18px;border-radius:22px;font-size:0.92em;font-weight:600;box-shadow:0 6px 20px rgba(0,0,0,0.35);z-index:999999;pointer-events:none;opacity:0;transition:opacity 0.2s ease, transform 0.2s ease;';
                            document.body.appendChild(toast);
                        }
                        toast.innerText = msg;
                        toast.style.opacity = '1';
                        toast.style.transform = 'translateX(-50%) translateY(0)';
                        clearTimeout(window._docToastTimer);
                        window._docToastTimer = setTimeout(function() {
                            if (toast) {
                                toast.style.opacity = '0';
                                toast.style.transform = 'translateX(-50%) translateY(6px)';
                            }
                        }, 2200);
                    }

                    function getTagInfo(el) {
                        var rawText = (el.innerText || '').replace(/^#+\s*/, '').trim();
                        var slug = el.getAttribute('data-slug') || '';
                        var href = el.getAttribute('href') || el.href || '';
                        if (!slug && href) {
                            if (href.indexOf('/tag/') !== -1) {
                                slug = href.split('/tag/')[1].split('/')[0].split('?')[0];
                            } else if (href.indexOf('/c/') !== -1) {
                                slug = href.split('/c/')[1].split('/')[0].split('?')[0];
                            }
                            try { slug = decodeURIComponent(slug); } catch(e) {}
                        }
                        var isCat = el.getAttribute('data-type') === 'category' ||
                                    el.classList.contains('badge-category') ||
                                    el.classList.contains('badge-wrapper') ||
                                    href.indexOf('/c/') !== -1;
                        var name = (slug || rawText).toLowerCase();

                        // Default icon and badge color
                        var icon = isCat ? 'square-full' : 'tag';
                        var color = '';

                        if (name.indexOf('精华') !== -1 || name.indexOf('神帖') !== -1) {
                            icon = 'thumbs-up';
                            color = '#00aeff';
                        } else if (name.indexOf('原创') !== -1) {
                            icon = 'pen-nib';
                            color = '#e5c07b';
                        } else if (name.indexOf('集中') !== -1) {
                            icon = 'layer-group';
                            color = '#98c379';
                        } else if (name.indexOf('公告') !== -1 || name.indexOf('官方') !== -1) {
                            icon = 'bullhorn';
                            color = '#e06c75';
                        } else if (name.indexOf('求助') !== -1 || name.indexOf('问答') !== -1) {
                            icon = 'question-circle';
                            color = '#61afef';
                        } else if (name.indexOf('福利') !== -1 || name.indexOf('抽奖') !== -1 || name.indexOf('羊毛') !== -1) {
                            icon = 'gift';
                            color = '#e5c07b';
                        } else if (name.indexOf('精选') !== -1 || name.indexOf('推荐') !== -1) {
                            icon = 'star';
                            color = '#e5c07b';
                        } else if (name.indexOf('火') !== -1 || name.indexOf('热') !== -1) {
                            icon = 'fire';
                            color = '#ff6b6b';
                        } else if (name.indexOf('快讯') !== -1) {
                            icon = 'bolt';
                            color = '#e5c07b';
                        } else if (name.indexOf('教程') !== -1 || name.indexOf('指南') !== -1 || name.indexOf('文档') !== -1) {
                            icon = 'book';
                            color = '#abb2bf';
                        } else if (name.indexOf('资源') !== -1 || name.indexOf('分享') !== -1) {
                            icon = 'share-alt';
                            color = '#98c379';
                        } else if (name.indexOf('开发') !== -1 || name.indexOf('代码') !== -1) {
                            icon = 'code';
                            color = '#61afef';
                        }

                        return { isCat: isCat, icon: icon, color: color, text: rawText };
                    }

                    function fixTagIcons() {
                        var tags = document.querySelectorAll('.hashtag-cooked, .hashtag, [class*="hashtag"], .discourse-tag, a[href*="/tag/"]');
                        tags.forEach(function(el) {
                            var info = getTagInfo(el);
                            var colorStyle = info.color ? ' style="color:' + info.color + ' !important; fill:' + info.color + ' !important;"' : '';
                            var svgHtml = '<svg class="d-icon d-icon-' + info.icon + ' svg-icon"' + colorStyle + '><use href="#' + info.icon + '"></use></svg>';

                            var placeholder = el.querySelector('.hashtag-icon-placeholder');
                            if (placeholder) {
                                placeholder.innerHTML = svgHtml;
                            } else {
                                var existingSvg = el.querySelector('svg');
                                var existingImg = el.querySelector('img');
                                if (existingSvg) {
                                    existingSvg.outerHTML = svgHtml;
                                } else if (!existingImg) {
                                    var p = document.createElement('span');
                                    p.className = 'hashtag-icon-placeholder';
                                    p.innerHTML = svgHtml;
                                    el.insertBefore(p, el.firstChild);
                                }
                            }
                        });
                    }

                    if (document.readyState === 'loading') {
                        document.addEventListener('DOMContentLoaded', function() {
                            setupLightboxEvents();
                            fixTagIcons();
                        });
                    } else {
                        setupLightboxEvents();
                        fixTagIcons();
                    }

                    // Gracefully hide broken tag/badge icons so they don't leave broken image outlines
                    window.addEventListener('error', function(e) {
                        if (e.target && e.target.tagName === 'IMG') {
                            var img = e.target;
                            if (img.classList.contains('hashtag-icon') ||
                                img.closest('.hashtag-cooked, .hashtag, [class*="hashtag"], .discourse-tag, .badge-category, .badge-wrapper')) {
                                img.style.display = 'none';
                            }
                        }
                    }, true);

                    // Helper to identify whether an element is an actual hashtag or category link
                    function getTagOrCategoryAnchor(target) {
                        if (!target) return null;
                        // Never treat elements inside lightbox or fold image boxes as tags
                        if (target.closest('.lightbox-wrapper') || target.closest('.fold-img-box') || target.closest('.image-lightbox-overlay')) {
                            return null;
                        }
                        var a = target.closest('.hashtag-cooked, .hashtag, [class*="hashtag"], .discourse-tag, a.discourse-tag, a.badge-category, .badge-wrapper, a');
                        if (!a) return null;

                        // Check href: upload paths and image URLs are strictly NOT tags
                        var href = a.getAttribute('href') || a.href || '';
                        if (href.indexOf('/uploads/') !== -1 || /\.(png|jpe?g|gif|webp|bmp|svg)(\?.*)?$/i.test(href)) {
                            return null;
                        }

                        if (a.classList.contains('hashtag-cooked') || a.classList.contains('hashtag') ||
                            a.classList.contains('discourse-tag') || a.classList.contains('badge-category') ||
                            a.classList.contains('badge-wrapper')) {
                            return a;
                        }

                        if (href.indexOf('/tag/') !== -1) {
                            return a;
                        }

                        // Category link: must match /c/ followed by category slug/id, strictly NOT /uploads/
                        if (href.indexOf('/c/') !== -1 && href.indexOf('/uploads/') === -1) {
                            if (/\/c\/[a-zA-Z0-9_\-]+/.test(href)) {
                                return a;
                            }
                        }

                        return null;
                    }

                    // --- Global Click Event Dispatcher ---
                    document.addEventListener('click', function(e) {
                        // 1. Lightbox header / tools click
                        if (e.target.closest('.image-lightbox-header')) {
                            return;
                        }
                        var overlay = document.getElementById('img-lightbox-overlay');
                        if (overlay && overlay.classList.contains('active')) {
                            if (e.target.id === 'img-lb-body' || e.target.id === 'img-lightbox-overlay') {
                                closeLightbox();
                                e.preventDefault();
                                e.stopPropagation();
                                return;
                            }
                            if (e.target.closest('.image-lightbox-overlay')) {
                                return;
                            }
                        }

                        // 2. Click on an IMG
                        if (e.target.tagName === 'IMG') {
                            e.preventDefault();
                            e.stopPropagation();

                            // A. Decorative inline icons (emojis, avatars, inline tags)
                            if (e.target.classList.contains('emoji') ||
                                e.target.classList.contains('avatar') ||
                                e.target.classList.contains('inline-img') ||
                                e.target.classList.contains('hashtag-icon')) {
                                return;
                            }

                            // B. If img is inside an actual tag or category badge
                            var tagAnchor = getTagOrCategoryAnchor(e.target);
                            if (tagAnchor) {
                                var tagHref = tagAnchor.getAttribute('href') || tagAnchor.href || '';
                                var tagText = tagAnchor.innerText.trim();
                                var cleanText = tagText.replace(/^#+/, '').trim();
                                showDocToast('🔍 正在侧边栏检索标签: #' + (cleanText || '...'));
                                if (window.intellijBridge && window.intellijBridge.handleTagClick) {
                                    window.intellijBridge.handleTagClick(tagHref, tagText);
                                }
                                return;
                            }

                            // C. Fold mode toggle if image is not yet expanded
                            var foldBox = e.target.closest('.fold-img-box');
                            if (foldBox && !e.target.classList.contains('expanded')) {
                                toggleImg(e.target.id, e);
                                return;
                            }

                            // D. Normal post image: Open in Lightbox!
                            var parentA = e.target.closest('a');
                            var fullSrc = e.target.getAttribute('data-orig-src') || e.target.getAttribute('src') || e.target.currentSrc || e.target.src;
                            if (parentA && parentA.href) {
                                var href = parentA.getAttribute('href') || parentA.href;
                                if (/\.(png|jpe?g|gif|webp|bmp|svg)/i.test(href) || href.indexOf('/uploads/') !== -1) {
                                    fullSrc = href;
                                }
                            }
                            var fallbackSrc = e.target.getAttribute('data-thumb-src') || e.target.currentSrc || e.target.src;
                            var title = e.target.getAttribute('data-title') || e.target.getAttribute('alt') || fullSrc.split('/').pop().split('?')[0];
                            openLightbox(fullSrc, title, e, fallbackSrc);
                            return;
                        }

                        // 3. Image placeholder in fold mode
                        if (e.target.closest('.img-placeholder')) {
                            return;
                        }

                        // 4. Floor quote jump
                        var quote = e.target.closest('.quote, aside.quote');
                        var quoteControls = e.target.closest('.quote-controls, .back');
                        if (quote && quoteControls) {
                            if (window.linuxDoReaderBound) return;
                            var postNum = quote.getAttribute('data-post');
                            var quotedTopic = quote.getAttribute('data-topic');
                            if (quotedTopic && quotedTopic !== '${topic.id}' && /^\d+$/.test(quotedTopic) && /^\d+$/.test(postNum || '')) {
                                e.preventDefault(); e.stopPropagation();
                                if (window.intellijBridge && window.intellijBridge.handleLinkClick)
                                    window.intellijBridge.handleLinkClick('https://linux.do/t/' + quotedTopic + '/' + postNum);
                                return;
                            }
                            if (postNum && jumpToFloor(parseInt(postNum, 10))) {
                                e.preventDefault();
                                e.stopPropagation();
                                return;
                            }
                        }

                        // 5. Check if clicking on or inside a Tag or Category badge/anchor
                        var tagA = getTagOrCategoryAnchor(e.target);
                        if (tagA) {
                            e.preventDefault();
                            e.stopPropagation();
                            var tagHref = tagA.getAttribute('href') || tagA.href || '';
                            var tagText = tagA.innerText.trim();
                            var cleanText = tagText.replace(/^#+/, '').trim();
                            showDocToast('🔍 正在侧边栏检索标签: #' + (cleanText || '...'));
                            if (window.intellijBridge && window.intellijBridge.handleTagClick) {
                                window.intellijBridge.handleTagClick(tagHref, tagText);
                            }
                            return;
                        }

                        // 6. Generic anchor links
                        var a = e.target.closest('a');
                        if (a && a.href) {
                            // Lightbox wrapper anchor or direct image link
                            if (a.classList.contains('lightbox') ||
                                /\.(png|jpe?g|gif|webp|bmp|svg)/i.test(a.href) ||
                                a.href.indexOf('/uploads/') !== -1) {
                                e.preventDefault();
                                e.stopPropagation();
                                // Images themselves are handled above; surrounding whitespace is inert.
                                return;
                            }

                            // In-topic floor jump
                            var currentTopicId = '${topic.id}';
                            var rawHref = a.getAttribute('href') || '';
                            if (rawHref.startsWith('#content-')) {
                                var heading = document.getElementById(rawHref.substring(1)) || document.querySelector('a[name="' + rawHref.substring(1) + '"]');
                                if (heading) { e.preventDefault(); heading.scrollIntoView({behavior:'smooth'}); }
                                return;
                            }
                            var inTopicFloorMatch = a.href.match(new RegExp('/t/(?:[^/]+/)?' + currentTopicId + '/(\\d+)')) ||
                                                    rawHref.match(/^#(?:floor-|post-num-|post-)?(\\d+)$/);
                            if (inTopicFloorMatch) {
                                var floorNum = parseInt(inTopicFloorMatch[1], 10);
                                if (jumpToFloor(floorNum)) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    return;
                                }
                            }

                            var href = a.href;
                            if (!href.startsWith('javascript:') && !href.includes('#post-')) {
                                e.preventDefault();
                                e.stopPropagation();
                                if (window.intellijBridge && window.intellijBridge.handleLinkClick) {
                                    window.intellijBridge.handleLinkClick(href);
                                }
                            }
                        }
                    });
                </script>
            </head>
            <body>
                <!-- Embedded SVG Icon Sprite for Discourse Tag & Category Icons -->
                <svg xmlns="http://www.w3.org/2000/svg" style="position: absolute; width: 0; height: 0; overflow: hidden; pointer-events: none;" aria-hidden="true">
                    <symbol id="tag" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M0 252.118V48C0 21.49 21.49 0 48 0h204.118a48 48 0 0 1 33.941 14.059l211.882 211.882c18.745 18.745 18.745 49.137 0 67.882L293.824 497.941c-18.745 18.745-49.137 18.745-67.882 0L14.059 286.059A48 48 0 0 1 0 252.118zM112 64a48 48 0 1 0 48 48 48 48 0 0 0-48-48z"/>
                    </symbol>
                    <symbol id="d-icon-tag" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M0 252.118V48C0 21.49 21.49 0 48 0h204.118a48 48 0 0 1 33.941 14.059l211.882 211.882c18.745 18.745 18.745 49.137 0 67.882L293.824 497.941c-18.745 18.745-49.137 18.745-67.882 0L14.059 286.059A48 48 0 0 1 0 252.118zM112 64a48 48 0 1 0 48 48 48 48 0 0 0-48-48z"/>
                    </symbol>
                    <symbol id="square-full" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M0 0h512v512H0z"/>
                    </symbol>
                    <symbol id="d-icon-square-full" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M0 0h512v512H0z"/>
                    </symbol>
                    <symbol id="hashtag" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M181.3 32.4c17.4 2.9 29.2 19.4 26.3 36.8L192.8 160h117.4l14.8-88.9c2.9-17.4 19.4-29.2 36.8-26.3s29.2 19.4 26.3 36.8L373.2 160H416c17.7 0 32 14.3 32 32s-14.3 32-32 32h-48.4l-16 96H384c17.7 0 32 14.3 32 32s-14.3 32-32 32h-37.2l-14.8 88.9c-2.9 17.4-19.4 29.2-36.8 26.3s-29.2-19.4-26.3-36.8L307.2 384H189.8l-14.8 88.9c-2.9 17.4-19.4 29.2-36.8 26.3s-29.2-19.4-26.3-36.8L126.8 384H80c-17.7 0-32-14.3-32-32s14.3-32 32-32h41.6l16-96H80c-17.7 0-32-14.3-32-32s14.3-32 32-32h32.4l14.8-88.9c2.9-17.4 19.4-29.2 36.8-26.3zM184.4 224l-16 96h117.4l16-96H184.4z"/>
                    </symbol>
                    <symbol id="d-icon-hashtag" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M181.3 32.4c17.4 2.9 29.2 19.4 26.3 36.8L192.8 160h117.4l14.8-88.9c2.9-17.4 19.4-29.2 36.8-26.3s29.2 19.4 26.3 36.8L373.2 160H416c17.7 0 32 14.3 32 32s-14.3 32-32 32h-48.4l-16 96H384c17.7 0 32 14.3 32 32s-14.3 32-32 32h-37.2l-14.8 88.9c-2.9 17.4-19.4 29.2-36.8 26.3s-29.2-19.4-26.3-36.8L307.2 384H189.8l-14.8 88.9c-2.9 17.4-19.4 29.2-36.8 26.3s-29.2-19.4-26.3-36.8L126.8 384H80c-17.7 0-32-14.3-32-32s14.3-32 32-32h41.6l16-96H80c-17.7 0-32-14.3-32-32s14.3-32 32-32h32.4l14.8-88.9c2.9-17.4 19.4-29.2 36.8-26.3zM184.4 224l-16 96h117.4l16-96H184.4z"/>
                    </symbol>
                    <symbol id="folder" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M64 480h384c35.3 0 64-28.7 64-64V160c0-35.3-28.7-64-64-64H288l-48-48H64C28.7 48 0 76.7 0 112v304c0 35.3 28.7 64 64 64z"/>
                    </symbol>
                    <symbol id="thumbs-up" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M104 224H40c-22.1 0-40 17.9-40 40v208c0 22.1 17.9 40 40 40h64c22.1 0 40-17.9 40-40V264c0-22.1-17.9-40-40-40zm400 34.6c0-20.9-14-38.6-33.1-43.8 6.4-10.2 10.1-22.2 10.1-35.3 0-29.3-19.8-54.3-46.9-62.3C436.7 94.4 416 80 392 80h-96.6c6.2-19.2 10.6-40.8 10.6-64 0-8.8-7.2-16-16-16h-16c-8.8 0-16 7.2-16 16 0 68.3-51.4 135.2-108.6 156.4-9.3 3.5-15.4 12.4-15.4 22.4V464c0 13.3 10.7 24 24 24h216.2c27.1 0 50.7-18.1 57.8-44.2l32-117.3c1.3-4.7 2-9.6 2-14.5v-73.4z"/>
                    </symbol>
                    <symbol id="d-icon-thumbs-up" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M104 224H40c-22.1 0-40 17.9-40 40v208c0 22.1 17.9 40 40 40h64c22.1 0 40-17.9 40-40V264c0-22.1-17.9-40-40-40zm400 34.6c0-20.9-14-38.6-33.1-43.8 6.4-10.2 10.1-22.2 10.1-35.3 0-29.3-19.8-54.3-46.9-62.3C436.7 94.4 416 80 392 80h-96.6c6.2-19.2 10.6-40.8 10.6-64 0-8.8-7.2-16-16-16h-16c-8.8 0-16 7.2-16 16 0 68.3-51.4 135.2-108.6 156.4-9.3 3.5-15.4 12.4-15.4 22.4V464c0 13.3 10.7 24 24 24h216.2c27.1 0 50.7-18.1 57.8-44.2l32-117.3c1.3-4.7 2-9.6 2-14.5v-73.4z"/>
                    </symbol>
                    <symbol id="pen-nib" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M368.4 18.4L252.3 74.1 349.9 171.7l55.7-116.1c9.4-19.6 3.1-43.3-15.1-55.7-9.4-6.4-20.7-9.5-32.1-9.5zM224 102.4L35.4 291c-12.3 12.3-19.4 28.9-19.4 46.3V480c0 17.7 14.3 32 32 32h142.7c17.4 0 34-7.1 46.3-19.4L425.6 304 224 102.4zM96 448c-17.7 0-32-14.3-32-32s14.3-32 32-32 32 14.3 32 32-14.3 32-32 32z"/>
                    </symbol>
                    <symbol id="d-icon-pen-nib" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M368.4 18.4L252.3 74.1 349.9 171.7l55.7-116.1c9.4-19.6 3.1-43.3-15.1-55.7-9.4-6.4-20.7-9.5-32.1-9.5zM224 102.4L35.4 291c-12.3 12.3-19.4 28.9-19.4 46.3V480c0 17.7 14.3 32 32 32h142.7c17.4 0 34-7.1 46.3-19.4L425.6 304 224 102.4zM96 448c-17.7 0-32-14.3-32-32s14.3-32 32-32 32 14.3 32 32-14.3 32-32 32z"/>
                    </symbol>
                    <symbol id="layer-group" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M12.41 125.59L244.4 270.39c7.22 4.49 16 4.49 23.21 0l231.99-144.8c9.06-5.65 9.06-18.9 0-24.55L267.61 16.24c-7.22-4.49-16-4.49-23.21 0L12.41 101.04c-9.06 5.65-9.06 18.9 0 24.55zM267.61 283.44l119.44-74.56 57.36 35.8c9.06 5.65 9.06 18.9 0 24.55l-176.8 110.36c-7.22 4.49-16 4.49-23.21 0l-176.8-110.36c-9.06-5.65-9.06-18.9 0-24.55l57.36-35.8 119.44 74.56c7.22 4.49 16 4.49 23.21 0zm0 106.67l119.44-74.56 57.36 35.8c9.06 5.65 9.06 18.9 0 24.55l-176.8 110.36c-7.22 4.49-16 4.49-23.21 0l-176.8-110.36c-9.06-5.65-9.06-18.9 0-24.55l57.36-35.8 119.44 74.56c7.22 4.49 16 4.49 23.21 0z"/>
                    </symbol>
                    <symbol id="d-icon-layer-group" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M12.41 125.59L244.4 270.39c7.22 4.49 16 4.49 23.21 0l231.99-144.8c9.06-5.65 9.06-18.9 0-24.55L267.61 16.24c-7.22-4.49-16-4.49-23.21 0L12.41 101.04c-9.06 5.65-9.06 18.9 0 24.55zM267.61 283.44l119.44-74.56 57.36 35.8c9.06 5.65 9.06 18.9 0 24.55l-176.8 110.36c-7.22 4.49-16 4.49-23.21 0l-176.8-110.36c-9.06-5.65-9.06-18.9 0-24.55l57.36-35.8 119.44 74.56c7.22 4.49 16 4.49 23.21 0zm0 106.67l119.44-74.56 57.36 35.8c9.06 5.65 9.06 18.9 0 24.55l-176.8 110.36c-7.22 4.49-16 4.49-23.21 0l-176.8-110.36c-9.06-5.65-9.06-18.9 0-24.55l57.36-35.8 119.44 74.56c7.22 4.49 16 4.49 23.21 0z"/>
                    </symbol>
                    <symbol id="bullhorn" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M511.1 63.1c-4.4-4.8-11.4-6.4-17.6-4.1L384 100.8V32c0-17.7-14.3-32-32-32H32C14.3 0 0 14.3 0 32v224c0 17.7 14.3 32 32 32h64v128c0 53 43 96 96 96h32c17.7 0 32-14.3 32-32v-32h96l109.5 41.8c6.2 2.4 13.2.7 17.6-4.1 4.4-4.8 5.7-11.7 3.3-17.8l-48-120 48-120c2.4-6.1 1.1-13-3.3-17.8z"/>
                    </symbol>
                    <symbol id="d-icon-bullhorn" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M511.1 63.1c-4.4-4.8-11.4-6.4-17.6-4.1L384 100.8V32c0-17.7-14.3-32-32-32H32C14.3 0 0 14.3 0 32v224c0 17.7 14.3 32 32 32h64v128c0 53 43 96 96 96h32c17.7 0 32-14.3 32-32v-32h96l109.5 41.8c6.2 2.4 13.2.7 17.6-4.1 4.4-4.8 5.7-11.7 3.3-17.8l-48-120 48-120c2.4-6.1 1.1-13-3.3-17.8z"/>
                    </symbol>
                    <symbol id="question-circle" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M256 8C119.043 8 8 119.083 8 256c0 136.997 111.043 248 248 248s248-111.003 248-248C504 119.083 392.957 8 256 8zm0 448c-110.532 0-200-89.431-200-200 0-110.495 89.472-200 200-200 110.491 0 200 89.471 200 200 0 110.53-89.468 200-200 200zm107.244-255.2c0 67.052-72.421 68.084-72.421 92.863v4.237c0 6.627-5.373 12-12 12h-45.647c-6.627 0-12-5.373-12-12v-8.659c0-35.745 57.379-39.275 57.379-88.441 0-23.771-15.011-36.216-36.568-36.216-24.819 0-38.318 16.592-38.318 41.691 0 6.627-5.373 12-12 12h-45.647c-6.627 0-12-5.373-12-12 0-53.861 38.307-88.441 108.965-88.441 64.908 0 108.257 32.33 108.257 86.966zM256 408c-19.882 0-36-16.118-36-36s16.118-36 36-36 36 16.118 36 36-16.118 36-36 36z"/>
                    </symbol>
                    <symbol id="d-icon-question-circle" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M256 8C119.043 8 8 119.083 8 256c0 136.997 111.043 248 248 248s248-111.003 248-248C504 119.083 392.957 8 256 8zm0 448c-110.532 0-200-89.431-200-200 0-110.495 89.472-200 200-200 110.491 0 200 89.471 200 200 0 110.53-89.468 200-200 200zm107.244-255.2c0 67.052-72.421 68.084-72.421 92.863v4.237c0 6.627-5.373 12-12 12h-45.647c-6.627 0-12-5.373-12-12v-8.659c0-35.745 57.379-39.275 57.379-88.441 0-23.771-15.011-36.216-36.568-36.216-24.819 0-38.318 16.592-38.318 41.691 0 6.627-5.373 12-12 12h-45.647c-6.627 0-12-5.373-12-12 0-53.861 38.307-88.441 108.965-88.441 64.908 0 108.257 32.33 108.257 86.966zM256 408c-19.882 0-36-16.118-36-36s16.118-36 36-36 36 16.118 36 36-16.118 36-36 36z"/>
                    </symbol>
                    <symbol id="gift" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M464 144h-56.7c15.8-21.6 24.7-47.7 24.7-75.1C432 30.9 401.1 0 363.1 0c-35.3 0-66.2 24.7-83.3 58.7L256 105.9l-23.8-47.2C215.1 24.7 184.2 0 148.9 0 110.9 0 80 30.9 80 68.9c0 27.4 8.9 53.5 24.7 75.1H48c-26.5 0-48 21.5-48 48v64c0 8.8 7.2 16 16 16h16v192c0 26.5 21.5 48 48 48h352c26.5 0 48-21.5 48-48V272h16c8.8 0 16-7.2 16-16v-64c0-26.5-21.5-48-48-48zM363.1 48c11.6 0 21.1 9.5 21.1 21.1 0 25.8-25.7 65.5-62.6 74.9h-36.8l20.4-40.4C316.6 62.9 338.4 48 363.1 48zM148.9 48c24.7 0 46.5 14.9 57.9 55.6l20.4 40.4h-36.8C153.5 134.6 127.8 94.9 127.8 69.1c0-11.6 9.5-21.1 21.1-21.1zM232 464H80c-8.8 0-16-7.2-16-16V272h168v192zm0-240H48v-32c0-8.8 7.2-16 16-16h168v48zm216 224c0 8.8-7.2 16-16 16H280V272h168v192zm16-240h-184v-48h168c8.8 0 16 7.2 16 16v32z"/>
                    </symbol>
                    <symbol id="d-icon-gift" viewBox="0 0 512 512">
                        <path fill="currentColor" d="M464 144h-56.7c15.8-21.6 24.7-47.7 24.7-75.1C432 30.9 401.1 0 363.1 0c-35.3 0-66.2 24.7-83.3 58.7L256 105.9l-23.8-47.2C215.1 24.7 184.2 0 148.9 0 110.9 0 80 30.9 80 68.9c0 27.4 8.9 53.5 24.7 75.1H48c-26.5 0-48 21.5-48 48v64c0 8.8 7.2 16 16 16h16v192c0 26.5 21.5 48 48 48h352c26.5 0 48-21.5 48-48V272h16c8.8 0 16-7.2 16-16v-64c0-26.5-21.5-48-48-48zM363.1 48c11.6 0 21.1 9.5 21.1 21.1 0 25.8-25.7 65.5-62.6 74.9h-36.8l20.4-40.4C316.6 62.9 338.4 48 363.1 48zM148.9 48c24.7 0 46.5 14.9 57.9 55.6l20.4 40.4h-36.8C153.5 134.6 127.8 94.9 127.8 69.1c0-11.6 9.5-21.1 21.1-21.1zM232 464H80c-8.8 0-16-7.2-16-16V272h168v192zm0-240H48v-32c0-8.8 7.2-16 16-16h168v48zm216 224c0 8.8-7.2 16-16 16H280V272h168v192zm16-240h-184v-48h168c8.8 0 16 7.2 16 16v32z"/>
                    </symbol>
                    <symbol id="star" viewBox="0 0 576 512">
                        <path fill="currentColor" d="M259.3 17.8L194 150.2 47.9 171.5c-26.2 3.8-36.7 36.1-17.7 54.6l105.7 103-25 145.5c-4.5 26.3 23.2 46 46.4 33.7L288 439.6l130.7 68.7c23.2 12.2 50.9-7.4 46.4-33.7l-25-145.5 105.7-103c19-18.5 8.5-50.8-17.7-54.6L382 150.2 316.7 17.8c-11.7-23.6-45.6-23.9-57.4 0z"/>
                    </symbol>
                    <symbol id="d-icon-star" viewBox="0 0 576 512">
                        <path fill="currentColor" d="M259.3 17.8L194 150.2 47.9 171.5c-26.2 3.8-36.7 36.1-17.7 54.6l105.7 103-25 145.5c-4.5 26.3 23.2 46 46.4 33.7L288 439.6l130.7 68.7c23.2 12.2 50.9-7.4 46.4-33.7l-25-145.5 105.7-103c19-18.5 8.5-50.8-17.7-54.6L382 150.2 316.7 17.8c-11.7-23.6-45.6-23.9-57.4 0z"/>
                    </symbol>
                    <symbol id="fire" viewBox="0 0 384 512">
                        <path fill="currentColor" d="M216 23.86c0-23.8-30.65-32.77-44.15-13.04C48 191.85 224 200 224 288c0 35.63-29.11 64.46-64.85 63.99-35.17-.45-63.15-29.77-63.15-64.94 0-22.19 11.07-42.53 28.53-54.91 14.54-10.31 9.4-32.96-8.58-36.21C53.79 184.28 0 241.6 0 308c0 102.12 80.97 185.95 182.74 187.97C290.72 498.11 384 413.9 384 308c0-142.36-168-200.63-168-284.14z"/>
                    </symbol>
                    <symbol id="d-icon-fire" viewBox="0 0 384 512">
                        <path fill="currentColor" d="M216 23.86c0-23.8-30.65-32.77-44.15-13.04C48 191.85 224 200 224 288c0 35.63-29.11 64.46-64.85 63.99-35.17-.45-63.15-29.77-63.15-64.94 0-22.19 11.07-42.53 28.53-54.91 14.54-10.31 9.4-32.96-8.58-36.21C53.79 184.28 0 241.6 0 308c0 102.12 80.97 185.95 182.74 187.97C290.72 498.11 384 413.9 384 308c0-142.36-168-200.63-168-284.14z"/>
                    </symbol>
                    <symbol id="share-alt" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M352 320c-22.608 0-43.387 7.819-59.79 20.895l-102.486-64.054a96.551 96.551 0 0 0 0-41.681l102.486-64.055C308.613 183.181 329.392 191 352 191c52.928 0 96-43.072 96-96s-43.072-96-96-96-96 43.072-96 96c0 7.201 1.054 14.076 2.626 20.817L155.938 179.99c-16.452-13.125-37.337-20.99-60.038-20.99-52.928 0-96 43.072-96 96s43.072 96 96 96c22.701 0 43.586-7.865 60.038-20.99l102.688 64.173c-1.572 6.741-2.626 13.616-2.626 20.817 0 52.928 43.072 96 96 96s96-43.072 96-96-43.072-96-96-96z"/>
                    </symbol>
                    <symbol id="d-icon-share-alt" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M352 320c-22.608 0-43.387 7.819-59.79 20.895l-102.486-64.054a96.551 96.551 0 0 0 0-41.681l102.486-64.055C308.613 183.181 329.392 191 352 191c52.928 0 96-43.072 96-96s-43.072-96-96-96-96 43.072-96 96c0 7.201 1.054 14.076 2.626 20.817L155.938 179.99c-16.452-13.125-37.337-20.99-60.038-20.99-52.928 0-96 43.072-96 96s43.072 96 96 96c22.701 0 43.586-7.865 60.038-20.99l102.688 64.173c-1.572 6.741-2.626 13.616-2.626 20.817 0 52.928 43.072 96 96 96s96-43.072 96-96-43.072-96-96-96z"/>
                    </symbol>
                    <symbol id="book" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M448 360V40c0-22.09-17.91-40-40-40H96C42.98 0 0 42.98 0 96v320c0 53.02 42.98 96 96 96h312c22.09 0 40-17.91 40-40v-16c0-6.72-2.17-13.12-6.1-18.42 12.16-10.42 20.1-25.75 20.1-43.58zm-96 64H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64zm0-128H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64zm0-128H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64z"/>
                    </symbol>
                    <symbol id="d-icon-book" viewBox="0 0 448 512">
                        <path fill="currentColor" d="M448 360V40c0-22.09-17.91-40-40-40H96C42.98 0 0 42.98 0 96v320c0 53.02 42.98 96 96 96h312c22.09 0 40-17.91 40-40v-16c0-6.72-2.17-13.12-6.1-18.42 12.16-10.42 20.1-25.75 20.1-43.58zm-96 64H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64zm0-128H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64zm0-128H96c-17.67 0-32-14.33-32-32s14.33-32 32-32h256v64z"/>
                    </symbol>
                    <symbol id="bolt" viewBox="0 0 320 512">
                        <path fill="currentColor" d="M296 160H180.6l42.6-129.8C227.2 18 217.4 0 200 0H48C34.2 0 22.3 9.4 19.3 22.9L.3 230.9C-2.4 243.2 7 256 19.6 256H136l-48 240c-2.4 12 7.1 23 19.2 23 6.4 0 12.8-3.1 16.8-8.8l192-272c7.6-10.8 1.9-26.2-10-26.2z"/>
                    </symbol>
                    <symbol id="d-icon-bolt" viewBox="0 0 320 512">
                        <path fill="currentColor" d="M296 160H180.6l42.6-129.8C227.2 18 217.4 0 200 0H48C34.2 0 22.3 9.4 19.3 22.9L.3 230.9C-2.4 243.2 7 256 19.6 256H136l-48 240c-2.4 12 7.1 23 19.2 23 6.4 0 12.8-3.1 16.8-8.8l192-272c7.6-10.8 1.9-26.2-10-26.2z"/>
                    </symbol>
                    <symbol id="code" viewBox="0 0 640 512">
                        <path fill="currentColor" d="M278.9 511.5l-61-17.7 122.3-421.4 61 17.7zm116.3-158.8l17.7 17.7 136-136-136-136-17.7 17.7 118.3 118.3zm-150.4 17.7l17.7-17.7-118.3-118.3 118.3-118.3-17.7-17.7-136 136z"/>
                    </symbol>
                    <symbol id="d-icon-code" viewBox="0 0 640 512">
                        <path fill="currentColor" d="M278.9 511.5l-61-17.7 122.3-421.4 61 17.7zm116.3-158.8l17.7 17.7 136-136-136-136-17.7 17.7 118.3 118.3zm-150.4 17.7l17.7-17.7-118.3-118.3 118.3-118.3-17.7-17.7-136 136z"/>
                    </symbol>
                </svg>
                <div class="doc-container">
                    <div class="doc-header">
                        <div class="doc-title">${escapeHtml(topic.title)}</div>
                        <div class="doc-meta-comment">$headerDoc</div>
                    </div>
                    $postsHtml
                </div>

                <!-- High-Resolution Image Lightbox Overlay -->
                <div class="image-lightbox-overlay" id="img-lightbox-overlay">
                    <div class="image-lightbox-header">
                        <div class="image-lightbox-title" id="img-lb-title">Image Viewer</div>
                        <div class="image-lightbox-tools">
                            <span class="lb-zoom-level" id="img-lb-zoom" style="color:var(--comment);margin-right:8px;">100%</span>
                            <button class="lb-btn" onclick="lbZoomOut()" title="缩小 (快捷键: -)">−</button>
                            <button class="lb-btn" onclick="lbZoomIn()" title="放大 (快捷键: +)">+</button>
                            <button class="lb-btn" onclick="lbResetZoom()" title="恢复 100% (快捷键: 0)">1:1</button>
                            <button class="lb-btn" onclick="lbFitScreen()" title="自适应窗口">⛶ 自适应</button>
                            <button class="lb-btn" onclick="copyDocImage(document.getElementById('img-lb-img'))" title="复制静态图像，动图仅复制当前帧">复制图片</button>
                            <button class="lb-btn" onclick="copyDocImageFile(document.getElementById('img-lb-img'))" title="复制原始文件，保留 GIF/WebP 动画；需粘贴到支持图片文件的应用">复制原图文件</button>
                            <button class="lb-btn" onclick="lbOpenExternal()" title="在外部系统浏览器中打开原图">↗ 浏览器</button>
                            <button class="lb-btn lb-close-btn" onclick="closeLightbox()" title="关闭 (快捷键: ESC)">✕</button>
                        </div>
                    </div>
                    <div class="image-lightbox-body" id="img-lb-body">
                        <img class="image-lightbox-img" id="img-lb-img" src="" alt="" />
                    </div>
                </div>
                <script>${ForumContent.script}
                    $enhancementsScript</script>
                <script>$paginationScript</script>
                <script>${requireNotNull(javaClass.getResource("/web/topic-reader.js")).readText()}</script>
            </body>
            </html>
        """.trimIndent()
    }

    /** Shared by initial documents and incremental replies; no page scripts or CSS are rebuilt. */
    fun buildPostFragment(
        topic: TopicDetailResponse,
        posts: List<Post>,
        settings: LinuxDoSettingsState,
        currentUsername: String? = try { LinuxDoAuthService.getInstance().currentUser?.username } catch (_: Throwable) { null }
    ): String {
        val postsHtml = StringBuilder()
        for (post in posts) {
            val author = escapeHtml(post.username)
            val authorJs = escapeHtml(com.google.gson.Gson().toJson(post.username))
            val likeCount = post.getLikeCount()
            val isLiked = post.isLiked()

            val isRead = LinuxDoReadTrackingService.getInstance().isPostRead(
                topic.id,
                post.postNumber,
                post.read,
                topic.lastReadPostNumber
            )
            val isUnread = !isRead
            val dotHtml = if (isUnread) """<span class="unread-dot" id="dot-floor-${post.postNumber}" data-floor="${post.postNumber}" onclick="event.stopPropagation(); markFloorRead(${topic.id}, ${post.postNumber});" title="未读楼层 (点击标记已读)"></span>""" else ""

            val inReplyText = if (post.replyToPostNumber != null && post.replyToPostNumber > 0) {
                """<span class="floor-reply-context">回复 <span class="floor-jump-link" data-context-floor="${post.replyToPostNumber}" title="查看 #${post.replyToPostNumber} 楼上下文">#${post.replyToPostNumber}</span></span>"""
            } else ""

            val time = """<time class="relative-time" datetime="${escapeHtml(post.createdAt ?: "")}" title="${escapeHtml(post.createdAt ?: "")}">${RelativeTime.format(post.createdAt)}</time>"""
            val floorComment = """<span class="action-link" data-reader-author="$author">@$author</span>$inReplyText$time"""

            val isMyPost = post.yours == true

            val actionItems = mutableListOf<String>()
            val moreItems = mutableListOf<String>()
            if (isMyPost || !com.lgguan.linuxdo.plugin.model.PostCapabilities.like(post, !isLiked)) {
                if (likeCount > 0) {
                    actionItems.add("""<span class="action-static" title="获赞数">♥ $likeCount</span>""")
                }
            } else {
                val likeText = if (isLiked) "已赞" else "点赞"
                val likedClass = if (isLiked) "action-link liked" else "action-link"
                val countHtml = if (likeCount > 0) "<span class=\"action-count\">$likeCount</span>" else ""
                actionItems.add("""<button type="button" class="$likedClass" title="$likeText" aria-label="$likeText" aria-pressed="$isLiked" onclick="toggleLikeUi(this, ${post.id}, ${!isLiked})">${ReaderIcons.svg("like")}$countHtml</button>""")
            }
            if (post.canBoost == true && post.boosts.isNullOrEmpty()) actionItems.add(boostEntry())
            if (com.lgguan.linuxdo.plugin.model.PostCapabilities.reply(topic)) actionItems.add("""<button type="button" class="action-link" title="回复" aria-label="回复" onclick="window.intellijBridge && window.intellijBridge.replyPost(${post.postNumber}, $authorJs)">${ReaderIcons.svg("reply")}</button>""")
            moreItems.add("""<button type="button" class="action-link" data-post-command="share" onclick="window.intellijBridge && window.intellijBridge.copyPostLink(${topic.id}, ${post.postNumber})">${ReaderIcons.svg("share")}<span>分享</span></button>""")
            fun control(action: String, label: String, enabled: Boolean, direction: String? = null) {
                if (!enabled) return
                val attribute=direction?.let { " data-direction=\"$it\"" }.orEmpty()
                moreItems.add("""<button type="button" class="action-link" data-reader-action="$action"$attribute>${ReaderIcons.svg(action)}<span>$label</span></button>""")
            }
            control("bookmark", if (post.bookmarked == true) "已收藏" else "收藏", post.bookmarked != null && !currentUsername.isNullOrBlank())
            control("edit", "编辑", post.canEdit == true)
            control("history", "历史", post.canViewEditHistory == true)
            control(if (post.canRecover == true) "recover" else "delete", if (post.canRecover == true) "恢复" else "删除", post.canRecover == true || post.canDelete == true)
            control("flag", "举报", post.actionsSummary?.any { it.id != 2 && it.canAct == true } == true)
            if (!topic.validReactions.isNullOrEmpty() || !post.reactions.isNullOrEmpty() || post.currentUserReaction != null)
                control("reaction", "表情回应", !currentUsername.isNullOrBlank() && com.lgguan.linuxdo.plugin.model.PostCapabilities.reaction(post))
            control("reactionUsers", "查看回应者", post.reactions?.any { it.count > 0 } == true)
            if (post.replyCount > 0) control("replies", "${post.replyCount} 条回复", true)
            if (post.canAcceptAnswer != null || post.canUnacceptAnswer != null || post.acceptedAnswer == true)
                control(if (post.acceptedAnswer == true) "unaccept" else "accept", if (post.acceptedAnswer == true) "✓ 已采纳" else "采纳答案", if (post.acceptedAnswer == true) post.canUnacceptAnswer == true else post.canAcceptAnswer == true)
            if (topic.isPostVoting == true && post.postNumber > 1 && post.replyToPostNumber == null) {
                val allowed = !currentUsername.isNullOrBlank() && com.lgguan.linuxdo.plugin.model.PostCapabilities.postVote(topic,post)
                control("postVote", "↑ ${post.postVotingVoteCount ?: 0}"+if(post.postVotingDirection=="up") " · 撤回赞成" else " · 赞成", allowed,"up")
                control("postVote", "↓ "+if(post.postVotingDirection=="down") "撤回反对" else "反对", allowed,"down")
            }

            if (post.acceptedAnswer == true && post.canUnacceptAnswer != true) actionItems.add("""<span class="action-static">✓ 已采纳</span>""")
            if (moreItems.isNotEmpty()) actionItems.add("""<details class="post-actions-menu"><summary class="action-link" title="更多帖子操作" aria-label="更多帖子操作">${ReaderIcons.svg("more")}</summary><div class="post-actions-menu-items">${moreItems.joinToString("\n")}</div></details>""")

            val actionsHtml = actionItems.joinToString("\n                            ")
            val avatarUrl = post.getAvatarUrl(48)
            val avatarHtml = if (!settings.hideAvatars && avatarUrl.isNotBlank()) {
                """<img class="avatar-img" src="$avatarUrl" alt="@$author" title="@$author" loading="lazy" onerror="this.style.display='none'"/>"""
            } else ""

            postsHtml.append("""
                <div class="post-entry" id="floor-${post.postNumber}" data-post-id="${post.id}" data-post-number="${post.postNumber}" data-author="$author" data-polls="${escapeHtml(com.google.gson.Gson().toJson(post.polls.orEmpty()))}" data-poll-votes="${escapeHtml(com.google.gson.Gson().toJson(post.pollsVotes))}">
                    <a id="post-${post.id}"></a>
                    <a id="post-num-${post.postNumber}"></a>
                    <div class="floor-comment-header">
                        <div class="floor-meta">
                            $avatarHtml<span class="floor-number">$floorComment</span>
                        </div>
                        <div class="floor-position"><span class="floor-label">#${post.postNumber}</span><span class="floor-read-indicator">$dotHtml</span></div>
                    </div>
                    <div class="post-content" data-source="${java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((post.cooked + "\u0000" + post.raw.orEmpty()).toByteArray()))}">
                        ${ForumContent.render(ScrollableSourceBlocks.render(post.cooked, post.raw), settings.foldImages, post.id.toString(), "https://linux.do/t/${topic.id}/${post.postNumber}")}
                    </div>
                    ${renderBoosts(post,currentUsername)}
                    <div class="floor-actions" aria-label="帖子操作">
                        $actionsHtml
                    </div>
                </div>
            """.trimIndent())
        }

        return postsHtml.toString()
    }

    fun normalizeUrl(url: String, baseUrl: String = "https://linux.do"): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${baseUrl.trimEnd('/')}$trimmed"
            else -> "${baseUrl.trimEnd('/')}/$trimmed"
        }
    }

    fun extractAttribute(tag: String, attrName: String): String? {
        val regex = Regex("""(?<=\s|^)$attrName=(?:["']([^"']*)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
        val match = regex.find(tag) ?: return null
        return match.groups[1]?.value ?: match.groups[2]?.value
    }

    fun isInlineOrSmallImage(imgTag: String, src: String): Boolean {
        val lowerTag = imgTag.lowercase()
        val lowerSrc = src.lowercase()

        // 1. Check class attribute for emoji, avatar, icon, badge, tag, logo, etc.
        val classValue = extractAttribute(lowerTag, "class") ?: ""
        val classTokens = classValue.split("""\s+""".toRegex())
        val isIconOrBadgeClass = classTokens.any { token ->
            token == "emoji" || token == "avatar" || token.contains("icon") ||
                    token.contains("badge") || token.contains("tag") || token.contains("hashtag") ||
                    token.contains("logo") || token == "inline-img" || token.contains("favicon")
        }
        if (isIconOrBadgeClass) return true

        // 2. Check URL path patterns for icons, emojis, avatars
        if (lowerSrc.contains("/emoji/") ||
            lowerSrc.contains("/avatar/") ||
            lowerSrc.contains("/user_avatar/") ||
            lowerSrc.contains("/letter_avatar/") ||
            lowerSrc.contains("site-icons") ||
            lowerSrc.contains("tag-icons") ||
            lowerSrc.contains("favicon") ||
            lowerSrc.contains("/favicon.")
        ) {
            return true
        }

        // 3. Check explicit small dimensions (e.g. width/height <= 48)
        val w = extractAttribute(lowerTag, "width")?.toIntOrNull()
        val h = extractAttribute(lowerTag, "height")?.toIntOrNull()
        if (w != null && h != null && w <= 48 && h <= 48) {
            return true
        }

        return false
    }

    fun balanceDivTags(html: String): String = org.jsoup.Jsoup.parseBodyFragment(html).apply {
        outputSettings().prettyPrint(false)
    }.body().html()

    fun processContent(cooked: String, foldImages: Boolean): String = ForumContent.render(cooked, foldImages)

    private fun boostEntry() = """<button type="button" class="action-link" data-post-command="boost" data-boost-open title="发送 Boost" aria-label="Boost">${ReaderIcons.svg("boost")}</button>"""

    private fun renderBoosts(post: Post, currentUsername: String?): String {
        val boosts = post.boosts
        if (boosts.isNullOrEmpty()) return ""
        val items = boosts.filterNotNull().mapNotNull { b ->
            val user = escapeHtml(b.getDisplayUsername())
            val safe = org.jsoup.safety.Safelist().addTags("p","span","br","img")
                .addAttributes("img","src","alt","title","class").addProtocols("img","src","https","http").preserveRelativeLinks(true)
            val content = org.jsoup.Jsoup.parseBodyFragment(org.jsoup.Jsoup.clean(b.cooked ?: escapeHtml(b.raw ?: b.content.orEmpty()),"https://linux.do/",safe)).apply {
                outputSettings().prettyPrint(false)
                select("img:not(.emoji)").remove()
                select("img").forEach { it.attr("src",normalizeUrl(it.attr("src"))) }
            }.body().html()
            val avatar = b.user?.avatarTemplate?.replace("{size}","24")?.takeIf { it.isNotBlank() }?.let {
                """<img class="boost-avatar" src="${escapeHtml(normalizeUrl(it))}" alt="" loading="lazy">"""
            } ?: """<span class="boost-avatar boost-initial">${escapeHtml(b.getDisplayUsername().take(1))}</span>"""
            val delete = if (b.canDelete==true && b.id!=null && currentUsername!=null && b.getDisplayUsername().equals(currentUsername,true))
                """<button type="button" class="action-link boost-delete" data-boost-delete="${b.id}" title="撤回自己的 Boost" aria-label="撤回自己的 Boost">×</button>""" else ""
            if (content.isBlank()) null else """<span class="boost-bubble" data-boost-id="${b.id ?: ""}"><button type="button" class="boost-user" data-reader-author="$user" title="@$user" aria-label="查看 @$user 的资料">$avatar</button><span class="boost-content">$content</span>$delete</span>"""
        }.joinToString("")
        if (items.isBlank()) return ""
        return "<div class=\"boost-container\">$items${if(post.canBoost==true)boostEntry() else ""}</div>"
    }

    internal fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
