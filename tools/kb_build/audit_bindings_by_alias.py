# -*- coding: utf-8 -*-
"""用别名证据反查错绑：材料标题恰好等于**另一个节点的名字**，却绑在当前节点上。

## 它消灭的失败

别名重建（`rebuild_aliases`）要求"别名必须是本节点绑定材料的标题"，于是撞车时会丢弃候选。
逐条看被丢弃的候选，发现其中一类不是脏数据，而是**错绑**：
- 标题《波的图像》绑在节点「电磁感应中的图像问题」上；
- 标题《二次免疫：记忆细胞快速增殖分化…》绑在节点「细胞的分化与全能性」上；
- 标题《卤代烃中卤素原子的检验方法》绑在节点「卤素离子的检验方法」上。

`title == 另一个节点的 name` 是强证据（材料讲的就是那个知识点），但**不是结论**：
上位概念节点收着下位材料是合法的（《自由落体运动》绑在「匀变速直线运动」下没问题）。
所以本模块只**出嫌疑表**、不自动改绑——改绑走 `rebind_materials`，由人/模型逐条裁定。

## 用法

    PYTHONPATH=tools python -m kb_build.audit_bindings_by_alias            # 打印统计与样例
    PYTHONPATH=tools python -m kb_build.audit_bindings_by_alias --write    # 落表 binding_suspects.csv
"""

from __future__ import annotations

import argparse
import csv
from collections import Counter
from pathlib import Path

from kb_build import pack_io

TABLES = Path(__file__).resolve().parent / "tables"
OUT = "binding_suspects.csv"


def suspects() -> list[dict]:
    pack = pack_io.load_json(pack_io.pack_path())
    name_to_nodes: dict[tuple[str, str], list[str]] = {}
    node_name: dict[tuple[str, str], str] = {}
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                key = (subject["subject"], point["name"])
                name_to_nodes.setdefault(key, []).append(point["slug"])
                node_name[(subject["subject"], point["slug"])] = point["name"]
    rows: list[dict] = []
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            subject = m["subject"]
            title = (m.get("title") or "").strip()
            if not title:
                continue
            for b in m.get("bindings") or []:
                slug = b["knowledgeNodeId"].split(":")[-1]
                own = node_name.get((subject, slug))
                if own is None or title == own:
                    continue
                targets = name_to_nodes.get((subject, title))
                if not targets:
                    continue
                if slug in targets:
                    continue          # 标题就是自己的名字，不是嫌疑
                rows.append({
                    "subject": subject,
                    "material": m["slug"],
                    "material_title": title,
                    "current_node_slug": slug,
                    "current_node_name": own,
                    "title_matches_node": targets[0],
                    "note": "材料标题与另一节点同名；需裁定是否改绑（上位概念收下位材料属合法）",
                })
    return rows


def reviewed_materials() -> set[str]:
    """已裁定过的材料（KEEP/NONE/REBIND 都会落进 reviewed 表）。

    为什么需要豁免：判据是启发式（材料标题 == 别的节点名），"已判定为误报"的行如果不豁免，
    每轮都会再报一遍——审计就成了复读机，真嫌疑会被噪声淹没。裁定结果在
    `binding_suspects_reviewed.csv`（由 `tools/kb_coverage/apply_rebind_verdicts.py` 落表）。
    """
    path = TABLES / "binding_suspects_reviewed.csv"
    if not path.exists():
        return set()
    import csv
    with path.open(encoding="utf-8", newline="") as fh:
        return {row["material"].strip() for row in csv.DictReader(fh)
                if (row.get("material") or "").strip()}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)
    rows = suspects()
    reviewed = reviewed_materials()
    skipped = [r for r in rows if r["material"] in reviewed]
    rows = [r for r in rows if r["material"] not in reviewed]
    print(f"错绑嫌疑 {len(rows)} 条（已裁定豁免 {len(skipped)} 条）")
    print("按科目：", Counter(r["subject"] for r in rows).most_common())
    for r in rows[:12]:
        print(f"  [{r['subject'][:3]}] 《{r['material_title'][:26]}》"
              f" 现挂「{r['current_node_name'][:18]}」→ 疑似应为「{r['title_matches_node'][:18]}」")
    if args.write:
        TABLES.mkdir(parents=True, exist_ok=True)
        with (TABLES / OUT).open("w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()) if rows else
                               ["subject", "material", "material_title", "current_node_slug",
                                "current_node_name", "title_matches_node", "note"])
            w.writeheader()
            w.writerows(rows)
        print(f"→ 已写 {TABLES / OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
