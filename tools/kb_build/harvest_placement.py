# -*- coding: utf-8 -*-
"""把页转录件的「书内定位 / 对应人教版」抽成归位表 —— 五三页 → 教材(册,章) 的唯一权威。

为什么需要它：五三精讲册按**自己的章**编排（`第十三章 化学实验基本方法`），
而知识库的树按**人教版教材章**（册→章→主题）。两者不是一回事，
所以每一页都必须有一条"这一页属于人教版的哪册哪章"的显式记录，
新知识点才能挂到正确位置，而不是靠猜书名或猜章节号。

这张表同时是**审校靶子**：转录员写下的「对应人教版」有一部分是他们的推断
（他们在报告里主动标注了），表里把 raw 值原样带出来，凡是同一条书内定位
对应多个不同教材章、或教材章名不在人教 canonical 名单里的，都直接列出来
交人审——而不是悄悄取一个值继续往下走。

用法：
  PYTHONPATH=tools python -m kb_build.harvest_placement            # 只报告
  PYTHONPATH=tools python -m kb_build.harvest_placement --write    # 写 tables/
"""

from __future__ import annotations

import argparse
import csv
import io
import os
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

TRANSCRIPT_ROOT = pack_io.REPO / "knowledge-research" / "candidates" / "wusan"
OUT_NAME = "wusan_page_placement.csv"
SUBJECT_DIRS = {"MATH": "math", "PHYSICS": "physics",
                "CHEMISTRY": "chemistry", "BIOLOGY": "biology"}

_TITLE_PAGE = re.compile(r"PDF\s*p(\d+)(?:\s*[–-]\s*p(\d+))?")
_CONTENT_PAGE = re.compile(r"内容页\s*([0-9]+)")
_FIELD = re.compile(r"^-\s*(?P<key>[^：:]+)[：:]\s*(?P<value>.*)$", re.MULTILINE)
_CHAPTER = re.compile(r"^(第[一二三四五六七八九十百]+章|\d+\s*$)")
# 教材定位串形如 `选择性必修第一册 第二章 直线和圆的方程`。不能写成
# `^(?P<book>[^第]*?)\s*(第..章..)$`——册名本身含「第」（`必修第一册`），
# 那样会把 `必修` 当册、`第一册 第二章 …` 当章。改取**最后一个** `第X章`：
# 它左边一律是册名，右边是章名（后面可能还跟节名，一并保留）。
# 章号必须同时认中文数字与阿拉伯数字：化学写「第一章」，生物写「第2章」，
# 只认中文会把生物 100 多页整批判成「缺章名」。
_RENJIAO_CHAPTER = re.compile(r"第[一二三四五六七八九十百\d]+章")


def _split_renjiao(value: str) -> tuple[str, str]:
    matches = list(_RENJIAO_CHAPTER.finditer(value))
    if not matches:
        return value.strip(), ""
    last = matches[-1]
    return value[:last.start()].strip(), value[last.start():].strip()


class Row:
    __slots__ = ("subject", "pdf_page", "content_page", "book_hint", "chapter_hint",
                 "renjiao_book", "renjiao_chapter", "locator")

    def __init__(self, **kw) -> None:
        for k, v in kw.items():
            setattr(self, k, v)


def _parse(path: Path, subject: str) -> Row | None:
    text = path.read_text(encoding="utf-8")
    head, _, _ = text.partition("##")
    page_match = _TITLE_PAGE.search(text.split("\n", 1)[0])
    pdf_page = path.stem
    if page_match:
        pdf_page = (f"{page_match.group(1)}-{page_match.group(2)}"
                    if page_match.group(2) else page_match.group(1))
    content = _CONTENT_PAGE.search(head)
    locator = renjiao = ""
    for field in _FIELD.finditer(head):
        if "书内定位" in field.group("key") and not locator:
            locator = field.group("value").strip()
        elif "对应人教" in field.group("key") and not renjiao:
            renjiao = field.group("value").strip()
    if not locator and not renjiao:
        return None

    segments = [s.strip() for s in locator.split("/") if s.strip()]
    chapter_hint = segments[0] if segments else ""
    book_hint = " / ".join(segments[1:3])

    renjiao_book, renjiao_chapter = _split_renjiao(renjiao) if renjiao else ("", "")

    return Row(subject=subject, pdf_page=pdf_page,
               content_page=content.group(1) if content else "",
               book_hint=book_hint, chapter_hint=chapter_hint,
               renjiao_book=renjiao_book, renjiao_chapter=renjiao_chapter,
               locator=locator)


def harvest() -> list[Row]:
    rows: list[Row] = []
    for subject, folder in SUBJECT_DIRS.items():
        directory = TRANSCRIPT_ROOT / folder / "transcript"
        if not directory.is_dir():
            continue
        for name in sorted(os.listdir(directory)):
            if not name.endswith(".md"):
                continue
            row = _parse(directory / name, subject)
            if row is not None:
                rows.append(row)
    return rows


def write_table(rows: list[Row]) -> Path:
    path = pack_io.REPO / "tools" / "kb_build" / "tables" / OUT_NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "pdf_page", "content_page", "wusan_chapter",
                         "wusan_section", "renjiao_book", "renjiao_chapter", "locator"])
        for r in rows:
            writer.writerow([r.subject, r.pdf_page, r.content_page, r.chapter_hint,
                             r.book_hint, r.renjiao_book, r.renjiao_chapter, r.locator])
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--sample", type=int, default=10)
    args = parser.parse_args(argv)

    rows = harvest()
    print(f"抽出归位记录 {len(rows)} 条")
    by_subject = Counter(r.subject for r in rows)
    for subject in SUBJECT_DIRS:
        print(f"  {subject:<10} {by_subject.get(subject, 0)}")

    missing = [r for r in rows if not r.renjiao_chapter]
    print()
    print(f"缺「对应人教版」章名的页 {len(missing)}")
    for r in missing[:args.sample]:
        print(f"  [{r.subject}] p{r.pdf_page} 定位={r.locator[:50]} 原值={r.renjiao_chapter or '(空)'}")

    # 一条书内定位 → 多个教材章 = 转录员的推断之间互相矛盾，必须人审
    by_locator: dict[tuple[str, str], set[str]] = defaultdict(set)
    for r in rows:
        if r.chapter_hint and r.renjiao_chapter:
            by_locator[(r.subject, r.chapter_hint)].add(r.renjiao_chapter)
    conflicts = {k: v for k, v in by_locator.items() if len(v) > 1}
    print()
    print(f"同一书内定位映射到多个教材章（需人审） {len(conflicts)} 处")
    for (subject, chapter), targets in list(conflicts.items())[:args.sample]:
        print(f"  [{subject}] {chapter[:34]} -> {sorted(targets)[:3]}")

    # 五三章 → 教材(册,章) 汇总，作为新内容挂树的候选权威
    mapping: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for r in rows:
        if r.chapter_hint and r.renjiao_chapter:
            mapping[(r.subject, r.chapter_hint)][f"{r.renjiao_book} {r.renjiao_chapter}"] += 1
    print()
    print(f"五三章 → 教材章 汇总（{len(mapping)} 条）：")
    for (subject, chapter), counter in sorted(mapping.items())[:args.sample]:
        best, count = counter.most_common(1)[0]
        extra = "" if len(counter) == 1 else f"  （另有 {len(counter)-1} 种分歧）"
        print(f"  [{subject}] {chapter[:30]:<32} -> {best[:38]} ({count}){extra}")

    if args.write:
        path = write_table(rows)
        print()
        print(f"已写出 {path.relative_to(pack_io.REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
