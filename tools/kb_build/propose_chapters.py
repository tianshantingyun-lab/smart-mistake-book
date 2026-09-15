# -*- coding: utf-8 -*-
"""生成 chapter_map.csv 的工作清单：以"来源定位单元"为行，而不是逐节点。

依据（2026-09-12 诊断）：每个教辅专题恰好落在一个教材章下，所以章节修正是
99 个专题级映射，不是 2573 个节点级映射。教辅专题自带子主题（如《专题03 烃的
衍生物》下有 卤代烃/有机合成），这些子主题保留为主题层级。

对每个单元给出：当前章、节点数、以及"能否用专题名对上教材章"的证据。
对能确定对上的给出提议；其余标 needs_review 交人工。

用法：PYTHONPATH=tools python -m kb_build.propose_chapters [--write]
"""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter, defaultdict

from kb_build import pack_io, tables

TOC_PATH = pack_io.REPO / "kb_tools" / "textbook_toc_final.json"
_LOCATOR = re.compile(r"^定位：(.+?)。")


def _load_toc() -> dict[str, list[str]]:
    """科目 -> 教材章节名列表（化学/生物/物理/数学）。"""
    if not TOC_PATH.exists():
        return {}
    raw = json.loads(TOC_PATH.read_text(encoding="utf-8"))
    out: dict[str, list[str]] = {}

    def walk(node, subject):
        if isinstance(node, dict):
            for key, value in node.items():
                if isinstance(value, list):
                    out.setdefault(subject, []).extend(str(v) for v in value)
                else:
                    walk(value, subject)
        elif isinstance(node, list):
            for item in node:
                walk(item, subject)

    for subject, node in raw.items():
        walk(node, subject)
    return out


def _topic_title(source_locator: str) -> str:
    """从来源定位串里取出《…》内的专题/讲标题（去掉学生版/教师版等修饰）。"""
    m = re.search(r"《(.+?)》", source_locator)
    title = m.group(1) if m else source_locator
    title = re.sub(r"（[^）]*(学生版|教师版|全国通用|知识清单|讲义|复习讲义)[^）]*）", "", title)
    title = re.sub(r"\.docx?$", "", title).strip()
    return title


def _core(title: str) -> str:
    return re.sub(r"^(第\s*\d+\s*[讲章节]|专题\s*\d+|微专题[一二三四五六七八九十\d]+|[0-9]+\.[0-9]+)\s*", "", title).strip()


def build_rows() -> list[dict[str, str]]:
    pack = pack_io.load_json(pack_io.pack_path())
    toc = _load_toc()

    units: dict[str, Counter] = defaultdict(Counter)
    for _s, _t, point in pack_io.iter_points(pack):
        match = _LOCATOR.match(point.get("boundary") or "")
        locator = match.group(1) if match else ""
        units[point["sourceLocator"]][locator] += 1

    rows: list[dict[str, str]] = []
    for source, locators in sorted(units.items()):
        total = sum(locators.values())
        current = locators.most_common(1)[0][0]
        title = _topic_title(source)
        core = _core(title)
        # 提议：专题名的核心词若能对上教材章/节名，即认为当前章可信
        subject = None
        for key in toc:
            if source.startswith("知识清单") and _subject_of(current) == key:
                subject = key
                break
        matched = ""
        if subject:
            for name in toc.get(subject, []):
                if core and (core in name or name.endswith(core)) and len(core) >= 2:
                    matched = name
                    break
        rows.append({
            "source_locator": source,
            "nodes": str(total),
            "current_chapter": current,
            "topic": title,
            "proposed_chapter": current if matched else "",
            "evidence": f"专题名与教材「{matched}」对上" if matched else "needs_review：专题名对不上任何教材章，需人工定章",
            "sub_topics": "; ".join(f"{loc[:40]}x{c}" for loc, c in locators.most_common()[1:4]),
        })
    return rows


def _subject_of(chapter: str) -> str:
    for key, name in (("chemistry", "化学"), ("biology", "生物"), ("physics", "物理"), ("math", "数学")):
        if chapter.startswith(name) or chapter.startswith(key):
            return key
    return ""


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args(argv)

    rows = build_rows()
    ok = [r for r in rows if r["proposed_chapter"]]
    print(f"来源定位单元 {len(rows)} 个；能对上教材章的 {len(ok)} 个，需人工定章 {len(rows) - len(ok)} 个")
    print(f"覆盖节点数 {sum(int(r['nodes']) for r in rows)}")
    print()
    print("需人工定章的单元（按节点数降序，前 20）：")
    for row in sorted((r for r in rows if not r["proposed_chapter"]),
                      key=lambda r: -int(r["nodes"]))[:20]:
        print(f"   {row['nodes']:>4}  {row['current_chapter'][:34]:<34} <- {row['topic'][:40]}")

    if args.write:
        path = tables.TABLES_DIR / tables.CHAPTER_MAP
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=[
                "source_locator", "nodes", "current_chapter", "topic",
                "proposed_chapter", "evidence", "sub_topics",
            ])
            writer.writeheader()
            writer.writerows(rows)
        print()
        print(f"已写入 {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
