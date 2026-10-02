"""Account-free production reader regression. All HTTP is served from local fixtures."""
import argparse,base64,json,time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/reader-regression'
OUT.mkdir(parents=True,exist_ok=True)
CSP="default-src 'none'; script-src 'unsafe-inline' https://linux.do/__linuxdo_plugin_assets/; style-src 'unsafe-inline'; img-src https: data:; media-src https:; frame-src https://player.bilibili.com https://www.youtube-nocookie.com; connect-src 'none'; base-uri https://linux.do; object-src 'none'"
SETUP=r"""
window.readerCalls=[];window.copied=[];window.bridgeCalls=[];
window.fakeFragment=ids=>ids.map(id=>`<div class="post-entry" id="floor-${id}" data-post-id="${id}" data-post-number="${id}" data-author="author" data-polls="[]"><div class="floor-comment-header"><div class="floor-actions"><button data-reader-action="bookmark">收藏</button></div></div><div class="post-content" data-source="${id}"><h3 id="heading-${id}">标题 ${id}</h3><p>${'模拟正文 '.repeat(20)}</p><details><summary>详情</summary><p>展开状态</p></details></div></div>`).join('');
window.linuxDoJumpFloor=(key,id,floor)=>{readerCalls.push({action:'jump',floor,time:Date.now()});const start=Math.max(1,Math.min(9981,floor-9));setTimeout(()=>linuxDoPagination.jumped(key,id,linuxDoPage.stream,fakeFragment(Array.from({length:20},(_,i)=>start+i)),false,10000,0),20);};
window.linuxDoLoadPosts=(key,direction,ids)=>{readerCalls.push({action:'load',ids,time:Date.now()});setTimeout(()=>linuxDoPagination.receive(key,direction,fakeFragment(ids),false),20);};
window.linuxDoRefreshPosts=key=>{readerCalls.push({action:'refresh'});setTimeout(()=>linuxDoPagination.refreshed(key,linuxDoPage.stream,false,10000,0),20);};
window.intellijBridge={
 copyCode:(text,id)=>{copied.push(text);window.docCodeCopyResult?.(id,true);},
 copyImage:()=>bridgeCalls.push('copyImage'),copyImageFile:()=>bridgeCalls.push('copyImageFile'),saveImage:()=>bridgeCalls.push('saveImage'),
 handleLinkClick:url=>bridgeCalls.push(url),quoteReply:(floor,text)=>bridgeCalls.push({floor,text}),copyPostLink:(topic,floor)=>bridgeCalls.push({topic,floor}),
 readerAction:(key,id,action,postId,input)=>{readerCalls.push({action,postId,input});let response={message:'已同步'};
   if(action==='search')response={items:[{floor:7000,author:'author',text:'服务器搜索摘要'}],more:input.page===1};
   if(action==='bookmarkInfo')response={bookmarked:false,name:'',reminder:null};
   if(action==='flagInfo')response={types:[{id:99,name:'社区自定义原因',description:'必须填写说明',requireMessage:true}]};
   if(action==='reactionInfo')response={reactions:['heart','tada'],current:'heart'};
   if(action==='pollInfo')response={undo:false,groups:['members'],readOnly:false};
   if(action==='context')response={items:[{floor:input.floor,author:'author',text:'引用上下文'}]};
   if(action==='profile')response={username:input.username,name:'公开用户',title:'成员'};
   if(action==='filter')response={ids:['1','11'],fragment:fakeFragment([1,11])};
   if(action==='edge')response={floor:input.last?11:1};
   if(action==='like')response={html:document.querySelector('[data-post-id="'+postId+'"]')?.outerHTML.replace('♥ 1','♥ 2')};
   setTimeout(()=>linuxDoReaderResult(key,id,response),10);
 }
};
"""

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--endpoint');args=parser.parse_args()
    results=[];requested=[];errors=[]
    def check(label,condition):
        assert condition,label
        results.append(label)
        print('PASS '+label,flush=True)
    with sync_playwright() as p:
        browser=p.chromium.connect_over_cdp(args.endpoint) if args.endpoint else p.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
        context=browser.new_context(viewport={'width':1150,'height':800})
        html=(ROOT/'build/reader-fixture-true.html').read_text(encoding='utf-8')
        def route(request):
            url=request.request.url;requested.append(url)
            if '/__linuxdo_plugin_assets/' in url:
                name=url.split('/')[-1];request.fulfill(content_type='application/javascript',body=(ROOT/'src/main/resources/web/vendor'/name).read_bytes())
            elif url.startswith('https://linux.do/__reader_fixture'):
                request.fulfill(content_type='text/html',body=html,headers={'Content-Security-Policy':CSP})
            elif url.startswith('https://fixture.test/'):
                request.fulfill(content_type='image/png',body=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jvLkAAAAASUVORK5CYII='))
            elif 'youtube-nocookie.com' in url or 'player.bilibili.com' in url:
                request.fulfill(content_type='text/html',body='<p>Mock controlled player</p>')
            else:request.abort()
        context.route('**/*',route);context.add_init_script(SETUP)
        page=context.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto('https://linux.do/__reader_fixture');page.wait_for_function('()=>window.linuxDoReaderBound')
        page.wait_for_function('()=>document.querySelectorAll(".forum-source-output svg").length===3',timeout=45000)
        check('offline MathJax and Mermaid render with CSP',page.locator('.forum-render-error').count()==0)
        check('generated diagrams cannot invoke bridge',page.locator('.forum-source-output [onclick],.forum-source-output a,.forum-source-output foreignObject').count()==0)
        check('no player requests before explicit click',not any('youtube-nocookie.com' in url or 'player.bilibili.com' in url for url in requested))
        check('no runtime CDN requests',not any('cdn.' in url or 'jsdelivr' in url for url in requested))
        page.screenshot(path=str(OUT/'reader-media-dark.png'),full_page=True)
        check('polls require server permission before participation',page.locator('.reader-poll button').filter(has_text='提交投票').evaluate_all('(items)=>items.every(item=>item.disabled)'))
        page.get_by_role('button',name='参与投票',exact=True).click();page.wait_for_timeout(50)
        check('poll permission read never writes a vote',page.evaluate('readerCalls.filter(c=>c.action==="pollInfo").length===1 && !readerCalls.some(c=>c.action==="poll")'))
        forms=page.locator('.reader-poll')
        check('forum setting disables removal',forms.nth(1).get_by_role('button',name='撤回投票').is_disabled())
        forms.nth(0).locator('input').first.check();forms.nth(0).get_by_role('button',name='提交投票').click();page.wait_for_timeout(30)
        check('single poll submits only explicitly selected option',page.evaluate('readerCalls.find(c=>c.action==="poll").input.options.join() === "a"'))
        forms.nth(1).locator('input').nth(2).check()
        check('multiple poll enforces maximum choices',forms.nth(1).get_by_role('button',name='提交投票').is_disabled())
        forms.nth(2).locator('input').nth(1).check();forms.nth(2).get_by_role('button',name='提交投票').click();page.wait_for_timeout(30)
        check('numeric poll preserves opaque option ID',page.evaluate('readerCalls.filter(c=>c.action==="poll").at(-1).input.options.join() === "two"'))
        forms.nth(3).get_by_role('button',name='↓',exact=True).first.click();forms.nth(3).get_by_role('button',name='提交投票').click();page.wait_for_timeout(30)
        check('ranked poll submits user order',page.evaluate('readerCalls.filter(c=>c.action==="poll").at(-1).input.options.join() === "b,a"'))
        page.screenshot(path=str(OUT/'reader-polls.png'))
        page.locator('.forum-code-block').first.get_by_role('button',name='复制代码',exact=True).click()
        check('code copy preserves raw indentation and newlines',page.evaluate('(expected)=>copied[0]===expected','fun answer(): Int {\n    return 42\n}\n'))
        page.locator('.forum-code-block').first.get_by_role('button',name='换行',exact=True).click()
        check('code wrap toggles',page.locator('pre.forum-code-wrap').count()==1)
        page.locator('.forum-code-block').first.get_by_role('button',name='放大',exact=True).click()
        check('code enlarge opens accessible dialog',page.locator('dialog[open]').count()==1);page.locator('dialog').get_by_role('button',name='关闭',exact=True).click()
        page.locator('details').filter(has=page.locator('.spoiler')).locator('summary').click()
        spoiler=page.locator('.spoiler').first;spoiler.click();check('spoiler reveals',spoiler.evaluate('(el)=>el.classList.contains("revealed")'))
        spoiler.click();check('spoiler hides again',not spoiler.evaluate('(el)=>el.classList.contains("revealed")'))
        page.locator('.forum-embed').first.get_by_role('button',name='加载播放器').click()
        check('click loads only approved controlled player',page.locator('.embedded-video-frame').get_attribute('src').startswith('https://www.youtube-nocookie.com/embed/'))
        image=page.locator('.post-content img').first;image.click();page.wait_for_function('()=>document.querySelector("#img-lightbox-overlay").classList.contains("active")')
        page.keyboard.press('ArrowRight');check('same post gallery advances by keyboard','second.png' in page.locator('#img-lb-img').get_attribute('src'))
        page.keyboard.press('Escape');image.click(button='right');check('image menu has all four actions',page.locator('.doc-image-menu button').count()==4)
        page.locator('.doc-image-menu').get_by_role('menuitem',name='保存图片',exact=True).click();check('image save is explicitly initiated',page.evaluate('bridgeCalls.includes("saveImage")'))
        page.locator('.topic-reader-tools summary').click()
        page.get_by_role('button',name='只看楼主',exact=True).click();page.wait_for_timeout(40)
        check('author filter keeps original server floor numbers',page.evaluate('linuxDoPage.filtered && [...document.querySelectorAll(".post-entry")].map(p=>p.dataset.postNumber).join() === "1,11"'))
        page.get_by_role('button',name='末楼',exact=True).click();page.wait_for_timeout(40)
        check('filtered last floor reads server edge instead of global last floor',page.evaluate('readerCalls.some(c=>c.action==="edge" && c.input.last)'))
        page.get_by_role('button',name='全部楼层',exact=True).click();page.wait_for_timeout(40)
        page.get_by_role('button',name='帖内搜索',exact=True).click()
        page.locator('.topic-reader-panel input').fill('正文');page.get_by_role('button',name='查找已加载内容',exact=True).click()
        check('local search searches loaded body',page.locator('.topic-reader-result').count()>=1)
        page.get_by_role('button',name='搜索整个话题',exact=True).click();page.wait_for_timeout(60)
        check('topic search uses one page request',page.evaluate('readerCalls.filter(c=>c.action==="search").length===1'))
        page.get_by_role('button',name='下一页',exact=True).click();page.wait_for_timeout(60)
        check('search pagination is explicit',page.evaluate('readerCalls.filter(c=>c.action==="search").length===2'))
        page.locator('.topic-reader-panel').get_by_role('button',name='#7000 @author').first.click();page.wait_for_function('()=>document.querySelector("#floor-7000")')
        check('search result loads neighborhood instead of entire topic',page.evaluate('linuxDoPagination.stats().nodes<50'))
        page.locator('.topic-reader-panel').get_by_role('button',name='关闭',exact=True).click()
        page.evaluate('linuxDoPagination.jump(3)');page.wait_for_function('()=>document.querySelector("#floor-3")')
        page.evaluate('document.querySelector("#floor-3 details").open=true;window.beforeY=scrollY;window.beforeBody=document.querySelector("#floor-3 .post-content")')
        page.locator('#floor-3').get_by_role('button',name='收藏',exact=True).click();page.wait_for_timeout(60)
        check('bookmark reads before user writes',page.evaluate('readerCalls.filter(c=>c.action==="bookmark").length===0'))
        page.locator('.topic-reader-panel').get_by_role('button',name='保存收藏',exact=True).click();page.wait_for_timeout(60)
        check('bookmark write follows click',page.evaluate('readerCalls.filter(c=>c.action==="bookmark").length===1'))
        page.locator('.topic-reader-panel').get_by_role('button',name='关闭',exact=True).click()
        # Cover 10,000 indexed floors and >400 visited bodies through production receive/jump paths.
        page.evaluate('''async()=>{for(let start=100;start<1100;start+=20){linuxDoPagination.showReply('fixture',String(start),fakeFragment([start]));let ids=Array.from({length:19},(_,i)=>String(start+i+1));linuxDoPagination.filter;window.linuxDoLoadPosts;linuxDoPagination.jump(start+10);await new Promise(r=>setTimeout(r,825));}}''')
        stats=page.evaluate('linuxDoPagination.stats()')
        check('10k stream retains at most 200 complete posts',stats['nodes']<=200)
        check('cache retains at most 400 posts and 32 MiB',stats['cached']<=400 and stats['bytes']<=32*1024*1024)
        check('removed bodies keep measured height placeholders',stats['placeholders']>0)
        page.evaluate('linuxDoPagination.jump(1)');page.wait_for_function('()=>document.querySelector("#floor-1")')
        check('evicted first floor can be restored',page.evaluate('document.querySelector("#floor-progress").value==="1"'))
        # A stable-body patch preserves the same nodes, selection and expanded state.
        page.evaluate('''()=>{const post=document.querySelector('#floor-1');post.querySelector('details').open=true;const text=post.querySelector('p').firstChild;const range=document.createRange();range.setStart(text,0);range.setEnd(text,4);getSelection().removeAllRanges();getSelection().addRange(range);window.stableBody=post.querySelector('.post-content');window.patchY=scrollY;const copy=post.cloneNode(true);copy.querySelector('.floor-actions').textContent='Updated';linuxDoPagination.patch(copy.outerHTML);}''')
        check('local updates preserve body selection and details',page.evaluate('stableBody===document.querySelector("#floor-1 .post-content") && getSelection().toString().length===4 && document.querySelector("#floor-1 details").open'))
        check('local updates preserve scroll',page.evaluate('Math.abs(scrollY-patchY)<2'))
        page.evaluate('''()=>{const post=document.querySelector('#floor-1');const copy=post.cloneNode(true);copy.querySelector('.post-content').dataset.source='changed';copy.querySelector('p').append(' 新内容');linuxDoPagination.patch(copy.outerHTML);}''')
        check('changed body restores surviving selection and details',page.evaluate('getSelection().toString().length===4 && document.querySelector("#floor-1 details").open'))
        for dark,width in [(True,1150),(False,1150),(True,360)]:
            page.set_viewport_size({'width':width,'height':800})
            if not dark:page.evaluate('document.documentElement.style.cssText="--bg:#fff;--fg:#24292f;--comment:#66707b;--link:#0969da;--border:#d8dee4;--code-bg:#f6f8fa;--title-color:#1f2328"')
            page.screenshot(path=str(OUT/f'reader-{dark}-{width}.png'))
            check(f'no horizontal overflow at {width}px',page.evaluate('document.documentElement.scrollWidth<=innerWidth+2'))
        check('no uncaught JavaScript errors',not errors)
        # Fresh production theme fixtures for delivery screenshots, before virtualization removes media.
        for dark,width in [(True,1150),(False,1150),(True,360)]:
            html=(ROOT/f'build/reader-fixture-{str(dark).lower()}.html').read_text(encoding='utf-8')
            page.set_viewport_size({'width':width,'height':800});page.goto('https://linux.do/__reader_fixture')
            page.wait_for_function('()=>document.querySelectorAll(".forum-source-output svg").length===3')
            check(f'fresh theme has no overflow {dark}-{width}',page.evaluate('document.documentElement.scrollWidth<=innerWidth+2'))
            page.screenshot(path=str(OUT/f'reader-media-{str(dark).lower()}-{width}.png'))
        (OUT/'result.json').write_text(json.dumps({'checks':results,'stats':stats,'requests':requested,'errors':errors},ensure_ascii=False,indent=2),encoding='utf-8')
        context.close();browser.close()
    print('READER_BROWSER_PASS=true')

if __name__=='__main__':main()
