# -*- coding: utf-8 -*-
"""按权威表删除知识点。

## 它消灭的失败

章层里残留一批**题干/残缺句**命名的"知识点"（`下列说法正确的是`、`按要求填空`、
`mol HCHO～4 mol Cu(OH)2～2 mol Cu2O`）——它们不是知识点，是抽取事故。留着会：
- 污染检索（名称进 `knowledge_search_feature`，把无关题面召回过来）
- 污染掌握度（学生无法对一个"下列说法正确的是"记掌握度）
- 让"覆盖/粒度"统计失真

## 安全边界（本脚本只删"可安全删"的）

删除一个点会 orphan 它的材料、断它的前置引用。所以本表**只列**满足条件的点：
1. 无材料绑定（orphan 风险＝0）
2. 经语义判定确为纯题干/残渣（不是"名字写成题干的真知识点"——那些该 rename/重绑，不在本表）
3. 若它被别的点当前置引用——**那本身就是错误绑定**（题干不该是任何知识点的前置），
   删除时清掉这些引用是**修正**而非破坏；脚本会显式报告清了几处。

`relocate_chapter_points` 处理"归位"，`delete_points` 处理"删除"，`rename`（另表）
处理"坏名知识点"，材料重绑处理"题目节点上的真材料"——四者不混。

## 无损

只删表里列的 slug；其余点数守恒、slug 集合除被删外不变。删点同时清理指向被删点的
悬挂 prerequisite（双保险，正常应为 0）。

## 用法
    PYTHONPATH=tools python -m kb_build.delete_points            # 报告
    PYTHONPATH=tools python -m kb_build.delete_points --write    # 写回
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from kb_build import pack_io, tables, update_manifest

TABLE = "point_delete.csv"
COLUMNS = ("subject", "slug", "reason")


def load_deletes(path: Path | None = None) -> dict[tuple[str, str], tuple[str, bool]]:
    """(subject, slug) -> (reason, drop_materials)。

    `drop_materials` 列（可选，值 yes）＝连同"只绑定到该点的材料"一起删除。
    没有该列而点又带着材料时，写回会被拒（见 main 的守卫）——防"删点留悬空材料"。
    """
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return {}
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    return {(r["subject"].strip(), r["slug"].strip()):
            (r["reason"].strip(), (r.get("drop_materials") or "").strip().lower() == "yes")
            for r in rows}


def drop_materials_for(keys: set[tuple[str, str]]) -> tuple[int, int]:
    """删除"只绑定到被删点"的材料；多绑材料里剥掉指向被删点的绑定。

    返回 (删除的材料数, 被剥绑定的材料数)。这是删点的**影响面闭合**：不这样做会留下
    指向不存在节点的悬空绑定（Kotlin 装载时该材料被静默丢弃，unbound_materials 虚高）。
    """
    removed = stripped = 0
    for sp in pack_io.sidecar_paths():
        doc = pack_io.load_json(sp)
        keep = []
        changed = False
        for m in doc["materials"]:
            binds = m.get("bindings") or []
            remain = [b for b in binds
                      if (m.get("subject", ""), b["knowledgeNodeId"].split(":")[-1]) not in keys]
            if not remain and binds:
                removed += 1
                changed = True
                continue
            if len(remain) != len(binds):
                m["bindings"] = remain
                stripped += 1
                changed = True
            keep.append(m)
        if changed:
            doc["materials"] = keep
            pack_io.dump_json(doc, sp)
    return removed, stripped


def _point_count(pack: dict) -> int:
    return sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])


def delete(pack: dict, to_delete: dict[tuple[str, str], tuple[str, bool]]) -> tuple[int, int]:
    """返回 (删除点数, 清理的悬挂前置引用数)。prerequisiteSlugs 保持 list（JSON 不可序列化 set）。"""
    removed = dangling = 0
    for subject in pack["subjects"]:
        subj = subject["subject"]
        topics = subject["topics"]
        valid = {p["slug"] for t in topics for p in t.get("knowledgePoints") or []}
        for t in topics:
            kept = []
            for p in t.get("knowledgePoints") or []:
                if (subj, p["slug"]) in to_delete:
                    removed += 1
                else:
                    kept.append(p)
            t["knowledgePoints"] = kept
        valid = {p["slug"] for t in topics for p in t.get("knowledgePoints") or []}
        for t in topics:
            for p in t.get("knowledgePoints") or []:
                kept_prereq = [q for q in (p.get("prerequisiteSlugs") or []) if q in valid]
                dangling += len(p.get("prerequisiteSlugs") or []) - len(kept_prereq)
                p["prerequisiteSlugs"] = kept_prereq
    return removed, dangling


def _purge_table_refs(pack: dict) -> dict[str, int]:
    """删除点会留下指向它的悬空引用（chapter_map / alias_map 等外部表）。

    闭合影响面：删点必须同时清掉这些引用，否则 `load_chapter_map` 的
    validate_chapter_map_slugs 会因"slug 不在包里"直接 raise，把整条门禁拖垮。
    返回各表清除的行数。
    """
    known = {(s["subject"], k["slug"]) for s in pack["subjects"] for t in s["topics"]
             for k in t.get("knowledgePoints") or []}
    purged = {}
    for name in ("chapter_map.csv", "alias_map.csv", "chapter_point_relocation.csv"):
        path = tables.TABLES_DIR / name
        if not path.exists():
            continue
        rows = list(csv.DictReader(open(path, encoding="utf-8")))
        if not rows:
            continue
        cols = list(rows[0].keys())
        kept = [r for r in rows if (r.get("subject", ""), r.get("slug", "")) in known]
        removed = len(rows) - len(kept)
        if removed:
            with path.open("w", encoding="utf-8", newline="") as fh:
                w = csv.DictWriter(fh, fieldnames=cols, lineterminator="\n")
                w.writeheader(); w.writerows(kept)
        purged[name] = removed
    return purged


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按表删除纯题干/残缺知识点")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    deletes = load_deletes()

    # 实际被删的 slug 用**前后集合求差**拿到，而不是改 `delete()` 的返回值——它的二元组
    # 被现有用例解包（`removed, dangling = dp.delete(...)`），改签名会白白打断那些断言。
    slugs_before = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    before = _point_count(pack)
    removed, dangling = delete(pack, deletes)
    after = _point_count(pack)
    slugs_after = {(s, p["slug"]) for s, _t, p in pack_io.iter_points(pack)}
    removed_keys = sorted(slugs_before - slugs_after)
    already = len(deletes) - removed
    print(f"删除 {removed} 个；已在包外（幂等跳过）{already} 个；清理错误前置引用 {dangling} 个")
    print(f"点数 {before} → {after}")
    if dangling:
        # 被删点若是题干残渣，别的点把它当前置本身就是错误绑定——清掉是修正，不是破坏。
        # 但这是**有副作用**的清理，必须显式报告，让调用者知道动了前置图。
        print(f"注意：清理了 {dangling} 处指向被删点的前置引用（被删点本不该当前置，属错误绑定修正）")

    # 守卫：被删点若还带材料，而表里没标 drop_materials=yes → 拒绝写回（防悬空材料）
    bound: dict[str, int] = {}
    for sp in pack_io.sidecar_paths():
        for m in pack_io.load_json(sp)["materials"]:
            for b in m.get("bindings") or []:
                if (m.get("subject", ""), b["knowledgeNodeId"].split(":")[-1]) in deletes:
                    k = (m["subject"], b["knowledgeNodeId"].split(":")[-1])
                    bound[k] = bound.get(k, 0) + 1
    need = {k: n for k, n in bound.items() if not deletes[k][1]}
    if need:
        for (subj, slug), n in list(need.items())[:5]:
            print(f"  拒绝：{subj}/{slug[:30]} 仍带 {n} 条材料，表未标 drop_materials=yes")
        print("（要么先重绑材料，要么在 point_delete.csv 该行加 drop_materials=yes）")
        return 1

    if args.write:
        pack_io.dump_json(pack, path)
        drop_keys = {k for k, (_r, drop) in deletes.items() if drop}
        if drop_keys:
            rm, st = drop_materials_for(drop_keys)
            print(f"→ 材料随删：删除 {rm} 条、剥离绑定 {st} 条")
        purged = _purge_table_refs(pack)
        purged_txt = "、".join(f"{k} {v}" for k, v in purged.items() if v) or "无"
        print(f"→ 已写回 {path}；清除外部表悬空引用 {purged_txt}")

        # 删除是"无取代目标"的退役：运行时据此把节点置 RETIRED 而**不**物删，
        # 学生数据（错题绑定/掌握度/复习队列）因此不会悬空。
        entries = [
            {
                "nodeId": update_manifest.node_id(pack["packId"], subj, slug),
                "supersededBy": None,
                "kind": update_manifest.KIND_DELETE,
                "reason": deletes[(subj, slug)][0],
            }
            for subj, slug in removed_keys
            if (subj, slug) in deletes
        ]
        doc = update_manifest.load_or_empty(pack["packId"])
        added = update_manifest.record(doc, entries)
        update_manifest.write(doc)
        print(f"→ 取代台账 +{added} 条（共 {len(doc['retired'])} 条退役）；内容戳由晋升时刷新")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
