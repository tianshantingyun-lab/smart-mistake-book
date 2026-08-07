"""Batch-grab Zhihu article text via Kimi WebBridge and record which ones are
text-rich (usable) vs image-based (skip). Reads a JSON list of {name, url}.

Usage:
    python tools/zhihu-batch-grab.py <manifest.json> <out_dir> <session>
"""

from __future__ import annotations

import json
import sys
import time
import urllib.request
from pathlib import Path

DAEMON = "http://127.0.0.1:10086/command"
MIN_TEXT = 800  # below this we consider the article image-based / login-walled


def cmd(action: str, args: dict, session: str) -> dict:
    req = urllib.request.Request(
        DAEMON,
        data=json.dumps({"action": action, "args": args, "session": session}).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def grab(url: str, session: str) -> tuple[int, int]:
    cmd("navigate", {"url": url, "newTab": True}, session)
    time.sleep(5)
    r = cmd(
        "evaluate",
        {
            "code": (
                '(() => { const el = document.querySelector(".Post-RichText"); '
                'const imgs = document.querySelectorAll(".Post-RichText img").length; '
                'return JSON.stringify({txt: el ? el.innerText.length : -1, imgs: imgs}); })()'
            )
        },
        session,
    )
    try:
        d = json.loads(r.get("data", {}).get("value", "{}"))
    except json.JSONDecodeError:
        d = {}
    return d.get("txt", -1), d.get("imgs", 0)


def fetch_text(url: str, session: str) -> str:
    r = cmd(
        "evaluate",
        {"code": '(() => { const el = document.querySelector(".Post-RichText"); return el ? el.innerText : ""; })()'},
        session,
    )
    return r.get("data", {}).get("value", "")


def main() -> int:
    if len(sys.argv) < 4:
        print("usage: python zhihu-batch-grab.py <manifest.json> <out_dir> <session>")
        return 2
    manifest = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    out_dir = Path(sys.argv[2])
    session = sys.argv[3]
    out_dir.mkdir(parents=True, exist_ok=True)

    results = []
    for item in manifest:
        name, url = item["name"], item["url"]
        txt_len, img_count = grab(url, session)
        usable = txt_len >= MIN_TEXT
        if usable:
            text = fetch_text(url, session)
            (out_dir / f"{name}.md").write_text(text, encoding="utf-8")
        results.append(
            {
                "name": name,
                "url": url,
                "textChars": txt_len,
                "imageCount": img_count,
                "usable": usable,
                "saved": f"{name}.md" if usable else None,
            }
        )
        print(f"{name}: chars={txt_len} imgs={img_count} usable={usable}")

    report = out_dir / "grab-report.json"
    report.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("report:", report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
