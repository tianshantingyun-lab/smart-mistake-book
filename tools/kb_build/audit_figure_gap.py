# -*- coding: utf-8 -*-
"""审计：找出"知识由图承载、但文字材料没抽到"的已有知识点。

为什么要做：既有材料是从教辅 PDF/docx 的**文字**抽的。教辅里的图（函数图象、装置图、
电路图、遗传系谱、流程框图）本身承载知识；若抽取时只读文字，这部分知识就没进库。
新素材（五三精讲册）已确定"图承载的知识必须转成文字"，那么存量的同类缺口也要一并补。

判据（可测量，不靠感觉）：
  1. 节点名或绑定材料标题里含"图依赖"词（图象/曲线/装置/电路/系谱/示意图/流程图…）
  2. 该节点绑定的材料里，是否出现"把图读成文字"时才有的具体信息词
     （斜率/交点/面积/由图可知/纵轴/横轴/曲线/连接顺序/世代/串联…）
  3. 一条都没有 → 判为缺口：该知识点的图承载信息很可能没被抽出来

用法：PYTHONPATH=tools python -m kb_build.audit_figure_gap [--write]
"""

from __future__ import annotations

import argparse
import csv
import re
from collections import defaultdict

from kb_build import pack_io, tables

# 名称或标题里出现这些词，说明这个知识点大概率要靠图才讲得清
_FIGURE_DEPENDENT = re.compile(
    r"图象|图像|曲线|装置|电路|系谱|示意图|流程图|结构图|框图|轨迹|图线|三视图|直观图|截面")
# 材料里出现这些词，说明图承载的信息已经被读成文字（"图已转文"的证据）
_FIGURE_VERBALIZED = re.compile(
    r"斜率|交点|面积|极值|单调|由图|据图|图示|纵轴|横轴|横坐标|纵坐标|曲线|图线|"
    r"顶点|开口|渐近线|对称轴|峰值|拐点|世代|邻接|串联|并联|支路|干路|流向|连接顺序")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", default=None,
                        help="读取根（默认成品目录）；对 build/kb-assess 跑可看修复后的真实缺口")
    args = parser.parse_args(argv)

    if args.root:
        from pathlib import Path
        pack_io.use_directory(Path(args.root).resolve())

    pack = pack_io.load_json(pack_io.pack_path())
    materials_by_node: dict[str, list[dict]] = defaultdict(list)
    for _path, material in pack_io.load_materials():
        for binding in material.get("bindings") or []:
            materials_by_node[binding["knowledgeNodeId"]].append(material)

    pack_id = pack["packId"]
    rows: list[dict[str, str]] = []
    for subject, _topic, point in pack_io.iter_points(pack):
        node_id = f"kb:{pack_id}:{subject.lower()}:atomic:{point['slug']}"
        bound = materials_by_node.get(node_id, [])
        titles = " ".join(m.get("title", "") for m in bound)
        if not (_FIGURE_DEPENDENT.search(point["name"]) or _FIGURE_DEPENDENT.search(titles)):
            continue
        blob = "\n".join(
            " ".join(m.get(k) or "" for k in
                     ("title", "summaryMarkdown", "applicabilityMarkdown",
                      "contentMarkdown", "boundaryMarkdown"))
            for m in bound
        )
        verbalized = _FIGURE_VERBALIZED.search(blob)
        rows.append({
            "subject": subject,
            "slug": point["slug"],
            "name": point["name"],
            "bound_materials": str(len(bound)),
            "figure_verbalized": "yes" if verbalized else "no",
            "hit": (verbalized.group(0) if verbalized else ""),
            "sample_content": blob[:80].replace("\n", " "),
        })

    gaps = [r for r in rows if r["figure_verbalized"] == "no"]
    bare = [r for r in gaps if r["bound_materials"] == "0"]
    print(f"图依赖知识点 {len(rows)} 个")
    print(f"  图信息已转成文字（有斜率/交点/由图…等证据）：{len(rows) - len(gaps)}")
    print(f"  判为缺口（材料里找不到任何读图证据）：{len(gaps)}，其中零绑定（更严重）：{len(bare)}")
    print()
    print("缺口样例（按绑定材料数升序，前 25）：")
    for row in sorted(gaps, key=lambda r: int(r["bound_materials"]))[:25]:
        print(f"   [{row['subject'][:4]}] 绑{row['bound_materials']:>2}条  {row['name'][:44]}")

    if args.write:
        path = tables.TABLES_DIR / "figure_gap_audit.csv"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", encoding="utf-8", newline="") as fh:
            writer = csv.DictWriter(fh, fieldnames=[
                "subject", "slug", "name", "bound_materials",
                "figure_verbalized", "hit", "sample_content"])
            writer.writeheader()
            writer.writerows(rows)
        print()
        print(f"已写出 {path}（含已转文与缺口两类，供人工复核判据）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
