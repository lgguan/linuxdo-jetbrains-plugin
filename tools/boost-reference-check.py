"""Read-only Boost API comparison through an existing dedicated Chrome session."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', default='http://127.0.0.1:19337')
parser.add_argument('--topic', type=int, default=482293)
args = parser.parse_args()
assert args.topic > 0
with sync_playwright() as playwright:
    browser = playwright.chromium.connect_over_cdp(args.endpoint)
    pages = [page for context in browser.contexts for page in context.pages if page.url.startswith('https://linux.do/')]
    assert pages, 'An existing Linux Do page is required; no new page or login is opened.'
    result = pages[0].evaluate('''async topic => {
      const response=await fetch('/t/'+topic+'.json?track_visit=false',{method:'GET',credentials:'include',headers:{Accept:'application/json'}});
      if(!response.ok)return {status:response.status,writes:0};
      const data=await response.json();
      return {status:response.status,topic:topic,writes:0,trackVisit:false,
        posts:(data.post_stream?.posts||[]).map(post=>({floor:post.post_number,canBoost:post.can_boost??null,
          boosts:(post.boosts||[]).map(boost=>({id:boost.id,hasCooked:typeof boost.cooked==='string',canDelete:boost.can_delete??null,
            hasUserId:typeof boost.user?.id==='number',hasUsername:typeof boost.user?.username==='string',hasAvatarTemplate:typeof boost.user?.avatar_template==='string'}))}))};
    }''', args.topic)
    output = ROOT/'build/boost-reference'
    output.mkdir(parents=True, exist_ok=True)
    (output/'linuxdo-boosts.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'status':result['status'],'posts':len(result.get('posts',[])),
                      'boosts':sum(len(post['boosts']) for post in result.get('posts',[])),'writes':0}))
    assert result['status']==200, 'Stopped on forum error; no retry or write is performed.'
