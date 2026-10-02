"""Explicitly authorized topic 482293 draft-only web/IDE handoff. Never posts replies.

Requires the user's logged-in dedicated Chrome CDP session and Playwright.
Refuses existing drafts; credentials stay inside Chrome. Writes only the specified
text (optionally with a trailing newline), and cleans up only its own draft.
"""
import argparse
import json
import re
import subprocess
import sys
import time
import uuid
from urllib.parse import urlparse, parse_qs
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
BODY = "保护好互联网的净土，让大家都能在社区平和的交流学习"
KEY = "topic_482293"
PATH = f"/drafts/{KEY}.json"
COOLDOWN = ROOT / "build/draft-handoff/rate-limit.json"


def cooldown_remaining():
    if not COOLDOWN.exists():
        return 0
    state = json.loads(COOLDOWN.read_text(encoding="utf-8"))
    if state.get("verificationRequired"):
        raise RuntimeError("Cloudflare human verification required in dedicated Chrome; no automatic forum request issued")
    return max(0, state["until"] - time.time())


def record_rate_limit(retry_after=None, verification_required=False, status=429):
    # A missing/short Retry-After never triggers a quick retry of the test.
    seconds = int(retry_after) if str(retry_after).isdigit() else 0
    COOLDOWN.parent.mkdir(parents=True, exist_ok=True)
    COOLDOWN.write_text(json.dumps({"until": time.time() + max(1800, seconds), "status": status,
                                   "verificationRequired": verification_required}), encoding="utf-8")


def needs_verification(status, headers, body):
    headers = {key.lower(): value for key, value in headers.items()}
    return status in (403, 429) and (headers.get("cf-mitigated", "").lower() == "challenge"
        or (status == 429 and "cloudflare" in body.lower())
        or ("text/html" in headers.get("content-type", "").lower()
            and bool(re.search(r"cf-chl-opt|challenge-platform|Just a moment|cf-turnstile", body, re.I))))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ide-home", required=True)
    parser.add_argument("--endpoint", default="http://127.0.0.1:19337")
    parser.add_argument("--cleanup-from", type=Path, help="Clear only the unchanged draft recorded by this handoff run's first read")
    parser.add_argument("--wait-cooldown", action="store_true", help="Wait locally for the recorded forum cooldown before one test attempt; no network polling")
    args = parser.parse_args()
    remaining = cooldown_remaining()
    if remaining and not args.wait_cooldown:
        raise RuntimeError(f"Recorded forum 429 cooldown: {remaining:.0f}s remaining; no forum request issued")
    if remaining:
        print(f"WAITING_FOR_FORUM_COOLDOWN={remaining:.0f}s; no forum requests during wait", flush=True)
        while cooldown_remaining():
            time.sleep(min(1, cooldown_remaining()))
    root = ROOT / "build/draft-handoff" / str(uuid.uuid4())
    root.mkdir(parents=True)
    last = 0
    rate_limited = False
    checks = []
    owned = None
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint)
        context = browser.contexts[0]
        page = next(p for p in context.pages if p.url.startswith("https://linux.do/"))

        def request(path, method="GET", form=None):
            nonlocal last, rate_limited
            if rate_limited:
                raise RuntimeError("Forum rate limit or Cloudflare verification; all further requests, including cleanup reads, stopped")
            allowed = {"GET": [PATH, "/session/csrf", "/t/482293.json"], "POST": ["/drafts.json"], "DELETE": [PATH]}
            if path not in allowed.get(method, []):
                raise ValueError("Only authorized draft requests are permitted")
            time.sleep(max(0, 5.0 - (time.monotonic() - last)))
            try:
                result = page.evaluate("""async ({path,method,form}) => {
                const headers = {'Accept':'application/json','X-Requested-With':'XMLHttpRequest'};
                const describe = async response => {
                    const text = await response.clone().text();
                    const html = response.headers.get('content-type')?.includes('text/html');
                    const verification = response.headers.get('cf-mitigated')?.toLowerCase() === 'challenge' ||
                        (response.status === 429 && /cloudflare/i.test(text)) ||
                        (html && /cf-chl-opt|challenge-platform|Just a moment|cf-turnstile/i.test(text));
                    return {status:response.status,retryAfter:response.headers.get('retry-after'),
                        requiresVerification:!!verification,data:{}};
                };
                if (method !== 'GET') {
                    const csrfResponse = await fetch('/session/csrf',{credentials:'same-origin',cache:'no-store',headers});
                    if ([403,429].includes(csrfResponse.status)) {
                        const failure = await describe(csrfResponse);
                        if (failure.requiresVerification || failure.status === 429) return failure;
                    }
                    if (!csrfResponse.ok || !csrfResponse.headers.get('content-type')?.includes('json')) throw new Error('CSRF JSON unavailable; no write issued');
                    const csrf = await csrfResponse.json();
                    headers['X-CSRF-Token'] = csrf.csrf || csrf.csrf_token;
                    headers['Content-Type'] = 'application/x-www-form-urlencoded';
                    await new Promise(resolve => setTimeout(resolve,5000));
                }
                const response = await fetch(path,{method,credentials:'same-origin',cache:'no-store',headers,
                    ...(form ? {body:new URLSearchParams(form).toString()} : {})});
                if ([403,429].includes(response.status)) {
                    const failure = await describe(response);
                    if (failure.requiresVerification || failure.status === 429) return failure;
                }
                if (!response.headers.get('content-type')?.includes('json')) {
                    const html = await response.text();
                    const title = new DOMParser().parseFromString(html,'text/html').title.slice(0,120);
                    throw new Error('Draft request returned non-JSON HTTP '+response.status+
                        '; cf-mitigated='+response.headers.get('cf-mitigated')+'; title='+title+
                        '; csrfMention='+/csrf/i.test(html)+'; challenge='+/cf-chl|challenge-platform|turnstile/i.test(html));
                }
                return {status:response.status,data:await response.json()};
                }""", {"path": path, "method": method, "form": form})
            finally:
                # Count from completion: the CSRF request and its write can take
                # longer than the interval, and must not be followed immediately.
                last = time.monotonic()
            if result["status"] == 429 or result.get("requiresVerification"):
                rate_limited = True
                record_rate_limit(result.get("retryAfter"), bool(result.get("requiresVerification")), result["status"])
                (root / "blocked-response.json").write_text(json.dumps({"status": result["status"],
                    "verificationRequired": bool(result.get("requiresVerification")), "retryAfter": result.get("retryAfter"),
                    "operation": method, "path": path, "postSubmitted": False}), encoding="utf-8")
            return result

        def read():
            result = request(PATH)
            if result.get("requiresVerification"):
                raise RuntimeError("Cloudflare human verification required; draft test stopped without retry")
            if result["status"] != 200:
                raise RuntimeError(f"Draft read HTTP {result['status']}; Retry-After={result.get('retryAfter') or 'unspecified'}; no retry issued")
            data = result["data"]
            raw = data.get("draft")
            return data["draft_sequence"], json.loads(raw) if isinstance(raw, str) else raw

        sequence, existing = read()
        if args.cleanup_from:
            recorded = json.loads((args.cleanup_from / "1.response.json").read_text(encoding="utf-8"))["data"]
            raw = recorded.get("draft")
            expected = json.loads(raw) if isinstance(raw, str) else raw
            if not expected or expected.get("action") != "reply" or expected.get("reply") != BODY or expected.get("reply_to_post_number") != 3:
                raise RuntimeError("Recorded draft is outside the authorized test content; cleanup refused")
            if existing is not None:
                if sequence != recorded["draft_sequence"] or existing != expected:
                    raise RuntimeError("Server draft changed; other client's content preserved")
                response = request(PATH, "DELETE", {"sequence": sequence})
                if response["status"] != 200:
                    raise RuntimeError(f"Cleanup HTTP {response['status']}; no retry issued")
                if read()[1] is not None:
                    raise RuntimeError("Cleanup unconfirmed")
            (root / "cleanup-result.json").write_text(json.dumps({"cleared": True, "topicId": 482293, "postSubmitted": False}), encoding="utf-8")
            print(f"OWNED_DRAFT_CLEANUP_PASS=true\nREPORT={root}")
            return
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
                                            "--plugin-zip", str(ROOT / "build/distributions/linuxdo-jetbrains-plugin-1.0.1.zip"),
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
                                def draft_only_route(route):
                                    nonlocal last
                                    req = route.request
                                    if rate_limited:
                                        route.abort()
                                        return
                                    destination = urlparse(req.url)
                                    def proceed():
                                        nonlocal last
                                        if destination.hostname == "linux.do" and (destination.path.endswith(".json") or destination.path == "/session/csrf"):
                                            time.sleep(max(0, 5.0 - (time.monotonic() - last)))
                                            last = time.monotonic()
                                        route.continue_()
                                    if req.method in ("GET", "HEAD", "OPTIONS"):
                                        proceed()
                                        return
                                    allowed = False
                                    if req.method == "POST" and destination.scheme == "https" and destination.hostname == "linux.do" and destination.path == "/drafts.json":
                                        try:
                                            form = parse_qs(req.post_data or "")
                                            draft = json.loads(form.get("data", [""])[0])
                                            allowed = (form.get("draft_key", [""])[0] == KEY and draft.get("action") == "reply"
                                                       and draft.get("reply") in (BODY, BODY + "\n") and draft.get("postId") == target["id"])
                                        except (ValueError, TypeError):
                                            pass
                                    proceed() if allowed else route.abort()
                                def observe_response(response):
                                    nonlocal last, rate_limited
                                    destination = urlparse(response.url)
                                    if destination.hostname != "linux.do":
                                        return
                                    verification = response.status in (403,429) and needs_verification(response.status, response.headers, response.text())
                                    if response.status == 429 or verification:
                                        rate_limited = True
                                        record_rate_limit(response.headers.get("retry-after"), verification, response.status)
                                    if destination.path.endswith(".json") or destination.path == "/session/csrf":
                                        last = time.monotonic()
                                tab.route("**/*", draft_only_route)
                                tab.on("response", observe_response)
                                try:
                                    tab.goto("https://linux.do/t/482293/3", wait_until="commit", timeout=20000)
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
            if not rate_limited:
                current = read()
                if current[1] is not None and current == owned:
                    request(PATH, "DELETE", {"sequence": current[0]})
                    if not rate_limited and read()[1] is not None:
                        raise RuntimeError("Cleanup unconfirmed")


if __name__ == "__main__":
    main()
