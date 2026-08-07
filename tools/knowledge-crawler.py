"""Knowledge crawler via Kimi WebBridge daemon (http://127.0.0.1:10086).

Controls the user's real browser (with login sessions) to grab article text
from anti-bot sites such as Zhihu. For non-login sites prefer open-websearch
or Playwright. Output is saved as UTF-8 text files under an artifacts dir.

Usage:
    python tools/knowledge-crawler.py <url> <out_path> [session]
"""

from __future__ import annotations

import json
import sys
import time
import urllib.request
from pathlib import Path

DAEMON = "http://127.0.0.1:10086/command"

CONTENT_SELECTORS = [
    ".Post-RichText",       # zhihu article
    "article",              # generic
    ".RichText",            # zhihu / generic
    ".blog-content-box",    # csdn
    "#content_views",       # csdn
    ".main-content",        # generic
    ".lemmaWgt-lemmaContent",  # baike
    ".j-lemma-content",
    ".content",
    "main",
]


def cmd(action: str, args: dict, session: str) -> dict:
    req = urllib.request.Request(
        DAEMON,
        data=json.dumps({"action": action, "args": args, "session": session}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def grab(url: str, session: str, wait_seconds: int = 6) -> str:
    cmd("navigate", {"url": url, "newTab": True}, session)
    time.sleep(wait_seconds)
    selector_expr = ", ".join(json.dumps(s) for s in CONTENT_SELECTORS)
    code = (
        "(() => { const sels = [" + selector_expr + "]; "
        "for (const s of sels) { const el = document.querySelector(s); "
        "if (el && el.innerText && el.innerText.length > 200) return el.innerText; } "
        "return document.body.innerText; })()"
    )
    r = cmd("evaluate", {"code": code}, session)
    return r.get("data", {}).get("value", "")


def main() -> int:
    if len(sys.argv) < 3:
        print("usage: python knowledge-crawler.py <url> <out_path> [session]")
        return 2
    url = sys.argv[1]
    out = Path(sys.argv[2])
    session = sys.argv[3] if len(sys.argv) > 3 else "kb-crawl"
    out.parent.mkdir(parents=True, exist_ok=True)
    text = grab(url, session)
    out.write_text(text, encoding="utf-8")
    print(f"url={url}\nsession={session}\nchars={len(text)}\nsaved={out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
