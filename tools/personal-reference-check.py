"""Five GETs in an existing dedicated forum browser. Export field presence and counts only."""
import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from playwright.sync_api import sync_playwright

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', default='http://127.0.0.1:19337')
args = parser.parse_args()
output = Path(__file__).resolve().parents[1] / 'build/personal-reference'
output.mkdir(parents=True, exist_ok=True)
try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint, timeout=5000)
        pages = [p for c in browser.contexts for p in c.pages if p.url.startswith('https://linux.do/')]
        assert pages, 'No existing dedicated forum page'
        result = pages[0].evaluate('''async () => {
          const out={writes:0,requests:[]};
          const current=await fetch('/session/current.json',{method:'GET',credentials:'include',headers:{Accept:'application/json'}});
          out.requests.push({kind:'account',status:current.status});if(!current.ok)return out;
          const user=(await current.json()).current_user;if(!user?.username){out.confirmed=false;return out;}
          out.confirmed=true;
          for(const [kind,path] of [
            ['topics','/user_actions.json?username='+encodeURIComponent(user.username)+'&filter=4&offset=0&limit=30'],
            ['replies','/user_actions.json?username='+encodeURIComponent(user.username)+'&filter=5&offset=0&limit=30'],
            ['bookmarks','/u/'+encodeURIComponent(user.username)+'/bookmarks.json?page=0'],
            ['drafts','/drafts.json?offset=0&limit=30']]) {
            const response=await fetch(path,{method:'GET',credentials:'include',headers:{Accept:'application/json'}});
            const entry={kind,status:response.status};out.requests.push(entry);if(!response.ok)break;
            const data=await response.json(),container=kind==='bookmarks'?(data.user_bookmark_list||data):data;
            const rows=container[kind==='bookmarks'?'bookmarks':kind==='drafts'?'drafts':'user_actions'];
            entry.array=Array.isArray(rows);entry.rows=rows?.length;
            entry.fields=[...new Set((rows||[]).flatMap(r=>Object.keys(r)))].sort();
            if(kind==='bookmarks') {entry.hasContinuation=!!container.more_bookmarks_url;entry.hasLinkedFloor=(rows||[]).some(r=>Number.isInteger(r.linked_post_number));}
            if(kind==='drafts')entry.dataTypes=[...new Set((rows||[]).map(r=>typeof r.data))];
          }
          return out;
        }''')
except Exception as error:
    result = {'writes': 0, 'available': False, 'reason': type(error).__name__}
result['checkedAt'] = datetime.now(timezone.utc).isoformat()
result['scope'] = 'site-readonly; no pagination or nonempty draft mutation'
(output / 'linuxdo-personal-readonly.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
