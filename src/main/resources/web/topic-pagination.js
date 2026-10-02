(function () {
    'use strict';
    const config = window.linuxDoPage;
    const navIcon = path => '<svg class="reader-icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="'+path+'"/></svg>';
    const jumpIcon = navIcon('M5 12h14m-6-6 6 6-6 6');
    const refreshIcon = navIcon('M20 7a9 9 0 1 0 1 7M20 2v5h-5');
    const returnIcon = navIcon('m9 5-6 6 6 6M3 11h10a7 7 0 0 1 7 7v1');
    const container = document.querySelector('.doc-container');
    if (!config || !container) return;
    let stream = [...new Set(config.stream.map(String))];
    let order = new Map(stream.map((id, index) => [id, index]));
    const attempted = new Set();
    const entries = () => Array.from(container.querySelectorAll('.post-entry'));
    const slots = () => Array.from(container.querySelectorAll('.post-entry,.post-placeholder'));
    const cache = new Map();
    let cacheBytes = 0;
    const MAX_NODES = 200, MAX_CACHE = 400, MAX_BYTES = 32 * 1024 * 1024;
    function remember(el) {
        const id = el.dataset.postId, old = cache.get(id);
        if (old) cacheBytes -= old.bytes;
        const bytes = el.outerHTML.length * 2;
        cache.set(id, {el,bytes}); cacheBytes += bytes;
    }
    function restore(slot) {
        if (!slot?.classList.contains('post-placeholder')) return slot;
        const item = cache.get(slot.dataset.postId);
        if (!item) return null;
        slot.replaceWith(item.el); return item.el;
    }
    function boundWindow(focus) {
        const live = entries();
        const anchor = focus || readingAnchor();
        const floor = Number(anchor?.dataset.postNumber) || 1;
        const top = anchor?.getBoundingClientRect().top;
        live.forEach(remember);
        let count = live.length;
        let liveBytes = live.reduce((sum, el) => sum + (cache.get(el.dataset.postId)?.bytes || 0), 0);
        live.sort((a,b) => Math.abs(Number(b.dataset.postNumber)-floor) - Math.abs(Number(a.dataset.postNumber)-floor)).forEach(el => {
            if (count <= MAX_NODES && liveBytes <= MAX_BYTES) return;
            const slot = document.createElement('div'); slot.className = 'post-placeholder';
            slot.dataset.postId = el.dataset.postId; slot.dataset.postNumber = el.dataset.postNumber;
            slot.style.height = Math.max(1, el.getBoundingClientRect().height) + 'px';
            slot.setAttribute('aria-label', '#' + el.dataset.postNumber + ' 楼，滚动到此处恢复');
            el.replaceWith(slot); count--; liveBytes -= cache.get(el.dataset.postId)?.bytes || 0;
        });
        const candidates = Array.from(cache.entries()).sort((a,b) => Math.abs(Number(b[1].el.dataset.postNumber)-floor) - Math.abs(Number(a[1].el.dataset.postNumber)-floor));
        for (const [id,item] of candidates) {
            if (cache.size <= MAX_CACHE && cacheBytes <= MAX_BYTES) break;
            if (!item.el.isConnected) { cache.delete(id); cacheBytes -= item.bytes; }
        }
        if (anchor?.isConnected) window.scrollBy(0, anchor.getBoundingClientRect().top - top);
    }
    function restoreVisible() {
        let focus;
        slots().filter(el => el.classList.contains('post-placeholder')).forEach(slot => {
            const rect = slot.getBoundingClientRect();
            if (rect.bottom < -innerHeight || rect.top > innerHeight * 2) return;
            const restored = restore(slot);
            if (restored) focus = focus || restored;
            else if (!busy && !refreshing && !jumping && !queuedFloor && !cooldownSeconds()) {
                // Evicted bodies are fetched by floor, keeping the complete-topic ID stream lightweight.
                jump(slot.dataset.postNumber, rect.top);
            }
        });
        boundWindow(focus);
    }
    entries().forEach(el => attempted.add(el.dataset.postId));
    let busy = null;
    let scheduled = false;
    let refreshing = false;
    let filtering = false;
    let refreshQueued = false;
    let refreshMessage = '';
    let revealedReplyId = null;
    let jumping = null;
    let queuedFloor = null;
    let navigationVersion = 0;
    let jumpSequence = 0;
    let returnFloor = Number(config.returnFloor) || null;
    let highestFloor = Math.max(Number(config.highest) || 1, ...entries().map(el => Number(el.dataset.postNumber) || 1));
    let draggingFloor = false;
    let sliderTimer = null;
    let jumpTimer = null;
    let nextJumpAt = 0;
    let prefetchArmed = true;
    let cooldownUntil = 0;
    let cooldownTimer = null;
    const cooldownSeconds = () => Math.max(0, Math.ceil((cooldownUntil - Date.now()) / 1000));
    function rateLimited(seconds) {
        if (!(Number(seconds) > 0)) return;
        cooldownUntil = Math.max(cooldownUntil, Date.now() + Number(seconds) * 1000);
        queuedFloor = null;
        refreshQueued = false;
        prefetchArmed = false;
        clearTimeout(jumpTimer);
        clearTimeout(sliderTimer);
        clearInterval(cooldownTimer);
        cooldownTimer = setInterval(() => {
            if (!cooldownSeconds()) {
                clearInterval(cooldownTimer);
                refreshMessage = '可以继续加载，请手动重试';
            }
            update();
        }, 1000);
    }
    const floorForm = document.createElement('form');
    floorForm.id = 'jump-floor-form';
    floorForm.className = 'topic-navigation';
    floorForm.setAttribute('aria-label', '话题导航');
    const progressLabel = document.createElement('span');
    progressLabel.id = 'floor-progress-label';
    progressLabel.className = 'floor-progress-label';
    const floorSlider = document.createElement('input');
    floorSlider.id = 'floor-progress';
    floorSlider.type = 'range';
    floorSlider.min = '1';
    floorSlider.step = '1';
    floorSlider.max = String(highestFloor);
    floorSlider.value = '1';
    floorSlider.setAttribute('aria-label', '楼层进度条');
    floorSlider.className = 'floor-progress-slider';
    floorSlider.oninput = () => {
        draggingFloor = true;
        clearTimeout(sliderTimer);
        progressLabel.textContent = '#' + floorSlider.value + ' / ' + highestFloor;
        floorInput.value = floorSlider.value;
    };
    floorSlider.onchange = () => {
        const target = floorSlider.value;
        clearTimeout(sliderTimer);
        sliderTimer = setTimeout(() => { draggingFloor = false; jump(target); }, 150);
    };
    floorSlider.addEventListener('pointercancel', () => { draggingFloor = false; updateProgress(); });
    const floorInput = document.createElement('input');
    floorInput.id = 'jump-floor-input';
    floorInput.type = 'text';
    floorInput.inputMode = 'numeric';
    floorInput.placeholder = '楼层号';
    floorInput.setAttribute('aria-label', '跳转到楼层');
    floorInput.autocomplete = 'off';
    floorInput.maxLength = 10;
    floorInput.addEventListener('input', () => update());
    const jumpButton = document.createElement('button');
    jumpButton.type = 'submit';
    jumpButton.innerHTML = jumpIcon;
    jumpButton.setAttribute('aria-label','跳转');jumpButton.title='跳转';
    jumpButton.className = 'topic-nav-button topic-jump-button';
    const jumpControls = document.createElement('div');
    jumpControls.className = 'floor-jump-controls';
    jumpControls.append(floorInput, jumpButton);
    floorForm.append(progressLabel, floorSlider, jumpControls);
    const returnButton = document.createElement('button');
    returnButton.type = 'button';
    returnButton.className = 'topic-nav-button topic-return-button';
    returnButton.onclick = () => { if (returnFloor) jump(returnFloor); };
    floorForm.appendChild(returnButton);
    floorForm.onsubmit = event => { event.preventDefault(); jump(floorInput.value); };
    document.body.appendChild(floorForm);
    const refreshButtons = [];
    const status = document.createElement('div');
    status.setAttribute('role', 'status');
    status.className = 'topic-navigation-status';
    status.setAttribute('aria-live', 'polite');
    const failed = {before: false, after: false};
    const buttons = {};
    function readingAnchor() {
        const revealed = entries().find(el => el.dataset.postId === revealedReplyId);
        const rect = revealed && revealed.getBoundingClientRect();
        return rect && rect.bottom > 0 && rect.top < innerHeight ? revealed :
            entries().find(el => el.getBoundingClientRect().bottom > 0);
    }
    function updateProgress() {
        floorSlider.max = String(highestFloor);
        if (draggingFloor || jumping || queuedFloor !== null) return;
        const visible = readingAnchor();
        const floor = visible ? Number(visible.dataset.postNumber) : 1;
        floorSlider.value = String(Math.max(1, floor));
        progressLabel.textContent = '#' + floorSlider.value + ' / ' + highestFloor;
        floorSlider.setAttribute('aria-valuetext', progressLabel.textContent);
    }
    function batch(direction) {
        const indexes = slots().map(el => order.get(el.dataset.postId)).filter(i => i !== undefined);
        if (!indexes.length) return direction === 'after' ? stream.filter(id => !attempted.has(id)).slice(0, 20) : [];
        const edge = direction === 'before' ? Math.min(...indexes) : Math.max(...indexes);
        const missing = stream.filter((id, i) => !attempted.has(id) && (direction === 'before' ? i < edge : i > edge));
        return direction === 'before' ? missing.slice(-20) : missing.slice(0, 20);
    }
    function update() {
        ['before', 'after'].forEach(direction => {
            const button = buttons[direction];
            button.hidden = !batch(direction).length;
            button.style.display = button.hidden ? 'none' : 'block';
            button.disabled = !!busy || refreshing || !!jumping || cooldownSeconds() > 0;
            button.textContent = busy && busy.direction === direction ? '正在加载楼层…' :
                failed[direction] ? '加载失败，点击重试' : direction === 'before' ? '加载更早楼层' : '加载更多楼层';
        });
        refreshButtons.forEach(button => {
            button.disabled = !!busy || refreshing || !!jumping || cooldownSeconds() > 0;
            button.innerHTML = refreshIcon;
            button.title = refreshing ? '刷新中…' : '刷新回复 (F5)，保留当前阅读位置';
        });
        status.textContent = cooldownSeconds() ? '请求过于频繁，' + cooldownSeconds() + ' 秒后可重试；已保留正文' : refreshMessage;
        status.hidden = !status.textContent;
        status.title = status.textContent;
        floorForm.classList.toggle('has-status', !status.hidden);
        floorForm.classList.toggle('is-cooling-down', cooldownSeconds() > 0);
        jumpButton.disabled = cooldownSeconds() > 0 && !entries().some(el => Number(el.dataset.postNumber) === Number(floorInput.value));
        jumpButton.title = jumping ? '加载中…' : '跳转';
        returnButton.innerHTML = returnIcon + (returnFloor ? '<span>#'+returnFloor+'</span>' : '');
        returnButton.setAttribute('aria-label',returnFloor ? '返回 #' + returnFloor + ' 楼' : '返回跳转前位置');returnButton.title=returnButton.getAttribute('aria-label');
        returnButton.hidden = !returnFloor || returnFloor > highestFloor;
        floorSlider.hidden = highestFloor <= 1;
        jumpControls.hidden = highestFloor <= 1;
        returnButton.disabled = !returnFloor || !!busy || refreshing || !!jumping ||
            (cooldownSeconds() > 0 && !entries().some(el => Number(el.dataset.postNumber) === returnFloor));
        updateProgress();
    }
    function request(direction, manual) {
        if (filtering) return;
        if (busy || refreshing || jumping || cooldownSeconds() || (failed[direction] && !manual)) return;
        const ids = batch(direction);
        if (!ids.length || !window.linuxDoLoadPosts) return;
        prefetchArmed = false;
        failed[direction] = false;
        busy = {direction, ids};
        update();
        window.linuxDoLoadPosts(config.key, direction, ids);
    }
    function check() {
        scheduled = false;
        updateProgress();
        if (busy || refreshing || jumping || queuedFloor !== null || !prefetchArmed || cooldownSeconds()) return;
        ['before', 'after'].some(direction => {
            const button = buttons[direction];
            const rect = button.getBoundingClientRect();
            if (!button.hidden && rect.bottom >= -250 && rect.top <= innerHeight + 250) request(direction, false);
            return !!busy;
        });
    }
    function schedule() {
        if (!scheduled) { scheduled = true; requestAnimationFrame(check); }
    }
    function refresh() {
        if (filtering) return;
        if (refreshing || cooldownSeconds() || !window.linuxDoRefreshPosts) return;
        if (busy || jumping) { refreshQueued = true; return; }
        refreshQueued = false;
        refreshing = true;
        refreshMessage = '';
        update();
        window.linuxDoRefreshPosts(config.key);
    }
    function afterBatch() {
        if (queuedFloor !== null) { const floor = queuedFloor; queuedFloor = null; jump(floor); }
        else if (refreshQueued) refresh();
        else schedule();
    }
    function reveal(target, message, fromFloor) {
        if (fromFloor && Number(target.dataset.postNumber) !== fromFloor) {
            returnFloor = fromFloor;
            if (window.intellijBridge && window.intellijBridge.navigationReturn) window.intellijBridge.navigationReturn(config.key, returnFloor);
        }
        prefetchArmed = false;
        revealedReplyId = target.dataset.postId;
        refreshMessage = message;
        update();
        target.scrollIntoView({behavior: 'instant', block: 'start'});
        floorSlider.value = target.dataset.postNumber;
        progressLabel.textContent = '#' + target.dataset.postNumber + ' / ' + highestFloor;
        floorSlider.setAttribute('aria-valuetext', progressLabel.textContent);
        target.classList.add('highlight-flash');
        setTimeout(() => target.classList.remove('highlight-flash'), 1800);
        if (window.showDocToast) window.showDocToast(message);
    }
    function jump(value, restoreTop) {
        if (filtering) return false;
        clearTimeout(sliderTimer);
        draggingFloor = false;
        const text = String(value).trim();
        const floor = Number(text);
        if (!/^\d+$/.test(text) || !Number.isSafeInteger(floor) || floor < 1 || floor > 2147483647) {
            refreshMessage = '请输入有效的正整数楼层号';
            update();
            if (window.showDocToast) window.showDocToast(refreshMessage);
            return false;
        }
        prefetchArmed = false;
        navigationVersion++;
        if (busy || refreshing || jumping) { queuedFloor = floor; return true; }
        const existing = restore(slots().find(el => Number(el.dataset.postNumber) === floor));
        if (existing) { queuedFloor = null; clearTimeout(jumpTimer); reveal(existing, '已定位到 #' + floor + ' 楼', Number(readingAnchor()?.dataset.postNumber)); boundWindow(existing); if (refreshQueued) refresh(); return true; }
        if (cooldownSeconds()) { update(); return false; }
        if (!window.linuxDoJumpFloor) return false;
        clearTimeout(jumpTimer);
        if (Date.now() < nextJumpAt) {
            queuedFloor = floor;
            refreshMessage = '准备跳转到 #' + floor + ' 楼…';
            update();
            jumpTimer = setTimeout(() => {
                if (queuedFloor !== null && !busy && !refreshing && !jumping) {
                    const target = queuedFloor; queuedFloor = null; jump(target);
                }
            }, nextJumpAt - Date.now());
            return true;
        }
        queuedFloor = null;
        nextJumpAt = Date.now() + 800;
        jumping = {id: String(++jumpSequence), floor, version: navigationVersion, fromFloor: Number(readingAnchor()?.dataset.postNumber), restoreTop};
        refreshMessage = '正在加载 #' + floor + ' 楼…';
        update();
        window.linuxDoJumpFloor(config.key, jumping.id, floor);
        return true;
    }
    function insertPosts(html) {
        const template = document.createElement('template');
        template.innerHTML = html;
        const existing = new Set(entries().map(el => el.dataset.postId));
        Array.from(template.content.querySelectorAll('.post-entry')).forEach(el => {
            const id = el.dataset.postId;
            if (existing.has(id) || !order.has(id)) return;
            const placeholder = slots().find(item => item.dataset.postId === id && item.classList.contains('post-placeholder'));
            if (placeholder) { placeholder.replaceWith(el); remember(el); existing.add(id); return; }
            const next = slots().find(item => order.get(item.dataset.postId) > order.get(id));
            container.insertBefore(el, next || buttons.after);
            existing.add(id);
        });
        if (window.observeDocPosts) window.observeDocPosts();
    }
    ['before', 'after'].forEach(direction => {
        const button = document.createElement('button');
        button.type = 'button';
        button.id = 'load-posts-' + direction;
        button.className = 'topic-load-button';
        button.onclick = () => request(direction, true);
        buttons[direction] = button;
        if (direction === 'before') container.insertBefore(button, container.querySelector('.post-entry'));
        else container.appendChild(button);
    });
    ['bottom'].forEach(position => {
        const button = document.createElement('button');
        button.type = 'button';
        button.id = 'refresh-posts-' + position;
        button.title = '刷新回复 (F5)，保留当前阅读位置';
        button.setAttribute('aria-label','刷新回复');
        button.className = 'topic-nav-button topic-refresh-button';
        button.onclick = refresh;
        refreshButtons.push(button);
        floorForm.insertBefore(button, progressLabel);
    });
    floorForm.appendChild(status);
    window.linuxDoPagination = {
        refresh,
        jump,
        stats() { return {nodes:entries().length, cached:cache.size, bytes:cacheBytes, placeholders:slots().length-entries().length}; },
        currentFloor() { return Number(readingAnchor()?.dataset.postNumber) || 1; },
        beginFilter() { if (busy || refreshing || jumping || filtering) return false; filtering=true; return true; },
        endFilter() { filtering=false; },
        lastFloor() { return highestFloor; },
        filter(ids, html) {
            if (busy || refreshing || jumping) return false;
            stream = [...new Set(ids.map(String))]; order = new Map(stream.map((id,index)=>[id,index]));
            slots().forEach(el=>el.remove()); cache.clear(); cacheBytes=0; attempted.clear();
            insertPosts(html); entries().forEach(el=>attempted.add(el.dataset.postId));
            boundWindow(); window.scrollTo(0,0); update(); return true;
        },
        patch(html) {
            const anchor = readingAnchor(), top = anchor?.getBoundingClientRect().top;
            const selection = getSelection();
            const selected = selection?.rangeCount ? selection.getRangeAt(0).cloneRange() : null;
            const selectedStart=selected?.startContainer,selectedEnd=selected?.endContainer;
            const selectedBody=selected && (selected.startContainer.parentElement?.closest('.post-content'));
            let preserved=null;
            if(selectedBody?.contains(selected.endContainer) && selection.toString()){
                const prefix=selected.cloneRange();prefix.selectNodeContents(selectedBody);prefix.setEnd(selected.startContainer,selected.startOffset);
                preserved={id:selectedBody.closest('.post-entry').dataset.postId,text:selection.toString(),offset:prefix.toString().length};
            }
            const template = document.createElement('template'); template.innerHTML = html;
            template.content.querySelectorAll('.post-entry').forEach(next => {
                const current = slots().find(el => el.dataset.postId === next.dataset.postId);
                const retained = current?.classList.contains('post-entry') ? current : cache.get(next.dataset.postId)?.el;
                if (!retained) return;
                // Keep body nodes and selection when only permissions, counters or controls changed.
                if (retained.querySelector('.post-content').dataset.source === next.querySelector('.post-content').dataset.source) {
                    retained.querySelector('.floor-actions').replaceWith(next.querySelector('.floor-actions'));
                    retained.dataset.polls = next.dataset.polls; retained.dataset.pollVotes = next.dataset.pollVotes;
                    window.linuxDoPolls?.(retained); remember(retained); return;
                }
                const opened = Array.from(retained.querySelectorAll('details')).map(el=>el.open);
                const spoilers = Array.from(retained.querySelectorAll('.spoiler')).map(el=>el.classList.contains('revealed'));
                const images = Array.from(retained.querySelectorAll('.fold-img-box img')).map(el=>el.classList.contains('expanded'));
                next.querySelectorAll('details').forEach((el,i)=>el.open=!!opened[i]);
                next.querySelectorAll('.spoiler').forEach((el,i)=>el.classList.toggle('revealed',!!spoilers[i]));
                next.querySelectorAll('.fold-img-box img').forEach((el,i)=>el.classList.toggle('expanded',!!images[i]));
                if (current?.classList.contains('post-entry')) { current.replaceWith(next); if (anchor === current) window.scrollBy(0,next.getBoundingClientRect().top-top); }
                remember(next);
            });
            if (anchor?.isConnected) window.scrollBy(0,anchor.getBoundingClientRect().top-top);
            window.linuxDoEnhanceContent?.(document); boundWindow();
            if (selected && selectedStart.isConnected && selectedEnd.isConnected) { selection.removeAllRanges(); selection.addRange(selected); }
            else if(preserved){
                const body=entries().find(el=>el.dataset.postId===preserved.id)?.querySelector('.post-content');
                if(body){
                    const content=body.textContent;let position=content.indexOf(preserved.text),best=-1;
                    while(position>=0){if(best<0 || Math.abs(position-preserved.offset)<Math.abs(best-preserved.offset))best=position;position=content.indexOf(preserved.text,position+1);}
                    if(best>=0){
                        const walker=document.createTreeWalker(body,NodeFilter.SHOW_TEXT);let node,offset=0,start=null,end=null;
                        while((node=walker.nextNode())){const length=node.textContent.length;if(!start && offset+length>best)start=[node,best-offset];if(offset+length>=best+preserved.text.length){end=[node,best+preserved.text.length-offset];break;}offset+=length;}
                        if(start && end){const restored=document.createRange();restored.setStart(...start);restored.setEnd(...end);selection.removeAllRanges();selection.addRange(restored);}
                    }
                }
            }
        },
        jumped(key, requestId, ids, html, error, highest, retryAfterSeconds) {
            if (key !== config.key || !jumping || jumping.id !== requestId) return;
            rateLimited(retryAfterSeconds);
            const request = jumping;
            jumping = null;
            const anchor = readingAnchor();
            const anchorTop = anchor && anchor.getBoundingClientRect().top;
            if (!error) {
                highestFloor = Math.max(highestFloor, Number(highest) || 1);
                stream = [...new Set(ids.map(String))];
                order = new Map(stream.map((id, index) => [id, index]));
                insertPosts(html);
                entries().forEach(el => attempted.add(el.dataset.postId));
            }
            if (request.version !== navigationVersion && anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - anchorTop);
            if (request.version === navigationVersion) {
                const target = entries().find(el => Number(el.dataset.postNumber) === request.floor);
                if (!error && target && request.restoreTop !== undefined) window.scrollBy(0,target.getBoundingClientRect().top-request.restoreTop);
                else if (!error && target) reveal(target, '已定位到 #' + request.floor + ' 楼', request.fromFloor);
                else {
                    refreshMessage = '无法加载 #' + request.floor + ' 楼，楼层可能不存在或无权查看；可点击跳转重试';
                    update();
                    if (window.showDocToast) window.showDocToast(refreshMessage);
                }
            }
            update();
            boundWindow(entries().find(el => Number(el.dataset.postNumber) === request.floor));
            afterBatch();
        },
        showReply(key, id, html) {
            if (key !== config.key) return;
            id = String(id);
            const template = document.createElement('template');
            template.innerHTML = html;
            const post = Array.from(template.content.querySelectorAll('.post-entry')).find(el => el.dataset.postId === id);
            if (!post) return;
            highestFloor = Math.max(highestFloor, Number(post.dataset.postNumber));
            if (!order.has(id)) {
                stream.push(id);
                order.set(id, stream.length - 1);
            }
            const existing = entries().find(el => el.dataset.postId === id);
            if (!existing) {
                const next = entries().find(el => order.get(el.dataset.postId) > order.get(id));
                container.insertBefore(post, next || buttons.after);
            }
            attempted.add(id);
            navigationVersion++;
            queuedFloor = null;
            if (window.observeDocPosts) window.observeDocPosts();
            const target = existing || post;
            reveal(target, '回复已发布，已定位到 #' + post.dataset.postNumber + ' 楼');
            boundWindow(target);
        },
        refreshed(key, ids, error, highest, retryAfterSeconds, html) {
            if (key !== config.key || !refreshing) return;
            refreshing = false;
            rateLimited(retryAfterSeconds);
            if (error) {
                refreshMessage = '刷新失败，已保留正文；点击刷新回复重试';
                update();
                afterBatch();
                return;
            }
            const next = [...new Set(ids.map(String))];
            highestFloor = Math.max(highestFloor, Number(highest) || 1);
            const added = next.filter(id => !order.has(id)).length;
            stream = next;
            order = new Map(stream.map((id, index) => [id, index]));
            if (html) this.patch(html);
            const anchor = readingAnchor(), top = anchor?.getBoundingClientRect().top;
            slots().filter(el=>!order.has(el.dataset.postId)).forEach(el=>{el.remove();const item=cache.get(el.dataset.postId);if(item){cacheBytes-=item.bytes;cache.delete(el.dataset.postId);}});
            if(anchor?.isConnected)window.scrollBy(0,anchor.getBoundingClientRect().top-top);
            refreshMessage = added ? '发现 ' + added + ' 条新回复，向下阅读即可加载' : '已检查，暂无新回复';
            failed.before = failed.after = false;
            prefetchArmed = false;
            update();
            afterBatch();
        },
        receive(key, direction, html, error, retryAfterSeconds) {
            if (key !== config.key || !busy || direction !== busy.direction) return;
            rateLimited(retryAfterSeconds);
            if (error) { failed[direction] = true; busy = null; update(); afterBatch(); return; }
            const anchor = readingAnchor();
            const top = anchor && anchor.getBoundingClientRect().top;
            insertPosts(html);
            busy.ids.forEach(id => attempted.add(id));
            busy = null;
            update();
            // Compensate only the insertion above the current reading anchor.
            if (anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - top);
            if (window.observeDocPosts) window.observeDocPosts();
            boundWindow(anchor);
            afterBatch();
        }
    };
    addEventListener('keydown', event => {
        if (event.key === 'F5' && !event.ctrlKey && !event.altKey && !event.metaKey && !event.target.closest('input,textarea,select,[contenteditable=true]')) {
            event.preventDefault();
            if (!event.repeat) refresh();
        }
    });
    addEventListener('scroll', schedule, {passive: true});
    let restorePending = false;
    addEventListener('scroll', () => { if (!restorePending) { restorePending = true; requestAnimationFrame(() => { restorePending = false; restoreVisible(); }); } }, {passive:true});
    function userScroll(event) {
        if (event.target instanceof Node && floorForm.contains(event.target)) return;
        revealedReplyId = null;
        prefetchArmed = !cooldownSeconds() && !busy && !jumping && !refreshing;
    }
    addEventListener('wheel', userScroll, {passive: true});
    addEventListener('touchmove', userScroll, {passive: true});
    addEventListener('pointerdown', userScroll, {passive: true});
    addEventListener('keydown', event => {
        if (!event.ctrlKey && !event.altKey && !event.metaKey && !event.target.closest('input,textarea,select,[contenteditable=true]') && ['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) userScroll(event);
    });
    addEventListener('pagehide', () => {
        clearTimeout(sliderTimer); clearTimeout(jumpTimer); clearInterval(cooldownTimer);
    });
    addEventListener('resize', schedule);
    const navResize = new ResizeObserver(() => { document.body.style.paddingBottom = (floorForm.offsetHeight + 24) + 'px'; });
    navResize.observe(floorForm);
    const quoteButton = document.createElement('button');
    quoteButton.type = 'button';
    quoteButton.textContent = '引用回复';
    quoteButton.className = 'topic-nav-button topic-quote-button';
    quoteButton.hidden = true;
    document.body.appendChild(quoteButton);
    let quoteSelection = null;
    const containingPost = node => (node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement)?.closest('.post-content')?.closest('.post-entry');
    document.addEventListener('selectionchange', () => {
        const selection = getSelection();
        const post = containingPost(selection?.anchorNode);
        const end = containingPost(selection?.focusNode);
        const text = selection?.toString() || '';
        quoteSelection = post && post === end && text.trim() && text.length <= 100000 ? {floor: Number(post.dataset.postNumber), text} : null;
        if (quoteSelection) {
            const pre = (selection.anchorNode.nodeType === Node.ELEMENT_NODE ? selection.anchorNode : selection.anchorNode.parentElement).closest('pre');
            if (pre && pre.contains(selection.focusNode)) {
                const fence = '`'.repeat(Math.max(3, ...Array.from(text.matchAll(/`+/g), m => m[0].length + 1)));
                quoteSelection.text = fence + '\n' + text + '\n' + fence;
            }
        }
        quoteButton.hidden = !quoteSelection;
        quoteButton.style.bottom = (floorForm.offsetHeight + 12) + 'px';
    });
    quoteButton.onmousedown = event => event.preventDefault();
    quoteButton.onclick = () => {
        if (quoteSelection && window.intellijBridge?.quoteReply) window.intellijBridge.quoteReply(quoteSelection.floor, quoteSelection.text);
        quoteButton.hidden = true;
    };
    addEventListener('pagehide', () => navResize.disconnect());
    update();
    boundWindow();
    // Let initial last-read navigation settle before prefetching the visible edge.
    setTimeout(schedule, 1200);
})();
