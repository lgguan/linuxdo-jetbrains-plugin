"""Production reader layout checks with two ordinary posts and no forum requests."""
from pathlib import Path
import importlib.util,json
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/reader-layout';OUT.mkdir(parents=True,exist_ok=True)
spec=importlib.util.spec_from_file_location('reader_fixture',ROOT/'tools/reader-regression.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)

def main():
    checks=[]
    def check(label,condition):
        assert condition,label
        checks.append(label);print('PASS '+label,flush=True)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
        try:
            for dark,width,height in [(True,1547,1330),(False,1150,850),(True,360,800)]:
                context=browser.new_context(viewport={'width':width,'height':height})
                errors=[]
                html=(ROOT/f'build/layout-reader-{str(dark).lower()}.html').read_text(encoding='utf-8')
                def route(r):
                    if '/__linuxdo_plugin_assets/' in r.request.url:
                        r.fulfill(content_type='application/javascript',body=(ROOT/'src/main/resources/web/vendor'/r.request.url.split('/')[-1]).read_bytes())
                    elif r.request.url=='https://linux.do/__layout_fixture':
                        r.fulfill(content_type='text/html',body=html,headers={'Content-Security-Policy':fixture.CSP})
                    else:r.abort()
                context.route('**/*',route);context.add_init_script(fixture.SETUP)
                page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto('https://linux.do/__layout_fixture');page.wait_for_function('()=>window.linuxDoReaderBound')
                check('no unavailable post placeholders',page.locator('.action-disabled,[data-reader-action=edit],[data-reader-action=history],[data-reader-action=delete],[data-reader-action=accept],[data-reader-action=reactionUsers]').count()==0)
                check('metadata has no action bar',page.locator('.floor-comment-header .floor-actions').count()==0)
                check('primary operations stay compact',page.locator('#floor-1 .floor-actions > button').count()==1 and page.locator('#floor-2 .floor-actions > button').count()==3)
                check('boost is primary and share is secondary',page.locator('#floor-2 .floor-actions > [data-post-command=boost]').count()==1 and page.locator('.floor-actions > [data-post-command=share],.post-actions-menu-items [data-post-command=boost]').count()==0 and page.locator('.post-actions-menu-items [data-post-command=share]').count()==2)
                check('primary icons have accessible labels',page.locator('.floor-actions > button').evaluate_all("items=>items.every(b=>b.querySelector('svg.reader-icon') && b.getAttribute('aria-label') && b.title)"))
                check('reading tools are anchored to document header',page.locator('.topic-reader-tools').evaluate("el=>{const r=el.getBoundingClientRect(),h=el.closest('.doc-header').getBoundingClientRect();return getComputedStyle(el).position==='absolute'&&Math.abs(r.top-h.top)<2&&Math.abs(r.right-h.right)<2&&!el.closest('.topic-navigation')}"))
                check('floor metadata is concise',page.locator('.floor-number').evaluate_all("items=>items.every(el=>el.querySelector('.floor-label')&&el.querySelector('[data-reader-author]')&&!/Original Specification|Revision|---/.test(el.textContent))"))
                check('bookmark has one icon without stars',page.locator('[data-reader-action=bookmark]').evaluate_all("items=>items.every(el=>el.querySelectorAll('svg').length===1&&!/[★☆]/.test(el.textContent)&&el.textContent==='收藏')"))
                page.evaluate('linuxDoPagination.jump(2)');page.wait_for_timeout(80)
                check('location status and floor shortcuts share a row',page.evaluate("(()=>{const a=document.querySelector('.topic-navigation-status').getBoundingClientRect(),b=document.querySelector('.floor-jump-controls').getBoundingClientRect();return !document.querySelector('.topic-navigation-status').hidden && Math.abs((a.top+a.bottom)/2-(b.top+b.bottom)/2)<2})()"))
                page.wait_for_timeout(2500)
                check('secondary operations are initially hidden',not page.locator('[data-reader-action=bookmark]').first.is_visible())
                check('body precedes operations consistently',page.evaluate("[...document.querySelectorAll('.post-entry')].every(p=>p.querySelector('.floor-actions').getBoundingClientRect().top>=p.querySelector('.post-content').getBoundingClientRect().bottom)"))
                check('buttons share height and font',page.locator('.floor-actions > button').evaluate_all("items=>new Set(items.map(b=>b.getBoundingClientRect().height)).size===1 && new Set(items.map(b=>getComputedStyle(b).fontSize)).size===1"))
                check('no horizontal overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                page.screenshot(path=str(OUT/f'layout-{str(dark).lower()}-{width}.png'))
                page.locator('#floor-2 .post-actions-menu > summary').click();page.wait_for_timeout(80)
                check('more contains only available secondary actions',page.locator('#floor-2 .post-actions-menu-items button:visible').count()==3)
                check('post menu stays inside viewport',page.locator('#floor-2 .post-actions-menu-items').evaluate('(el)=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight}'))
                page.screenshot(path=str(OUT/f'more-{str(dark).lower()}-{width}.png'))
                page.keyboard.press('Escape');check('escape closes post menu',page.locator('.post-actions-menu[open]').count()==0)
                page.locator('.topic-reader-tools > summary').click();page.wait_for_timeout(80)
                check('inapplicable reading tools are hidden',all(not page.get_by_role('button',name=name,exact=True).count() for name in ['目录','未读','全部楼层']))
                check('reading menu stays inside viewport',page.locator('.topic-reader-menu').evaluate('(el)=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight}'))
                page.screenshot(path=str(OUT/f'tools-{str(dark).lower()}-{width}.png'))
                page.set_viewport_size({'width':360 if width>600 else 1200,'height':700})
                page.wait_for_timeout(150)
                check('open reading menu follows dynamic resize',page.locator('.topic-reader-menu').evaluate('(el)=>{const r=el.getBoundingClientRect();return r.left>=0&&r.right<=innerWidth&&r.top>=0&&r.bottom<=innerHeight}') and page.locator('.topic-reader-tools').evaluate('el=>{const r=el.getBoundingClientRect(),h=el.closest(".doc-header").getBoundingClientRect();return Math.abs(r.right-h.right)<2}'))
                check('dynamic resize reflows content without overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth&&document.querySelector(".doc-container").getBoundingClientRect().right<=innerWidth'))
                page.set_viewport_size({'width':width,'height':height});page.wait_for_timeout(150)
                check('read acknowledgment advances only contiguous unread floors',page.evaluate("(()=>{linuxDoReaderResult('fixture','unused',{topic:{unreadFloor:1}});applyDocRead([2]);const kept=linuxDoPage.unreadFloor===1;applyDocRead([1]);return kept&&linuxDoPage.unreadFloor===null&&[...document.querySelectorAll('.unread-dot')].every(el=>getComputedStyle(el).display==='none')&&[...document.querySelectorAll('.topic-reader-menu button')].find(el=>el.textContent==='未读').hidden})()"))
                page.evaluate("linuxDoReaderResult('fixture','unused',{topic:{loggedIn:false,notificationLevel:null}})")
                check('account controls disappear when unavailable',not page.get_by_role('button',name='通知',exact=True).count() and not page.get_by_role('button',name='我的书签',exact=True).count())
                page.evaluate("document.querySelector('.post-content').insertAdjacentHTML('beforeend','<h2>新的标题</h2>')")
                page.wait_for_timeout(60)
                check('directory appears when headings become available',page.get_by_role('button',name='目录',exact=True).count()==1)
                check('no writes or javascript errors',not errors and page.evaluate('readerCalls.length===0'))
                context.close()
        finally:browser.close()
    (OUT/'result.json').write_text(json.dumps({'passed':len(checks),'checks':checks},ensure_ascii=False,indent=2),encoding='utf-8')

if __name__=='__main__':main()
