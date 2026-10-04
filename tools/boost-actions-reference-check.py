"""Inspect real Boost actions without submitting a flag or other forum writes."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
output = ROOT / 'build/boost-actions-reference'
output.mkdir(parents=True, exist_ok=True)
with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp('http://127.0.0.1:19337')
    context = next(c for c in browser.contexts if any(x.url.startswith('https://linux.do/') for x in c.pages))
    forum_page = next(x for x in context.pages if x.url.startswith('https://linux.do/'))
    preflight = forum_page.evaluate('''async () => {
      const response=await fetch('/t/482293.json?track_visit=false',{headers:{Accept:'application/json'},credentials:'include'});
      if(!response.ok)return {status:response.status,writes:0};
      const topic=await response.json();
      const post=topic.post_stream?.posts?.find(p=>p.boosts?.length);
      return {status:response.status,writes:0,floor:post?.post_number,boostId:post?.boosts?.[0]?.id,
        fields:Object.keys(post?.boosts?.[0]||{})};
    }''')
    (output/'preflight.json').write_text(json.dumps(preflight,ensure_ascii=False,indent=2),encoding='utf-8')
    assert preflight['status']==200, f"Stopped on HTTP {preflight['status']}; no retry or write"
    assert preflight.get('boostId'), 'No Boost available in the first topic page'
    page = context.new_page()
    page.route('**/*', lambda r: r.continue_() if r.request.method in ('GET', 'HEAD') else r.abort())
    try:
        page.goto('https://linux.do/t/482293/'+str(preflight['floor']), wait_until='domcontentloaded')
        page.locator('.discourse-boosts__bubble').first.wait_for(timeout=30000)
        bubble = page.locator('.discourse-boosts__bubble').first
        bubble.locator('.discourse-boosts__cooked').click()
        result = page.evaluate('''() => ({
          bubble:document.querySelector('.discourse-boosts__bubble')?.outerHTML,
          buttons:[...document.querySelectorAll('.discourse-boosts__bubble button')].map(b=>({title:b.title,label:b.getAttribute('aria-label'),class:b.className})),
          scripts:[...document.scripts].map(s=>s.src).filter(s=>s.includes('boost'))
        })''')
        (output/'bubble.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in result.items() if k!='bubble'},ensure_ascii=False))
        # Save only the component markup; no cookies, auth headers or account bootstrap data.
    finally:
        page.close()
