# -*- coding: utf-8 -*-
"""成品包的"契约镜像"检查 + 表↔包一致性（五张权威表）。

## 为什么要单独写

`:core:data` 的 Kotlin 用例是最权威的契约门，但它依赖 :core:domain / :core:database 能编译；
工作树由两条会话共享，另一条会话改那两个模块时这扇门会临时关上（2026-09-19 实测：
先卡在 `LearningProjector.knowledgeNodeSuccessors`，再卡在 `RoomStudyDatabase` 抽象成员，
最后卡在 Room/KSP 处理 `ProblemOrganizationDao`）。本脚本覆盖
`BundledKnowledgePackResources` 对**主题/节点**字段的全部已知约束 + 表↔包一致性，
让"包本身是否合法"在 Python 侧随时可查——它不是替代 Kotlin 门，而是补上它不可用时的下限。

## 检查项

- slug 唯一、parentSlug 悬空、字段 trim、控制字符（ISO Cc 排除 \\n\\r\\t + 双向控制符）
- 节点 kind 必须落在 App 的 KnowledgeNodeKind 枚举内
- 别名：非空、首项是主名、无重复、长度 ≤256
- 同主题内节点重名
- 五张权威表↔包一致性（见 `check_table_consistency` 的逐表语义）

检查对象是 `pack_io.work_dir()`（默认 staging）里的包：门校验的是**晋升候选**，
成品目录在晋升前不被任何检查改写。

用法： PYTHONPATH=tools python -m kb_build.check_pack_contract
"""

from __future__ import annotations

import copy
import csv
import sys
import unicodedata
from collections import Counter
from pathlib import Path

from kb_build import gen_chapter_table, pack_io, tables, update_manifest
from kb_build import gate as _gate

VALID_KINDS = {"TOPIC", "CONCEPT", "PROCEDURE", "REASONING", "REPRESENTATION",
               "EXPERIMENT", "EXPRESSION"}


def bad_chars(text: str) -> list[str]:
    out = []
    for ch in text:
        if unicodedata.category(ch) == "Cc" and ch not in "\n\r\t":
            out.append(hex(ord(ch)))
        if ch in "\u061c\u200e\u200f" or 0x202A <= ord(ch) <= 0x202E or 0x2066 <= ord(ch) <= 0x2069:
            out.append(hex(ord(ch)))
    return out


# ---------------------------------------------------------------------------
# 契约镜像：codec 会拒的字段形状
# ---------------------------------------------------------------------------


def check_contract(pack: dict) -> tuple[list[str], Counter]:
    """字段形状检查（codec 镜像）。返回 (问题, 统计)。"""
    problems: list[str] = []
    stats = Counter()
    for subject in pack["subjects"]:
        all_slugs = {t["slug"] for t in subject["topics"]}
        seen_topics: set[str] = set()
        for topic in subject["topics"]:
            stats["topics"] += 1
            slug = topic["slug"]
            if slug in seen_topics:
                problems.append(f"主题 slug 重复：{slug}")
            seen_topics.add(slug)
            for field in ("slug", "name", "sourceLocator"):
                value = topic.get(field) or ""
                if bad_chars(value):
                    problems.append(f"[{subject['subject']}] 主题 {slug} 的 {field} 含控制字符")
                if value != value.strip():
                    problems.append(f"[{subject['subject']}] 主题 {slug} 的 {field} 未 trim")
            parent = topic.get("parentSlug")
            # 顺序无关：父只要在同科内存在即可（既有包的"综合"链子先父后也存在）
            if parent and parent not in all_slugs:
                problems.append(f"[{subject['subject']}] 主题 {slug} 的父悬空：{parent}")
            stats["points"] += len(topic.get("knowledgePoints") or [])
            seen_names: dict[str, str] = {}
            for point in topic.get("knowledgePoints") or []:
                if point.get("kind") not in VALID_KINDS:
                    problems.append(f"[{subject['subject']}] {point['slug']} kind 越界：{point.get('kind')}")
                aliases = point.get("aliases") or []
                if len(set(aliases)) != len(aliases):
                    problems.append(f"[{subject['subject']}] {point['slug']} 别名重复")
                if not aliases or aliases[0] != point["name"]:
                    problems.append(f"[{subject['subject']}] {point['slug']} 别名首项不是主名")
                for field in ("slug", "name", "boundary", "sourceLocator"):
                    value = point.get(field) or ""
                    if bad_chars(value):
                        problems.append(f"[{subject['subject']}] {point['slug']} 的 {field} 含控制字符")
                    if value != value.strip():
                        problems.append(f"[{subject['subject']}] {point['slug']} 的 {field} 未 trim")
                if len(point.get("name") or "") > 256:
                    problems.append(f"[{subject['subject']}] {point['slug']} 名称超长")
                for alias in aliases:
                    if len(alias) > 256:
                        problems.append(f"[{subject['subject']}] {point['slug']} 别名超长：{alias[:20]}")
                if point["name"] in seen_names:
                    problems.append(f"[{subject['subject']}] 同主题内重名：{point['name']}")
                seen_names[point["name"]] = point["slug"]
    return problems, stats


# ---------------------------------------------------------------------------
# 表↔包一致性：五张权威表
# ---------------------------------------------------------------------------

def _read_rows(tables_dir: Path, name: str) -> list[dict[str, str]]:
    path = tables_dir / name
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def _alias_map_check(pack: dict, tables_dir: Path) -> list[str]:
    """alias_map 必须与包**逐节点双向**一致：

    表里多出的行 = 别名没写进包（丢了召回）；包里多出的别名 = 表没记账
    （下一次重建别名表时会把现行别名当噪声清掉）。
    历史上就是因为一个读 staging、一个读成品，差点用过期快照覆盖成品别名。
    """
    from_table: dict[tuple[str, str], set[str]] = {}
    for row in _read_rows(tables_dir, "alias_map.csv"):
        from_table.setdefault((row["subject"].strip(), row["slug"].strip()), set()).add(row["alias"].strip())
    problems: list[str] = []
    if not from_table:
        problems.append("别名表为空")
        return problems
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                extra = set(point.get("aliases") or []) - {point["name"]}
                key = (subject["subject"], point["slug"])
                if from_table.get(key, set()) != extra:
                    problems.append(
                        f"别名表与包不一致：{subject['subject']}/{point['slug']} "
                        f"表 {sorted(from_table.get(key, set()))[:3]} vs 包 {sorted(extra)[:3]}")
    return problems[:10]


def _unit_baseline(point: dict, chapter_table: dict) -> tuple[str, str] | None:
    """节点在"没有 chapter_map 覆盖行"时应有的（册, 章）；None = 无基线要求。

    与 gate 的 chapter_locator_mismatch 同一口径：
    单元不在章表 / 决定是 keep_per_node → 无要求；split → 必须有覆盖行（基线 None）；
    其余（fix/keep）→ 章表声明的（册, 章）。
    """
    unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
    entry = chapter_table.get(unit)
    if entry is None or entry["decision"] == "keep_per_node":
        return None
    if entry["decision"] == "split":
        return None  # split 单元一律要求覆盖行，见 _chapter_map_check
    return (entry["book"].strip(), entry["chapter"].strip())


def _chapter_map_check(pack: dict, tables_dir: Path) -> list[str]:
    """chapter_map（节点级章节覆盖）三条语义：

    - 行可在包中定位：(subject, slug) 必须是包里的点；
    - 动作已生效：点的定位串（册, 章）== 行的 (volume, chapter)；
    - 偏离有账：定位串偏离单元基线的点（含 split 单元的全部点）必须有行——
      行被删而定位串还在偏离，就是"表领先/落后于包"的分叉。
    """
    rows = _read_rows(tables_dir, "chapter_map.csv")
    points = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    chapter_table = tables.load_chapter_by_source()
    problems: list[str] = []
    covered: set[tuple[str, str]] = set()
    for row in rows:
        key = (row["subject"].strip(), row["slug"].strip())
        point = points.get(key)
        if point is None:
            problems.append(f"chapter_map 行在包中不存在：{key[0]}/{key[1]}")
            continue
        covered.add(key)
        loc = _gate._place_of(point.get("boundary") or "")
        want = (row["volume"].strip(), row["chapter"].strip())
        if loc != want:
            problems.append(
                f"chapter_map 未生效：{key[0]}/{key[1]} 定位于 {loc}，行声明 {want}")
    for key, point in points.items():
        baseline = _unit_baseline(point, chapter_table)
        unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
        is_split = chapter_table.get(unit, {}).get("decision") == "split"
        loc = _gate._place_of(point.get("boundary") or "")
        needs_row = is_split or (baseline is not None and loc != baseline)
        if needs_row and key not in covered:
            problems.append(
                f"章节偏离无账：{key[0]}/{key[1]} 定位于 {loc}"
                + ("（split 单元必须有覆盖行）" if is_split else f"（基线 {baseline}）"))
    return problems[:20]


def _chapter_by_node_check(pack: dict, tables_dir: Path) -> list[str]:
    """chapter_by_node（split 单元逐节点定章的提案账）两条语义：

    - 行可在包中定位：(subject, slug) 必须是包里的点（节点删/并后行要清，
      否则这张表就变成过期快照——它曾因 stale 行差点反向污染别名表）；
    - 该列的都在：split 单元的每个点都必须有行（gen_chapter_table 的约定：
      "split 单元逐节点细分，列在 chapter_by_node.csv 里"）。
    """
    rows = _read_rows(tables_dir, "chapter_by_node.csv")
    points = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    chapter_table = tables.load_chapter_by_source()
    problems: list[str] = []
    covered: set[tuple[str, str]] = set()
    for row in rows:
        key = (row["subject"].strip(), row["slug"].strip())
        if key not in points:
            problems.append(f"chapter_by_node 行在包中不存在：{key[0]}/{key[1]}")
            continue
        covered.add(key)
    for key, point in points.items():
        unit = gen_chapter_table.source_unit(point.get("sourceLocator", ""))
        if chapter_table.get(unit, {}).get("decision") == "split" and key not in covered:
            problems.append(f"split 单元缺逐节点行：{key[0]}/{key[1]}（{unit[:40]}）")
    return problems[:20]


def _prereq_map_check(pack: dict, tables_dir: Path) -> list[str]:
    """prereq_map（前置权威表）三条语义，双向等价：

    - 行可在包中定位：point 与 prerequisite 都必须是**同科**包内的点；
    - 动作已生效：边的两端都在，且点的 prerequisiteSlugs 含该前置；
    - 包里有账：包中每条前置边都必须在表里（表里没有 = 未声明，
      与 gate 的 undeclared_prereq 同口径）。
    """
    rows = _read_rows(tables_dir, "prereq_map.csv")
    points = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    problems: list[str] = []
    table_edges: set[tuple[str, str, str]] = set()
    for row in rows:
        subj, slug, preq = row["subject"].strip(), row["slug"].strip(), row["prerequisite"].strip()
        table_edges.add((subj, slug, preq))
        point = points.get((subj, slug))
        if point is None:
            problems.append(f"prereq_map 点在包中不存在：{subj}/{slug}")
            continue
        if (subj, preq) not in points:
            problems.append(f"prereq_map 前置在包中不存在：{subj}/{slug} ← {preq}")
            continue
        if preq not in (point.get("prerequisiteSlugs") or []):
            problems.append(f"prereq_map 未生效：{subj}/{slug} 的包内前置不含 {preq}")
    for (subj, slug), point in points.items():
        for preq in point.get("prerequisiteSlugs") or []:
            if (subj, slug, preq) not in table_edges:
                problems.append(f"包内前置未声明：{subj}/{slug} ← {preq}")
    return problems[:20]


def _rename_rows(tables_dir: Path) -> dict[tuple[str, str], str]:
    """point_rename：同一 slug 多行时**最后一行**生效（执行器按文件序覆盖，
    与 rename_points.load_renames 的 dict 语义一致）。"""
    out: dict[tuple[str, str], str] = {}
    for row in _read_rows(tables_dir, "point_rename.csv"):
        out[(row["subject"].strip(), row["slug"].strip())] = row["new_name"].strip()
    return out


def _delete_rows(tables_dir: Path) -> dict[tuple[str, str], str]:
    out: dict[tuple[str, str], str] = {}
    for row in _read_rows(tables_dir, "point_delete.csv"):
        out[(row["subject"].strip(), row["slug"].strip())] = row.get("reason", "")
    return out


def _merge_rows(tables_dir: Path) -> set[tuple[str, str, str]]:
    out: set[tuple[str, str, str]] = set()
    for row in _read_rows(tables_dir, "point_merge.csv"):
        out.add((row["subject"].strip(), row["survivor_slug"].strip(), row["merged_slug"].strip()))
    return out


def _point_table_checks(pack: dict, manifest: dict | None, tables_dir: Path) -> dict[str, list[str]]:
    """point_* 四张动作表（rename/delete/merge/relocation）。

    语义（表 = 动作账，包 = 事实源）：
    - point_rename：行可在包中定位（slug 还在）；生效 = 点名 == 该 slug 最后一行的 new_name。
    - point_delete：生效 = slug 已不在包内；台账有账 = 台账里每条 atomic DELETE
      都能找到对应的表行（表行被删而台账还在 = 动作失去溯源）。
    - point_merge：survivor 在包内、merged 已不在（生效）；台账里每条 atomic MERGE
      的 (survivor, merged) 都能找到对应的表行。
    - point_relocation：行可在包中定位；生效 = 点当前所在 topic 的 slug == to_topic_slug。

    台账方向按**晋升形态**对账（`update_manifest.canonicalize`，与 promote 同一变换）：
    schema 1 的链式条目晋升时会被压平到终局，压平后指向 DELETE 底的条目降级为
    DELETE——对账必须用压平后的指向，否则历史形态会被误判成"表无行"。
    """
    points = {(s, p["slug"]): p for s, _t, p in pack_io.iter_points(pack)}
    point_topic = {
        (s, p["slug"]): t["slug"] for s, t, p in pack_io.iter_points(pack)
    }
    out: dict[str, list[str]] = {
        "point_rename": [], "point_delete": [], "point_merge": [], "point_relocation": [],
    }

    for key, want in _rename_rows(tables_dir).items():
        point = points.get(key)
        if point is None:
            out["point_rename"].append(f"point_rename 点在包中不存在：{key[0]}/{key[1]}")
        elif point["name"] != want:
            out["point_rename"].append(
                f"point_rename 未生效：{key[0]}/{key[1]} 现为「{point['name'][:30]}」，表定稿「{want[:30]}」")

    for key in _delete_rows(tables_dir):
        if key in points:
            out["point_delete"].append(f"point_delete 未生效：{key[0]}/{key[1]} 仍在包内")

    for key in _merge_rows(tables_dir):
        subj, survivor, merged = key
        if (subj, survivor) not in points:
            out["point_merge"].append(f"point_merge 幸存者不在包内：{subj}/{survivor} ← {merged}")
        elif (subj, merged) in points:
            out["point_merge"].append(f"point_merge 未生效：{subj}/{merged} 仍在包内")

    for row in _read_rows(tables_dir, "point_relocation.csv"):
        key = (row["subject"].strip(), row["slug"].strip())
        target = row["to_topic_slug"].strip()
        point = points.get(key)
        if point is None:
            out["point_relocation"].append(f"point_relocation 点在包中不存在：{key[0]}/{key[1]}")
        elif point_topic[key] != target:
            out["point_relocation"].append(
                f"point_relocation 未生效：{key[0]}/{key[1]} 在 {point_topic[key][:40]}，表目标 {target[:40]}")

    if manifest is not None:
        # 台账按**晋升形态**对账（与 promote 同一 canonicalize）：schema 1 的链式
        # 条目在晋升时会被压平，对账若用未压平形态会把历史形态误判成"表无行"。
        try:
            doc = update_manifest.canonicalize(copy.deepcopy(manifest))
        except ValueError as exc:
            out["point_merge"].append(f"台账规范化失败：{exc}")
            out["point_delete"].append(f"台账规范化失败：{exc}")
            return _truncate(out)
        for entry in doc.get("retired", []):
            parts = entry["nodeId"].split(":")
            if len(parts) < 5 or parts[-2] != "atomic":
                continue  # topic 退役走 topic 家族账（topic_actions），不在本组
            subj, slug = parts[-3].upper(), parts[-1]
            if entry["kind"] == "DELETE" and (subj, slug) not in _delete_rows(tables_dir):
                out["point_delete"].append(f"台账有账、表无行：DELETE {subj}/{slug}")
            if entry["kind"] == "MERGE":
                tparts = (entry.get("supersededBy") or "").split(":")
                if len(tparts) < 5 or (subj, tparts[-1], slug) not in _merge_rows(tables_dir):
                    out["point_merge"].append(
                        f"台账有账、表无行：MERGE {subj}/{slug} ← {tparts[-1] if len(tparts) >= 5 else '?'}")
    return _truncate(out)


def _truncate(out: dict[str, list[str]]) -> dict[str, list[str]]:
    for key in out:
        out[key] = out[key][:20]
    return out


def check_table_consistency(pack: dict, manifest: dict | None,
                            tables_dir: Path | None = None) -> dict[str, list[str]]:
    """五张权威表↔包一致性。返回 {表名: 问题列表}；全空 = 一致。

    表名：alias_map / chapter_map / chapter_by_node / prereq_map /
    point_rename / point_delete / point_merge / point_relocation
    （point_* 四表记为一组，语义见 `_point_table_checks`）。
    """
    tables_dir = tables_dir or tables.TABLES_DIR
    result: dict[str, list[str]] = {
        "alias_map": _alias_map_check(pack, tables_dir),
        "chapter_map": _chapter_map_check(pack, tables_dir),
        "chapter_by_node": _chapter_by_node_check(pack, tables_dir),
        "prereq_map": _prereq_map_check(pack, tables_dir),
    }
    result.update(_point_table_checks(pack, manifest, tables_dir))
    return result


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------


def evaluate(root: Path | None = None) -> tuple[dict[str, list[str]], Counter]:
    """跑全部检查（契约镜像 + 五表一致性）。root 缺省 = 当前工作目录（staging）。"""
    if root is not None:
        pack_io.use_directory(root)
    pack = pack_io.load_json(pack_io.pack_path())
    manifest = None
    mp = pack_io.work_dir() / update_manifest.MANIFEST_NAME
    if mp.exists():
        manifest = pack_io.load_json(mp)
    problems, stats = check_contract(pack)
    consistency = check_table_consistency(pack, manifest)
    consistency["contract"] = problems
    return consistency, stats


def main() -> int:
    consistency, stats = evaluate()
    total = sum(len(v) for v in consistency.values())
    print(f"主题 {stats['topics']}、知识点 {stats['points']}；问题 {total}")
    for name in ("contract", "alias_map", "chapter_map", "chapter_by_node", "prereq_map",
                 "point_rename", "point_delete", "point_merge", "point_relocation"):
        for p in consistency[name]:
            print(f"  ! [{name}]", p)
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
