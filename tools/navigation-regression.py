"""Run account-free browser regressions against production navigation JS.

Requires Playwright and a dedicated Chrome CDP endpoint; creates and closes its
own incognito context, without changing existing pages or reading credentials.
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
ORIGIN = "http://127.0.0.1:8765/navigation-fixture.html"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:19337")
    args = parser.parse_args()
    fixture = (ROOT / "output/playwright/navigation-fixture.html").read_text(encoding="utf-8")
    results = []

    def check(name, passed):
        assert passed, name
        results.append(name)

    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint)
        context = browser.new_context(viewport={"width": 1155, "height": 650})
        try:
            context.route("http://127.0.0.1:8765/**", lambda route: route.fulfill(content_type="text/html", body=fixture))
            page = context.new_page()
            page.set_default_timeout(15000)
            page.goto(ORIGIN)
            page.get_by_role("textbox", name="跳转到楼层").fill("6386")
            page.get_by_role("button", name="跳转", exact=True).click()
            page.wait_for_function("document.querySelector('[data-post-number=\"6386\"]')")
            page.wait_for_timeout(1300)
            check("one tail request without gap backfill", page.evaluate("calls.length === 1 && calls[0].type === 'jump'"))
            check("tail stays above navigation", page.evaluate("document.querySelector('[data-post-number=\"6386\"]').getBoundingClientRect().bottom <= document.querySelector('.topic-navigation').getBoundingClientRect().top"))
            page.evaluate("linuxDoPagination.jump(6380)")
            page.get_by_role("button", name="返回 #6386 楼", exact=True).click()
            check("return to previous floor is local", page.evaluate("calls.length === 1 && document.querySelector('#floor-progress').value === '6386'"))
            page.screenshot(path=str(ROOT / "output/playwright/navigation-dark.png"))
            page.evaluate("hold = true; linuxDoPagination.jump(4000); linuxDoPagination.jump(4200); linuxDoPagination.jump(4300)")
            page.wait_for_function("calls.filter(c=>c.type==='jump').length===2")
            page.evaluate("respondJump(calls[1])")
            page.wait_for_function("calls.filter(c=>c.type==='jump').length===3")
            check("latest queued jump with request spacing", page.evaluate("calls[2].floor === 4300 && calls[2].time - calls[1].time >= 780"))
            page.evaluate("respondJump(calls[2]); hold = false")
            page.wait_for_timeout(100)
            check("stale target never replaces current floor", page.evaluate("document.querySelector('#floor-progress').value === '4300'"))
            page.evaluate("linuxDoPagination.jumped('stale','1',allIds,fragment(['5555']),false,6386)")
            check("stale page responses are ignored", page.evaluate("!document.querySelector('[data-post-number=\"5555\"]')"))
            page.evaluate("failNext = 3; linuxDoPagination.jump(5000)")
            page.wait_for_function("document.querySelector('.topic-navigation').classList.contains('is-cooling-down')")
            count = page.evaluate("calls.length")
            check("failed jump preserves return destination", page.get_by_role("button", name="返回 #6386 楼", exact=True).count() == 1)
            page.evaluate("linuxDoPagination.jump(5100); linuxDoPagination.refresh(); dispatchEvent(new Event('scroll'))")
            page.wait_for_timeout(100)
            check("429 blocks remote navigation", page.evaluate("calls.length") == count)
            page.get_by_role("button", name="返回 #6386 楼", exact=True).click()
            check("return works locally during 429 cooldown", page.evaluate("document.querySelector('#floor-progress').value === '6386' && calls.length === " + str(count)))
            page.screenshot(path=str(ROOT / "output/playwright/navigation-cooldown.png"))
            page.wait_for_function("!document.querySelector('.topic-navigation').classList.contains('is-cooling-down')")
            check("cooldown does not replay requests", page.evaluate("calls.length") == count)

            page.goto(ORIGIN + "?start=500")
            page.wait_for_function("calls.length === 1 && document.querySelector('[data-post-number=\"480\"]')")
            page.wait_for_timeout(300)
            check("one adjacent upward batch", page.evaluate("calls.length === 1 && calls[0].ids[0] === '480' && calls[0].ids.length === 20"))
            page.mouse.move(500, 300)
            page.mouse.wheel(0, 10000)
            page.wait_for_function("calls.length === 2")
            check("downward batch starts after loaded edge", page.evaluate("calls[1].direction === 'after' && calls[1].ids[0] === '520'"))

            page.goto(ORIGIN + "?light")
            page.set_viewport_size({"width": 390, "height": 700})
            page.evaluate("linuxDoPagination.jump(6386)")
            page.wait_for_function("document.querySelector('[data-post-number=\"6386\"]')")
            check("narrow layout has no overflow", page.evaluate("document.documentElement.scrollWidth <= innerWidth && [...document.querySelectorAll('.topic-navigation button,.topic-navigation input')].every(el => {const r=el.getBoundingClientRect();return r.left>=0 && r.right<=innerWidth;})"))
            page.screenshot(path=str(ROOT / "output/playwright/navigation-light-narrow.png"))

            page.evaluate("""() => {
                window.quoteCalls = [];
                window.intellijBridge = {quoteReply: (floor,text) => quoteCalls.push({floor,text})};
                const p = document.querySelector('[data-post-number="6386"] .post-content p');
                p.innerHTML = '中文第一行<br>第二行 &amp; &lt;tag&gt; "引号"';
                const range = document.createRange(); range.selectNodeContents(p);
                getSelection().removeAllRanges(); getSelection().addRange(range);
            }""")
            page.get_by_role("button", name="引用回复", exact=True).click()
            check("selection quote preserves Chinese and newlines", page.evaluate("quoteCalls[0].floor === 6386 && quoteCalls[0].text === '中文第一行\\n第二行 & <tag> \"引号\"'"))
            page.evaluate("""() => {
                const content = document.querySelector('[data-post-number="6386"] .post-content');
                content.innerHTML = '<pre><code>val x = 1\\nprintln(x)</code></pre>';
                const range = document.createRange(); range.selectNodeContents(content.querySelector('code'));
                getSelection().removeAllRanges(); getSelection().addRange(range);
            }""")
            page.get_by_role("button", name="引用回复", exact=True).click()
            check("selected code uses Markdown fence", page.evaluate("quoteCalls[1].text === '```\\nval x = 1\\nprintln(x)\\n```'"))
            page.evaluate("""() => {
                const entries = document.querySelectorAll('.post-content');
                const range = document.createRange(); range.setStart(entries[0],0); range.setEnd(entries[1],0);
                getSelection().removeAllRanges(); getSelection().addRange(range);
            }""")
            page.wait_for_timeout(50)
            check("cross-floor selection cannot quote a wrong author", page.get_by_role("button", name="引用回复", exact=True).is_hidden())
            report = {"passed": len(results), "results": results}
            (ROOT / "output/playwright/navigation-result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(report, ensure_ascii=False))
        finally:
            context.close()


if __name__ == "__main__":
    main()
