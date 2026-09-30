(function () {
    'use strict';
    function relativeTime(value, now) {
        const timestamp = Date.parse(value);
        if (!Number.isFinite(timestamp)) return '时间未知';
        const seconds = Math.max(0, Math.floor((now - timestamp) / 1000));
        if (seconds >= 604800) {
            const date = new Date(timestamp);
            const pad = value => String(value).padStart(2, '0');
            return date.getFullYear() + '-' + pad(date.getMonth()+1) + '-' + pad(date.getDate()) + ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
        }
        if (seconds < 60) return '刚刚';
        for (const [unit, limit, label] of [[60, 3600, '分钟前'], [3600, 86400, '个小时前'],
            [86400, 2592000, '天前'], [2592000, 31536000, '个月前'], [31536000, Infinity, '年前']]) {
            if (seconds < limit) return Math.floor(seconds / unit) + label;
        }
    }
    function updateTimes(root) {
        root.querySelectorAll('time.relative-time').forEach(el => {
            const text = relativeTime(el.dateTime, Date.now());
            if (el.textContent !== text) el.textContent = text;
        });
    }
    function mediaUrl(raw) {
        if (!raw) return '';
        try {
            const url = new URL(raw, document.baseURI);
            return /^https?:$/.test(url.protocol) ? url.href : '';
        } catch (_) { return ''; }
    }
    let copySequence = 0;
    function imagePng(img) {
        if (!img.naturalWidth || !img.naturalHeight) throw Error('图片尚未加载');
        if (img.naturalWidth * img.naturalHeight > 16000000) throw Error('图片尺寸过大');
        const canvas = document.createElement('canvas');
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
        canvas.getContext('2d').drawImage(img, 0, 0);
        const png = canvas.toDataURL('image/png');
        if (png.length > 32 * 1024 * 1024) throw Error('图片超过 24 MB');
        return png;
    }
    window.decodeDocClipboardImage = function (url, base64, requestId) {
        if (String(copySequence) !== requestId) return;
        const img = new Image();
        img.onload = function () {
            if (String(copySequence) !== requestId) return;
            try { window.intellijBridge.copyImage(url, imagePng(img), requestId); }
            catch (error) { showDocToast('图片复制失败：' + error.message); }
        };
        img.onerror = () => showDocToast('下载内容不是可解码的图片，请在浏览器中打开检查');
        img.src = 'data:image/png;base64,' + base64;
    };
    window.copyDocImage = function (img) {
        if (!img) return;
        if (!window.intellijBridge?.copyImage) { showDocToast('图片复制尚未就绪，请重试'); return; }
        const raw = img.currentSrc || img.src;
        const url = /^(data:image\/|blob:)/.test(raw) ? raw : mediaUrl(raw);
        if (!url) { showDocToast('图片尚未加载，请稍后重试'); return; }
        const requestId = String(++copySequence);
        showDocToast('正在复制图片…');
        try { window.intellijBridge.copyImage(url, imagePng(img), requestId); }
        catch (error) {
            if (/^https?:/.test(url)) window.intellijBridge.copyImage(url, '', requestId);
            else showDocToast('图片复制失败：' + error.message);
        }
    };
    window.copyDocImageFile = function (img) {
        if (!img || !window.intellijBridge?.copyImageFile) { showDocToast('原图复制尚未就绪，请重试'); return; }
        const src = img.getAttribute('data-orig-src') || img.closest('a.lightbox')?.href || img.currentSrc || img.src;
        const url = mediaUrl(src);
        if (!url) { showDocToast('此图片没有可下载的原图地址'); return; }
        showDocToast('正在复制原图文件…');
        window.intellijBridge.copyImageFile(url);
    };
    document.addEventListener('contextmenu', function (event) {
        const img = event.target.closest('.post-content img, #img-lb-img');
        if (!img) return;
        event.preventDefault();
        window.copyDocImage(img);
    });
    let codeCopySequence = 0;
    const pendingCodeCopies = new Map();
    window.docCodeCopyResult = function (requestId, success) {
        const pending = pendingCodeCopies.get(requestId);
        if (!pending) return;
        pendingCodeCopies.delete(requestId);
        clearTimeout(pending.timer);
        pending.button.disabled = false;
        pending.button.textContent = success ? '已复制' : '重试复制';
        showDocToast(success ? '代码已复制' : '复制失败，请重试');
        if (success) setTimeout(() => { pending.button.textContent = '复制代码'; }, 1800);
    };
    function prepareCodeBlocks(root) {
        root.querySelectorAll('.post-content pre').forEach(pre => {
            if (pre.dataset.docCopyReady) return;
            pre.dataset.docCopyReady = 'true';
            const wrapper = document.createElement('div');
            wrapper.className = 'doc-code-block';
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'doc-code-copy';
            button.textContent = '复制代码';
            button.title = '复制完整代码，保留缩进和换行';
            button.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                if (!window.intellijBridge?.copyCode) {
                    showDocToast('代码复制尚未就绪，请稍后重试');
                    return;
                }
                const requestId = String(++codeCopySequence);
                button.disabled = true;
                button.textContent = '正在复制…';
                const timer = setTimeout(() => window.docCodeCopyResult(requestId, false), 5000);
                pendingCodeCopies.set(requestId, { button, timer });
                // The floating button stays outside <pre>, so it never becomes part of the copied source.
                try { window.intellijBridge.copyCode(pre.textContent, requestId); }
                catch (_) { window.docCodeCopyResult(requestId, false); }
            });
            pre.before(wrapper);
            wrapper.append(pre, button);
        });
    }
    function prepare(root) {
        updateTimes(root);
        prepareCodeBlocks(root);
        root.querySelectorAll('.post-content iframe').forEach(frame => {
            if (frame.dataset.mediaReady) return;
            frame.dataset.mediaReady = 'true';
            const src = mediaUrl(frame.getAttribute('src') || frame.dataset.src);
            if (!src) return;
            const url = new URL(src);
            const bili = url.hostname === 'player.bilibili.com';
            const bvid = url.searchParams.get('bvid');
            const aid = url.searchParams.get('aid');
            let external = src;
            if (bili && (/^BV[0-9a-z]+$/i.test(bvid || '') || /^\d+$/.test(aid || ''))) {
                external = 'https://www.bilibili.com/video/' + (bvid || 'av' + aid) + '/';
                const part = url.searchParams.get('p');
                if (/^\d+$/.test(part || '')) external += '?p=' + part;
            }
            const supported = !!document.createElement('video').canPlayType('video/mp4; codecs="avc1.42E01E"') &&
                !!document.createElement('audio').canPlayType('audio/mp4; codecs="mp4a.40.2"');
            const link = document.createElement('a');
            link.className = 'media-fallback';
            link.href = external;
            link.textContent = bili ? '在浏览器播放哔哩哔哩视频 ↗' : '在浏览器打开视频 ↗';
            link.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                window.intellijBridge?.openMedia(external);
            });
            if (bili && !supported) {
                const card = document.createElement('div');
                card.className = 'embedded-video-card';
                const title = document.createElement('strong');
                title.textContent = '哔哩哔哩视频';
                const message = document.createElement('p');
                message.textContent = '当前 IDE 不支持此视频编码，请在浏览器中播放。';
                card.append(title, message, link);
                frame.replaceWith(card);
            } else {
                frame.src = src;
                frame.allowFullscreen = true;
                frame.setAttribute('allow', 'fullscreen; picture-in-picture');
                frame.classList.add('embedded-video-frame');
                frame.after(link);
            }
        });
        root.querySelectorAll('.post-content [data-video-src]').forEach(placeholder => {
            if (placeholder.dataset.mediaReady) return;
            const src = mediaUrl(placeholder.getAttribute('data-video-src'));
            if (!src) return;
            if (placeholder.tagName === 'VIDEO') { placeholder.src = src; return; }
            placeholder.dataset.mediaReady = 'true';
            const video = document.createElement('video');
            video.src = src;
            placeholder.replaceChildren(video);
        });
        root.querySelectorAll('.post-content video').forEach(video => {
            if (video.dataset.mediaReady) return;
            video.dataset.mediaReady = 'true';
            video.controls = true;
            video.playsInline = true;
            video.preload = 'metadata';
            if (video.loop && video.hasAttribute('muted')) {
                video.muted = true;
                video.autoplay = true;
            } else video.removeAttribute('autoplay');
            for (const source of [video, ...video.querySelectorAll('source')]) {
                if (!source.getAttribute('src') && source.dataset.src) source.src = mediaUrl(source.dataset.src);
            }
            const src = mediaUrl(video.getAttribute('src') || video.querySelector('source')?.getAttribute('src') || '');
            if (!src || src === document.baseURI) return;
            const fallback = document.createElement('a');
            fallback.className = 'media-fallback';
            fallback.href = src;
            fallback.textContent = '无法播放？在浏览器中打开视频';
            fallback.addEventListener('click', event => {
                event.preventDefault();
                event.stopPropagation();
                window.intellijBridge?.openMedia(src);
            });
            video.after(fallback);
            video.addEventListener('error', () => { fallback.textContent = '此视频无法在 IDE 中播放，点击在浏览器中打开'; });
            video.load();
        });
        root.querySelectorAll('.post-content img').forEach(img => {
            if (img.dataset.animationReady) return;
            img.dataset.animationReady = 'true';
            const original = img.getAttribute('data-orig-src') || img.closest('a.lightbox')?.href || img.dataset.origSrc;
            if (original && /\.gif(?:[?#]|$)/i.test(original)) {
                const src = mediaUrl(original);
                if (src) { img.removeAttribute('srcset'); img.src = src; }
            }
        });
    }
    prepare(document);
    let pending = false;
    new MutationObserver(() => {
        if (pending) return;
        pending = true;
        requestAnimationFrame(() => { pending = false; prepare(document); });
    }).observe(document.querySelector('.doc-container') || document.body, { childList: true, subtree: true });
    setInterval(() => updateTimes(document), 30000);
})();
