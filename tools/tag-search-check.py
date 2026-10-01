"""Read-only checks of native composer tag search; never opens or saves a composer."""
import argparse
import importlib.util
import json
import time
import uuid
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("draft_guards", Path(__file__).with_name("draft-handoff.py"))
guards = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guards)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default="http://127.0.0.1:19337")
    parser.add_argument("--category", type=int, default=4)
    args = parser.parse_args()
    if guards.cooldown_remaining():
        raise RuntimeError("Forum cooldown active; no tag request issued")
    root = ROOT / "build/tag-search" / str(uuid.uuid4())
    root.mkdir(parents=True)
    checks = []
    cases = [("previous_fixed_limit", {"q": "", "limit": "10"}),
             ("default_limit", {"q": ""}), ("keyword", {"q": "软件"})]
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint)
        page = next(p for p in browser.contexts[0].pages if p.url.startswith("https://linux.do/"))
        last = 0.0
        for name, query in cases:
            time.sleep(max(0, 5.0 - (time.monotonic() - last)))
            result = page.evaluate("""async params => {
              const query = new URLSearchParams({...params,filterForInput:'true'});
              const response = await fetch('/tags/filter/search.json?'+query,{method:'GET',credentials:'same-origin',
                cache:'no-store',headers:{Accept:'application/json','X-Requested-With':'XMLHttpRequest'}});
              const text = await response.text();
              const verification = response.headers.get('cf-mitigated') === 'challenge' ||
                (response.status === 429 && /cloudflare/i.test(text)) ||
                (response.headers.get('content-type')?.includes('text/html') && /cf-chl-opt|challenge-platform|Just a moment|cf-turnstile/i.test(text));
              let data = {}; try {data=JSON.parse(text)} catch {}
              return {status:response.status,verificationRequired:!!verification,retryAfter:response.headers.get('retry-after'),
                errors:data.errors || [],count:data.results?.length || 0,
                tags:(data.results || []).map(t=>({id:t.id,name:t.name || t.text,disabled:!!t.disabled}))};
            }""", {**query, "categoryId": str(args.category)})
            last = time.monotonic()
            checks.append({"check": name, **result})
            (root / "result.json").write_text(json.dumps({"checks": checks, "writes": 0}, ensure_ascii=False, indent=2), encoding="utf-8")
            if result["status"] == 429 or result["verificationRequired"]:
                guards.record_rate_limit(result["retryAfter"], result["verificationRequired"], result["status"])
                raise RuntimeError("Forum cooldown or human verification required; tag checks stopped without retry")
            if name != "previous_fixed_limit" and result["status"] != 200:
                raise RuntimeError(f"Tag search {name} returned HTTP {result['status']}; REPORT={root}")
    (root / "result.json").write_text(json.dumps({"passed": True, "checks": checks, "writes": 0}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"TAG_SEARCH_PASS=true\nREPORT={root}")


if __name__ == "__main__":
    main()
