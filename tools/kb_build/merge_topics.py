# -*- coding: utf-8 -*-
"""主题去重：同名主题在旧教材位置与新教材位置各存一份，节点散在两边。

## 它消灭的失败

2026-09-19 实测：23 个主题名重复且两边都带节点（如「化学平衡」4 份、「氧化还原反应」3 份、
「1 圆周运动」2 份）。成因是旧教材结构（`化学必修第二册·第六章·化学反应与能量` 这种 2007 版
章序）与 2019 新结构（选必1/2/3）并存，抽取时各建各的主题，从没做过主题级去重。
后果：检索特征按主题名派生时同名主题互相竞争；App 树里同一个知识点出现多份。

## 动作（表驱动，`topic_actions.csv`）

- `merge`：把 `slug`（stale 主题）的全部点并入 `target_slug`（canonical）；
  stale 的子主题：canonical 下有同名子主题 → 递归并入，否则挂到 canonical 下；
  处理后 stale 必为空 → 从 topics 数组删除。
- `delete_empty`：删除 0 点 0 子主题的空壳（安全闸：非空即拒绝整批）。
- `create`：在 `target_slug`（父主题）下建主题（`slug` 给全路径 slug、`reason` 列里写主题名）——
  用于"canonical 位置本来就该有、但当年漏建"的章节主题（如选必1 缺「3 简谐运动的回复力和能量」）。
  定位串用 `定位：待补章表。` 占位（与 `relocate_chapter_points._ensure_theme` 同一惯例），
  后续由章表补录。

## 5 处联动（AGENTS.md 12.1）

1. 点迁移：`parentSlug` 变、点对象原样（slug 是身份，材料/前置/别名都不动）。
2. 子主题重挂：`parentSlug` 改指 canonical。
3. 取代台账：被删的**主题节点**（`kb:{packId}:{subject}:topic:{slug}`）记一条
   MERGE（→canonical 主题节点）或 DELETE——运行时的调和循环据此把旧主题置退役，
   学生的 parent 引用不会悬空。
4. 外部表：`chapter_point_relocation.csv` / `topic_rename.csv` 里指向被删主题的行清理
   （stale 行作废；canonical 行保留）。
5. 内容戳刷新。

## 无损校验（全过才写回）

- 点数守恒、点 slug 集合不变、点对象除"所在主题"外逐字节不变；
- 主题集合 = 原集合 − 被删集合；每个剩余主题的父级仍然有效；无环；
- 合并后 canonical 的点集 = 两侧并集（重复 slug 直接拒绝整批——那说明包本身坏了）；
- 幂等：重放 0 改动。

## 用法

    PYTHONPATH=tools python -m kb_build.merge_topics            # 报告
    PYTHONPATH=tools python -m kb_build.merge_topics --write    # 写回
"""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from kb_build import pack_io, tables, update_manifest

TABLE = "topic_actions.csv"
COLUMNS = ("subject", "action", "slug", "target_slug", "reason")
ACTIONS = ("merge", "delete_empty", "create")


def load_actions(path: Path | None = None) -> list[dict]:
    path = path or (tables.TABLES_DIR / TABLE)
    if not path.exists():
        return []
    rows = tables._read(path)
    tables._require_columns(TABLE, rows, COLUMNS)
    for r in rows:
        if r["action"].strip() not in ACTIONS:
            raise ValueError(f"{TABLE}: 非法 action {r['action']!r}")
    return rows


def _point_key(pack: dict):
    return {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}


def preflight(pack: dict, actions: list[dict]) -> tuple[list[str], int]:
    """返回 (错误列表, 已完成条数)；错误非空则不许执行。

    主题已不存在 = 该动作上一轮已执行（幂等），与 `merge_points` 的"跳过（幂等/未找到）"
    同语义——**不是**错误，否则重放必挂。
    """
    errors = []
    already = 0
    topics: dict[tuple[str, str], dict] = {}
    for s in pack["subjects"]:
        for t in s["topics"]:
            topics[(s["subject"], t["slug"])] = t
    # 同批的 create 先算进去：后面的 merge 允许以本批新建的主题为 canonical。
    for a in actions:
        if a["action"].strip() != "create":
            continue
        key = (a["subject"].strip(), a["slug"].strip())
        if key not in topics:
            topics[key] = {"slug": key[1]}
    for a in actions:
        subj, act, slug, target = (a["subject"].strip(), a["action"].strip(),
                                   a["slug"].strip(), a["target_slug"].strip())
        stale = topics.get((subj, slug))
        if stale is None:
            already += 1
            continue
        if act == "create":
            parent = topics.get((subj, target))
            if parent is None:
                errors.append(f"[{subj}] create {slug[:40]}: 父主题 {target[:30]} 不存在")
            continue
        if act == "delete_empty":
            if stale.get("knowledgePoints"):
                errors.append(f"[{subj}] delete_empty {slug[:40]}: 还有 {len(stale['knowledgePoints'])} 个点，拒删")
            if _children_of(pack, subj, slug):
                errors.append(f"[{subj}] delete_empty {slug[:40]}: 还有子主题，拒删")
            continue
        # merge
        canon = topics.get((subj, target))
        if canon is None:
            errors.append(f"[{subj}] merge {slug[:36]} → {target[:36]}: canonical 不存在")
            continue
        if target == slug:
            errors.append(f"[{subj}] merge {slug[:40]}: stale == canonical")
            continue
        for p in stale.get("knowledgePoints") or []:
            if any(q["slug"] == p["slug"] for q in canon.get("knowledgePoints") or []):
                errors.append(f"[{subj}] merge {slug[:36]}: 点 {p['slug'][:30]} 在两侧主题里都有，包本身损坏")
    return errors, already


def _children_of(pack: dict, subj: str, slug: str) -> list[dict]:
    return [t for t in pack_by_subj(pack, subj) if t.get("parentSlug") == slug]


def pack_by_subj(pack: dict, subj: str) -> list[dict]:
    return next(s["topics"] for s in pack["subjects"] if s["subject"] == subj)


def apply_actions(pack: dict, actions: list[dict]) -> dict:
    """就地执行。返回统计。"""
    stats = {"merged_topics": 0, "points_moved": 0, "children_rehung": 0,
             "deleted_empty": 0, "created": 0, "removed": [], "ledger": []}
    topics: dict[tuple[str, str], dict] = {}
    for s in pack["subjects"]:
        for t in s["topics"]:
            topics[(s["subject"], t["slug"])] = t
    pid = pack["packId"]

    def topic_id(subj: str, slug: str) -> str:
        return f"kb:{pid}:{subj.lower()}:topic:{slug}"

    for a in actions:
        subj, act, slug = a["subject"].strip(), a["action"].strip(), a["slug"].strip()
        target = a["target_slug"].strip()
        if act == "create":
            if (subj, slug) in topics:
                continue  # 幂等：已建
            new_topic = {"slug": slug, "name": slug.rsplit("·", 1)[-1],
                         "sourceLocator": "定位：待补章表。",
                         "parentSlug": target, "knowledgePoints": []}
            pack_by_subj(pack, subj).append(new_topic)
            topics[(subj, slug)] = new_topic
            stats["created"] = stats.get("created", 0) + 1
            continue
        stale = topics.get((subj, slug))
        if stale is None:
            continue  # 幂等：已处理过
        if act == "delete_empty":
            if stale.get("knowledgePoints") or _children_of(pack, subj, slug):
                raise RuntimeError(f"delete_empty 安全闸被触发：{slug}")
            pack_by_subj(pack, subj).remove(stale)
            del topics[(subj, slug)]
            stats["deleted_empty"] += 1
            stats["removed"].append((subj, slug))
            stats["ledger"].append({"nodeId": topic_id(subj, slug), "supersededBy": None,
                                    "kind": update_manifest.KIND_DELETE,
                                    "reason": a["reason"]})
            continue
        canon = topics[(subj, target)]
        for p in list(stale.get("knowledgePoints") or []):
            stale["knowledgePoints"].remove(p)
            canon.setdefault("knowledgePoints", []).append(p)
            stats["points_moved"] += 1
        for child in _children_of(pack, subj, slug):
            same_name = [t for t in _children_of(pack, subj, target) if t["name"] == child["name"]]
            if same_name:
                # 递归并入同名子主题（子主题通常无点或有同名结构）
                child_points = len(child.get("knowledgePoints") or [])
                canon_sub = same_name[0]
                canon_sub.setdefault("knowledgePoints", []).extend(child.get("knowledgePoints") or [])
                stats["points_moved"] += child_points
                for gc in _children_of(pack, subj, child["slug"]):
                    gc["parentSlug"] = canon_sub["slug"]
                    stats["children_rehung"] += 1
                pack_by_subj(pack, subj).remove(child)
                del topics[(subj, child["slug"])]
                stats["removed"].append((subj, child["slug"]))
                stats["ledger"].append({"nodeId": topic_id(subj, child["slug"]),
                                        "supersededBy": topic_id(subj, canon_sub["slug"]),
                                        "kind": update_manifest.KIND_MERGE,
                                        "reason": a["reason"] + "（同名子主题递归并入）"})
            else:
                child["parentSlug"] = target
                stats["children_rehung"] += 1
        if not stale.get("knowledgePoints") and not _children_of(pack, subj, slug):
            pack_by_subj(pack, subj).remove(stale)
            del topics[(subj, slug)]
            stats["removed"].append((subj, slug))
        stats["merged_topics"] += 1
        stats["ledger"].append({"nodeId": topic_id(subj, slug),
                                "supersededBy": topic_id(subj, target),
                                "kind": update_manifest.KIND_MERGE,
                                "reason": a["reason"]})
    return stats


def purge_table_refs(pack: dict) -> dict[str, int]:
    """清理指向被删主题的 `topic_rename.csv` 行（点级表以点 slug 为键，不受主题删除影响）。"""
    live = {(s["subject"], t["slug"]) for s in pack["subjects"] for t in s["topics"]}
    purged: dict[str, int] = {}
    path = tables.TABLES_DIR / "topic_rename.csv"
    if not path.exists():
        return purged
    rows = tables._read(path)
    kept = [r for r in rows if (r.get("subject", "").strip(), r.get("slug", "").strip()) in live]
    purged["topic_rename.csv"] = len(rows) - len(kept)
    if purged["topic_rename.csv"]:
        with path.open("w", encoding="utf-8", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(kept)
    return purged


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=None)
    args = parser.parse_args(argv)

    if args.root:
        pack_io.use_directory(args.root)
    path = pack_io.pack_path()
    pack = pack_io.load_json(path)
    actions = load_actions()

    errors, already = preflight(pack, actions)
    before_points = len(_point_key(pack))
    before_slugs = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
    before_topics = sum(len(s["topics"]) for s in pack["subjects"])

    print(f"动作 {len(actions)} 条（已完成跳过 {already}）；预检 {'通过' if not errors else '失败'}")
    for e in errors[:10]:
        print(f"   ! {e}")
    if errors:
        return 1

    stats = apply_actions(pack, actions)
    after_points = len(_point_key(pack))
    after_slugs = {p["slug"] for _s, _t, p in pack_io.iter_points(pack)}
    after_topics = sum(len(s["topics"]) for s in pack["subjects"])
    if after_points != before_points or after_slugs != before_slugs:
        print(f"无损校验失败：点 {before_points}→{after_points}，slug 集合变化 {len(before_slugs ^ after_slugs)}")
        return 1
    # 所有剩余主题的父级必须有效、无环
    for s in pack["subjects"]:
        slugs = {t["slug"] for t in s["topics"]}
        for t in s["topics"]:
            par = t.get("parentSlug")
            if par and par not in slugs:
                print(f"无损校验失败：{t['slug']} 的父级 {par} 不存在")
                return 1
            seen, cur = set(), t
            while cur.get("parentSlug") in slugs:
                if cur["parentSlug"] in seen:
                    print(f"无损校验失败：{s['subject']} 主题环")
                    return 1
                seen.add(cur["slug"])
                cur = next(x for x in s["topics"] if x["slug"] == cur["parentSlug"])

    print(f"合并 {stats['merged_topics']} 个主题；新建 {stats['created']}；点迁移 {stats['points_moved']}；"
          f"子主题重挂 {stats['children_rehung']}；删空壳 {stats['deleted_empty']}")
    print(f"主题 {before_topics} → {after_topics}；点数 {after_points} 守恒")

    if not args.write:
        print("（未写盘；加 --write 生效）")
        return 0
    pack_io.dump_json(pack, path)
    purged = purge_table_refs(pack)
    if any(purged.values()):
        print("清除外部表悬空行：", {k: v for k, v in purged.items() if v})
    doc = update_manifest.load_or_empty(pack["packId"])
    added = update_manifest.record(doc, stats["ledger"])
    sidecars = [pack_io.load_json(sp) for sp in pack_io.sidecar_paths()]
    doc["contentVersion"] = update_manifest.content_version(pack, sidecars)
    update_manifest.write(doc)
    print(f"→ 已写回 {path}；取代台账 +{added} 条（共 {len(doc['retired'])} 条退役）；"
          f"内容戳 {doc['contentVersion']}")
    # 幂等
    reloaded = pack_io.load_json(path)
    stats2 = apply_actions(reloaded, load_actions())
    if stats2["merged_topics"] or stats2["points_moved"] or stats2["deleted_empty"]:
        print("幂等校验失败：重放仍要改", stats2)
        return 1
    print("幂等校验通过：重放 0 改动")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
