# -*- coding: utf-8 -*-
"""把五三候选知识点对到修复后节点集上：先做**字形归一**的机械匹配，再把剩下的交人工判。

为什么必须先归一：库内写 `Fe(OH)3胶体的制备`（ASCII 3），五三转录件写 `Fe(OH)₃ 胶体的制备`
（下标 ₃ + 一个空格）。按字面比对会把同一条判成"库内没有"，于是**重复新建**。
上一个会话把绑定做坏（按标题字符串机械匹配 → 38.8% 零绑定、绑定错乱）是同一个坑的
另一个方向；这里的纪律是：**机械能判的必须判准，判不了的显式留出来给人，绝不猜。**

判定目标集固定取 `build/kb-staging`（修复后节点），不是成品目录——
对着成品包判会把材料绑到即将被删除的节点上，材料随即未绑定、导入时被剔除。

用法：
  PYTHONPATH=tools python -m kb_build.resolve_points            # 统计
  PYTHONPATH=tools python -m kb_build.resolve_points --write    # 写工作表
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import harvest_points, pack_io

OUT_RESOLVED = "wusan_resolved.csv"
OUT_UNRESOLVED = "wusan_unresolved.csv"
STAGING = pack_io.REPO / "build" / "kb-staging"

# 下标/上标数字与常见符号 -> ASCII。覆盖化学式与数学记法里实际出现过的写法。
_SUB = str.maketrans("₀₁₂₃₄₅₆₇₈₉₊₋", "0123456789+-")
_SUP = str.maketrans("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻", "0123456789+-")
# LaTeX 包装：$\mathrm{CO_2}$ / $\ce{...}$ / \(...\) 都剥成裸文本再比
_LATEX_WRAP = re.compile(r"\$+([^$]*)\$+")
_LATEX_CMD = re.compile(r"\\(?:mathrm|text|ce|rm|it)\s*\{([^{}]*)\}")
_BRACES = re.compile(r"[{}]")
# 只保留有区分度的字符：中文、字母、数字、化学/数学里带意义的符号
_KEEP = re.compile(r"[^\u4e00-\u9fff a-z0-9()+\-=<>·．.、,，]")
_PUNCT_TAIL = re.compile(r"[。．.；;：:，,、）)]+$")


def normalize(name: str) -> str:
    """把名称压成可比的规范形。

    规则只在"同义写法"上做等价，不在"近义概念"上做等价——后者必须人工判。
    因此这里不做同义词替换、不做去后缀（`…的定义` 不剥，那是另一个概念）。
    """
    text = unicodedata.normalize("NFKC", name or "")
    text = _LATEX_WRAP.sub(r"\1", text)
    text = _LATEX_CMD.sub(r"\1", text)
    text = text.translate(_SUB).translate(_SUP)
    text = _BRACES.sub("", text)
    text = text.replace("_", "").replace("\\", "")
    text = _KEEP.sub("", text.lower())
    text = re.sub(r"\s+", "", text)
    text = _PUNCT_TAIL.sub("", text)
    return text


def load_staged_nodes() -> tuple[dict[str, dict], dict[str, list[dict]]]:
    """返回 (规范化名 -> 节点, 科目 -> 该科全部节点)。节点集为修复后的 staging。"""
    path = STAGING / pack_io.PACK_NAME
    if not path.exists():
        raise SystemExit(f"缺少 {path}；先跑 PYTHONPATH=tools python -m kb_build.build --write")
    pack = json.loads(path.read_text(encoding="utf-8"))
    by_key: dict[str, dict] = {}
    by_subject: dict[str, list[dict]] = defaultdict(list)
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                entry = {"subject": subject["subject"], "slug": point["slug"],
                         "name": point["name"], "kind": point["kind"],
                         "theme": topic["name"]}
                by_subject[subject["subject"]].append(entry)
                by_key[f"{subject['subject']}::{normalize(point['name'])}"] = entry
                for alias in point.get("aliases") or []:
                    by_key.setdefault(f"{subject['subject']}::{normalize(alias)}", entry)
    return by_key, by_subject


class Resolution:
    __slots__ = ("row", "kind", "node")

    def __init__(self, row: dict, kind: str, node: dict | None) -> None:
        self.row = row
        self.kind = kind
        self.node = node


def resolve() -> tuple[list[Resolution], list[dict], dict]:
    points, _missing = harvest_points.harvest()
    by_key, by_subject = load_staged_nodes()

    out: list[Resolution] = []
    unresolved: list[dict] = []
    for p in points:
        key = f"{p.subject}::{normalize(p.name)}"
        node = by_key.get(key)
        kind = "exact" if (node and node["name"] == p.name) else ("normalized" if node else "none")
        item = {"subject": p.subject, "pdf_page": p.pdf_page,
                "content_page": p.content_page, "locator": p.locator, "candidate": p.name}
        out.append(Resolution(item, kind, node))
        if node is None:
            unresolved.append(item)
    return out, unresolved, by_subject


def write_tables(resolutions: list[Resolution], unresolved: list[dict],
                 by_subject: dict[str, list[dict]]) -> tuple[Path, Path]:
    tables = pack_io.REPO / "tools" / "kb_build" / "tables"
    resolved_path = tables / OUT_RESOLVED
    with resolved_path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["subject", "pdf_page", "content_page", "candidate",
                    "match_kind", "node_slug", "node_name"])
        for r in resolutions:
            w.writerow([r.row["subject"], r.row["pdf_page"], r.row["content_page"],
                        r.row["candidate"], r.kind,
                        r.node["slug"] if r.node else "",
                        r.node["name"] if r.node else ""])

    unresolved_path = tables / OUT_UNRESOLVED
    with unresolved_path.open("w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["subject", "pdf_page", "content_page", "locator", "candidate",
                    "chapter_nodes_sample"])
        # 把同章的既有节点名带出来，人工判定时不必再查库
        by_chapter: dict[tuple[str, str], list[str]] = defaultdict(list)
        for subject, nodes in by_subject.items():
            for n in nodes:
                by_chapter[(subject, n["theme"])].append(n["name"])
        for item in unresolved:
            chapter = item["locator"].split("/")[0].strip() if item["locator"] else ""
            names = by_chapter.get((item["subject"], chapter), [])
            w.writerow([item["subject"], item["pdf_page"], item["content_page"],
                        item["locator"], item["candidate"], " | ".join(names[:60])])
    return resolved_path, unresolved_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--sample", type=int, default=12)
    args = parser.parse_args(argv)

    resolutions, unresolved, by_subject = resolve()
    kinds = Counter(r.kind for r in resolutions)
    total = len(resolutions)
    print(f"候选知识点 {total} 条")
    print(f"  字符完全相同      {kinds['exact']}")
    print(f"  归一化后命中      {kinds['normalized']}   ← 字形差异（下标/全角/空格/LaTeX），库内其实有")
    print(f"  库内没有          {kinds['none']}")
    print(f"  机械命中率        {(kinds['exact']+kinds['normalized'])/total:.1%}")
    print()
    print(f"修复后节点集：{sum(len(v) for v in by_subject.values())} 个"
          f"（{'、'.join(f'{k} {len(v)}' for k, v in by_subject.items())}）")

    if args.sample:
        print()
        print("归一化命中的样例（这些按字面比会误判为「库内没有」）：")
        shown = 0
        for r in resolutions:
            if r.kind == "normalized" and shown < args.sample:
                print(f"  [{r.row['subject']}] 「{r.row['candidate']}」 -> 「{r.node['name']}」")
                shown += 1

    if args.write:
        rp, up = write_tables(resolutions, unresolved, by_subject)
        print()
        print(f"已写出 {rp.relative_to(pack_io.REPO)}")
        print(f"已写出 {up.relative_to(pack_io.REPO)}（待人工判定 {len(unresolved)} 条）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
