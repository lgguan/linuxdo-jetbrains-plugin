"""Generate a local, account-free long-topic fixture using production CSS and JS.

Run Gradle's TopicPresentationTest first to generate the base document.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
output = ROOT / "output/playwright"
output.mkdir(parents=True, exist_ok=True)
document = (ROOT / "build/topic-media-false.html").read_text(encoding="utf-8")
css = re.search(r"<style\b[^>]*>(.*?)</style>", document, re.S).group(1)
source = (ROOT / "src/main/resources/web/topic-pagination.js").read_text(encoding="utf-8")
setup = r"""
window.calls = [];
window.hold = false;
window.failNext = 0;
window.allIds = Array.from({length:6386}, (_, i) => String(i+1));
window.fragment = ids => ids.map(id => `<article class="post-entry" data-post-id="${id}" data-post-number="${id}"><div class="post-meta" style="display:flex;justify-content:space-between;color:var(--comment);font-size:12px"><span>● &nbsp; // --- [Revision #${id}] @community_member</span><span>♡ Like &nbsp; Reply &nbsp; Share</span></div><div class="post-content"><p>${Number(id)%2 ? '每日打卡，支持社区！一起分享开发经验。' : '感谢分享，期待更多有趣的项目与讨论。'}</p></div></article>`).join('');
window.linuxDoPage = {key:'test', stream:allIds, highest:6386};
const offset = Number(new URLSearchParams(location.search).get('start') || 1) - 1;
document.querySelector('.doc-container').innerHTML = fragment(allIds.slice(offset,offset+20));
window.respondJump = call => {
    const retry = failNext; failNext=0;
    const start = Math.max(0,Math.min(6366,call.floor-10));
    linuxDoPagination.jumped('test',call.id,allIds,retry?'':fragment(allIds.slice(start,start+20)),!!retry,6386,retry);
};
window.linuxDoJumpFloor = (key,id,floor) => {
    const call={type:'jump',key,id,floor,time:Date.now()}; calls.push(call);
    if(!hold) setTimeout(()=>respondJump(call),40);
};
window.linuxDoLoadPosts = (key,direction,ids) => {
    calls.push({type:'page',key,direction,ids,time:Date.now()});
    if(!hold) setTimeout(()=>linuxDoPagination.receive(key,direction,fragment(ids),false),40);
};
window.linuxDoRefreshPosts = key => {
    calls.push({type:'refresh',time:Date.now()});
    if(!hold) setTimeout(()=>linuxDoPagination.refreshed(key,allIds,false,6386),40);
};
document.documentElement.style.cssText='--bg:#1e1f22;--fg:#dfe1e5;--comment:#939ba5;--link:#6aa7ed;--border:#383a40;--code-bg:#26282e;--title-color:#ffffff';
if(location.search.includes('light')) document.documentElement.style.cssText='--bg:#ffffff;--fg:#24292f;--comment:#66707b;--link:#0969da;--border:#d8dee4;--code-bg:#f6f8fa;--title-color:#1f2328';
"""
(output / "navigation-fixture.html").write_text(
    '<!doctype html><html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
    '<title>Linux Do navigation fixture</title><style>' + css + '</style><body>'
    '<header class="doc-header"><h1 class="doc-title">社区每日打卡 · 分享开发生活</h1>'
    '<div class="doc-meta-comment">// Issue #847468 · 6386 replies</div></header>'
    '<main class="doc-container"></main><script>' + setup + source + '</script></body></html>',
    encoding="utf-8")
print(output / "navigation-fixture.html")
