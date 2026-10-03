"""Exercise production notification display acknowledgements in an isolated browser context."""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', default='http://127.0.0.1:19337')
args = parser.parse_args()
fixture = (ROOT / 'output/playwright/navigation-fixture.html').read_text(encoding='utf-8')
origin = 'http://127.0.0.1:8765/notification-fixture.html'
checks = []

with sync_playwright() as playwright:
    browser = playwright.chromium.connect_over_cdp(args.endpoint)
    context = browser.new_context(viewport={'width': 1000, 'height': 700})
    try:
        context.route('**/*', lambda route: route.fulfill(content_type='text/html', body=fixture))
        page = context.new_page()

        def reset():
            page.goto(origin)
            page.evaluate('''() => {
                window.acks=[];
                window.intellijBridge={targetOpened:(key,id,floor,success)=>acks.push({key,id,floor,success})};
            }''')

        def check(name, condition):
            assert condition, name
            checks.append(name)

        reset()
        page.evaluate("linuxDoPagination.openTarget('test','fresh',6386)")
        page.wait_for_function('acks.length === 1')
        check('new remote floor waits until body is displayed', page.evaluate("acks[0].id==='fresh' && acks[0].floor===6386 && acks[0].success && calls.filter(c=>c.type==='jump').length===1"))
        page.evaluate("linuxDoPagination.openTarget('test','reuse',6380)")
        page.wait_for_function('acks.length === 2')
        check('existing loaded floor is acknowledged after positioning', page.evaluate("acks[1].id==='reuse' && acks[1].floor===6380 && acks[1].success && calls.filter(c=>c.type==='jump').length===1"))
        page.evaluate("linuxDoPagination.openTarget('test','body',null)")
        page.wait_for_function('acks.length === 3')
        check('notification without floor confirms a visible body', page.evaluate("acks[2].id==='body' && acks[2].success"))
        page.evaluate("linuxDoPagination.openTarget('stale-page','stale',1)")
        page.wait_for_timeout(100)
        check('stale page cannot acknowledge current request', page.evaluate('acks.length === 3'))

        reset()
        page.evaluate("linuxDoPagination.openTarget('test','missing',7000)")
        page.wait_for_function('acks.length === 1')
        check('missing floor preserves unread', page.evaluate("acks[0].id==='missing' && !acks[0].success"))

        reset()
        page.evaluate("failNext=3; linuxDoPagination.openTarget('test','denied',6000)")
        page.wait_for_function('acks.length === 1')
        check('failed floor request and cooldown cannot confirm display', page.evaluate("!acks[0].success && document.querySelector('.topic-navigation').classList.contains('is-cooling-down')"))
        count = page.evaluate('calls.length')
        page.wait_for_timeout(150)
        check('failure never automatically replays a navigation', page.evaluate('calls.length') == count)

        reset()
        page.evaluate("hold=true; linuxDoPagination.openTarget('test','cancelled',4000); linuxDoPagination.jump(5)")
        page.wait_for_function('acks.length === 1')
        check('superseding navigation cancels pending notification', page.evaluate("acks[0].id==='cancelled' && !acks[0].success"))
        page.evaluate('respondJump(calls[0]); hold=false')
        page.wait_for_timeout(1000)
        check('late floor response cannot restore cancelled confirmation', page.evaluate('acks.length === 1'))

        reset()
        page.evaluate("document.querySelectorAll('.post-content').forEach(el=>el.style.display='none'); linuxDoPagination.openTarget('test','invisible',2)")
        page.wait_for_function('acks.length === 1')
        check('hidden body does not count as displayed', page.evaluate('!acks[0].success'))

        reset()
        page.evaluate("hold=true; linuxDoPagination.openTarget('test','old',4000); linuxDoPagination.openTarget('test','new',5); respondJump(calls[0]); hold=false")
        page.wait_for_function('acks.some(a=>a.id === "new")')
        check('replaced request cannot mark the earlier notification', page.evaluate("acks.every(a=>a.id!=='old') && acks.find(a=>a.id==='new').success"))
    finally:
        context.close()

report = {'passed': len(checks), 'checks': checks, 'realForumWrites': 0}
output = ROOT / 'build/notification-browser'
output.mkdir(parents=True, exist_ok=True)
(output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False))
