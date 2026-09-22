# -*- coding: utf-8 -*-
"""把每一页五三的「对应人教版」归到知识库的**规范(册,章)**上。

为什么需要它：转录员在页头写下的「对应人教版」是自由文本——同一个人会在两页上
分别写成 `第5章 细胞的能量供应和利用` 与 `第五章 细胞的能量供应和利用`，同一页
也可能同时提好几个册章（跨章综合页）。而知识库的树只认 `chapter_by_source.csv`
里那份**规范章名**（`生物学必修1` + `第五章 细胞的能量供应和利用`）。

两边的差异必须在**一处**归一，否则每个下游脚本都要各自写一遍"第5章 == 第五章"，
而写漏的那一处会安静地把整章内容挂错位置。这里就是那一处。

口径：
- 规范侧只取知识库自己认的 (册, 章)（见 `tables/chapter_by_source.csv`、
  `tables/chapter_map.csv`）。
- 页侧按**主体在前的第一个**匹配取：跨章综合页把首位的册章作为归属，其余记为
  旁及（`also`），供人工在挂树时判断，不机械地取"最后一个"或"出现最多的"。
- 匹配不上的显式列出，不猜、不丢。

用法：
  PYTHONPATH=tools python -m kb_build.wusan_route            # 报告
  PYTHONPATH=tools python -m kb_build.wusan_route --write    # 写 tables/
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

PLACEMENT_NAME = "wusan_page_placement.csv"
OUT_NAME = "wusan_routed.csv"

# 章号必须同时认中文数字与阿拉伯数字（化学写「第一章」，生物写「第2章」）。
CHAPTER_RE = re.compile(r"第(?P<num>[一二三四五六七八九十百\d]+)章\s*(?P<title>[^\s（(；;、,，]*)?")
SUBJECT_PREFIX = {"MATH": "数学", "PHYSICS": "物理",
                  "CHEMISTRY": "化学", "BIOLOGY": "生物学"}
_CN_DIGIT = {1: "一", 2: "二", 3: "三", 4: "四", 5: "五",
             6: "六", 7: "七", 8: "八", 9: "九"}
_CN_NUM = {v: k for k, v in _CN_DIGIT.items()}
_CN_NUM.update({"十": 10, "十一": 11, "十二": 12, "十三": 13})

# 册名写法：知识库里**自己就不统一**——物理/化学/数学写 `必修第一册`，
# 生物写 `必修1`，而两者都写 `选择性必修1`。所以册名不能靠"前缀+简写"拼，
# 只能按**规范册名**反推它可以被写成哪几种样子，再去文本里找。
# 关键坑：`必修1` 是 `选择性必修1` 的子串，匹配必修时必须排除前面带「选择性/选必」的。
_SELECTIVE_LITERALS = ("选择性", "选必")
_SELECTIVE = r"(?:选择性|选必)"
_BOOK_RE_CACHE: dict[str, re.Pattern[str]] = {}


def book_pattern(book: str) -> re.Pattern[str]:
    """规范册名（`化学必修第一册`）→ 能在页头文本里认出它的正则。"""
    cached = _BOOK_RE_CACHE.get(book)
    if cached is not None:
        return cached
    subject = _subject_of_book(book)
    suffix = book[len(SUBJECT_PREFIX[subject]):] if subject in SUBJECT_PREFIX else book
    kind = "选择性必修" if suffix.startswith("选择性必修") else "必修"
    # 册号可能是阿拉伯数字（`必修1`）也可能是中文数字（`必修第一册`）——只按
    # `\D` 剥数字会把中文写法的册号剥空，于是整条规则退化成"字面量匹配"：
    # 既认不出 `必修一` 这类写法，也丢掉"排除选择性必修"的守卫，`必修第三册`
    # 就会在 `选择性必修第三册` 里命中，把整页挂到错误的册上。
    found = re.search(r"[一二三四五六七八九十]+|\d+", suffix)
    number = _cn_to_int(found.group(0)) if found else None
    if number is None:
        pattern = re.compile(re.escape(suffix))
    else:
        cn = _CN_DIGIT.get(number, str(number))
        # 三种写法都认：`必修第一册` / `必修1` / `必修一`
        number_alt = f"(?:第{cn}册|第{number}册|{cn}|{number})"
        lead = rf"{_SELECTIVE}必修" if kind == "选择性必修" else r"必修"
        guard = "" if kind == "选择性必修" else "".join(
            f"(?<!{lit})" for lit in _SELECTIVE_LITERALS)
        pattern = re.compile(guard + lead + number_alt)
    _BOOK_RE_CACHE[book] = pattern
    return pattern


def _subject_of_book(book: str) -> str:
    for subject, prefix in SUBJECT_PREFIX.items():
        if book.startswith(prefix):
            return subject
    return "?"
# 章名比较时抹掉的书写差异
_NOISE = re.compile(r"[\s·・、,，。．;；:：()（）\[\]【】]")


def _cn_to_int(text: str) -> int | None:
    if text.isdigit():
        return int(text)
    return _CN_NUM.get(text)


def _norm_title(text: str) -> str:
    return _NOISE.sub("", text or "")


def canonical_pairs() -> dict[str, set[tuple[str, str]]]:
    """科目 → 该科知识库承认的 (册, 章)。

    权威源是**生成好的包**（`build/kb-staging`，没有就退回成品）：册与章的层级
    就在包的 topic 树里。授权表（`chapter_by_source.csv` / `chapter_map.csv`）
    只覆盖走过审校流程的来源单元，把它当全集会把库里**已经存在**的章判成
    "库无此章"——那会让报告长出一批根本不存在的缺口（实测：22 项里多数如此）。
    两张表仍然要读：它们记的是"应该归到哪"，是修树时的目标值。
    """
    out: dict[str, set[tuple[str, str]]] = defaultdict(set)
    tables = pack_io.REPO / "tools" / "kb_build" / "tables"
    for name in ("chapter_by_source.csv", "chapter_map.csv"):
        path = tables / name
        if not path.exists():
            continue
        with path.open(encoding="utf-8", newline="") as fh:
            for row in csv.DictReader(fh):
                book = (row.get("book") or row.get("volume") or "").strip()
                chapter = (row.get("chapter") or "").strip()
                subject = _subject_of_book(book)
                # `跨册综合` 不属于任何一科，不是归位目标；它的节点在各科之下另行出现
                if book and chapter and subject != "?":
                    out[subject].add((book, chapter))
    for subject, book, chapter in _pack_chapters():
        out[subject].add((book, chapter))
    return out


def _pack_chapters() -> list[tuple[str, str, str]]:
    """包里的 (科目, 册, 章) —— 章层 = 父节点是册层的那一层。"""
    for root in (pack_io.work_dir(), pack_io.release_dir()):
        path = root / pack_io.PACK_NAME
        if not path.exists():
            continue
        pack = json.loads(path.read_text(encoding="utf-8"))
        found: list[tuple[str, str, str]] = []
        for subject in pack["subjects"]:
            name = subject["subject"]
            by_slug = {t["slug"]: t for t in subject["topics"]}
            for topic in subject["topics"]:
                parent = by_slug.get(topic.get("parentSlug"))
                if parent is not None and parent.get("parentSlug") is None:
                    found.append((name, parent["name"], topic["name"]))
        return found
    return []


def _book_hits(subject: str, text: str, known: set[tuple[str, str]]) -> list[tuple[int, str]]:
    """文本里出现过的规范册名 (出现位置, 册名)，按位置排序。

    只认**规范册名**（`化学必修第一册`）：不认得就不会被匹配，也不会悄悄拼出
    一个不存在的册名（`化学必修1`）来把整页挡在门外。
    """
    found: list[tuple[int, str]] = []
    for book in {b for b, _c in known}:
        if _subject_of_book(book) != subject:
            continue
        match = book_pattern(book).search(text)
        if match:
            found.append((match.start(), book))
    found.sort()
    return found


def _book_variants(subject: str, text: str, known: set[tuple[str, str]]) -> list[str]:
    out: list[str] = []
    for _index, book in _book_hits(subject, text, known):
        if book not in out:
            out.append(book)
    return out


def _chapter_mentions(text: str) -> list[tuple[int, int, str]]:
    """(位置, 章号, 章名) —— 按出现顺序。

    页头会写 `第2章 第4节 蛋白质是……`，`第X章` 后面紧跟的是**节**号。不剥掉它，
    就会造出一个 `第2章 第4节` 这样根本不存在的章名。
    """
    out: list[tuple[int, int, str]] = []
    for match in CHAPTER_RE.finditer(text):
        title = re.sub(r"^第[一二三四五六七八九十百\d]+[节讲]\s*", "",
                       match.group("title") or "").strip()
        out.append((match.start(), _cn_to_int(match.group("num")) or 0, title))
    return out


def _numbered_chapters(known: set[tuple[str, str]]) -> dict[tuple[str, int], list[tuple[str, str]]]:
    """(册, 章号) → 该号下的规范章。册名 + 章号在教材里唯一标识一章。"""
    index: dict[tuple[str, int], list[tuple[str, str]]] = defaultdict(list)
    for book, chapter in known:
        match = CHAPTER_RE.match(chapter)
        if not match:
            continue
        number = _cn_to_int(match.group("num"))
        if number is None:
            continue
        index[(book, number)].append((book, chapter))
    return index


def _titles_agree(want: str, canonical: str) -> bool:
    """章名互相包含即算同一个章（`人体的内环境与稳态` vs `内环境与稳态`）。"""
    have = _norm_title(CHAPTER_RE.sub("", canonical, count=1))
    if not want or not have:
        return False
    return want in have or have in want


def raw_pairs(subject: str, hint: str, known: set[tuple[str, str]]) -> list[tuple[str, str]]:
    """页头里**写过**的 (册, 章)，不要求它们是库内的规范章。

    用途只有一个：把"五三讲了、库里根本没有这一章"的章列出来——那是结构性缺口，
    规范化那一步会把它们判成"匹配不上"，从此在报告里消失。
    册名仍走规范册名识别（`数学必修第二册` 是库内就有的册，只是缺这一章）。
    每个章只配**它前面最近的那个册**，不做册×章的笛卡尔积——一页里同时提到
    `必修第一册 第一章` 与 `选择性必修3 第一章` 时，叉乘会造出
    `选择性必修3 第一章 物质及其变化` 这种根本不存在、却看着很像的章。
    """
    text = (hint or "").strip()
    hits = _book_hits(subject, text, known)
    pairs: list[tuple[str, str]] = []
    for match in CHAPTER_RE.finditer(text):
        title = (match.group("title") or "").strip()
        chapter = f"第{match.group('num')}章 {title}".strip()
        before = [book for index, book in hits if index <= match.start()]
        book = before[-1] if before else (hits[0][1] if hits else "")
        if (book, chapter) not in pairs:
            pairs.append((book, chapter))
    return pairs


def route(subject: str, hint: str, known: set[tuple[str, str]]) -> tuple[str, str, list[str], str]:
    """返回 (册, 章, 旁及的册章, 说明)。匹配不上时册章为空串。

    判据是**册名 + 章号**：转录件的章名写法与库里的规范章名经常不同（五三写
    `第一章 认识有机化合物`，人教叫 `第一章 有机化合物的结构特点与研究方法`；
    五三写 `第一章 人体的内环境与稳态`，库里叫 `第一章 内环境与稳态`）。
    要求字面相同会把同一章判成"库内没有"，整章内容被挡在外面。只有同号多章
    （教材一册内几乎不会发生）时才用章名消歧。
    """
    text = (hint or "").strip()
    if not text:
        return "", "", [], "页头无「对应人教版」"
    books = _book_variants(subject, text, known)
    mentions = _chapter_mentions(text)
    if not mentions:
        # 只写了册没写章（如「全书目录」）——不猜章
        return "", "", [], f"只有册无章：{text[:40]}"

    index = _numbered_chapters(known)
    # 页头没点名册时不能就此放弃：`对应人教版：第5章 细胞的能量供应和利用`
    # 是常见写法（册写在另一个字段里）。此时在该科的全部册里找，再用章名消歧。
    if books:
        search_books = books
    else:
        search_books = sorted({book for book, _c in known
                               if _subject_of_book(book) == subject})

    hits: list[tuple[str, str]] = []
    for _pos, number, title in mentions:
        found: list[tuple[str, str]] = []
        for book in search_books:
            for pair in index.get((book, number), []):
                if pair not in found:
                    found.append(pair)
        if not found:
            continue
        chosen = found[0]
        if title:
            narrowed = [p for p in found if _titles_agree(_norm_title(title), p[1])]
            if len(narrowed) == 1:
                chosen = narrowed[0]
        if chosen not in hits:
            hits.append(chosen)
    if not hits:
        missed_numbers = [m[1] for m in mentions if m[1]]
        if books and missed_numbers:
            return "", "", [], (f"{books[0]} 里没有第 {missed_numbers[0]} 章："
                                f"{(mentions[0][2] or text)[:30]}")
        return "", "", [], f"章名不在规范表内：{text[:46]}"
    primary = hits[0]
    return primary[0], primary[1], [f"{b} {c}" for b, c in hits[1:]], ""


def load_placements() -> list[dict]:
    path = pack_io.REPO / "tools" / "kb_build" / "tables" / PLACEMENT_NAME
    with path.open(encoding="utf-8", newline="") as fh:
        return list(csv.DictReader(fh))


def run() -> tuple[list[dict], list[dict]]:
    known = canonical_pairs()
    routed: list[dict] = []
    missed: list[dict] = []
    for row in load_placements():
        subject = row["subject"]
        # 册与章常常分在两个字段里（`renjiao_book` 写册、`renjiao_chapter` 写章），
        # 先合并成一段再归位：分两次试会把"只有章"的那一半当成"确认归不了"。
        raw = " ".join(part for part in
                       ((row.get("renjiao_book") or "").strip(),
                        (row.get("renjiao_chapter") or "").strip()) if part)
        book, chapter, also, note = route(subject, raw, known.get(subject, set()))
        record = {"subject": subject, "pdf_page": row["pdf_page"],
                  "content_page": row["content_page"], "book": book, "chapter": chapter,
                  "also": " | ".join(also), "note": note,
                  "wusan_chapter": (row.get("wusan_chapter") or "").strip(),
                  "raw": raw}
        (routed if book else missed).append(record)
    missed = _fill_by_wusan_chapter(routed, missed)
    return routed, missed


def _fill_by_wusan_chapter(routed: list[dict], missed: list[dict]) -> list[dict]:
    """页头**完全没写章号**时，用同一五三章里已归位的页反推。

    这类页占剩下的多数（数学整段只写「必修第二册」）。做法是先统计每个五三章 →
    规范(册,章)的分布，只在该五三章**一致**指向同一个规范章时才回填，
    否则留给人审——五三的章与教材的章本来就不保证对齐，分歧处不许猜。

    **页头写了章号、只是库里没有这一章**的页不走这里：那说明内容落在知识库还没有的
    章上，是要人处理的**结构性缺口**。曾经放它走这条路，结果把数学必修第二册
    「第十章 概率」的 7 页反推成了「第七章 复数」——因为它们同属五三的
    「第五章 平面向量与复数」，多数票落在复数上。
    """
    votes: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for record in routed:
        if record["wusan_chapter"]:
            votes[(record["subject"], record["wusan_chapter"])][
                (record["book"], record["chapter"])] += 1

    still: list[dict] = []
    for record in missed:
        if _chapter_mentions(record["raw"]):
            record["note"] = f"页头写了章号但库里没有这一章：{record['raw'][:34]}"
            still.append(record)
            continue
        key = (record["subject"], record["wusan_chapter"])
        counter = votes.get(key)
        if not counter:
            still.append(record)
            continue
        (book, chapter), count = counter.most_common(1)[0]
        share = count / sum(counter.values())
        if share < 0.9:
            record["note"] = f"五三章内归属分歧（{len(counter)} 种）：{record['note'] or record['raw'][:30]}"
            still.append(record)
            continue
        record["book"], record["chapter"] = book, chapter
        record["note"] = "由同一五三章已归位页反推"
        routed.append(record)
    return still


def write_table(routed: list[dict], missed: list[dict]) -> Path:
    path = pack_io.REPO / "tools" / "kb_build" / "tables" / OUT_NAME
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["subject", "pdf_page", "content_page", "book", "chapter",
                         "also", "note", "raw"])
        for record in routed + missed:
            writer.writerow([record[k] for k in
                             ("subject", "pdf_page", "content_page", "book", "chapter",
                              "also", "note", "raw")])
    return path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--sample", type=int, default=12)
    args = parser.parse_args(argv)

    routed, missed = run()
    total = len(routed) + len(missed)
    print(f"归位 {len(routed)}/{total} 页（{len(routed)/total:.1%}）")
    by_subject = Counter(r["subject"] for r in routed)
    miss_subject = Counter(r["subject"] for r in missed)
    for subject in SUBJECT_PREFIX:
        print(f"  {subject:<10} 归位 {by_subject.get(subject,0):>4} / 缺 {miss_subject.get(subject,0):>3}")

    print()
    print("按规范章汇总：")
    per_chapter = Counter((r["subject"], r["book"], r["chapter"]) for r in routed)
    for (subject, book, chapter), count in sorted(per_chapter.items(), key=lambda kv: -kv[1])[:args.sample]:
        print(f"  {count:>3} 页  [{subject}] {book} {chapter}")

    if missed:
        print()
        print(f"未归位 {len(missed)} 页（需人审，不猜）：")
        for record in missed[:args.sample]:
            print(f"  [{record['subject']}] p{record['pdf_page']} {record['note']}")
    if args.write:
        path = write_table(routed, missed)
        print()
        print(f"已写出 {path.relative_to(pack_io.REPO)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
