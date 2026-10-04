"""Production reader bookmark entry opens the native personal center; isolated fixture only."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', default='http://127.0.0.1:19337')
args = parser.parse_args()
script = (ROOT / 'src/main/resources/web/topic-reader.js').read_text(encoding='utf-8')
setup = '''
window.linuxDoPage={key:'fixture',topic:9,loggedIn:true};window.calls=[];window.links=[];window.toasts=[];window.failNext=false;
window.showDocToast=value=>toasts.push(value);
window.intellijBridge={handleLinkClick:url=>links.push(url),readerAction:(key,id,action,post,input)=>{
  calls.push({action,input});let result;
  if(failNext){failNext=false;result={error:'HTTP 403'};}
  else result={message:'已打开我的书签'};
  setTimeout(()=>linuxDoReaderResult(key,id,result),0);
}};
'''
html = '<!doctype html><meta charset="utf-8"><header class="doc-header"></header><main class="doc-container"></main><script>'+(setup+script).replace('</script', r'<\/script')+'</script>'
checks = []
with sync_playwright() as playwright:
    browser = playwright.chromium.connect_over_cdp(args.endpoint)
    context = browser.new_context(viewport={'width': 1000, 'height': 700})
    try:
        context.route('**/*', lambda route: route.fulfill(content_type='text/html', body=html))
        page = context.new_page()
        page.goto('http://127.0.0.1:8765/personal-reader-fixture')
        page.locator('.topic-reader-tools summary').click()
        page.get_by_role('button', name='我的书签', exact=True).click()
        page.wait_for_function('calls.length===1 && toasts.length===1')

        def check(name, condition):
            assert condition, name
            checks.append(name)

        check('bookmark entry calls native personal center', page.evaluate('calls[0].action==="openBookmarks" && Object.keys(calls[0].input).length===0'))
        check('no independent bookmark list or browser navigation', page.evaluate('!document.querySelector(".topic-reader-panel") && links.length===0'))
        page.evaluate('failNext=true')
        page.get_by_role('button', name='我的书签', exact=True).click()
        page.wait_for_function('toasts.length===2')
        check('failed native entry reports error without losing content', page.evaluate('toasts[1]==="HTTP 403" && !document.querySelector(".topic-reader-panel")'))
        page.get_by_role('button', name='我的书签', exact=True).click()
        page.wait_for_function('toasts.length===3')
        check('explicit retry opens native entry', page.evaluate('calls.length===3 && calls.every(c=>c.action==="openBookmarks")'))
    finally:
        context.close()
result = {'passed': len(checks), 'checks': checks, 'transport': 'isolated browser fixture', 'realForumWrites': 0}
output = ROOT / 'build/personal-browser'
output.mkdir(parents=True, exist_ok=True)
(output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
