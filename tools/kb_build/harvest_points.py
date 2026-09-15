# -*- coding: utf-8 -*-
"""把五三页转录件里"本页可形成的知识点"抽成一张索引表。

为什么要有这一步：页转录件是**给人读**的正文（含表格、框图、公式），而入库要的是
**原子知识点**这一层。两者之间必须有一层显式映射，否则"结构化录入"就成了凭印象
手打——既不可审（没法核对某页到底提出了哪些点），也不可重放（页转录更新后
索引不会跟着变）。

本模块只做抽取与分类，不改任何知识库文件。产物：
  tables/wusan_page_points.csv   一行一个（页, 知识点名），含页码与书内定位
  分类结果打印到 stdout（命中已有节点 / 需要新建 / 疑似同义）

分类判据只用**确定性**的三条（不做模糊匹配）：名称完全相同、命中已有节点的
aliases、去掉常见后缀词后相同。模糊匹配在这里是负面的：把"电离平衡"并进
"水解平衡"这种错，比留下两个候选更难发现。

用法：
  PYTHONPATH=tools python -m kb_build.harvest_points            # 只统计
  PYTHONPATH=tools python -m kb_build.harvest_points --write    # 写索引表
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io, tables

TRANSCRIPT_ROOT = pack_io.REPO / "knowledge-research" / "candidates" / "wusan"
OUT_NAME = "wusan_page_points.csv"

SUBJECT_DIRS = {
    "MATH": "math",
    "PHYSICS": "physics",
    "CHEMISTRY": "chemistry",
    "BIOLOGY": "biology",
}

_POINTS_HEADING = re.compile(r"^##\s*本页可形成的知识点\s*$", re.MULTILINE)
_HEADER_FIELD = re.compile(r"^-\s*(?P<key>[^：:]+)[：:]\s*(?P<value>.*)$", re.MULTILINE)
_PAGE_IN_TITLE = re.compile(r"PDF\s*p(\d+)(?:\s*[–-]\s*p(\d+))?")
_CONTENT_PAGE = re.compile(r"内容页\s*([0-9]+)")
_BULLET = re.compile(r"^[-*]\s+(?P<text>.+?)\s*$")

# 页转录里这些行不是知识点：说明性文字与"无"占位。
_NOT_A_POINT = re.compile(r"^[（(]\s*无|^\s*$|题型标签|不收录|本页为")


class HarvestedPoint:
    __slots__ = ("subject", "pdf_page", "content_page", "locator", "name")

    def __init__(self, subject: str, pdf_page: str, content_page: str,
                 locator: str, name: str) -> None:
        self.subject = subject
        self.pdf_page = pdf_page
        self.content_page = content_page
        self.locator = locator
        self.name = name


def _parse_page_file(path: Path, subject: str) -> list[HarvestedPoint]:
    text = path.read_text(encoding="utf-8")
    title = text.split("\n", 1)[0]
    match = _PAGE_IN_TITLE.search(title)
    pdf_page = match.group(1) if match else path.stem
    span = ""
    if match and match.group(2):
        span = f"{match.group(1)}-{match.group(2)}"
        pdf_page = span
    content = _CONTENT_PAGE.search(text)
    content_page = content.group(1) if content else ""

    locator = ""
    for field in _HEADER_FIELD.finditer(text.split("##", 1)[0]):
        if "书内定位" in field.group("key"):
            locator = field.group("value").strip()
            break

    heading = _POINTS_HEADING.search(text)
    if not heading:
        return []
    tail = text[heading.end():]
    # 知识清单段到下一个 ## 标题为止
    stop = re.search(r"^##\s", tail, re.MULTILINE)
    if stop:
        tail = tail[:stop.start()]

    points: list[HarvestedPoint] = []
    for line in tail.split("\n"):
        bullet = _BULLET.match(line.rstrip())
        if not bullet:
            continue
        name = bullet.group("text").strip().strip("`")
        if _NOT_A_POINT.search(name):
            continue
        # 去掉行尾的括注（如"（$0^\circ\le\alpha<180^\circ$）"），它是对名字的注解，
        # 不是名字的一部分；入库时正文会带公式，名称必须保持短。
        name = re.sub(r"（[^（）]*）\s*$", "", name).strip()
        if not name:
            continue
        points.append(HarvestedPoint(subject, pdf_page, content_page, locator, name))
    return points


def harvest() -> tuple[list[HarvestedPoint], list[tuple[str, str]]]:
    """返回 (所有知识点, [(科目, 文件) 缺"本页可形成的知识点"段的文件])。"""
    points: list[HarvestedPoint] = []
    missing: list[tuple[str, str]] = []
    for subject, folder in SUBJECT_DIRS.items():
        directory = TRANSCRIPT_ROOT / folder / "transcript"
        if not directory.is_dir():
            continue
        for path in sorted(directory.glob("p*.md")):
            found = _parse_page_file(path, subject)
            if not found:
                missing.append((subject, path.name))
            points.extend(found)
    return points, missing


def _normalize(name: str) -> str:
    """只做确定性的归一：去空白、去结尾标点、去难度记号。"""
    return re.sub(r"[★☆\s]", "", name).strip().rstrip("。．.：:，、；;").strip()


def classify(points: list[HarvestedPoint]) -> dict[str, object]:
    """把抽取到的点名对到已有节点上。只用确定性判据。"""
    pack = pack_io.load_json(pack_io.pack_path())
    by_name: dict[tuple[str, str], str] = {}
    by_alias: dict[tuple[str, str], str] = {}
    all_slugs: dict[str, set[str]] = defaultdict(set)
    for subject, _t, point in pack_io.iter_points(pack):
        by_name[(subject, _normalize(point["name"]))] = point["slug"]
        all_slugs[subject].add(point["slug"])
        for alias in point.get("aliases") or []:
            by_alias.setdefault((subject, _normalize(alias)), point["slug"])

    exact: list[tuple[HarvestedPoint, str]] = []
    via_alias: list[tuple[HarvestedPoint, str]] = []
    new: list[HarvestedPoint] = []
    for item in points:
        key = (item.subject, _normalize(item.name))
        if key in by_name:
            exact.append((item, by_name[key]))
        elif key in by_alias:
            via_alias.append((item, by_alias[key]))
        else:
            new.append(item)
    return {"exact": exact, "alias": via_alias, "new": new}


def write_index(points: list[HarvestedPoint]) -> Path:
    path = tables.TABLES_DIR / OUT_NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "pdf_page", "content_page", "locator", "point_name"])
        for item in points:
            writer.writerow([item.subject, item.pdf_page, item.content_page,
                             item.locator, item.name])
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="写 tables/wusan_page_points.csv")
    parser.add_argument("--sample", type=int, default=8, help="每类打印多少条样例")
    args = parser.parse_args(argv)

    points, missing = harvest()
    per_subject = Counter(p.subject for p in points)
    print(f"抽出知识点条目 {len(points)} 条")
    for subject in SUBJECT_DIRS:
        print(f"  {subject:<10} {per_subject.get(subject, 0)}")

    result = classify(points)
    exact = result["exact"]
    via_alias = result["alias"]
    new = result["new"]
    print()
    print(f"命中已有节点（名称完全相同） {len(exact)}")
    print(f"命中已有节点（别名）         {len(via_alias)}")
    print(f"库里没有、需新建             {len(new)}")
    dupes = [n for n, c in Counter((p.subject, _normalize(p.name)) for p in new).items() if c > 1]
    print(f"其中在五三内部重复出现       {len(dupes)} 个名字")

    for label, rows in (("需新建样例", [(p, "") for p in new]),
                        ("同名命中样例", exact[:args.sample])):
        if not rows:
            continue
        print()
        print(f"{label}：")
        for item, slug in rows[:args.sample]:
            suffix = f" -> {slug}" if slug else ""
            print(f"  [{item.subject}] p{item.pdf_page} {item.name}{suffix}")

    if missing:
        print()
        print(f"缺「本页可形成的知识点」段的文件 {len(missing)} 个（前 10）：")
        for subject, name in missing[:10]:
            print(f"  {subject} {name}")

    if args.write:
        path = write_index(points)
        print()
        print(f"已写出 {path.relative_to(pack_io.REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
