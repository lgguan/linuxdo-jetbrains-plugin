"""Production bookmark reader script in an isolated browser; no forum transport."""
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
window.linuxDoPage={key:'fixture',topic:9,loggedIn:true};window.calls=[];window.links=[];window.executed=false;window.failNext=false;
window.intellijBridge={handleLinkClick:url=>links.push(url),readerAction:(key,id,action,post,input)=>{
  calls.push({action,input});let result;
  if(failNext){failNext=false;result={error:'HTTP 403'};}
  else if(input.page===0)result={items:[
    {title:'<img src=x onerror="executed=true">',name:'<script>executed=true</script>',topic:9,floor:7},
    {title:'Topic bookmark',topic:10,floor:null}, {title:'Unknown bookmark',topic:null,floor:null,url:null}
  ],more:true,nextPage:3};else result={items:[],more:false};
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
        page.wait_for_function('calls.length===1 && document.querySelectorAll(".topic-reader-panel button").length>=4')

        def check(name, condition):
            assert condition, name
            checks.append(name)

        check('title and name stay text without HTML execution', page.evaluate('!executed && !document.querySelector(".topic-reader-panel img,.topic-reader-panel script")'))
        page.get_by_role('button', name='<img src=x onerror="executed=true"> · #7', exact=True).click()
        page.get_by_role('button', name='Topic bookmark', exact=True).click()
        check('post floor and topic-only links preserve server targets', page.evaluate('JSON.stringify(links)===JSON.stringify(["https://linux.do/t/9/7","https://linux.do/t/10"])'))
        check('unknown target is disabled', page.get_by_role('button', name='Unknown bookmark', exact=True).is_disabled())
        page.evaluate('failNext=true')
        page.get_by_role('button', name='加载下一页', exact=True).click()
        page.get_by_role('button', name='重试当前页', exact=True).wait_for()
        check('continuation uses validated page rather than increment', page.evaluate('calls[1].input.page===3'))
        page.get_by_role('button', name='重试当前页', exact=True).click()
        page.wait_for_function('calls.length===3 && [...document.querySelectorAll(".topic-reader-panel button")].some(b=>b.textContent==="加载下一页" && b.hidden)')
        check('failed continuation retries same page', page.evaluate('calls[2].input.page===3'))
        check('empty terminal page stops loading', page.get_by_role('button', name='加载下一页', exact=True).is_hidden())
    finally:
        context.close()
result = {'passed': len(checks), 'checks': checks, 'transport': 'isolated browser fixture', 'realForumWrites': 0}
output = ROOT / 'build/personal-browser'
output.mkdir(parents=True, exist_ok=True)
(output / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
