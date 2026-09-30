(function () {
    'use strict';
    const config = window.linuxDoPage;
    const container = document.querySelector('.doc-container');
    if (!config || !container) return;
    let stream = [...new Set(config.stream.map(String))];
    let order = new Map(stream.map((id, index) => [id, index]));
    const attempted = new Set();
    const entries = () => Array.from(container.querySelectorAll('.post-entry'));
    entries().forEach(el => attempted.add(el.dataset.postId));
    let busy = null;
    let scheduled = false;
    let refreshing = false;
    let refreshQueued = false;
    let refreshMessage = '';
    let revealedReplyId = null;
    let jumping = null;
    let queuedFloor = null;
    let navigationVersion = 0;
    let jumpSequence = 0;
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
    jumpButton.textContent = '跳转';
    jumpButton.className = 'topic-nav-button topic-jump-button';
    const jumpControls = document.createElement('div');
    jumpControls.className = 'floor-jump-controls';
    jumpControls.append(floorInput, jumpButton);
    floorForm.append(progressLabel, floorSlider, jumpControls);
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
        const indexes = entries().map(el => order.get(el.dataset.postId)).filter(i => i !== undefined);
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
            button.textContent = refreshing ? '刷新中…' : '刷新回复';
        });
        status.textContent = cooldownSeconds() ? '请求过于频繁，' + cooldownSeconds() + ' 秒后可重试；已保留正文' : refreshMessage;
        status.hidden = !status.textContent;
        floorForm.classList.toggle('has-status', !status.hidden);
        floorForm.classList.toggle('is-cooling-down', cooldownSeconds() > 0);
        jumpButton.disabled = cooldownSeconds() > 0 && !entries().some(el => Number(el.dataset.postNumber) === Number(floorInput.value));
        jumpButton.textContent = jumping ? '加载中…' : '跳转';
        updateProgress();
    }
    function request(direction, manual) {
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
    function reveal(target, message) {
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
    function jump(value) {
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
        const existing = entries().find(el => Number(el.dataset.postNumber) === floor);
        if (existing) { queuedFloor = null; clearTimeout(jumpTimer); reveal(existing, '已定位到 #' + floor + ' 楼'); if (refreshQueued) refresh(); return true; }
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
        jumping = {id: String(++jumpSequence), floor, version: navigationVersion};
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
            const next = entries().find(item => order.get(item.dataset.postId) > order.get(id));
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
        button.className = 'topic-nav-button topic-refresh-button';
        button.onclick = refresh;
        refreshButtons.push(button);
        floorForm.insertBefore(button, progressLabel);
    });
    floorForm.appendChild(status);
    window.linuxDoPagination = {
        refresh,
        jump,
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
                if (!error && target) reveal(target, '已定位到 #' + request.floor + ' 楼');
                else {
                    refreshMessage = '无法加载 #' + request.floor + ' 楼，楼层可能不存在或无权查看；可点击跳转重试';
                    update();
                    if (window.showDocToast) window.showDocToast(refreshMessage);
                }
            }
            update();
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
        },
        refreshed(key, ids, error, highest, retryAfterSeconds) {
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
            refreshMessage = added ? '发现 ' + added + ' 条新回复，向下阅读即可加载' : '已检查，暂无新回复';
            failed.before = failed.after = false;
            prefetchArmed = true;
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
            afterBatch();
        }
    };
    addEventListener('keydown', event => {
        if (event.key === 'F5') {
            event.preventDefault();
            if (!event.repeat) refresh();
        }
    });
    addEventListener('scroll', schedule, {passive: true});
    function userScroll(event) {
        if (event.target instanceof Node && floorForm.contains(event.target)) return;
        revealedReplyId = null;
        prefetchArmed = !cooldownSeconds() && !busy && !jumping && !refreshing;
    }
    addEventListener('wheel', userScroll, {passive: true});
    addEventListener('touchmove', userScroll, {passive: true});
    addEventListener('pointerdown', userScroll, {passive: true});
    addEventListener('keydown', event => {
        if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(event.key)) userScroll(event);
    });
    addEventListener('pagehide', () => {
        clearTimeout(sliderTimer); clearTimeout(jumpTimer); clearInterval(cooldownTimer);
    });
    addEventListener('resize', schedule);
    update();
    // Let initial last-read navigation settle before prefetching the visible edge.
    setTimeout(schedule, 1200);
})();
