# -*- coding: utf-8 -*-
"""生成 `tables/material_bindings.csv` —— 材料绑定的修正表。

两类修正，判据都不靠"哪个最像"，而靠可核对的字面事实：

1. **补绑**：完全没绑定的材料（导入时被 `BundledKnowledgePackResources` 整条剔除）。
   候选来自 `match_unbound.py` 的整名包含匹配，再由人工逐条复核取舍——`REJECTED`
   记的就是否掉的那 13 条及理由。

2. **重绑**：有绑定、但材料标题与目标节点名**一个 2 字组合都不共享**的。实测这批
   里既有真错配（材料叫「人船模型」、库里明明有同名节点，却绑给了「动量守恒定律」；
   连绑 4 条），也有"绑对了但表述不同"（「排尿不仅受到脊髓的控制」→
   「神经系统的分级调节」，两者字面确实不沾边）。**光看相似度分不出这两种**，
   所以只在同科目里找到**安全包含**的节点时才改，找不到的一律不动。

   安全包含分方向：节点名被材料标题包含（材料比节点更具体）可以直接用；
   反过来（材料比节点更泛）要求两者长度接近，否则「实验材料」会命中
   「耐药菌实验材料的灭菌处理」这类泛节点。

  用法： PYTHONPATH=tools python tools/kb_build/build_bindings_table.py
"""

from __future__ import annotations

import argparse
import collections
import csv
import re
import unicodedata
from pathlib import Path

from kb_build import pack_io

SRC = pack_io.REPO / "build" / "kb-staging" / "unbound_match.csv"
OUT = pack_io.REPO / "tools" / "kb_build" / "tables" / "material_bindings.csv"

_LATEX = re.compile(r"\$[^$]*\$|\\(?:[a-zA-Z]+|.)")
_KEEP = re.compile(r"[\w一-鿿]+")

# 材料标题 -> 否掉的理由（逐条读过；标题是稳定键，slug 只在源文件里）
REJECTED: dict[str, str] = {
    "结构简式": "目标节点是残句",
    "在做有关离子共存题目时": "材料标题是残句",
    "在无催化剂的情况下": "材料标题是残句",
    "平衡常数表达式中": "材料标题是残句",
    "C原子的电子式为": "材料标题是残句",
    "C、N原子的电子式为": "材料标题是残句",
    "“趁热过滤”后": "材料标题是残句",
    "抗氧化剂": "与目标「氧化剂」语义相反",
    "玻璃棒": "与目标「玻璃」不是同一概念（仪器 ≠ 材料）",
    "玻璃纤维": "与目标「玻璃」不是同一概念（制品 ≠ 材料）",
    "安安法测电阻": "讲的是测电阻的方法，目标「电阻」是概念，不等价",
    "伏伏法测电阻": "讲的是测电阻的方法，目标「电阻」是概念，不等价",
    "电桥法测电阻": "讲的是测电阻的方法，目标「电阻」是概念，不等价",
}


def normalize(text: str) -> str:
    return "".join(_KEEP.findall(_LATEX.sub("", unicodedata.normalize("NFKC", text)))).lower()


def shared_pair(left: str, right: str) -> bool:
    """两个串是否共享任何 2 字组合（长度不足 2 的按相同字符算）。"""
    def grams(s: str) -> set[str]:
        return {s[i:i + 2] for i in range(len(s) - 1)} if len(s) >= 2 else {s}
    return bool(grams(left) & grams(right))


def safe_target(title: str, candidates: list[str]) -> str | None:
    """材料标题能安全改绑到哪个候选节点；没有就返回 None。

    命中分三档，先看档再看长短——**不能只取最长的**：`动能` 既能命中同名节点
    `动能`，也能命中 `动能定理`，取最长会把材料送到更具体的那个去（实测踩过）。
    档内取最长的（最具体），档间按 相等 > 节点名被标题包含 > 标题被节点名包含。

    方向要分开：节点名被标题包含（材料更具体）可以直接用；标题被节点名包含
    （材料更泛）时要求长度接近，挡住「实验材料」→「耐药菌实验材料的灭菌处理」。
    """
    text = normalize(title)
    exact: list[str] = []
    node_in_title: list[str] = []
    title_in_node: list[str] = []
    for name in candidates:
        node = normalize(name)
        if not node:
            continue
        if node == text:
            exact.append(name)
        elif node in text:
            node_in_title.append(name)
        elif text in node and len(text) * 2 >= len(node):
            title_in_node.append(name)
    for bucket in (exact, node_in_title, title_in_node):
        if bucket:
            return max(bucket, key=lambda x: len(normalize(x)))
    return None


def load_pack() -> tuple[dict[tuple[str, str], str], dict[str, list[str]],
                         dict[tuple[str, str], str], list[dict]]:
    """返回 (目标节点索引, 按科目分的节点名, 现状节点索引, 材料)。

    三个来源必须分清，混用会静默漏掉整批（两处都踩过）：
    - **现状索引**：材料的 `bindings` 指向**成品包**的节点 id，所以要知道"现在绑的是谁"，
      只能查成品包——查暂存包的话，那些已被删/并的节点根本查不到名，整条就被跳过了。
    - **目标索引**：`build.py` 会删/并节点，重绑目标必须在**生成后**仍存在，查暂存包。
    - **材料**：取成品包 sidecar 的**原始**绑定；读暂存包的会让脚本读到自己上一次的输出
      （重绑过的绑定当然与标题有重合，第二轮一条都筛不出来）。
    """
    staged = pack_io.load_json(pack_io.REPO / "build" / "kb-staging" / pack_io.PACK_NAME)
    targets: dict[tuple[str, str], str] = {}
    for subject in staged["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                targets[(subject["subject"], point["slug"])] = point["name"]
    by_subject: dict[str, list[str]] = collections.defaultdict(list)
    for (sub, _slug), name in targets.items():
        by_subject[sub].append(name)

    base = pack_io.load_json(pack_io.pack_path())
    current: dict[tuple[str, str], str] = {}
    for subject in base["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                current[(subject["subject"], point["slug"])] = point["name"]

    materials: list[dict] = []
    for path in pack_io.sidecar_paths():
        materials.extend(pack_io.load_json(path)["materials"])
    return targets, by_subject, current, materials


def bigrams(text: str) -> set[str]:
    return {text[i:i + 2] for i in range(len(text) - 1)} if len(text) >= 2 else {text}


def jaccard(left: set[str], right: set[str]) -> float:
    return len(left & right) / len(left | right) if left and right else 0.0


def export_review(path: Path) -> int:
    """导出**待人工裁决**的绑定：现绑节点名与材料标题零字面重合的那些。

    自动判据只能处理"整名包含"这一种；剩下的正确目标用的是另一种表述
    （「排尿不仅受到脊髓的控制」→「神经系统的分级调节」确实是对的），
    字面够不着，只能逐条读内容判。所以摘要和**同科目里最像的 3 个节点**一起导出：
    判断要看材料讲什么，光看标题会把"对但表述不同"误判成错。

    已在本表里的材料要排除——本脚本读的是成品包的**原始**绑定，重绑过的材料
    原始绑定和标题照旧零重合，不排除就会重复导出。
    """
    targets, by_subject, current, materials = load_pack()
    already = {row["material_slug"] for row in
               (csv.DictReader(open(OUT, encoding="utf-8")) if OUT.exists() else [])}
    rows = []
    for material in materials:
        bindings = material.get("bindings") or []
        if not bindings or material["slug"] in already:
            continue
        subject = material["subject"]
        cur_name = current.get((subject, bindings[0]["knowledgeNodeId"].split(":")[-1]))
        if not cur_name:
            continue
        title = material["title"]
        if shared_pair(normalize(title), normalize(cur_name)):
            continue
        grams = bigrams(normalize(title))
        near = sorted(((jaccard(grams, bigrams(normalize(n))), n)
                       for n in by_subject[subject] if n != cur_name),
                      reverse=True)[:3]
        rows.append({
            "material_slug": material["slug"], "subject": subject,
            "title": title,
            "summary": " ".join((material.get("summaryMarkdown") or "").split())[:70],
            "current_node_name": cur_name,
            "nearest_nodes": " ; ".join(f"{n}({s:.2f})" for s, n in near if s > 0.05),
        })
    if not rows:
        print("没有待审条目")
        return 0
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"导出待审 {len(rows)} 条 → {path}")
    return len(rows)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--export-review", action="store_true",
                        help="只导出待人工裁决的绑定清单，不改表")
    args = parser.parse_args(argv)
    if args.export_review:
        export_review(pack_io.REPO / "build" / "kb-staging" / "binding_review.csv")
        return 0
    targets, by_subject, current, materials = load_pack()
    slug_of = {(sub, name): slug for (sub, slug), name in targets.items()}

    rows: list[dict] = []
    rebound = 0
    for material in materials:
        subject = material["subject"]
        bindings = material.get("bindings") or []
        title, slug = material["title"], material["slug"]
        if bindings:
            current_slug = bindings[0]["knowledgeNodeId"].split(":")[-1]
            current_name = current.get((subject, current_slug))
            if not current_name:
                continue
            if shared_pair(normalize(title), normalize(current_name)):
                continue                      # 与现目标有字面证据，不动
            target = safe_target(title, by_subject[subject])
            if target is None or target == current_name:
                continue
            rows.append({"material_slug": slug, "point_slug": slug_of[(subject, target)],
                         "subject": subject,
                         "reason": f"原绑「{current_name}」与标题零字面重合；标题与"
                                   f"「{target}」互为整名包含，改指"})
            rebound += 1

    with SRC.open(encoding="utf-8", newline="") as fh:
        matched = [r for r in csv.DictReader(fh)
                   if r["target_slug"] and r["title"] not in REJECTED]
    done = {row["material_slug"] for row in rows}
    attached = 0
    for row in matched:
        # `unbound_match.csv` 是上一轮的产物，里面有些材料本轮已经走了重绑分支
        # （原绑指向被判为抽取事故的节点），同一条材料不能既重绑又补绑
        if row["slug"] in done:
            continue
        rows.append({"material_slug": row["slug"], "point_slug": row["target_slug"],
                     "subject": row["subject"],
                     "reason": "原无绑定；标题与节点名归一化后互为整名包含且唯一，已复核"})
        attached += 1

    with OUT.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.DictWriter(
            fh, fieldnames=["material_slug", "point_slug", "subject", "reason"],
            extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)
    print(f"material_bindings.csv：重绑 {rebound} 条、补绑 {attached} 条"
          f"（否掉 {len(REJECTED)} 条），共 {len(rows)} 行")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
