"""Fetch PEP senior high textbook tables of contents (knowledge baseline).

Only book titles + chapter/section TOC structure are captured. Third-party
textbook aggregator (dzkbw) is used only to obtain official PEP titles and
structure; no textbook body text is stored.
"""

from __future__ import annotations

import json
import re
import sys
import urllib.request
from pathlib import Path

BASE = "http://www.dzkbw.com/books/rjb/"
OUT = Path(
    r"D:\智能错题本\.worktrees\ui-rebuild\knowledge-production"
    r"\pep-textbook-toc-2026-v1.json"
)

BOOKS = {
    "CHINESE": [
        ("yuwen/gzbxgysc", "语文必修 上册"),
        ("yuwen/gzbxxc", "语文必修 下册"),
        ("yuwen/gzxzxbxsc", "语文选择性必修 上册"),
        ("yuwen/gzxzxbxzc", "语文选择性必修 中册"),
        ("yuwen/gzxzxbxxc", "语文选择性必修 下册"),
    ],
    "MATH": [
        ("shuxue/gzabx1", "数学A版必修 第一册"),
        ("shuxue/gzabx2", "数学A版必修 第二册"),
        ("shuxue/gzaxzxbx1", "数学A版选择性必修 第一册"),
        ("shuxue/gzaxzxbx2", "数学A版选择性必修 第二册"),
        ("shuxue/gzaxzxbx3", "数学A版选择性必修 第三册"),
    ],
    "ENGLISH": [
        ("yingyu/gzbxd1c", "英语必修 第一册"),
        ("yingyu/gzbxd2c", "英语必修 第二册"),
        ("yingyu/gzbxd3c", "英语必修 第三册"),
        ("yingyu/gzxzxbxd1c", "英语选择性必修 第一册"),
        ("yingyu/gzxzxbxd2c", "英语选择性必修 第二册"),
        ("yingyu/gzxzxbxd3c", "英语选择性必修 第三册"),
        ("yingyu/gzxzxbxd4c", "英语选择性必修 第四册"),
    ],
    "PHYSICS": [
        ("wuli/pgzd1c", "物理必修 第一册"),
        ("wuli/pgzd2c", "物理必修 第二册"),
        ("wuli/pgzd3c", "物理必修 第三册"),
        ("wuli/pgzxbd1c", "物理选择性必修 第一册"),
        ("wuli/pgzxbd2c", "物理选择性必修 第二册"),
        ("wuli/pgzxbd3c", "物理选择性必修 第三册"),
    ],
    "CHEMISTRY": [
        ("huaxue/pgzbxd1c", "化学必修 第一册"),
        ("huaxue/pgzbxd2c", "化学必修 第二册"),
        ("huaxue/pgzxzxbxd1c", "化学选择性必修1 化学反应原理"),
        ("huaxue/pgzxzxbxd2c", "化学选择性必修2 物质结构与性质"),
        ("huaxue/pgzxzxbxd3c", "化学选择性必修3 有机化学基础"),
    ],
    "BIOLOGY": [
        ("shengwu/pgzbx1", "生物学必修1 分子与细胞"),
        ("shengwu/pgzbx2", "生物学必修2 遗传与进化"),
        ("shengwu/pgzxzxbx1", "生物学选择性必修1 稳态与调节"),
        ("shengwu/pgzxzxbx2", "生物学选择性必修2 生物与环境"),
        ("shengwu/pgzxzxbx3", "生物学选择性必修3 生物技术与工程"),
    ],
    "HISTORY": [
        ("lishi/zwlsgys", "历史必修 中外历史纲要（上）"),
        ("lishi/zwlsgyx", "历史必修 中外历史纲要（下）"),
        ("lishi/gzxzxbx1", "历史选择性必修1 国家制度与社会治理"),
        ("lishi/gzxzxbx2", "历史选择性必修2 经济与社会生活"),
        ("lishi/gzxzxbx3", "历史选择性必修3 文化交流与传播"),
    ],
    "GEOGRAPHY": [
        ("dili/gzbxd1c", "地理必修 第一册"),
        ("dili/gzbx2", "地理必修 第二册"),
        ("dili/gzzxxbx1", "地理选择性必修1 自然地理基础"),
        ("dili/gzzxxbx2", "地理选择性必修2 区域发展"),
        ("dili/gzzxxbx3", "地理选择性必修3 资源、环境与国家安全"),
    ],
    "POLITICS": [
        ("zhengzhi/gzbx1", "思想政治必修1 中国特色社会主义"),
        ("zhengzhi/gzbx2", "思想政治必修2 经济与社会"),
        ("zhengzhi/gzbx3", "思想政治必修3 政治与法治"),
        ("zhengzhi/gzbx4", "思想政治必修4 哲学与文化"),
        ("zhengzhi/gzxzxbx1", "思想政治选择性必修1 当代国际政治与经济"),
        ("zhengzhi/gzxzxbx2", "思想政治选择性必修2 法律与生活"),
        ("zhengzhi/gzxzxbx3", "思想政治选择性必修3 逻辑与思维"),
    ],
}

UA = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    )
}


def fetch(url: str, timeout: int = 25) -> str:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read()
    return raw.decode("gb18030", "replace")


def parse_book(html: str) -> dict:
    chapters = []
    for b in re.findall(r"<B>([^<]+)</B>", html):
        t = b.strip()
        if not t or t in ("关注公众号快速搜索电子课本",):
            continue
        chapters.append(t)

    sections = []
    seen = set()
    for href, text in re.findall(
        r'<a[^>]+href="(/books/rjb/[^"]*\.htm)"[^>]*>([^<]{1,80})</a>', html
    ):
        t = text.strip()
        if not t or t in ("返回目录", "上一页", "下一页") or href in seen:
            continue
        seen.add(href)
        sections.append(t)

    return {"chapters": chapters, "sections": sections}


def main() -> int:
    result = {
        "schemaVersion": 1,
        "artifactId": "pep-textbook-toc-2026-v1",
        "targetBaselineId": "pep-high-school-textbooks",
        "sourceKind": "TEXTBOOK_TOC_ONLY",
        "updatedAtEpochMillis": 1784908800000,
        "subjects": [],
    }
    failures = []
    for subject, books in BOOKS.items():
        subject_entry = {"subject": subject, "books": []}
        for path, label in books:
            url = BASE + path + "/"
            try:
                html = fetch(url)
            except Exception as exc:  # noqa: BLE001
                failures.append(f"{url}: {exc}")
                subject_entry["books"].append(
                    {"title": label, "url": url, "chapters": [], "sections": []}
                )
                continue
            parsed = parse_book(html)
            subject_entry["books"].append(
                {
                    "title": label,
                    "url": url,
                    "chapters": parsed["chapters"],
                    "sections": parsed["sections"],
                }
            )
        result["subjects"].append(subject_entry)

    with open(OUT, "w", encoding="utf-8") as fh:
        json.dump(result, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    print("wrote", OUT)
    print("failures:", len(failures))
    for f in failures[:12]:
        print(" -", f)
    for s in result["subjects"]:
        ch = sum(len(b["chapters"]) for b in s["books"])
        sec = sum(len(b["sections"]) for b in s["books"])
        print(f"{s['subject']}: {len(s['books'])} books, {ch} chapters, {sec} sections")
    return 0


if __name__ == "__main__":
    sys.exit(main())
