"""Production Boost actions and public cards with an isolated browser fixture."""
import json
import argparse
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--headless', action='store_true', help='Launch installed Chrome headlessly instead of connecting to Chrome CDP')
args = parser.parse_args()
script = (ROOT/'src/main/resources/web/topic-reader.js').read_text(encoding='utf-8')
css = (ROOT/'src/main/resources/web/boost.css').read_text(encoding='utf-8')
# Include the actual renderer dispatcher: IMG clicks used to open its lightbox before the reader card.
renderer = (ROOT/'src/main/kotlin/com/lgguan/linuxdo/plugin/theme/TopicDocumentRenderer.kt').read_text(encoding='utf-8')
dispatcher = renderer.split('// --- Global Click Event Dispatcher ---',1)[1].split('</script>',1)[0].replace('${topic.id}','9')
setup = '''
window.linuxDoPage={key:'fixture',topic:9,loggedIn:true,hideAvatars:false};window.calls=[];window.links=[];window.toasts=[];
window.showDocToast=value=>toasts.push(value);window.mode='normal';window.flagOutcome='failure';window.delay=0;
window.lightboxCalls=[];window.getTagOrCategoryAnchor=()=>null;window.openLightbox=(...args)=>lightboxCalls.push(args);
window.intellijBridge={handleLinkClick:url=>links.push(url),readerAction:(key,id,action,post,input)=>{
  calls.push({action,post,input});let result;
  if(action==='boostActionsInfo')result=mode==='denied'?{types:[],canFlag:false,username:'booster'}:mode==='infoFailure'?{error:'HTTP 403'}:{canFlag:true,username:'booster',types:[{id:6,name:'其他',description:'举报说明',requireMessage:true}],readOnly:mode==='readonly'};
  else if(action==='profile')result=mode==='profileFailure'?{error:'HTTP 404'}:{username:input.username,name:'User',title:'Member',avatarUrl:mode==='missingAvatar'?'':mode==='brokenAvatar'?'https://linux.do/broken-avatar.png':'data:image/svg+xml,%3Csvg xmlns="http://www.w3.org/2000/svg" width="48" height="48"%3E%3C/svg%3E',bio_cooked:'<img onerror=bad()> plain text',trust_level:2,profileUrl:'https://linux.do/u/'+input.username};
  else if(action==='boostFlag')result=flagOutcome==='failure'?{error:'HTTP 422: invalid'}:flagOutcome==='cancel'?{cancelled:true,message:'已取消'}:flagOutcome==='unknown'?{error:'结果未确认',unconfirmed:true}:{message:'Boost 举报已提交'};
  else result={error:'unexpected action'};
  setTimeout(()=>linuxDoReaderResult(key,id,result),delay);
}};
'''
body='''<header class="doc-header"></header><main class="doc-container"><article class="post-entry" data-post-id="20" data-post-number="2"><div class="post-content">Retained body</div><span class="boost-bubble" data-boost-id="81"><button class="boost-user" data-reader-author="booster">avatar</button><button class="boost-content boost-expand" data-boost-expand="81" aria-expanded="false">Boost</button><span class="boost-bubble-actions" hidden><button class="boost-flag" data-boost-flag="81" hidden>举报</button></span></span></article></main>'''
body=body.replace('>avatar</button>','><img class="boost-avatar" data-user-avatar src="data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2224%22 height=%2224%22%3E%3C/svg%3E" alt=""><span class="boost-avatar boost-initial" aria-hidden="true" hidden>b</span></button>').replace('data-boost-flag="81"','data-boost-flag="81" data-boost-can-flag="true"')
html='<meta charset="utf-8"><style>:root{--bg:#222;--fg:#eee;--border:#555;--code-bg:#333;--comment:#aaa;--link:#69f;}'+css+'</style>'+body+'<script>'+(setup+dispatcher+script).replace('</script',r'<\/script')+'</script>'
checks=[]
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True, channel='chrome') if args.headless else p.chromium.connect_over_cdp('http://127.0.0.1:19337')
    context=browser.new_context(viewport={'width':800,'height':650})
    try:
        context.route('**/*',lambda r:r.fulfill(content_type='text/html',body=html))
        page=context.new_page();page.goto('http://127.0.0.1:8765/boost-actions')
        def check(name,value):
            assert value,(name,page.evaluate('({calls,expanded:document.querySelector(".boost-expand").outerHTML,actions:document.querySelector(".boost-bubble-actions").outerHTML})'))
            checks.append(name)
        def reset(): page.reload()
        def expanded():
            page.locator('.boost-expand').click()
            page.locator('.boost-flag').wait_for(state='visible')
        def form():
            expanded();page.locator('.boost-flag').click();page.get_by_label('Boost 举报说明',exact=True).wait_for()
        def close():page.get_by_role('button',name='关闭',exact=True).click()
        page.evaluate("delay=1200")
        immediate=page.evaluate("(()=>{document.querySelector('.boost-expand').click();const f=document.querySelector('.boost-flag');return !f.hidden&&!f.disabled&&calls.length===0})()")
        check('report is immediately clickable without expansion requests',immediate)
        page.locator('.boost-flag').click()
        check('delayed form permissions do not authorize early submission',page.get_by_role('button',name='确认举报 Boost').count()==0)
        page.get_by_label('Boost 举报说明',exact=True).wait_for()
        check('form performs a single permissions read',page.evaluate('calls.length===1&&calls[0].action==="boostActionsInfo"'))
        close()
        count=page.evaluate('calls.length')
        check('repeated expansion sends no permissions request',page.evaluate("(()=>{const b=document.querySelector('.boost-expand');b.click();b.click();b.click();return calls.length})()")==count)
        page.evaluate("document.querySelector('.boost-expand').click();mode='denied';delay=0")
        expanded();page.locator('.boost-flag').click();page.get_by_label('Boost 举报说明',exact=True).wait_for()
        check('report form rechecks current permissions',page.get_by_role('button',name='确认举报 Boost').is_disabled() and page.evaluate('calls.at(-1).action==="boostActionsInfo"'))
        reset()
        form()
        submit=page.get_by_role('button',name='确认举报 Boost',exact=True)
        check('required message disables submit',submit.is_disabled())
        page.get_by_label('Boost 举报说明',exact=True).fill('retained reason')
        submit.click();page.wait_for_function('document.querySelector(".topic-reader-panel").textContent.includes("HTTP 422")')
        check('rejection preserves reason and retry',page.get_by_label('Boost 举报说明',exact=True).input_value()=='retained reason' and submit.is_enabled())
        check('flag payload carries Boost ID and selected reason',page.evaluate('calls.at(-1).action==="boostFlag"&&calls.at(-1).post==="20"&&calls.at(-1).input.boostId===81&&calls.at(-1).input.type===6'))
        page.evaluate("flagOutcome='cancel'");submit.click();page.wait_for_function('document.querySelector(".topic-reader-panel").textContent.includes("已取消")')
        check('cancel retains input and enables explicit retry',submit.is_enabled() and page.get_by_label('Boost 举报说明',exact=True).input_value()=='retained reason')
        close();form();check('reopening retains unsent report draft',page.get_by_label('Boost 举报说明',exact=True).input_value()=='retained reason')
        page.evaluate("flagOutcome='success'");submit.click();page.wait_for_function('document.querySelector(".topic-reader-panel").textContent.includes("Boost 举报已提交")')
        check('success disables duplicate submit',submit.is_disabled())
        close();page.locator('img.boost-avatar').click();page.locator('.reader-user-card').wait_for()
        check('avatar loads Booster public card',page.evaluate('calls.at(-1).action==="profile"&&calls.at(-1).input.username==="booster"'))
        check('avatar IMG target bypasses production lightbox dispatcher',page.evaluate('lightboxCalls.length===0'))
        page.evaluate("(()=>{const img=document.createElement('img');img.src='https://linux.do/uploads/body.png';document.querySelector('.post-content').append(img);img.click()})()")
        check('ordinary body image still reaches production lightbox dispatcher',page.evaluate('lightboxCalls.length===1&&lightboxCalls[0][0].includes("body.png")'))
        check('public biography remains inert text',page.locator('.topic-reader-panel img[onerror]').count()==0 and '<img onerror=bad()>' in page.locator('.topic-reader-panel').inner_text())
        page.get_by_role('button',name='在网页查看完整资料').click();check('full profile opens selected user',page.evaluate('links.at(-1)==="https://linux.do/u/booster"'))
        close();expanded();check('expanded actions do not duplicate avatar profile entry',page.locator('.boost-bubble-actions [data-reader-author]').count()==0)
        reset();page.locator('.boost-user').focus();page.keyboard.press('Enter');page.locator('.reader-user-card img').wait_for()
        check('visible profile avatar retains original image',page.locator('.reader-user-card .boost-initial').is_hidden())
        for username in ['Reader','中文','𐐀reader','<reader','   ']:
            close();page.evaluate('(username)=>{linuxDoPage.hideAvatars=true;document.querySelector(".boost-user").dataset.readerAuthor=username}',username)
            page.locator('.boost-user').click();page.locator('.reader-user-card .boost-initial').wait_for()
            check('hidden profile initial '+repr(username),page.locator('.reader-user-card .boost-initial').inner_text()==(username[0] if username.strip() else '?') and page.locator('.reader-user-card img').count()==0)
            check('profile initial has 48px dimensions '+repr(username),page.locator('.reader-user-card .boost-initial').evaluate('(el)=>el.getBoundingClientRect().width===48&&el.getBoundingClientRect().height===48'))
        for mode in ['missingAvatar','brokenAvatar']:
            reset();page.evaluate('(mode)=>window.mode=mode',mode);page.locator('.boost-user').click();page.locator('.reader-user-card .boost-initial').wait_for()
            check('profile falls back for '+mode,page.locator('.reader-user-card .boost-initial').inner_text()=='b' and page.locator('.reader-user-card img').count()==0)
        reset();page.evaluate('document.querySelector("img.boost-avatar").src="https://linux.do/broken-avatar.png"');page.locator('.boost-user .boost-initial').wait_for()
        check('Boost image error reveals 24px initial',page.locator('.boost-user img').count()==0 and page.locator('.boost-user .boost-initial').evaluate('(el)=>el.textContent==="b"&&el.getBoundingClientRect().width===24&&el.getBoundingClientRect().height===24'))
        page.locator('.boost-user').focus();page.keyboard.press('Enter');page.locator('.reader-user-card').wait_for()
        check('fallback remains keyboard accessible and bypasses lightbox',page.evaluate('calls.at(-1).action==="profile"&&lightboxCalls.length===0'))
        close();page.evaluate('document.querySelector(".post-entry").insertAdjacentHTML("beforeend",\'<button data-reader-author="later"><img class="boost-avatar" data-user-avatar src="https://linux.do/broken-avatar.png"><span class="boost-avatar boost-initial" hidden>l</span></button>\')')
        page.locator('[data-reader-author=later] .boost-initial').wait_for()
        check('dynamically inserted Boost image falls back',page.locator('[data-reader-author=later] img').count()==0)
        reset();page.evaluate("mode='readonly'");form();check('read only disables report submission',page.get_by_role('button',name='确认举报 Boost').is_disabled())
        reset();page.evaluate("document.querySelector('.boost-flag').dataset.boostCanFlag='false'");expanded();check('loaded server denial disables report without a request',page.locator('.boost-flag').is_disabled() and page.evaluate('calls.length===0'))
        reset();page.evaluate("document.querySelector('.boost-flag').dataset.boostCanFlag='unknown';mode='denied'");form();check('unknown metadata permits reading the form but cannot submit without permission',page.get_by_role('button',name='确认举报 Boost').is_disabled())
        reset();page.evaluate("linuxDoPage.loggedIn=false");expanded();check('signed out report entry stays disabled without requests',page.locator('.boost-flag').is_disabled() and page.evaluate('calls.length===0'))
        reset();page.evaluate("document.querySelector('.boost-flag').remove();document.querySelector('.boost-bubble-actions').innerHTML='<button data-boost-delete=81><svg width=16 height=16 viewBox=\"0 0 24 24\"><path d=\"M3 6h18\"/></svg></button>'");page.locator('.boost-expand').click();check('own Boost only expands trash without permission reads',page.locator('[data-boost-delete] svg').is_visible() and page.locator('.boost-flag').count()==0 and page.evaluate('calls.length===0'))
        reset();page.evaluate("delay=400");page.locator('.boost-expand').click();page.locator('.boost-flag').click();page.locator('.boost-user').click();page.locator('.reader-user-card').wait_for();check('late form permissions do not replace user card or reopen bubble',page.locator('.boost-expand').get_attribute('aria-expanded')=='false' and page.get_by_label('Boost 举报说明',exact=True).count()==0)
        reset();page.evaluate("mode='profileFailure'");page.locator('.boost-user').click();page.get_by_role('button',name='重新读取').wait_for();page.evaluate("mode='normal'");page.get_by_role('button',name='重新读取').click();page.locator('.reader-user-card').wait_for();check('profile error offers explicit retry',page.locator('.reader-user-card').count()==1)
        reset();form();page.get_by_label('Boost 举报说明',exact=True).fill('reason');page.evaluate("flagOutcome='unknown'");page.get_by_role('button',name='确认举报 Boost').click();page.get_by_role('button',name='在网页核对').wait_for();check('uncertain submission blocks retry and provides web verification',page.get_by_role('button',name='确认举报 Boost').is_disabled())
    finally:
        context.close()
        if args.headless: browser.close()
output=ROOT/'build/boost-actions-browser';output.mkdir(parents=True,exist_ok=True)
result={'passed':len(checks),'checks':checks,'realForumWrites':0}
(output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result))
