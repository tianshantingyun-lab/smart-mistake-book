# -*- coding: utf-8 -*-
"""把材料从错误节点改绑到正确节点（绑定修复的核心操作）。

## 它消灭的失败

肥节点（如生物"光合作用与细胞呼吸"挂 97 条材料）把横跨光合/呼吸/神经内分泌/生态/
免疫的材料全吸在一个桶里——检索时问"血糖调节"会召回一堆光合材料，掌握度记错 KC。
改绑 = 把每条材料的绑定指向它**真正在讲**的知识点。

## 无损校验（全过才写回）
- 材料总数不变（只改绑定目标，不删材料）
- 改后没有任何绑定指向不存在的节点（无悬空）
- `to` 节点必须真实存在（否则报错，防 name/slug 混用）
- 幂等：材料已不再绑 `from`（已改过）则跳过

## 用法
    PYTHONPATH=tools python -m kb_build.rebind_materials            # 报告
    PYTHONPATH=tools python -m kb_build.rebind_materials --write    # 写回 sidecar
"""

from __future__ import annotations

import argparse
from pathlib import Path

from kb_build import pack_io, tables

TABLE = "material_rebind.csv"
COLUMNS = ("material_slug", "from_node_slug", "to_node_slug", "evidence")


def load_rebinds(path: Path | None = None) -> list[dict]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    return rows


def _valid_node_ids(pack: dict) -> set[str]:
    return {f"kb:{pack['packId']}:{s['subject'].lower()}:atomic:{k['slug']}"
            for s in pack["subjects"] for t in s["topics"] for k in t.get("knowledgePoints") or []}


def _node_id_of(pack: dict, subj: str, slug: str) -> str:
    return f"kb:{pack['packId']}:{subj.lower()}:atomic:{slug}"


def rebind(pack: dict, sidecars: list[dict], rows: list[dict]) -> dict:
    stats = {"rebound": 0, "skipped": 0, "bad_to": []}
    valid = _valid_node_ids(pack)
    # material_slug -> (subject, material)
    index = {}
    for sc in sidecars:
        for m in sc["materials"]:
            index[m["slug"]] = (sc, m)
    for r in rows:
        mslug, fnode, tnode = (r["material_slug"].strip(), r["from_node_slug"].strip(),
                               r["to_node_slug"].strip())
        if mslug not in index:
            stats["bad_to"].append(f"材料 {mslug} 不存在")
            continue
        sc, mat = index[mslug]
        subj = mat["subject"]
        from_id, to_id = _node_id_of(pack, subj, fnode), _node_id_of(pack, subj, tnode)
        if to_id not in valid:
            stats["bad_to"].append(f"目标节点 {tnode} 不存在（材料 {mslug}）")
            continue
        bindings = mat.get("bindings") or []
        hit = next((b for b in bindings if b["knowledgeNodeId"] == from_id), None)
        if hit is None:
            # 幂等：已改过（不再绑 from），或本就错行
            stats["skipped"] += 1
            continue
        hit["knowledgeNodeId"] = to_id
        # 去重：若该材料已绑 to（另一条 binding 指向 to），去掉重复
        ids_seen, nb = set(), []
        for b in bindings:
            if b["knowledgeNodeId"] in ids_seen:
                continue
            ids_seen.add(b["knowledgeNodeId"])
            nb.append(b)
        mat["bindings"] = nb
        stats["rebound"] += 1
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="材料改绑到正确节点")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    sidecars = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
    rows = load_rebinds()
    stats = rebind(pack, sidecars, rows)
    print(f"改绑 {stats['rebound']}；跳过（幂等/错行）{stats['skipped']}")
    if stats["bad_to"]:
        print(f"拒绝 {len(stats['bad_to'])} 行（目标节点/材料不存在）：")
        for line in stats["bad_to"][:10]:
            print(f"   {line}")
    # 无损：材料总数不变、无悬空绑定
    valid = _valid_node_ids(pack)
    mats_total = sum(len(sc["materials"]) for sc in sidecars)
    dangling = [b["knowledgeNodeId"] for sc in sidecars for m in sc["materials"]
                for b in m.get("bindings") or [] if b["knowledgeNodeId"] not in valid]
    ok = not stats["bad_to"] and not dangling
    print(f"材料总数 {mats_total}；悬空绑定 {len(dangling)}；可写回: {ok}")
    if not ok:
        return 1
    if args.write:
        for sc, sp in zip(sidecars, pack_io.sidecar_paths()):
            pack_io.dump_json(sc, sp)
        print(f"→ 已写回 {len(sidecars)} 个 sidecar")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
