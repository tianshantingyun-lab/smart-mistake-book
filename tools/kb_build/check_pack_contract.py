# -*- coding: utf-8 -*-
"""成品包的"契约镜像"检查：把 App 侧 codec 会拒的字段形状在这里先扫一遍。

## 为什么要单独写

`:core:data` 的 Kotlin 用例是最权威的契约门，但它依赖 :core:domain / :core:database 能编译；
工作树由两条会话共享，另一条会话改那两个模块时这扇门会临时关上（2026-09-19 实测：
先卡在 `LearningProjector.knowledgeNodeSuccessors`，再卡在 `RoomStudyDatabase` 抽象成员，
最后卡在 Room/KSP 处理 `ProblemOrganizationDao`）。本脚本覆盖
`BundledKnowledgePackResources` 对**主题/节点**字段的全部已知约束 + 别名表↔包一致性，
让"包本身是否合法"在 Python 侧随时可查——它不是替代 Kotlin 门，而是补上它不可用时的下限。

## 检查项

- slug 唯一、parentSlug 悬空、字段 trim、控制字符（ISO Cc 排除 \\n\\r\\t + 双向控制符）
- 节点 kind 必须落在 App 的 KnowledgeNodeKind 枚举内
- 别名：非空、首项是主名、无重复、长度 ≤256
- 同主题内节点重名
- `tables/alias_map.csv` 与包逐节点一致（防"表与包分叉"，历史上差点用过期快照覆盖成品）

用法： PYTHONPATH=tools python -m kb_build.check_pack_contract
"""

from __future__ import annotations

import csv
import sys
import unicodedata
from collections import Counter
from pathlib import Path

from kb_build import pack_io

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


def check_alias_table_agrees_with_pack(pack: dict) -> list[str]:
    """别名表必须与包逐行一致。

    它防的是"表与包分叉"这类失败：`alias_map.csv` 既被 build.py 消费、又是重建工具的产物，
    历史上就是因为一个读 staging、一个读成品，差点用过期快照覆盖成品别名。
    """
    table = Path(pack_io.REPO) / "tools" / "kb_build" / "tables" / "alias_map.csv"
    if not table.exists():
        return [f"别名表不存在：{table}"]
    from_table: dict[tuple[str, str], set[str]] = {}
    with table.open(encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            from_table.setdefault((row["subject"], row["slug"]), set()).add(row["alias"])
    problems: list[str] = []
    for subject in pack["subjects"]:
        for topic in subject["topics"]:
            for point in topic.get("knowledgePoints") or []:
                extra = set(point.get("aliases") or []) - {point["name"]}
                key = (subject["subject"], point["slug"])
                if from_table.get(key, set()) != extra:
                    problems.append(
                        f"别名表与包不一致：{subject['subject']}/{point['slug']} "
                        f"表 {sorted(from_table.get(key, set()))[:3]} vs 包 {sorted(extra)[:3]}")
    if not from_table:
        problems.append("别名表为空")
    return problems[:10]


def main() -> int:
    pack = pack_io.load_json(pack_io.pack_path())
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
    problems += check_alias_table_agrees_with_pack(pack)
    print(f"主题 {stats['topics']}、知识点 {stats['points']}；问题 {len(problems)}")
    for p in problems[:20]:
        print("  !", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
