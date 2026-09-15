# -*- coding: utf-8 -*-
"""全量质量扫描：材料绑定的可疑对 + 节点名/材料名的残句。

为什么要先扫再改：上一轮抽查 14 条绑定得出"约一半不搭"，但那是主观抽样、不是测量，
拿它去改 9000 条绑定等于用估计当判据。这里把口径固定下来——用 **2-gram Jaccard**
量材料标题与目标节点名的字面重合，低分的就是"值得逐条看"的可疑对；高分的不动。

可疑 ≠ 错误：材料讲方法（"半周期反号式：f(x+a)=-f(x)⇒T=2a"）、节点记概念
（"函数的周期性"），字面不重合也可能是对的。所以本脚本只**排序**，判定留给人。

  用法： PYTHONPATH=tools python tools/kb_build/audit_quality.py
"""

from __future__ import annotations

import collections
import json
import random
import re
import unicodedata

from kb_build import pack_io, textfix

STAGING = pack_io.REPO / "build" / "kb-staging"

_LATEX = re.compile(r"\$[^$]*\$|\\(?:[a-zA-Z]+|.)")
_KEEP = re.compile(r"[\w一-鿿]+")

# 残句特征：句读标点出现在名称里、疑问语气、以不完整结构收尾。
_FRAGMENT_TAIL = re.compile(r"[的与和及或是在为]{1}$")
_QUESTION = re.compile(r"请回答|请写出|请计算|求下列|试判断|能否|是什么|为什么|如何")
_PUNCT = re.compile(r"[。？，；！]")


def normalize(text: str) -> str:
    text = _LATEX.sub("", text)
    text = unicodedata.normalize("NFKC", text)
    return "".join(_KEEP.findall(text)).lower()


def bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / len(left | right)


def main() -> int:
    pack_io.use_directory(STAGING)
    pack = pack_io.load_json(pack_io.pack_path())
    nodes: dict[tuple[str, str], str] = {}
    names: list[tuple[str, str]] = []
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                nodes[(subject["subject"], point["slug"])] = point["name"]
                names.append((subject["subject"], point["name"]))

    materials = []
    for path in pack_io.sidecar_paths():
        materials.extend(pack_io.load_json(path)["materials"])

    print("=" * 72)
    print("一、材料绑定：标题与目标节点名的字面重合")
    print("=" * 72)
    scored = []
    unbound = 0
    for material in materials:
        bindings = material.get("bindings") or []
        if not bindings:
            unbound += 1
            continue
        slug = bindings[0]["knowledgeNodeId"].split(":")[-1]
        name = nodes.get((material["subject"], slug))
        if name is None:
            continue
        score = jaccard(bigrams(normalize(material["title"])), bigrams(normalize(name)))
        scored.append((score, material, name))
    scored.sort(key=lambda row: row[0])
    buckets = collections.Counter()
    for score, _m, _n in scored:
        buckets["0.00 完全无重合" if score == 0 else
                "0.01-0.10 几乎无重合" if score < 0.1 else
                "0.10-0.25 弱重合" if score < 0.25 else
                "0.25-0.50 部分重合" if score < 0.5 else "≥0.50 高度重合"] += 1
    print(f"有绑定材料 {len(scored)} 条（另有 {unbound} 条无绑定）。分组：")
    for key in ("0.00 完全无重合", "0.01-0.10 几乎无重合", "0.10-0.25 弱重合",
                "0.25-0.50 部分重合", "≥0.50 高度重合"):
        print(f"  {buckets[key]:>5}  {key}")
    zero = [row for row in scored if row[0] == 0]
    print()
    print("完全无重合的绑定集中在哪些目标节点（前 15，看根因）：")
    for name, count in collections.Counter(row[2] for row in zero).most_common(15):
        print(f"  {count:>4}  {name[:52]}")
    print()
    print("随机抽 30 条——对错要人读，分数只是排序：")
    for score, material, name in random.Random(20260913).sample(zero, 30):
        print(f"  [{material['subject']}] {material['title'][:46]}")
        print(f"        -> {name[:46]}")

    print()
    print("=" * 72)
    print("二、名称残句")
    print("=" * 72)
    for label, pred in (("节点名含句读标点", lambda t: bool(_PUNCT.search(t))),
                        ("节点名含疑问语气", lambda t: bool(_QUESTION.search(t))),
                        ("节点名以虚词收尾", lambda t: bool(_FRAGMENT_TAIL.search(t)))):
        hits = [(s, n) for s, n in names if pred(n)]
        print(f"\n{label}：{len(hits)}")
        for s, n in hits[:14]:
            print(f"  [{s}] {n[:58]}")
    print()
    print("=" * 72)
    print("三、名称结构缺陷")
    print("=" * 72)
    structural = {
        "引号/括号不成对": lambda t: (t.count("“") != t.count("”")
                                     or t.count("「") != t.count("」")
                                     or t.count("（") != t.count("）")),
        "以数字收尾（抽取截断）": lambda t: bool(re.search(r"\d$", t)),
        "属性前缀（定义：/表达式：…）": lambda t: bool(
            re.match(r"^(定义|概念|大小|方向|表达式|应用|公式|单位|性质|特点|现象)[：:]", t)),
        "疑问句当名称": lambda t: t.rstrip().endswith("？") or "请回答" in t,
    }
    for label, pred in structural.items():
        hits = [(s, n) for s, n in names if pred(n)]
        print(f"\n{label}：{len(hits)}")
        for s, n in hits[:22]:
            print(f"  [{s}] {n[:60]}")

    print()
    print("=" * 72)
    print("四、零重合绑定：来源分布与同科目内最相似的节点")
    print("=" * 72)
    origin = collections.Counter()
    rows_best = []
    for material in materials:
        bindings = material.get("bindings") or []
        if not bindings:
            continue
        slug = bindings[0]["knowledgeNodeId"].split(":")[-1]
        name = nodes.get((material["subject"], slug))
        if not name:
            continue
        left = bigrams(normalize(material["title"]))
        right = bigrams(normalize(name))
        if not left or not right or (left & right):
            continue
        origin[(material["subject"], material["sourceId"])] += 1
        among = sorted(((jaccard(left, bigrams(normalize(other))), other)
                        for sub, other in names
                        if sub == material["subject"] and other != name),
                       reverse=True)
        rows_best.append((material, name, among[0] if among else (0.0, "")))
    print(f"零重合绑定共 {len(rows_best)} 条，按来源：")
    for (sub, src), count in origin.most_common(10):
        print(f"  {count:>4}  [{sub}] {src}")
    print()
    print("随机 25 条：现在绑的 对 同科目里最像的节点")
    for material, name, (score, alt) in random.Random(7).sample(
            rows_best, min(25, len(rows_best))):
        print(f"  [{material['subject']}] {material['title'][:40]}")
        print(f"       现绑 {name[:30]:<32} 最像 {alt[:28]} ({score:.2f})")

    print()
    print("按来源分层抽样：同一来源的绑定是不是同一批操作产生的（错误率是否集中）")
    buckets: dict[tuple[str, str], list] = {}
    for row in rows_best:
        key = (row[0]["subject"], row[0]["sourceId"])
        buckets.setdefault(key, []).append(row)
    for (sub, src), rows in sorted(buckets.items(), key=lambda kv: -len(kv[1]))[:5]:
        sample = random.Random(3).sample(rows, min(8, len(rows)))
        high = sum(1 for _m, _n, (s, _a) in sample if s >= 0.24)
        print(f"\n  [{sub}] {src}（{len(rows)} 条，抽样 8 条；"
              f"其中 {high} 条存在明显更优目标）")
        for material, name, (score, alt) in sample:
            print(f"     {material['title'][:38]}")
            print(f"          现绑 {name[:26]:<28} 最像 {alt[:24]} ({score:.2f})")

    print()
    print("=" * 72)
    print("五、高置信重绑候选（现绑零重合、同科目内存在 ≥0.30 的节点）")
    print("=" * 72)
    def contains(title: str, name: str) -> bool:
        """候选节点名与材料标题**整名互相包含**（归一化后）。

        这是安全重绑的判据，比"最像"可靠：`人船模型` → `“人船模型”` 通过，
        而 `自由水的作用` → `酶的作用` 这种只共享"的作用"两字的巧合会被挡掉。
        """
        left, right = normalize(title), normalize(name)
        return len(right) >= 2 and (right in left or (len(left) >= 2 and left in right))

    strong = sorted((r for r in rows_best
                     if r[2][1] and contains(r[0]["title"], r[2][1])),
                    key=lambda r: -r[2][0])
    print(f"共 {len(strong)} 条")
    for material, name, (score, alt) in strong:
        print(f"  {score:.2f} [{material['subject']}] {material['slug']}")
        print(f"       {material['title'][:44]}")
        print(f"       现绑 {name[:30]:<32} → {alt[:32]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
