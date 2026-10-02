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
    let imageMenu;
    document.addEventListener('contextmenu', function (event) {
        const img = event.target.closest('.post-content img, #img-lb-img');
        if (!img) return;
        event.preventDefault(); imageMenu?.remove();
        imageMenu = document.createElement('div'); imageMenu.className = 'doc-image-menu'; imageMenu.setAttribute('role','menu');
        const url = mediaUrl(img.getAttribute('data-orig-src') || img.closest('a.lightbox')?.href || img.currentSrc || img.src);
        const item = (label, action) => { const b=document.createElement('button'); b.textContent=label;b.type='button';b.setAttribute('role','menuitem');b.onclick=()=>{imageMenu.remove();action();};imageMenu.append(b); };
        item('复制图片',()=>window.copyDocImage(img));
        item('复制原图文件',()=>window.copyDocImageFile(img));
        item('复制图片地址',()=>window.intellijBridge?.copyCode(url,'image-url'));
        item('保存图片',()=>window.intellijBridge?.saveImage(url));
        document.body.append(imageMenu);
        imageMenu.style.left = Math.max(0,Math.min(event.clientX,innerWidth-imageMenu.offsetWidth))+'px';
        imageMenu.style.top = Math.max(0,Math.min(event.clientY,innerHeight-imageMenu.offsetHeight))+'px';
        imageMenu.querySelector('button').focus();
    });
    document.addEventListener('click',e=>{if(!e.target.closest('.doc-image-menu'))imageMenu?.remove();});
    document.addEventListener('keydown',e=>{if(e.key==='Escape')imageMenu?.remove();});
    const originalOpen = window.openLightbox;
    let gallery=[], galleryIndex=0;
    window.openLightbox = function(src,title,event,fallback) {
        const post=event?.target?.closest('.post-entry');
        if(post){
            gallery=Array.from(post.querySelectorAll('.post-content img')).filter(img=>!img.matches('.emoji,.avatar,.avatar-img,.site-icon')).map(img=>({src:mediaUrl(img.getAttribute('data-orig-src')||img.closest('a.lightbox')?.href||img.src),fallback:mediaUrl(img.getAttribute('data-thumb-src')||img.src),title:img.alt}));
            galleryIndex=Math.max(0,gallery.findIndex(img=>img.src===mediaUrl(src)||img.fallback===mediaUrl(fallback)));
        }
        originalOpen(src,title,event,fallback);
    };
    window.lbStep = function(step) { if(!gallery.length)return;galleryIndex=(galleryIndex+step+gallery.length)%gallery.length;const img=gallery[galleryIndex];originalOpen(img.src,img.title,null,img.fallback); };
    const toolbar=document.querySelector('.image-lightbox-tools');
    if(toolbar){
        for(const [label,action] of [['上一张',()=>window.lbStep(-1)],['下一张',()=>window.lbStep(1)],['复制地址',()=>window.intellijBridge?.copyCode(lbCurrentSrc,'image-url')],['保存',()=>window.intellijBridge?.saveImage(lbCurrentSrc)]]){
            const b=document.createElement('button');b.type='button';b.className='lb-btn';b.textContent=label;b.onclick=action;toolbar.prepend(b);
        }
    }
    document.addEventListener('keydown',event=>{
        if(event.ctrlKey||event.altKey||event.metaKey||event.target.closest('input,textarea,select,[contenteditable=true]'))return;
        if(document.getElementById('img-lightbox-overlay')?.classList.contains('active') && ['ArrowLeft','ArrowRight'].includes(event.key)){event.preventDefault();window.lbStep(event.key==='ArrowLeft'?-1:1);}
    });
    let codeCopySequence = 0;
    const pendingCodeCopies = new Map();
    window.docCodeCopyResult = function (requestId, success) {
        const pending = pendingCodeCopies.get(requestId);
        if (!pending) { showDocToast(success ? '已复制' : '复制失败，请重试'); return; }
        pendingCodeCopies.delete(requestId);
        clearTimeout(pending.timer);
        pending.button.disabled = false;
        pending.button.textContent = success ? '已复制' : '重试复制';
        showDocToast(success ? '代码已复制' : '复制失败，请重试');
        if (success) setTimeout(() => { pending.button.textContent = '复制代码'; }, 1800);
    };
    function prepare(root) {
        updateTimes(root);
        window.linuxDoEnhanceContent?.(root);
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
