(function () {
  'use strict';
  if (window.linuxDoContentBound) return;
  window.linuxDoContentBound = true;
  const originals = new WeakMap();
  let renderId = 0;
  function button(label, action) {
    const item = document.createElement('button'); item.type = 'button'; item.textContent = label;
    item.addEventListener('click', e => { e.preventDefault(); e.stopPropagation(); action(item); });
    return item;
  }
  function copy(text) {
    if (window.intellijBridge?.copyCode) window.intellijBridge.copyCode(text, 'content-' + (++renderId));
    else {
      const input = document.createElement('textarea'); input.value = text; document.body.append(input);
      input.select(); document.execCommand('copy'); input.remove();
    }
  }
  function enlarge(node, source) {
    const dialog = document.createElement('dialog'); dialog.className = 'forum-source-dialog';
    const close = button('关闭', () => { dialog.close(); dialog.remove(); });
    dialog.append(close, button('复制源码', () => copy(source)), node.cloneNode(true)); document.body.append(dialog); dialog.showModal();
    dialog.addEventListener('close', () => dialog.remove(), {once:true});
  }
  function safeSvg(svg) {
    svg.querySelectorAll('script,foreignObject,iframe,object,image,a').forEach(el => el.remove());
    svg.querySelectorAll('style').forEach(el=>{el.textContent=el.textContent.replace(/@import[^;]*;|url\((?!['"]?#)[^)]*\)|expression\([^)]*\)/gi,'');});
    [svg,...svg.querySelectorAll('*')].forEach(el => Array.from(el.attributes).forEach(attr => {
      if (/^on/i.test(attr.name) || (/href$/i.test(attr.name) && !attr.value.startsWith('#')) || /(?:javascript:|https?:|url\((?!#))/i.test(attr.value)) el.removeAttribute(attr.name);
    }));
    return svg;
  }
  async function diagram(node) {
    if (node.dataset.forumReady) return;
    node.dataset.forumReady = 'true';
    const source = node.getAttribute('data-math-source') || node.textContent;
    const mermaid = node.matches('.mermaid') || /(?:lang|language)-mermaid/.test(node.className);
    const wrapper = document.createElement('div'); wrapper.className = 'forum-rendered-source';
    const output = document.createElement('div'); output.className = 'forum-source-output';
    const controls = document.createElement('div'); controls.className = 'forum-source-controls';
    controls.append(button('复制源码', () => copy(source)), button('放大', () => enlarge(output, source)));
    const details = document.createElement('details'); const summary = document.createElement('summary'); summary.textContent = '查看源码';
    const pre = document.createElement('pre'); pre.textContent = source; details.append(summary, pre);
    (node.closest('pre') || node).replaceWith(wrapper); wrapper.append(controls, output, details);
    try {
      if (source.length > 100000) throw Error('内容过长');
      let svg;
      if (mermaid) {
        if (!window.mermaid) throw Error('图表引擎不可用');
        window.mermaid.initialize({startOnLoad:false, securityLevel:'strict', flowchart:{htmlLabels:false}, theme:'neutral', maxTextSize:100000});
        const result = await window.mermaid.render('forum-diagram-' + (++renderId), source);
        const documentSvg = new DOMParser().parseFromString(result.svg, 'image/svg+xml');
        svg = documentSvg.querySelector('svg'); if (!svg) throw Error('图表输出无效');
      } else {
        await window.MathJax?.startup?.promise;
        if (!window.MathJax?.tex2svgPromise) throw Error('公式引擎不可用');
        const tex = source.replace(/^\$\$?|\$\$?$/g, '').replace(/^\\\[|\\\]$/g, '').replace(/^\\\(|\\\)$/g, '');
        svg = (await window.MathJax.tex2svgPromise(tex, {display:node.tagName !== 'SPAN'})).querySelector('svg');
      }
      output.append(document.importNode(safeSvg(svg), true));
    } catch (error) { output.textContent = '渲染失败：' + error.message; output.classList.add('forum-render-error'); details.open = true; }
  }
  function player(raw) {
    try {
      const url = new URL(raw, 'https://linux.do/');
      if (url.protocol !== 'https:' || url.username || url.password) return null;
      if (url.hostname === 'player.bilibili.com' && url.pathname === '/player.html') {
        const bv = url.searchParams.get('bvid'), av = url.searchParams.get('aid');
        if (!/^BV[\da-z]+$/i.test(bv || '') && !/^\d+$/.test(av || '')) return null;
        const safe = new URL('https://player.bilibili.com/player.html');
        safe.searchParams.set(bv ? 'bvid' : 'aid', bv || av); safe.searchParams.set('autoplay', '0');
        const part = url.searchParams.get('p'); if (/^[1-9]\d*$/.test(part || '')) safe.searchParams.set('p', part);
        return {src:safe.href, external:'https://www.bilibili.com/video/' + (bv || 'av' + av) + '/'};
      }
      if (['www.youtube.com','www.youtube-nocookie.com','youtube.com'].includes(url.hostname)) {
        const id = url.pathname.match(/^\/embed\/([\w-]{11})$/)?.[1];
        if (id) return {src:'https://www.youtube-nocookie.com/embed/' + id,external:'https://www.youtube.com/watch?v=' + id};
      }
    } catch (_) {}
    return null;
  }
  function enhance(root) {
    root.querySelectorAll('.post-content .math,.post-content .mermaid,.post-content code.language-mermaid,.post-content code.lang-mermaid,.post-content code.language-math,.post-content code.language-latex').forEach(diagram);
    root.querySelectorAll('.post-content pre').forEach(pre => {
      if (pre.dataset.forumReady || pre.closest('.forum-rendered-source')) return;
      pre.dataset.forumReady = 'true';
      const code = pre.querySelector('code') || pre;
      const source = code.textContent; originals.set(pre, source);
      const language = code.className.match(/(?:lang|language)-([\w+#-]+)/)?.[1] || 'text';
      const wrapper = document.createElement('div'); wrapper.className = 'forum-code-block';
      const controls = document.createElement('div'); controls.className = 'forum-source-controls';
      const label = document.createElement('span'); label.textContent = language;
      controls.append(label, button('复制代码', () => copy(originals.get(pre))), button('换行', item => {
        pre.classList.toggle('forum-code-wrap'); item.setAttribute('aria-pressed', pre.classList.contains('forum-code-wrap'));
      }), button('放大', () => enlarge(pre, source)));
      pre.before(wrapper); wrapper.append(controls, pre);
      if (source.length <= 256000 && language !== 'text' && window.hljs?.getLanguage(language)) {
        try { code.innerHTML = window.hljs.highlight(source, {language,ignoreIllegals:true}).value; } catch (_) {}
      }
    });
    root.querySelectorAll('.post-content .forum-embed').forEach(card => {
      if (card.dataset.forumReady) return; card.dataset.forumReady = 'true';
      const link = card.querySelector('a.forum-embed-source'); if (!link) return;
      const supported = player(link.getAttribute('href'));
      if (supported) {
        link.href = supported.external; link.textContent = '在浏览器播放';
        card.prepend(button('加载播放器', item => {
          const frame = document.createElement('iframe'); frame.className = 'embedded-video-frame';
          frame.setAttribute('sandbox','allow-scripts allow-same-origin allow-presentation');
          frame.allow = 'fullscreen; picture-in-picture'; frame.allowFullscreen = true; frame.referrerPolicy = 'no-referrer';
          frame.src = supported.src; item.replaceWith(frame);
        }));
      }
    });
    root.querySelectorAll('.post-content audio,.post-content video').forEach(media => { media.controls = true; media.preload = 'metadata'; media.removeAttribute('autoplay'); });
  }
  document.addEventListener('click', event => {
    const spoiler = event.target.closest('.spoiler');
    if (spoiler && !event.target.closest('button,a,input,textarea')) {
      spoiler.classList.toggle('revealed'); spoiler.setAttribute('aria-expanded', spoiler.classList.contains('revealed'));
      event.preventDefault(); event.stopImmediatePropagation();
    }
  }, true);
  window.linuxDoEnhanceContent = enhance;
  window.linuxDoPlayer = player;
  enhance(document);
  let pending = false;
  new MutationObserver(() => {
    if (pending) return; pending = true;
    requestAnimationFrame(() => { pending = false; enhance(document); });
  }).observe(document.querySelector('.doc-container') || document.querySelector('#composer-content') || document.body, {childList:true,subtree:true});
})();
