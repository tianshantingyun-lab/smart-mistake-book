# -*- coding: utf-8 -*-
"""无绑定材料的机械重绑候选。

判据只认**归一化后的整名包含**：把标题与节点名都去掉 LaTeX 命令、标点、空白并
统一大小写后，一方完整出现在另一方里，且该科目内这个节点名唯一。

不做模糊/拼音/编辑距离匹配：讲题时检索错材料比检索不到更糟——错的材料会被
当成该知识点的依据讲出来，用户无从发现。宁可漏，不可错。

  用法： PYTHONPATH=tools python tools/kb_build/match_unbound.py
"""

from __future__ import annotations

import csv
import json
import re
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io

OUT = pack_io.REPO / "build" / "kb-staging"
STAGING = OUT / pack_io.PACK_NAME

_LATEX = re.compile(r"\$[^$]*\$|\\(?:[a-zA-Z]+|.)")
_KEEP = re.compile(r"[\w一-鿿]+")


def normalize(text: str) -> str:
    text = _LATEX.sub("", text)
    text = unicodedata.normalize("NFKC", text)
    return "".join(_KEEP.findall(text)).lower()


def main() -> int:
    pack = json.loads(STAGING.read_text(encoding="utf-8"))
    nodes: dict[tuple[str, str], list[tuple[str, str]]] = defaultdict(list)
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                nodes[subject["subject"]].append((point["slug"], point["name"]))
    # 别名与名称一样参与匹配：规范要求名称进别名表正是为了召回，
    # 只用名称会漏掉"材料标题用的是别名写法"的那一批。
    aliases: dict[tuple[str, str], set[str]] = defaultdict(set)
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                for alias in point.get("aliases") or [point["name"]]:
                    aliases[(subject["subject"], point["slug"])].add(alias)
    norm_nodes = {s: [(slug, name, {normalize(a) for a in aliases.get((s, slug), {name})} - {""})
                      for slug, name in rows]
                  for s, rows in nodes.items()}

    rows: list[dict] = []
    for name in ("unbound_released", "unbound_native"):
        with (OUT / f"{name}.csv").open(encoding="utf-8", newline="") as fh:
            rows.extend(csv.DictReader(fh))

    hits: list[dict] = []
    for row in rows:
        subject = row["subject"]
        title = normalize(row["title"])
        if not title:
            continue
        candidates = []
        for slug, node_name, nforms in norm_nodes.get(subject, []):
            for form in nforms:
                # 名字太短（"基因""电流"）时子串匹配几乎必然误命中，要求它至少
                # 占标题的三成，否则"电流"会命中一切含"电流"的方法标题。
                if len(form) < 2:
                    continue
                if form in title and len(form) * 3 >= len(title):
                    candidates.append((slug, node_name))
                    break
                if title in form and len(title) * 3 >= len(form):
                    candidates.append((slug, node_name))
                    break
        if len(candidates) == 1:
            hits.append({**row, "target_slug": candidates[0][0],
                         "target_name": candidates[0][1]})
        elif len(candidates) > 1:
            hits.append({**row, "target_slug": "", "target_name": "",
                         "ambiguous": " | ".join(f"{s}={n}" for s, n in candidates[:4])})

    unique = [h for h in hits if h["target_slug"]]
    ambiguous = [h for h in hits if not h["target_slug"]]
    print(f"无绑定材料 {len(rows)} 条：唯一命中 {len(unique)}、多义 {len(ambiguous)}、"
          f"未命中 {len(rows) - len(hits)}")
    print()
    print("唯一命中按科目：", dict(Counter(h["subject"] for h in unique)))
    print()
    for h in unique[:15]:
        print(f"[{h['subject']}] {h['title'][:58]}")
        print(f"      -> {h['target_name']}")
    fields = ["sidecar", "subject", "slug", "title", "was_bound_to", "delete_reason",
              "target_slug", "target_name", "ambiguous"]
    with (OUT / "unbound_match.csv").open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        writer.writerows(hits)
    print(f"\n已写 {OUT / 'unbound_match.csv'}（{len(hits)} 行）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
