"""Read native advanced-search controls and public tag metadata without forum writes."""
import json
import argparse
import importlib.util
import time
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-requests', action='store_true')
args = parser.parse_args()
with sync_playwright() as p:
    browser = p.chromium.connect_over_cdp('http://127.0.0.1:19337')
    context = browser.contexts[0]
    page = context.new_page()
    try:
        page.goto('https://linux.do/search?expanded=true', wait_until='domcontentloaded')
        page.wait_for_selector('.search-advanced-options', timeout=30000)
        result = page.evaluate('''() => {
          const container = Discourse.__container__;
          const settings = container.lookup('service:site-settings');
          const current = container.lookup('service:current-user');
          const controller = container.lookup('controller:full-page-search');
          const advanced = container.factoryFor('component:search-advanced-options')?.create({searchTerm:''});
          const nativeFilters = advanced ? {inOptions:advanced.inOptions,statusOptions:advanced.statusOptions,postTimeOptions:advanced.postTimeOptions,
            expertKeys:Object.keys(advanced).filter(k=>/expert/i.test(k)),
            expertMethods:Object.getOwnPropertyNames(Object.getPrototypeOf(advanced)).filter(k=>/expert/i.test(k)).map(k=>({name:k,source:typeof advanced[k]==='function'?advanced[k].toString():String(advanced[k])}))} : {};
          advanced?.destroy();
          return {url:location.href, loggedIn:!!current, taggingEnabled:settings.tagging_enabled,
            nativeFilters,
            featureSettings:Object.fromEntries(Object.keys(settings).filter(k=>/solved|vot|expert|personal|tagging/.test(k)).map(k=>[k,settings[k]])),
            personalMessagesEnabled:settings.enable_personal_messages,
            options:[...document.querySelectorAll('.search-advanced-options select option')].map(e=>({value:e.value,label:e.textContent})),
            controls:document.querySelector('.search-advanced-options').innerText,
            sortOrders:controller.sortOrders.map(o=>({id:o.id,term:o.term,name:o.name})),writes:0};
        }''')
        target = ROOT / 'build/search-reference'
        target.mkdir(parents=True, exist_ok=True)
        (target/'linuxdo.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        page.screenshot(path=str(target/'linuxdo-advanced-search.png'), full_page=True)
        if args.check_requests:
            spec = importlib.util.spec_from_file_location('read_guards', Path(__file__).with_name('draft-handoff.py'))
            guards = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(guards)
            if guards.cooldown_remaining():
                raise RuntimeError('Forum cooldown active; no additional requests issued')
            checks = []
            for path in ['/tags.json', '/tags/c/4/%E7%BA%AF%E6%B0%B4/l/top.json?page=0&period=daily', '/tags/c/4/%E7%BA%AF%E6%B0%B4/l/unread.json?page=0']:
                time.sleep(5)
                check = page.evaluate('''async path => {
                  const response=await fetch(path,{method:'GET',credentials:'same-origin',headers:{Accept:'application/json'}});
                  const text=await response.text(); let data={};try{data=JSON.parse(text)}catch{}
                  return {path,status:response.status,retryAfter:response.headers.get('retry-after'),
                    verificationRequired:response.headers.get('cf-mitigated')==='challenge'||/cf-chl-opt|challenge-platform|Just a moment/i.test(text),
                    tagCount:data.tags?.length,groups:data.extras?.tag_groups?.map(g=>({name:g.name,count:g.tags?.length})),
                    topicCount:data.topic_list?.topics?.length,tagSample:data.tags?.slice(0,3),errors:data.errors||[]};
                }''', path)
                checks.append(check)
                (target/'requests.json').write_text(json.dumps({'checks':checks,'writes':0},ensure_ascii=False,indent=2),encoding='utf-8')
                if check['status']==429 or check['verificationRequired']:
                    guards.record_rate_limit(check['retryAfter'],check['verificationRequired'],check['status'])
                    raise RuntimeError('Forum cooldown or verification required; stopped without retry')
            print(json.dumps(checks,ensure_ascii=False))
        print(json.dumps(result, ensure_ascii=False))
    finally:
        page.close()
