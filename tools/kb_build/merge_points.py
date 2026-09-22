# -*- coding: utf-8 -*-
"""合并同章同名重复知识点（`X` 与 `X-x1` 这类去重后缀副本）。

## 它消灭的失败

同一知识点被重复导入会产生 `X` 与 `X-x1`（`_unique_slug` 去重后缀）。两个节点、
同一章、同一名字，材料/前置各挂一半——检索时互相竞争、掌握度被拆到两个 id 上、
学生端看到两条同名条目。合并成一个节点，材料与前置归并。

## 合并 = 5 处联动（AGENTS.md 12.1 闭合影响面）

删掉 merged 节点会让它在 5 个地方留下悬空引用，必须一处处清：
1. **材料绑定**（sidecar JSON）：`bindings[].knowledgeNodeId == merged` → 改指 survivor
2. **本节点前置**：survivor.prerequisiteSlugs ∪ merged.prerequisiteSlugs（去重、去自身/merged）
3. **他节点前置**：全库任何点的 prerequisiteSlugs 里的 merged → survivor
4. **别名**：survivor.aliases ∪ merged.aliases
5. **外部表**：chapter_map / alias_map / chapter_point_relocation / point_delete /
   point_rename 里指向 merged 的行 → 删（或改指 survivor）

## 幸存者选择

优先无 `-xN` 后缀的（原始节点）；否则材料多的；否则字典序小的。

## 无损校验

合并前后：**知识点总数减 = 合并的对数**；**材料总数不变**（只改绑定目标，不删材料）；
合并后**没有任何绑定还指向已删节点**。三条都不满足则拒绝写回。

## 用法
    PYTHONPATH=tools python -m kb_build.merge_points            # 报告
    PYTHONPATH=tools python -m kb_build.merge_points --write    # 写回
"""

from __future__ import annotations

import argparse
import copy
import csv
import re
from pathlib import Path

from kb_build import pack_io, tables, update_manifest

TABLE = "point_merge.csv"
COLUMNS = ("subject", "survivor_slug", "merged_slug", "reason")
_XN = re.compile(r"-x\d+$")


def load_merges(path: Path | None = None) -> list[dict]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    return rows


def pick_survivor(cands: list[tuple[str, int]]) -> str:
    """cands = [(slug, material_count)]。优先无 -xN 后缀 → 材料多 → 字典序。"""
    def key(item):
        slug, mc = item
        no_suffix = 0 if _XN.search(slug) else 1
        return (-no_suffix, -mc, slug)
    return min(cands, key=key)[0]


def _node_id(pack_id: str, subj: str, slug: str) -> str:
    return f"kb:{pack_id}:{subj.lower()}:atomic:{slug}"


def _find_point(pack: dict, subj: str, slug: str):
    for s in pack["subjects"]:
        if s["subject"] != subj:
            continue
        for topic in s["topics"]:
            for p in topic.get("knowledgePoints") or []:
                if p["slug"] == slug:
                    return topic, p
    return None, None


def _has_prereq_cycle(pack: dict) -> bool:
    """任意科的前置图是否有环。DFS 三色法。"""
    for subject in pack["subjects"]:
        node = {k["slug"]: set(k.get("prerequisiteSlugs") or [])
                for t in subject["topics"] for k in t.get("knowledgePoints") or []}
        WHITE, GRAY, BLACK = 0, 1, 2
        color = {n: WHITE for n in node}

        def dfs(n: str) -> bool:
            color[n] = GRAY
            for q in node[n]:
                if q not in node:
                    continue
                if color[q] == GRAY:
                    return True
                if color[q] == WHITE and dfs(q):
                    return True
            color[n] = BLACK
            return False

        for n in node:
            if color[n] == WHITE and dfs(n):
                return True
    return False


def _apply_pack_side(pack: dict, subj: str, surv: str, merged: str,
                     drop_prereq: str = "") -> bool:
    """在给定 pack（或其副本）上应用单条合并的包侧改动。返回是否可行。

    `drop_prereq`：前置并集里丢弃的指定 slug。消灭的失败：占位节点带语义垃圾
    前置（如 `细胞器 ← 细胞呼吸的影响因素及应用`），并集会把垃圾边带给存活节点。
    """
    _stopic, spoint = _find_point(pack, subj, surv)
    _mtopic, mpoint = _find_point(pack, subj, merged)
    if spoint is None or mpoint is None:
        return False
    union = list(dict.fromkeys(list(spoint.get("prerequisiteSlugs") or []) +
                               list(mpoint.get("prerequisiteSlugs") or [])))
    if drop_prereq:
        union = [q for q in union if q != drop_prereq]
    spoint["prerequisiteSlugs"] = [q for q in union if q not in (surv, merged)]
    spoint["aliases"] = list(dict.fromkeys(list(spoint.get("aliases") or []) +
                                           list(mpoint.get("aliases") or [])))
    for s in pack["subjects"]:
        for topic in s["topics"]:
            for p in topic.get("knowledgePoints") or []:
                pr = p.get("prerequisiteSlugs") or []
                # 重指后必须去重：同时含 surv 与 merged 的点会得到重复项，
                # 违反 Kotlin 契约 "prerequisiteSlugs must not contain duplicates"
                newpr = list(dict.fromkeys(surv if q == merged else q for q in pr))
                if newpr != pr:
                    p["prerequisiteSlugs"] = newpr
    _mtopic["knowledgePoints"].remove(mpoint)
    return True


def merge(pack: dict, sidecars: list[dict], rows: list[dict]) -> dict:
    """逐条合并，每条先在同一份 pack 副本上试跑并做环检测；产生环则跳过并报告。"""
    pack_id = pack["packId"]
    stats = {"merged": 0, "skipped": 0, "skipped_cycle": 0, "materials_repointed": 0,
             "bindings_deduped": 0, "retired": []}
    points_before = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    mats_before = sum(len(sc["materials"]) for sc in sidecars)

    for row in rows:
        subj, surv, merged = (row["subject"].strip(), row["survivor_slug"].strip(),
                              row["merged_slug"].strip())
        drop = (row.get("drop_prereq") or "").strip()
        if _find_point(pack, subj, surv)[1] is None or _find_point(pack, subj, merged)[1] is None:
            stats["skipped"] += 1            # 幂等：merged 已删，或 survivor 不在
            continue
        trial = copy.deepcopy(pack)
        _apply_pack_side(trial, subj, surv, merged, drop)
        if _has_prereq_cycle(trial):
            stats["skipped_cycle"] += 1      # 该合并会造前置环，跳过
            continue
        _apply_pack_side(pack, subj, surv, merged, drop)
        surv_id, merged_id = _node_id(pack_id, subj, surv), _node_id(pack_id, subj, merged)
        for sc in sidecars:
            for m in sc["materials"]:
                bindings = m.get("bindings") or []
                if not any(b["knowledgeNodeId"] == merged_id for b in bindings):
                    continue
                seen: set[str] = set()
                nb = []
                for b in bindings:
                    b["knowledgeNodeId"] = surv_id if b["knowledgeNodeId"] == merged_id else b["knowledgeNodeId"]
                    if b["knowledgeNodeId"] in seen:
                        continue            # 材料原已绑 survivor + 又从 merged 改指 → 去重
                    seen.add(b["knowledgeNodeId"])
                    nb.append(b)
                if len(nb) != len(bindings):
                    stats["bindings_deduped"] += len(bindings) - len(nb)
                    m["bindings"] = nb
                stats["materials_repointed"] += 1
        stats["merged"] += 1
        # 台账条目必须**在执行的这一次**就收集：merged 一旦从包里消失，下一次跑会因幂等
        # 跳过，映射就永久丢了。`reason` 留给运行时的"为什么不见了"回答。
        stats["retired"].append({
            "nodeId": merged_id,
            "supersededBy": surv_id,
            "kind": update_manifest.KIND_MERGE,
            "reason": (row.get("reason") or "").strip(),
        })

    points_after = sum(len(t.get("knowledgePoints") or []) for s in pack["subjects"] for t in s["topics"])
    mats_after = sum(len(sc["materials"]) for sc in sidecars)
    stats["points_before"] = points_before
    stats["points_after"] = points_after
    stats["materials_before"] = mats_before
    stats["materials_after"] = mats_after
    stats["ok"] = (points_before - points_after == stats["merged"]) and (mats_before == mats_after) \
        and not _has_prereq_cycle(pack)
    valid_ids = {_node_id(pack_id, s["subject"], k["slug"]) for s in pack["subjects"]
                 for t in s["topics"] for k in t.get("knowledgePoints") or []}
    dangling = [b["knowledgeNodeId"] for sc in sidecars for m in sc["materials"]
                for b in m.get("bindings") or [] if b["knowledgeNodeId"] not in valid_ids]
    stats["dangling_bindings"] = len(dangling)
    stats["ok"] = stats["ok"] and not dangling
    return stats


def _purge_table_refs(pack: dict) -> dict[str, int]:
    """清外部表里指向已删 merged slug 的行。"""
    # 只清"当前归属表"（chapter_map/alias_map/chapter_point_relocation）——它们被
    # validate/load 校验，残留已删 slug 会 raise。动作日志（point_delete/point_rename/
    # point_merge）不清：它们是历史账本，清掉会丢"删过什么"的溯源，且 delete 的幂等靠
    # "slug 在不在包里"判定、不靠日志。
    known = {(s["subject"], k["slug"]) for s in pack["subjects"] for t in s["topics"] for k in t.get("knowledgePoints") or []}
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
    parser = argparse.ArgumentParser(description="合并同章同名重复知识点")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    sidecars = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
    rows = load_merges()

    stats = merge(pack, sidecars, rows)
    print(f"合并 {stats['merged']}；跳过（幂等/未找到）{stats['skipped']}；"
          f"因会产生前置环而跳过 {stats['skipped_cycle']}")
    print(f"  材料改指 {stats['materials_repointed']}；绑定去重 {stats['bindings_deduped']}")
    print(f"  点数 {stats['points_before']} → {stats['points_after']}；"
          f"材料 {stats['materials_before']} → {stats['materials_after']}；悬空绑定 {stats['dangling_bindings']}")
    if not stats["ok"]:
        print("无损校验失败（点数减量≠合并数 / 材料数变 / 有环 / 有悬空绑定），拒绝写回")
        return 1
    print("无损校验通过：点数减量=合并数、材料总数不变、无环、无悬空绑定")

    if args.write:
        pack_io.dump_json(pack, path)
        for sc, sp in zip(sidecars, pack_io.sidecar_paths()):
            pack_io.dump_json(sc, sp)
        purged = _purge_table_refs(pack)
        pt = "、".join(f"{k} {v}" for k, v in purged.items() if v) or "无"
        print(f"→ 已写回 staging 包 + {len(sidecars)} 个 sidecar；清除外部表悬空引用 {pt}")

        # 台账条目落 staging 台账（record 时已压平到终局）；**不刷内容戳**——
        # 戳只在晋升路径（promote）刷新，门全绿那一刻才算数。
        doc = update_manifest.load_or_empty(pack["packId"])
        added = update_manifest.record(doc, stats["retired"])
        update_manifest.write(doc)
        print(f"→ 取代台账 +{added} 条（共 {len(doc['retired'])} 条退役）；内容戳由晋升时刷新")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
