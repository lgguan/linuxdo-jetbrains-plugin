"""Explicitly authorized topic 482293 draft-only web/IDE handoff. Never posts replies.

Requires the user's logged-in dedicated Chrome CDP session and Playwright.
Refuses existing drafts; credentials stay inside Chrome. Writes only the specified
text (optionally with a trailing newline), and cleans up only its own draft.
"""
import argparse
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
BODY = "保护好互联网的净土，让大家都能在社区平和的交流学习"
KEY = "topic_482293"
PATH = f"/drafts/{KEY}.json"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home", required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:19337")
    args = parser.parse_args()
    root = ROOT / "build/draft-handoff" / str(uuid.uuid4())
    root.mkdir(parents=True)
    last = 0
    checks = []
    owned = None
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint)
        context = browser.contexts[0]
        page = next(p for p in context.pages if p.url.startswith("https://linux.do/"))

        def request(path, method="GET", form=None):
            nonlocal last
            allowed = {"GET": [PATH, "/session/csrf", "/t/482293.json"], "POST": ["/drafts.json"], "DELETE": [PATH]}
            if path not in allowed.get(method, []):
                raise ValueError("Only authorized draft requests are permitted")
            time.sleep(max(0, 1.2 - (time.monotonic() - last)))
            last = time.monotonic()
            return page.evaluate("""async ({path,method,form}) => {
                const headers = {'Accept':'application/json','X-Requested-With':'XMLHttpRequest'};
                if (method !== 'GET') {
                    const csrfResponse = await fetch('/session/csrf',{credentials:'same-origin',headers});
                    if (!csrfResponse.ok || !csrfResponse.headers.get('content-type')?.includes('json')) throw new Error('CSRF JSON unavailable; no write issued');
                    const csrf = await csrfResponse.json();
                    headers['X-CSRF-Token'] = csrf.csrf || csrf.csrf_token;
                    headers['Content-Type'] = 'application/x-www-form-urlencoded';
                }
                const response = await fetch(path,{method,credentials:'same-origin',headers,
                    ...(form ? {body:new URLSearchParams(form).toString()} : {})});
                if (!response.headers.get('content-type')?.includes('json')) throw new Error('Draft request returned non-JSON HTTP '+response.status);
                return {status:response.status,data:await response.json()};
            }""", {"path": path, "method": method, "form": form})

        def read():
            result = request(PATH)
            if result["status"] != 200:
                raise RuntimeError(f"Draft read HTTP {result['status']}")
            data = result["data"]
            raw = data.get("draft")
            return data["draft_sequence"], json.loads(raw) if isinstance(raw, str) else raw

        sequence, existing = read()
        if existing is not None:
            raise RuntimeError("Existing forum draft preserved; handoff test did not write")
        topic = request("/t/482293.json")
        target = next(p for p in topic["data"]["post_stream"]["posts"] if p["post_number"] == 3)
        seed = {"action": "reply", "reply": BODY, "postId": target["id"],
                "reply_to_post_number": 3, "reply_to_user": {"username": target["username"]}, "composerTime": 0}
        try:
            first = request("/drafts.json", "POST", {"draft_key": KEY, "sequence": sequence, "data": json.dumps(seed, ensure_ascii=False)})
            if first["status"] != 200 or first["data"].get("success") != "OK":
                raise RuntimeError("Initial test draft save was not confirmed")
            owned = (first["data"]["draft_sequence"], seed)
            with (root / "ide.log").open("w", encoding="utf-8") as log:
                process = subprocess.Popen([sys.executable, str(ROOT / "tools/smoke.py"), "ui", "--ide-home", args.ide_home,
                                            "--plugin-zip", str(ROOT / "build/distributions/linuxdo-jetbrains-plugin-1.0.0.zip"),
                                            "--draft-bridge", str(root)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                handled = set()
                while process.poll() is None:
                    for file in sorted(root.glob("*.request.json")):
                        if file in handled:
                            continue
                        handled.add(file)
                        message = json.loads(file.read_text(encoding="utf-8"))
                        response = {}
                        try:
                            op = message["operation"]
                            if op == "post":
                                response = {"data": target}
                            elif op == "web":
                                tab = context.new_page()
                                # Block every post creation even if UI automation mis-clicks.
                                tab.route("**/posts*", lambda route: route.abort() if route.request.method != "GET" else route.continue_())
                                try:
                                    tab.goto("https://linux.do/t/482293/3", wait_until="domcontentloaded")
                                    tab.wait_for_selector(".topic-body")
                                    editor = tab.locator("#reply-control textarea.d-editor-input")
                                    if not editor.is_visible():
                                        tab.locator("#post_3 button.reply.create").click()
                                    editor.wait_for(state="visible")
                                    tab.wait_for_function("expected => document.querySelector('#reply-control textarea.d-editor-input')?.value === expected", arg=BODY + "\n")
                                    tab.screenshot(path=str(root / "web-restored-ide-draft.png"))
                                    checks.append("web_restores_ide_body")
                                    editor.fill(BODY)
                                    editor.press("End")
                                    editor.press("Space")
                                    editor.press("Backspace")
                                    deadline = time.monotonic() + 30
                                    while time.monotonic() < deadline:
                                        seq, current = read()
                                        if current and current.get("reply") == BODY:
                                            owned = (seq, current)
                                            break
                                        time.sleep(1)
                                    else:
                                        tab.screenshot(path=str(root / "web-save-failure.png"))
                                        (root / "web-save-observed.json").write_text(json.dumps({"sequence": seq, "draft": current}, ensure_ascii=False), encoding="utf-8")
                                        raise RuntimeError("Web composer did not synchronize the specified body")
                                    checks.append("web_saves_body_for_ide")
                                    response = {"data": {"restored": True, "saved": True}}
                                finally:
                                    tab.close()
                                    seq, current = read()
                                    # Opening the native composer can normalize and save its draft
                                    # even if a later UI assertion fails. Track that exact result.
                                    if current and current.get("reply") in (BODY, BODY + "\n") and current.get("action") == "reply" and current.get("postId") in (None, target["id"]):
                                        owned = (seq, current)
                            else:
                                if message.get("key") != KEY:
                                    raise ValueError("Unauthorized draft key")
                                if op == "read":
                                    response = request(PATH)
                                elif op == "save":
                                    data = message["data"]
                                    if data.get("action") != "reply" or data.get("reply") not in (BODY, BODY + "\n") or data.get("postId") != target["id"] or data.get("reply_to_post_number") != 3:
                                        raise ValueError("Unexpected draft content or target; write refused")
                                    response = request("/drafts.json", "POST", {"draft_key": KEY, "sequence": message["sequence"], "data": json.dumps(data, ensure_ascii=False)})
                                    if response["status"] == 200:
                                        owned = (response["data"]["draft_sequence"], data)
                                        checks.append("ide_autosaves_real_draft")
                                elif op == "delete":
                                    current = read()
                                    if current != owned or message["sequence"] != current[0]:
                                        raise ValueError("Other client's draft preserved; delete refused")
                                    response = request(PATH, "DELETE", {"sequence": message["sequence"]})
                                else:
                                    raise ValueError("Unknown bridge request")
                        except Exception as error:
                            response = {"error": str(error)}
                        destination = root / file.name.replace("request", "response")
                        temp = destination.with_suffix(".tmp")
                        temp.write_text(json.dumps(response, ensure_ascii=False), encoding="utf-8")
                        temp.replace(destination)
                    time.sleep(.04)
            if process.returncode:
                raise RuntimeError(f"IDE handoff failed: {root / 'ide.log'}")
            _, final = read()
            if final is not None:
                raise RuntimeError("Draft cleanup was not confirmed")
            checks.append("owned_test_draft_cleared")
            (root / "result.json").write_text(json.dumps({"passed": True, "topicId": 482293, "postSubmitted": False, "checks": checks}, indent=2), encoding="utf-8")
            print(f"DRAFT_HANDOFF_PASS=true\nREPORT={root}")
        finally:
            current = read()
            if current[1] is not None and current == owned:
                request(PATH, "DELETE", {"sequence": current[0]})
                if read()[1] is not None:
                    raise RuntimeError("Cleanup unconfirmed")


if __name__ == "__main__":
    main()
