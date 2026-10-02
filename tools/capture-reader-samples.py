"""Capture only structural cooked markup via one GET; discard forum text and account data."""
import argparse,html,json,re,hashlib
from html.parser import HTMLParser
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT=Path(__file__).resolve().parents[1]
class Skeleton(HTMLParser):
    def __init__(self):super().__init__(convert_charrefs=True);self.parts=[];self.stack=[];self.skip=0
    def handle_starttag(self,tag,attrs):
        if tag in ('script','style','svg'):self.skip+=1;return
        if self.skip:return
        values=dict(attrs);kept=[]
        for key,value in attrs:
            if key not in ('class','width','height','type','controls','preload','open','data-topic','data-post','data-username','src','href','data-math-source','alt','id','title'):continue
            value=value or ''
            if key=='data-topic':value='1'
            elif key=='data-post':value='2'
            elif key=='data-username':value='sample_author'
            elif key in ('alt','title'):value='脱敏样本'
            elif key=='data-math-source':value='x^2'
            elif key=='id':value='sample-'+str(len(self.parts))
            elif key in ('src','href'):
                if tag=='iframe':value='https://www.youtube.com/embed/dQw4w9WgXcQ'
                elif tag=='img':value='https://fixture.test/image.png'
                elif tag in ('audio','video','source'):value='https://fixture.test/media.webm'
                else:value='https://fixture.test/resource'
            kept.append(f'{key}="{html.escape(value,quote=True)}"')
        self.parts.append('<'+tag+(' '+' '.join(kept) if kept else '')+'>')
        if tag not in ('img','source','input','br','hr','wbr'):self.stack.append((tag,values.get('class','')))
    def handle_endtag(self,tag):
        if tag in ('script','style','svg') and self.skip:self.skip-=1;return
        if self.skip:return
        self.parts.append('</'+tag+'>')
        if self.stack and self.stack[-1][0]==tag:self.stack.pop()
    def handle_data(self,text):
        if self.skip or not text.strip():return
        classes=' '.join(cls for _,cls in self.stack)
        if 'mermaid' in classes:value='graph LR; A--&gt;B'
        elif 'math' in classes:value='x^2'
        elif any(tag in ('pre','code') for tag,_ in self.stack):value='sample'
        else:value='脱敏样本文本'
        self.parts.append(value)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--endpoint',default='http://127.0.0.1:19337');parser.add_argument('--topic',type=int,default=847468);parser.add_argument('--from-open-page',action='store_true');args=parser.parse_args()
    assert args.topic>0
    with sync_playwright() as p:
        browser=p.chromium.connect_over_cdp(args.endpoint)
        pages=[page for context in browser.contexts for page in context.pages if page.url.startswith('https://linux.do/')]
        assert pages,'No dedicated Linux Do page is available'
        if args.from_open_page:
            sources=[page for page in pages if '/t/' in page.url and page.locator('.topic-body .cooked').count()>0]
            assert sources,'No rendered topic is currently available'
            response={'status':200,'cooked':sources[0].locator('.topic-body .cooked').evaluate_all('(nodes)=>nodes.slice(0,20).map(node=>node.innerHTML)')}
            source=re.sub(r'[?#].*','',sources[0].url);method='DOM read (no HTTP request)'
        else:
            response=pages[0].evaluate('''async topic=>{const response=await fetch('/t/'+topic+'.json?track_visit=false',{method:'GET',credentials:'include',headers:{Accept:'application/json'}});if(!response.ok)return {status:response.status};const data=await response.json();return {status:response.status,cooked:(data.post_stream?.posts||[]).slice(0,20).map(post=>post.cooked||'')};}''',args.topic)
            source=f'https://linux.do/t/{args.topic}.json?track_visit=false';method='GET'
        assert response['status']==200,f"Read-only sample GET returned HTTP {response['status']}"
        result=[]
        for cooked in response['cooked']:
            skeleton=Skeleton();skeleton.feed(cooked);result.append(''.join(skeleton.parts))
        output=ROOT/'src/test/resources/reader/forum-structure.html';output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text('\n'.join('<article class="post-content">'+body+'</article>' for body in result),encoding='utf-8')
        (output.parent/'source.json').write_text(json.dumps({'source':source,'method':method,'captured':'2026-10-02','posts':len(result),'policy':'Only tag topology and structural attributes retained; all text, authors, uploads and external targets replaced. No credentials or original response saved.','sha256':hashlib.sha256(output.read_bytes()).hexdigest()},ensure_ascii=False,indent=2),encoding='utf-8')
        print('READ_ONLY_STRUCTURAL_SAMPLE='+str(output))
        browser.close()

if __name__=='__main__':main()
